package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.JapaneseUtil
import com.holopengin.instantjpdict.util.Deinflector
import com.holopengin.instantjpdict.data.DictionaryEntry

data class LineResult(
    var text: String,
    val charBoxes: List<JpDictRect>,
    val alternatives: List<List<Pair<Char, Float>>> = emptyList(),
    val isVertical: Boolean = false,
    val chunkBoxes: List<JpDictRect> = emptyList()
)

enum class GamepadAction {
    NONE,
    NAVIGATE_LEFT, NAVIGATE_RIGHT, NAVIGATE_UP, NAVIGATE_DOWN,
    CONFIRM, BACK,
    SCROLL_UP, SCROLL_DOWN
}

class OcrOverlayStateController {
    var currentScale = 1f
    var currentTransX = 0f
    var currentTransY = 0f
    var currentWordLength = 0
    
    var activeLineBoxes: List<JpDictRect> = emptyList()
    var activeAllChars = mutableListOf<String>()
    var activeAllAlternatives = mutableListOf<List<Pair<Char, Float>>>()
    
    var currentTappedIdx = -1
    var currentTappedLineIdx = -1
    var currentTappedCharIdxInLine = -1
    
    var activeLineResults: MutableList<LineResult?> = mutableListOf()
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
            updateGlobalData()
        }
    }

    fun navigate(keyCode: Int, rootWidth: Double, rootHeight: Double): Boolean {
        if (activeLineResults.isEmpty()) return false
        if (currentTappedLineIdx == -1 || currentTappedCharIdxInLine == -1) return false

        val line = activeLineResults[currentTappedLineIdx] ?: return false
        val box = line.charBoxes[currentTappedCharIdxInLine]
        val centerX = box.centerX().toDouble()
        val centerY = box.centerY().toDouble()

        var bestDist = Double.MAX_VALUE
        var bestIdx = -1
        var bestCharIdx = -1

        when (keyCode) {
            JpDictKeyEvent.KEYCODE_DPAD_LEFT, JpDictKeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (keyCode == JpDictKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                if (currentTappedCharIdxInLine + dir in line.charBoxes.indices) {
                    currentTappedCharIdxInLine += dir
                    currentTappedIdx = getGlobalIdx(currentTappedLineIdx, currentTappedCharIdxInLine)
                    return true
                }

                for (i in activeLineResults.indices) {
                    val otherLine = activeLineResults[i] ?: continue
                    for (c in otherLine.charBoxes.indices) {
                        val cBox = otherLine.charBoxes[c]
                        var dx = cBox.centerX().toDouble() - centerX
                        val dy = cBox.centerY().toDouble() - centerY

                        if (dir == 1 && dx <= 5) dx += rootWidth
                        else if (dir == -1 && dx >= -5) dx -= rootWidth

                        if ((dir == 1 && dx <= 5) || (dir == -1 && dx >= -5)) continue

                        val dist = (dx * dx) + (dy * dy * 64.0)
                        if (dist < bestDist) {
                            bestDist = dist
                            bestIdx = i
                            bestCharIdx = c
                        }
                    }
                }
            }
            JpDictKeyEvent.KEYCODE_DPAD_UP, JpDictKeyEvent.KEYCODE_DPAD_DOWN -> {
                val dir = if (keyCode == JpDictKeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                for (i in activeLineResults.indices) {
                    val otherLine = activeLineResults[i] ?: continue
                    for (c in otherLine.charBoxes.indices) {
                        val cBox = otherLine.charBoxes[c]
                        val dx = cBox.centerX().toDouble() - centerX
                        var dy = cBox.centerY().toDouble() - centerY

                        if (dir == 1 && dy <= 5) dy += rootHeight
                        else if (dir == -1 && dy >= -5) dy -= rootHeight

                        if ((dir == 1 && dy <= 5) || (dir == -1 && dy >= -5)) continue

                        val dist = (dx * dx * 64.0) + (dy * dy)
                        if (dist < bestDist) {
                            bestDist = dist
                            bestIdx = i
                            bestCharIdx = c
                        }
                    }
                }
            }
        }

        if (bestIdx != -1) {
            currentTappedLineIdx = bestIdx
            currentTappedCharIdxInLine = bestCharIdx
            currentTappedIdx = getGlobalIdx(currentTappedLineIdx, currentTappedCharIdxInLine)
            return true
        }
        return false
    }

    fun ensureCursorPosition() {
        if (currentTappedLineIdx == -1 || currentTappedCharIdxInLine == -1) {
            for (i in activeLineResults.indices) {
                val line = activeLineResults[i]
                if (line != null && line.text.isNotEmpty()) {
                    currentTappedLineIdx = i
                    currentTappedCharIdxInLine = 0
                    currentTappedIdx = getGlobalIdx(i, 0)
                    break
                }
            }
        }
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
        val (panelWidth, panelHeight) = getPanelDimensions(rootWidth, rootHeight)
        val isLandscape = rootWidth > rootHeight

        if (isLandscape) {
            if (lastLandscapeGravity == JpDictGravity.END) {
                if (tappedBox.right > rootWidth - panelWidth) lastLandscapeGravity = JpDictGravity.START
            } else {
                if (tappedBox.left < panelWidth) lastLandscapeGravity = JpDictGravity.END
            }
        } else {
            if (lastPortraitGravity == JpDictGravity.BOTTOM) {
                if (tappedBox.bottom > rootHeight - panelHeight) lastPortraitGravity = JpDictGravity.TOP
            } else {
                if (tappedBox.top < panelHeight) lastPortraitGravity = JpDictGravity.BOTTOM
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

    fun calculateDisplayBoxes(line: LineResult): List<JpDictRect> {
        val fixedSize = if (line.isVertical) {
            line.charBoxes.map { it.width() }.maxOrNull() ?: 0
        } else {
            line.charBoxes.map { it.height() }.maxOrNull() ?: 0
        }

        val result = mutableListOf<JpDictRect>()
        var lastX = -1
        var lastY = -1

        for (i in line.charBoxes.indices) {
            val originalBox = line.charBoxes[i]
            var centerX = originalBox.centerX()
            var centerY = originalBox.centerY()
            
            if (i == line.charBoxes.size - 1 && i > 0) {
                if (line.isVertical) centerY = lastY + fixedSize
                else centerX = lastX + fixedSize
            }

            val left = centerX - fixedSize / 2
            val top = centerY - fixedSize / 2
            val right = centerX + fixedSize / 2
            val bottom = centerY + fixedSize / 2
            
            result.add(JpDictRect(left, top, right, bottom))
            
            lastX = centerX
            lastY = centerY
        }
        return result
    }
}
