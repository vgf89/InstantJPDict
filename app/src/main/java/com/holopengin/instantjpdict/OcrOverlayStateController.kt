package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.JapaneseUtil
import com.holopengin.instantjpdict.util.Deinflector
import com.holopengin.instantjpdict.data.DictionaryEntry
import com.google.gson.Gson
import android.graphics.Bitmap
import uniffi.nav_graph_core.*

data class LineResult(
    var text: String,
    val charBoxes: List<JpDictRect>,
    val alternatives: List<MutableList<Pair<Char, Float>>>,
    val isVertical: Boolean = false,
    val overrides: MutableMap<Int, Pair<Char, Float>> = mutableMapOf()
)

sealed class DefinitionNode {
    data class Text(val text: String) : DefinitionNode()
    data class Ruby(val term: String, val reading: String, val isMini: Boolean) : DefinitionNode()
    data class Tag(val text: String, val category: String = "general") : DefinitionNode()
    data class Example(val japanese: String?, val english: String?, val content: List<DefinitionNode>?) : DefinitionNode()
    data class ListBlock(val items: List<List<DefinitionNode>>, val type: String?) : DefinitionNode()
    data class Table(val rows: List<List<List<DefinitionNode>>>) : DefinitionNode()
    data class Group(val nodes: List<DefinitionNode>, val isInline: Boolean) : DefinitionNode()
}

data class FormattedSense(
    val index: Int,
    val nodes: List<DefinitionNode>
)

data class FormattedSenseGroup(
    val tags: List<String>,
    val senses: List<FormattedSense>,
    val isForms: Boolean
)

data class FormattedHeadword(
    val kanji: String,
    val onyomi: String?,
    val kunyomi: String?
)

data class FormattedReadingGroup(
    val reading: String,
    val headwords: List<FormattedHeadword>,
    val senseGroups: List<FormattedSenseGroup>,
    val isKanjiEntry: Boolean
)

data class FormattedEntry(
    val term: String,
    val readingGroups: List<FormattedReadingGroup>
)

data class NeighborChar(
    val text: String,
    val isSelected: Boolean,
    val lineIdx: Int,
    val charIdx: Int
)

data class NeighborLine(
    val chars: List<NeighborChar>,
    val lineIdx: Int
)

data class AlternativeChar(
    val char: Char,
    val isSelected: Boolean
)

data class AlternativesUiState(
    val candidates: List<AlternativeChar>,
    val showManualInput: Boolean
)

enum class GamepadAction {
    NONE,
    NAVIGATE_LEFT, NAVIGATE_RIGHT, NAVIGATE_UP, NAVIGATE_DOWN,
    CONFIRM, BACK,
    SCROLL_UP, SCROLL_DOWN
}

class OcrOverlayStateController {
    var deinflector: Deinflector? = null
    var dictionaryProvider: DictionaryProvider? = null
    var gson: Gson? = null

    var currentScale = 1f
    var currentTransX = 0f
    var currentTransY = 0f
    var currentWordLength = 0
    var recConfidenceThreshold = 0.1f

    fun refreshLinesWithThreshold(ocrEngine: OcrEngine, screenshotBitmap: Bitmap?) {
        val oldTappedBoxCenter = if (currentTappedLineIdx != -1 && currentTappedCharIdxInLine != -1) {
            activeLineResults.getOrNull(currentTappedLineIdx)?.charBoxes?.getOrNull(currentTappedCharIdxInLine)?.let {
                Pair(it.centerX(), it.centerY())
            }
        } else null

        activeLineResults.forEachIndexed { i, line ->
            line?.let { oldLine ->
                // Re-recognize from bitmap crop (PP-OCR is fast enough to re-run)
                if (screenshotBitmap != null && oldLine.charBoxes.isNotEmpty()) {
                    val firstBox = oldLine.charBoxes.first()
                    val box = oldLine.charBoxes.last()
                    val cropX = minOf(firstBox.left, box.left).coerceAtLeast(0)
                    val cropY = minOf(firstBox.top, box.top).coerceAtLeast(0)
                    val cropW = (maxOf(firstBox.right, box.right) - cropX).coerceAtMost(screenshotBitmap.width - cropX)
                    val cropH = (maxOf(firstBox.bottom, box.bottom) - cropY).coerceAtMost(screenshotBitmap.height - cropY)
                    if (cropW > 0 && cropH > 0) {
                        val crop = Bitmap.createBitmap(screenshotBitmap, cropX, cropY, cropW, cropH)
                        val newLine = ocrEngine.processLineFromRawChunks(oldLine, crop)
                        crop.recycle()
                        activeLineResults[i] = newLine
                    }
                }
            }
        }
        
        // Re-find the tapped character by its position
        if (oldTappedBoxCenter != null) {
            val line = activeLineResults.getOrNull(currentTappedLineIdx)
            if (line != null) {
                var bestIdx = -1
                var minDist = 1000f
                for (j in line.charBoxes.indices) {
                    val box = line.charBoxes[j]
                    val dx = (box.centerX() - oldTappedBoxCenter.first).toDouble()
                    val dy = (box.centerY() - oldTappedBoxCenter.second).toDouble()
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy).toFloat()
                    if (dist < minDist && dist < 20) { // small radius to ensure it's the same char
                        minDist = dist
                        bestIdx = j
                    }
                }
                if (bestIdx != -1) {
                    currentTappedCharIdxInLine = bestIdx
                    currentTappedIdx = getGlobalIdx(currentTappedLineIdx, currentTappedCharIdxInLine)
                }
            }
        }

        updateGlobalData()
    }
    
    var activeLineBoxes: List<JpDictRect> = emptyList()
    var activeAllChars = mutableListOf<String>()
    var activeAllAlternatives = mutableListOf<List<Pair<Char, Float>>>()
    
    var currentTappedIdx = -1
    var currentTappedLineIdx = -1
    var currentTappedCharIdxInLine = -1
    
    var activeLineResults: MutableList<LineResult?> = mutableListOf()
    var navGraph: NavGraph? = null
    var lastHighlightedCoords = mutableListOf<Pair<Int, Int>>()
    var lastJoystickKeyCode = 0
    var lastLandscapeGravity = JpDictGravity.END
    var lastPortraitGravity = JpDictGravity.BOTTOM
    var lastManualInputCloseTime = 0L
    var lastNeighborHighlightedLine = -1
    var lastNeighborHighlightedChar = -1
    var isControllerNavigation = false
    
    var isDictionaryVisible = false
    var isAlternativesVisible = false

    fun updateGlobalData() {
        activeAllChars.clear()
        activeAllAlternatives.clear()
        activeLineResults.forEach { line ->
            line?.let {
                it.text.forEach { char -> activeAllChars.add(char.toString()) }
                it.alternatives.forEach { alts -> activeAllAlternatives.add(alts) }
            }
        }
        rebuildNavGraph()
    }

    fun rebuildNavGraph() {
        val boxes = mutableListOf<BoundingBox>()
        for (line in activeLineResults) {
            line?.let {
                for (box in it.charBoxes) {
                    boxes.add(BoundingBox(box.left, box.top, box.width(), box.height()))
                }
            }
        }
        navGraph = if (boxes.size >= 5) buildNavGraph(boxes) else null
    }

    fun getGlobalIdx(lineIdx: Int, charIdxInLine: Int): Int {
        var count = 0
        for (i in 0 until lineIdx) {
            count += activeLineResults[i]?.text?.length ?: 0
        }
        return count + charIdxInLine
    }

    fun getCoordsFromGlobalIdx(globalIdx: Int): Pair<Int, Int>? {
        var count = 0
        for (lineIdx in activeLineResults.indices) {
            val line = activeLineResults[lineIdx] ?: continue
            if (globalIdx < count + line.text.length) {
                return Pair(lineIdx, globalIdx - count)
            }
            count += line.text.length
        }
        return null
    }

    fun resetState() {
        currentScale = 1f
        currentTransX = 0f
        currentTransY = 0f
        currentTappedIdx = -1
        currentTappedLineIdx = -1
        currentTappedCharIdxInLine = -1
        recConfidenceThreshold = 0.1f
        activeLineResults.clear()
        activeLineBoxes = emptyList()
        isControllerNavigation = false
        isDictionaryVisible = false
        isAlternativesVisible = false
        updateGlobalData()
    }

    fun updateCharacter(lineIdx: Int, charIdx: Int, newChar: Char) {
        val line = activeLineResults.getOrNull(lineIdx) ?: return
        val charArray = line.text.toCharArray()
        if (charIdx in charArray.indices) {
            charArray[charIdx] = newChar
            line.text = String(charArray)
            
            // Save override (charIdx-based for PP-OCR)
            line.overrides[charIdx] = newChar to 1f

            updateGlobalData()
        }
    }

    fun navigate(keyCode: Int, rootWidth: Double, rootHeight: Double): Boolean {
        if (activeLineResults.isEmpty()) return false
        if (currentTappedLineIdx == -1 || currentTappedCharIdxInLine == -1) return false

        // Use nav graph if available
        val graph = navGraph
        if (graph != null) {
            val dir = when (keyCode) {
                JpDictKeyEvent.KEYCODE_DPAD_UP -> 0
                JpDictKeyEvent.KEYCODE_DPAD_DOWN -> 1
                JpDictKeyEvent.KEYCODE_DPAD_RIGHT -> 2
                JpDictKeyEvent.KEYCODE_DPAD_LEFT -> 3
                else -> return false
            }
            val target = navigate(graph, currentTappedIdx, dir) ?: return false
            val coords = getCoordsFromGlobalIdx(target) ?: return false
            currentTappedLineIdx = coords.first
            currentTappedCharIdxInLine = coords.second
            currentTappedIdx = target
            return true
        }

        // Legacy fallback: same-line left/right
        val line = activeLineResults[currentTappedLineIdx] ?: return false
        when (keyCode) {
            JpDictKeyEvent.KEYCODE_DPAD_LEFT, JpDictKeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (keyCode == JpDictKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                if (currentTappedCharIdxInLine + dir in line.charBoxes.indices) {
                    currentTappedCharIdxInLine += dir
                    currentTappedIdx = getGlobalIdx(currentTappedLineIdx, currentTappedCharIdxInLine)
                    return true
                }
            }
        }
        return false
    }

    fun navigateLines(direction: Int): Boolean {
        if (currentTappedLineIdx == -1) return false
        val currentLine = activeLineResults[currentTappedLineIdx] ?: return false
        val currentCharBox = currentLine.charBoxes[currentTappedCharIdxInLine]
        val centerX = currentCharBox.centerX()
        val centerY = currentCharBox.centerY()

        var nextLineIdx = currentTappedLineIdx + direction
        while (nextLineIdx in activeLineResults.indices) {
            val nextLine = activeLineResults[nextLineIdx]
            if (nextLine != null && nextLine.text.isNotEmpty()) {
                var minInfo: Pair<Int, Double>? = null
                for (i in nextLine.charBoxes.indices) {
                    val box = nextLine.charBoxes[i]
                    val dx = (box.centerX() - centerX).toDouble()
                    val dy = (box.centerY() - centerY).toDouble()
                    val dist = dx * dx + dy * dy
                    if (minInfo == null || dist < minInfo.second) minInfo = i to dist
                }
                if (minInfo != null) {
                    currentTappedLineIdx = nextLineIdx
                    currentTappedCharIdxInLine = minInfo.first
                    currentTappedIdx = getGlobalIdx(currentTappedLineIdx, currentTappedCharIdxInLine)
                    return true
                }
            }
            nextLineIdx += direction
        }
        return false
    }

    fun ensureCursorPosition() {
        val currentLine = activeLineResults.getOrNull(currentTappedLineIdx)
        if (currentTappedLineIdx == -1 || currentTappedCharIdxInLine == -1 || 
            currentLine == null || currentTappedCharIdxInLine >= currentLine.charBoxes.size) {
            for (i in activeLineResults.indices) {
                val line = activeLineResults[i]
                if (line != null && line.charBoxes.isNotEmpty()) {
                    currentTappedLineIdx = i
                    currentTappedCharIdxInLine = 0
                    currentTappedIdx = getGlobalIdx(i, 0)
                    break
                }
            }
        }
    }

    suspend fun lookup(lineIdx: Int, charIdx: Int): Result? {
        val deinf = deinflector ?: return null
        val provider = dictionaryProvider ?: return null
        val g = gson ?: return null

        val globalIdx = getGlobalIdx(lineIdx, charIdx)
        currentTappedIdx = globalIdx
        currentTappedLineIdx = lineIdx
        currentTappedCharIdxInLine = charIdx

        val line = activeLineResults.getOrNull(lineIdx) ?: return null
        val tappedBox = line.charBoxes.getOrNull(charIdx) ?: JpDictRect(0, 0, 0, 0)

        val endIdx = kotlin.math.min(globalIdx + 20, activeAllChars.size)
        val followingText = activeAllChars.subList(globalIdx, endIdx).joinToString("")

        val (allTermsToSearch, candidatesByLength) = prepareSearchCandidates(followingText, deinf)
        val dbResults = provider.findByTexts(allTermsToSearch.toList())
        val (uniqueMatches, maxLen) = processResults(dbResults, candidatesByLength, allTermsToSearch, followingText)
        
        val formatted = formatDictionaryResults(uniqueMatches, g)
        currentWordLength = maxLen

        // ── Second pass: look up each individual kanji in the matched term ──
        val matchedTerm = followingText.take(maxLen)
        val appendKanji = mutableListOf<FormattedEntry>()
        for (ch in matchedTerm) {
            // Only CJK Unified Ideographs (kanji)
            if (ch !in '\u4E00'..'\u9FFF' && ch !in '\u3400'..'\u4DBF') {
                continue
            }
            val kanjiStr = ch.toString()
            // Deduplicate: skip if this kanji already has an entry from the
            // first-pass term lookup or we already appended it above
            if (formatted.any { it.term == kanjiStr } || appendKanji.any { it.term == kanjiStr }) {
                continue
            }
            val kanjiResults = provider.findByTexts(listOf(kanjiStr))
            val kanjiOnly = kanjiResults.filter { it.onyomi != null || it.kunyomi != null }
            if (kanjiOnly.isNotEmpty()) {
                val formattedKanji = formatDictionaryResults(listOf(kanjiStr to kanjiOnly), g)
                appendKanji.addAll(formattedKanji)
            }
        }
        if (appendKanji.isNotEmpty()) {
            formatted.addAll(appendKanji)
        }
        
        return Result(formatted, maxLen, tappedBox, followingText)
    }

    data class Result(val matches: List<FormattedEntry>, val maxLen: Int, val tappedBox: JpDictRect, val cacheKey: String)

    fun getNeighborUiState(): List<NeighborLine> {
        return activeLineResults.mapIndexed { lIdx, line ->
            if (line == null) NeighborLine(emptyList(), lIdx)
            else NeighborLine(
                line.text.mapIndexed { cIdx, char ->
                    NeighborChar(
                        char.toString(),
                        isSelected = (lIdx == currentTappedLineIdx && cIdx == currentTappedCharIdxInLine),
                        lIdx,
                        cIdx
                    )
                },
                lIdx
            )
        }
    }

    fun getAlternativesUiState(): AlternativesUiState? {
        val line = activeLineResults.getOrNull(currentTappedLineIdx) ?: return null
        val alts = line.alternatives.getOrNull(currentTappedCharIdxInLine) ?: return null
        val currentChar = line.text.getOrNull(currentTappedCharIdxInLine)
        
        return AlternativesUiState(
            alts.take(15).map { (char, _) ->
                AlternativeChar(char, isSelected = char == currentChar)
            },
            showManualInput = true
        )
    }

    fun navigateAlternatives(keyCode: Int, isLandscape: Boolean): Char? {
        val diff = when (keyCode) {
            JpDictKeyEvent.KEYCODE_DPAD_DOWN -> if (isLandscape) 1 else 0
            JpDictKeyEvent.KEYCODE_DPAD_UP -> if (isLandscape) -1 else 0
            JpDictKeyEvent.KEYCODE_DPAD_RIGHT -> if (!isLandscape) 1 else 0
            JpDictKeyEvent.KEYCODE_DPAD_LEFT -> if (!isLandscape) -1 else 0
            else -> 0
        }
        if (diff == 0) return null

        val line = activeLineResults.getOrNull(currentTappedLineIdx) ?: return null
        val alts = line.alternatives.getOrNull(currentTappedCharIdxInLine) ?: return null
        val currentChar = line.text.getOrNull(currentTappedCharIdxInLine) ?: return null

        val candidates = alts.take(15).map { it.first }
        val currentIndex = candidates.indexOf(currentChar)

        if (currentIndex != -1) {
            val newIndex = (currentIndex + diff).coerceIn(0, candidates.size - 1)
            if (newIndex != currentIndex) {
                return candidates[newIndex]
            }
        }
        return null
    }

    fun getPanelDimensions(rootWidth: Int, rootHeight: Int): Pair<Float, Float> {
        val isLandscape = rootWidth > rootHeight
        val panelWidth = if (isLandscape) (rootWidth * 0.4f) else rootWidth.toFloat()
        val panelHeight = if (isLandscape) rootHeight.toFloat() else (rootHeight * 0.4f)
        return Pair(panelWidth, panelHeight)
    }

    fun updateGravity(rootWidth: Int, rootHeight: Int, tappedBox: JpDictRect) {
        val isLandscape = rootWidth > rootHeight

        // Calculate screen center of the character, taking into account current scale and translation
        val screenCenterX = tappedBox.centerX() * currentScale + currentTransX
        val screenCenterY = tappedBox.centerY() * currentScale + currentTransY

        if (isLandscape) {
            lastLandscapeGravity = if (screenCenterX < rootWidth / 2f) {
                JpDictGravity.END
            } else {
                JpDictGravity.START
            }
        } else {
            lastPortraitGravity = if (screenCenterY < rootHeight / 2f) {
                JpDictGravity.BOTTOM
            } else {
                JpDictGravity.TOP
            }
        }
    }

    fun centerOnCharacter(lineIdx: Int, charIdx: Int, rootWidth: Int, rootHeight: Int): Boolean {
        val line = activeLineResults.getOrNull(lineIdx) ?: return false
        val charBox = line.charBoxes.getOrNull(charIdx) ?: return false
        
        var changed = false
        if (isControllerNavigation) {
            isControllerNavigation = false
            
            val visibleCenterX: Float
            val visibleCenterY: Float
            
            if (!isDictionaryVisible) {
                val left = charBox.left * currentScale + currentTransX
                val right = charBox.right * currentScale + currentTransX
                val top = charBox.top * currentScale + currentTransY
                val bottom = charBox.bottom * currentScale + currentTransY
                
                var nudgeX = 0f
                if (left < 0) nudgeX = -left
                else if (right > rootWidth) nudgeX = rootWidth.toFloat() - right
                
                var nudgeY = 0f
                if (top < 0) nudgeY = -top
                else if (bottom > rootHeight) nudgeY = rootHeight.toFloat() - bottom
                
                if (nudgeX != 0f || nudgeY != 0f) {
                    currentTransX += nudgeX
                    currentTransY += nudgeY
                    changed = true
                }
            } else {
                val (panelWidth, panelHeight) = getPanelDimensions(rootWidth, rootHeight)
                val isLandscape = rootWidth > rootHeight
                if (isLandscape) {
                    val isEnd = lastLandscapeGravity == JpDictGravity.END
                    visibleCenterX = if (isEnd) (rootWidth - panelWidth) / 2f else panelWidth + (rootWidth - panelWidth) / 2f
                    visibleCenterY = rootHeight / 2f
                } else {
                    val isBottom = lastPortraitGravity == JpDictGravity.BOTTOM
                    visibleCenterX = rootWidth / 2f
                    visibleCenterY = if (isBottom) (rootHeight - panelHeight) / 2f else panelHeight + (rootHeight - panelHeight) / 2f
                }
                
                currentTransX = visibleCenterX - charBox.centerX() * currentScale
                currentTransY = visibleCenterY - charBox.centerY() * currentScale
                changed = true
            }
        }
        return changed
    }

    fun isNearCharacter(screenX: Float, screenY: Float, marginDp: Float, density: Float): Boolean {
        val s = currentScale
        val ix = (screenX - currentTransX) / s
        val iy = (screenY - currentTransY) / s
        val m = (marginDp * density) / s
        
        return activeLineResults.filterNotNull().any { line ->
            line.charBoxes.any { b ->
                ix >= b.left - m && ix <= b.right + m && iy >= b.top - m && iy <= b.bottom + m
            }
        } || activeLineBoxes.any { b ->
            ix >= b.left - m && ix <= b.right + m && iy >= b.top - m && iy <= b.bottom + m
        }
    }

    fun prepareSearchCandidates(
        followingText: String,
        deinflector: Deinflector
    ): Pair<Set<String>, List<Pair<Int, List<Pair<String, List<String>?>>>>> {
        val allTermsToSearch = mutableSetOf<String>()
        val candidatesByLength = mutableListOf<Pair<Int, List<Pair<String, List<String>?>>>>()

        for (len in followingText.length downTo 1) {
            val queryTextRaw = followingText.substring(0, len)
            val queryText = JapaneseUtil.normalize(queryTextRaw)

            val variants = listOf(
                queryText,
                JapaneseUtil.katakanaToHiragana(queryText),
                JapaneseUtil.collapseEmphatic(queryText)
            ).distinct()

            val deinflections = deinflector.deinflect(queryText)
            val lengthCandidates = mutableListOf<Pair<String, List<String>?>>()
            variants.forEach { lengthCandidates.add(it to null); allTermsToSearch.add(it) }
            deinflections.forEach { if (it.term != queryText) { lengthCandidates.add(it.term to it.type); allTermsToSearch.add(it.term) } }
            candidatesByLength.add(len to lengthCandidates)
        }
        return Pair(allTermsToSearch, candidatesByLength)
    }

    fun processResults(
        dbResults: List<DictionaryEntry>,
        candidatesByLength: List<Pair<Int, List<Pair<String, List<String>?>>>>,
        allTermsToSearch: Set<String>,
        followingText: String
    ): Pair<List<Pair<String, List<DictionaryEntry>>>, Int> {
        val resultsByTerm = mutableMapOf<String, MutableList<DictionaryEntry>>()
        dbResults.forEach { entry ->
            if (entry.kanji in allTermsToSearch) resultsByTerm.getOrPut(entry.kanji) { mutableListOf() }.add(entry)
            if (entry.reading in allTermsToSearch) resultsByTerm.getOrPut(entry.reading) { mutableListOf() }.add(entry)
        }

        val matches = mutableListOf<Pair<String, List<DictionaryEntry>>>()
        var maxLen = 0
        for ((len, candidates) in candidatesByLength) {
            var found = false
            for ((term, requiredTypes) in candidates) {
                val termEntries = resultsByTerm[term] ?: continue
                val filteredResults = if (requiredTypes == null) {
                    val queryText = JapaneseUtil.normalize(followingText.substring(0, len))
                    termEntries.filter { entry ->
                        val isKanjiEntry = entry.onyomi != null || entry.kunyomi != null
                        !isKanjiEntry || entry.kanji == queryText
                    }
                } else {
                    termEntries.filter { entry ->
                        val entryTags = entry.rules.split(" ")
                        requiredTypes.isEmpty() || requiredTypes.any { it in entryTags } ||
                                (entryTags.any { it.startsWith("v") } && requiredTypes.any { it.startsWith("v") })
                    }
                }
                if (filteredResults.isNotEmpty()) {
                    matches.add(term to filteredResults.distinctBy { it.id })
                    found = true
                }
            }
            if (found && maxLen == 0) maxLen = len
        }
        return matches.distinctBy { it.first } to maxLen
    }

    fun resolveGamepadAction(keyCode: Int, layoutSwap: Boolean): GamepadAction {
        val mappedEnter = if (layoutSwap) JpDictKeyEvent.KEYCODE_BUTTON_B else JpDictKeyEvent.KEYCODE_BUTTON_A
        val mappedBack = if (layoutSwap) JpDictKeyEvent.KEYCODE_BUTTON_A else JpDictKeyEvent.KEYCODE_BUTTON_B

        return when (keyCode) {
            JpDictKeyEvent.KEYCODE_DPAD_LEFT -> GamepadAction.NAVIGATE_LEFT
            JpDictKeyEvent.KEYCODE_DPAD_RIGHT -> GamepadAction.NAVIGATE_RIGHT
            JpDictKeyEvent.KEYCODE_DPAD_UP -> GamepadAction.NAVIGATE_UP
            JpDictKeyEvent.KEYCODE_DPAD_DOWN -> GamepadAction.NAVIGATE_DOWN
            JpDictKeyEvent.KEYCODE_ENTER, JpDictKeyEvent.KEYCODE_DPAD_CENTER, mappedEnter -> GamepadAction.CONFIRM
            JpDictKeyEvent.KEYCODE_BACK, JpDictKeyEvent.KEYCODE_ESCAPE, mappedBack -> GamepadAction.BACK
            JpDictKeyEvent.KEYCODE_BUTTON_L1, JpDictKeyEvent.KEYCODE_BUTTON_L2 -> GamepadAction.SCROLL_UP
            JpDictKeyEvent.KEYCODE_BUTTON_R1, JpDictKeyEvent.KEYCODE_BUTTON_R2 -> GamepadAction.SCROLL_DOWN
            else -> GamepadAction.NONE
        }
    }

    fun isHandledKey(keyCode: Int): Boolean {
        // Simple check without considering layoutSwap as most keys are shared
        return resolveGamepadAction(keyCode, false) != GamepadAction.NONE || 
               resolveGamepadAction(keyCode, true) != GamepadAction.NONE
    }

    fun getRepeatInterval(rate: Int): Long = (1000L / rate.toLong()).coerceAtLeast(16L)

    fun updateHighlightCoords(lineIdx: Int, charIdx: Int, wordLength: Int) {
        lastHighlightedCoords.clear()
        lastHighlightedCoords.add(Pair(lineIdx, charIdx))
        for (i in 1 until wordLength) {
            val targetGlobalIdx = getGlobalIdx(lineIdx, charIdx) + i
            getCoordsFromGlobalIdx(targetGlobalIdx)?.let { lastHighlightedCoords.add(it) }
        }
    }

    fun calculateDisplayBoxes(line: LineResult, advances: List<Float>? = null): List<JpDictRect> {
        val fixedSize = if (line.isVertical) {
            line.charBoxes.map { it.width() }.maxOrNull() ?: 0
        } else {
            line.charBoxes.map { it.height() }.maxOrNull() ?: 0
        }

        val refinedBoxes = mutableListOf<JpDictRect>()
        if (line.charBoxes.isNotEmpty()) {
            refinedBoxes.add(line.charBoxes[0])
            for (i in 1 until line.charBoxes.size) {
                // To eliminate cumulative drift, we anchor the advance constraint to the
                // ORIGINAL position of the previous character. This ensures that any
                // necessary push (e.g. for punctuation) only affects the character
                // relative to its immediate predecessor's detection, rather than
                // snowballing across the entire line.
                val prevOriginal = line.charBoxes[i - 1]
                val curOriginal = line.charBoxes[i]
                val advance = advances?.getOrNull(i - 1)?.toInt() ?: fixedSize
                
                if (line.isVertical) {
                    val newTop = maxOf(prevOriginal.top + advance, curOriginal.top)
                    refinedBoxes.add(JpDictRect(curOriginal.left, newTop, curOriginal.right, curOriginal.bottom))
                } else {
                    val newLeft = maxOf(prevOriginal.left + advance, curOriginal.left)
                    refinedBoxes.add(JpDictRect(newLeft, curOriginal.top, curOriginal.right, curOriginal.bottom))
                }
            }
        }

        val result = mutableListOf<JpDictRect>()
        for (i in refinedBoxes.indices) {
            val box = refinedBoxes[i]
            // Calculate center using refined boundaries.
            // The right/bottom edges remain at their original detected positions.
            val centerX = (box.left.toDouble() + box.right.toDouble()) / 2.0
            val centerY = (box.top.toDouble() + box.bottom.toDouble()) / 2.0

            val left = (centerX - fixedSize / 2.0).toInt()
            val top = (centerY - fixedSize / 2.0).toInt()
            val right = left + fixedSize
            val bottom = top + fixedSize
            
            result.add(JpDictRect(left, top, right, bottom))
        }
        return result
    }

    fun formatDictionaryResults(
        matches: List<Pair<String, List<DictionaryEntry>>>,
        gson: Gson
    ): List<FormattedEntry> {
        return matches.map { (term, entries) ->
            val readingGroups = entries.groupBy { it.reading }.map { (reading, readingEntries) ->
                val isKanjiEntry = readingEntries.firstOrNull()?.let { it.onyomi != null || it.kunyomi != null } ?: false
                val kanjiVariants = readingEntries.map { it.kanji }.distinct()
                
                val headwords = kanjiVariants.map { kanji ->
                    val entry = readingEntries.find { it.kanji == kanji } ?: readingEntries.first()
                    FormattedHeadword(kanji, entry.onyomi, entry.kunyomi)
                }

                val senseGroups = mutableListOf<FormattedSenseGroup>()
                var globalSenseNum = 1
                val groupSeenTags = mutableSetOf<String>()
                var currentGroupTags: List<String>? = null
                var currentGroupSenses = mutableListOf<FormattedSense>()

                for (e in readingEntries) {
                    val definitionsJson = try { gson.fromJson(e.definitions, Any::class.java) } catch (ex: Exception) { e.definitions }
                    val definitionsList = definitionsJson as? List<*> ?: listOf(definitionsJson)

                    val metaTags = mutableListOf<String>()
                    val senseTagsMap = mutableMapOf<Int, MutableList<String>>()
                    e.jlpt?.takeIf { it.isNotEmpty() }?.let { metaTags.add("jlpt: N$it") }
                    "grade:(\\s+)".toRegex().find(e.rules)?.groupValues?.get(1)?.let { metaTags.add("grade: $it") }

                    val segments = e.rules.split(" | ")
                    fun parseToMaps(s: String?) {
                        var currentSense: Int? = null
                        s?.split(" ")?.filter { it.isNotEmpty() }?.forEach { tag ->
                            val n = tag.toIntOrNull()
                            if (n != null) currentSense = n
                            else if (!tag.startsWith("grade:")) {
                                currentSense?.let { senseTagsMap.getOrPut(it) { mutableListOf() }.add(tag) } ?: metaTags.add(tag)
                            }
                        }
                    }
                    parseToMaps(segments.getOrNull(0))
                    parseToMaps(segments.getOrNull(2))

                    val senseIdx = globalSenseNum++
                    val tags = (metaTags + (senseTagsMap[1] ?: emptyList())).distinct()
                    val nodes = parseDefinition(definitionsList)

                    if (currentGroupTags == null || tags == currentGroupTags) {
                        currentGroupTags = tags
                        currentGroupSenses.add(FormattedSense(senseIdx, nodes))
                    } else {
                        val tagsToRender = currentGroupTags!!
                        val isForms = tagsToRender.any { it.equals("Forms", ignoreCase = true) || it.equals("Other forms", ignoreCase = true) }
                        senseGroups.add(FormattedSenseGroup(tagsToRender.filter { groupSeenTags.add(it) }, currentGroupSenses, isForms))
                        currentGroupTags = tags
                        currentGroupSenses = mutableListOf(FormattedSense(senseIdx, nodes))
                    }
                }
                currentGroupTags?.let { tagsToRender ->
                    val isForms = tagsToRender.any { it.equals("Forms", ignoreCase = true) || it.equals("Other forms", ignoreCase = true) }
                    senseGroups.add(FormattedSenseGroup(tagsToRender.filter { groupSeenTags.add(it) }, currentGroupSenses, isForms))
                }

                FormattedReadingGroup(reading, headwords, senseGroups, isKanjiEntry)
            }
            FormattedEntry(term, readingGroups)
        }
    }

    private fun getAttr(data: Map<*, *>, key: String) = 
        (data["data"] as? Map<*, *>)?.get(key) as? String ?: data["data-$key"] as? String ?: data[key] as? String

    private fun isExample(data: Map<*, *>) = 
        data["type"] == "sentence" || data["type"] == "example" || 
        data.containsKey("japanese") || 
        getAttr(data, "content")?.let { it.contains("example") || it == "examples" } == true ||
        getAttr(data, "class")?.contains("example") == true

    private fun isBlock(item: Any?): Boolean {
        if (item is List<*>) return item.any { isBlock(it) }
        val data = item as? Map<*, *> ?: return false
        if (isExample(data)) return true

        val content = data["content"] ?: data["list"]
        if (content != null && isBlock(content)) return true

        val tag = data["tag"] as? String
        val scContent = getAttr(data, "content")
        
        // Structural elements like tables and lists are blocks.
        // Glosses, notes, and references should generally remain inline.
        return tag == "table" || 
               ((tag == "ul" || tag == "ol") && scContent !in listOf("glossary", "infoGlossary", "sourceLanguages", "info-gloss", "sense-note"))
    }

    private fun isInlineNode(node: DefinitionNode): Boolean {
        return node is DefinitionNode.Text || node is DefinitionNode.Ruby || node is DefinitionNode.Tag
    }

    private fun parseDefinition(data: Any?, inExample: Boolean = false): List<DefinitionNode> {
        val nodes = mutableListOf<DefinitionNode>()
        when (data) {
            is String -> {
                // Remove internal newlines to allow normal wrapping for long definitions.
                val replaced = data.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
                    .replace(";", "; ").replace(";  ", "; ")
                val trimmed = replaced.trim().replace(Regex("\\s+"), " ")
                if (trimmed.isNotEmpty()) {
                    nodes.add(DefinitionNode.Text(trimmed))
                }
            }
            is List<*> -> {
                data.forEach { item ->
                    val itemNodes = parseDefinition(item, inExample)
                    if (itemNodes.isNotEmpty()) {
                        // Attach comma to PREVIOUS text node if possible to prevent it from wrapping to a new line alone (\n,)
                        if (nodes.isNotEmpty() && !isBlock(item)) {
                            val last = nodes.last()
                            val first = itemNodes.first()
                            if (isInlineNode(last) && isInlineNode(first)) {
                                val separator = if (inExample) "\n" else ", "
                                if (last is DefinitionNode.Text) {
                                    nodes[nodes.size - 1] = DefinitionNode.Text(last.text + separator)
                                } else {
                                    nodes.add(DefinitionNode.Text(separator))
                                }
                            }
                        }
                        nodes.addAll(itemNodes)
                    }
                }
            }
            is Map<*, *> -> {
                val tag = data["tag"] as? String
                val content = data["content"] ?: data["list"]
                val scContent = getAttr(data, "content")
                val scClass = getAttr(data, "class")

                when {
                    isExample(data) -> {
                        val jp = (data["japanese"] as? String) ?: (content as? String)
                        val en = data["english"] as? String
                        if (jp != null) {
                            nodes.add(DefinitionNode.Example(jp, en, null))
                        } else {
                            nodes.add(DefinitionNode.Example(null, null, parseDefinition(content, inExample = true)))
                        }
                    }
                    scClass == "tag" || (tag == "span" && scContent?.endsWith("-info") == true) -> {
                        nodes.add(DefinitionNode.Tag(content?.toString() ?: ""))
                    }
                    tag == "ruby" -> {
                        val rubyList = content as? List<*>
                        if (rubyList != null && rubyList.size >= 2) {
                            nodes.add(DefinitionNode.Ruby(rubyList[0].toString(), (rubyList[1] as? Map<*, *>)?.get("content")?.toString() ?: "", isMini = true))
                        }
                    }
                    tag == "table" -> {
                        nodes.add(DefinitionNode.Table(emptyList())) // Placeholder
                    }
                    tag == "ul" || tag == "ol" -> {
                        if (scContent in listOf("glossary", "infoGlossary", "sourceLanguages", "info-gloss", "sense-note")) {
                            nodes.addAll(parseDefinition(content, inExample))
                        } else {
                            val items = (content as? List<*>)?.map { parseDefinition(it, inExample) } ?: emptyList()
                            nodes.add(DefinitionNode.ListBlock(items, scContent))
                        }
                    }
                    content != null -> nodes.addAll(parseDefinition(content, inExample))
                }
            }
        }
        return nodes
    }
}
