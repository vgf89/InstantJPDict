package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.JapaneseUtil
import com.holopengin.instantjpdict.util.Deinflector
import com.holopengin.instantjpdict.util.KanaOrthography
import com.holopengin.instantjpdict.util.DeinflectionChain
import com.holopengin.instantjpdict.util.DictionaryRedirects
import com.holopengin.instantjpdict.util.OovCandidates
import com.holopengin.instantjpdict.util.CharLm
import com.holopengin.instantjpdict.util.GapCandidates
import com.holopengin.instantjpdict.util.OovSuggestions
import com.holopengin.instantjpdict.util.PitchAccent
import com.holopengin.instantjpdict.data.DictionaryEntry
import com.google.gson.Gson
import uniffi.nav_graph_core.*

data class LineResult(
    var text: String,
    val charBoxes: List<JpDictRect>,
    val alternatives: List<MutableList<Pair<Char, Float>>>,
    val isVertical: Boolean = false,
    val overrides: MutableMap<Int, Pair<Char, Float>> = mutableMapOf(),
    /** Cached top-15 alternatives for EVERY timestep, for slider re-decode without re-running model. */
    val rawAlternatives: List<List<Pair<Char, Float>>> = emptyList(),
    val seqLenTotal: Int = 0,
    val cropW: Int = 0,
    val cropH: Int = 0,
    val cropX: Int = 0,
    val cropY: Int = 0,
    /** CTC timestep column per emitted char (#49) — lets tests recompute
     * char boxes under any BOX_LAYOUT_MODE from one recognition run. */
    val charCols: FloatArray = floatArrayOf(),
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
    val isKanjiEntry: Boolean,
    /** #43: downstep positions for this reading (empty = no pitch data).
     * Rendered as one colored-morae item on the entry's pitch line. */
    val pitchPositions: List<Int> = emptyList()
)

data class FormattedEntry(
    val term: String,
    val readingGroups: List<FormattedReadingGroup>,
    /** Non-null when this entry matched via deinflection (or a JMdict
     * redirect, folded in as a "redirect" chain step, #65); null for direct
     * surface matches (which render exactly as before). */
    val deinflection: DeinflectionChain? = null,
    /** Display name of the dictionary this entry came from (null = unknown,
     * caption omitted). Entries never mix dictionaries. */
    val dictionaryName: String? = null
)

/** One lookup candidate: a dictionary-form term plus how it was reached.
 *  [requiredTypes] is null for direct surface variants; [chain] is null
 *  for direct matches and set for deinflected ones. */
data class SearchCandidate(
    val term: String,
    val requiredTypes: List<String>?,
    val chain: DeinflectionChain?
)

/** Dictionary entries for one matched term plus its deinflection chain. */
data class TermMatch(
    val term: String,
    val entries: List<DictionaryEntry>,
    val chain: DeinflectionChain? = null
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
    val isSelected: Boolean,
    /**
     * Where this entry came from (#44): the head's own ranking, a component neighbour of
     * the current character, or one of its variant forms. The panel tints the generated
     * entries so "what the model saw" stays distinguishable from "what the components
     * suggest".
     */
    val source: OovSuggestions.Source = OovSuggestions.Source.HEAD
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

    /**
     * Component-derived popup candidates (#44). Null until the service finishes loading the
     * 266 KB component table off the main thread; the panel falls back to the head's own
     * list meanwhile, which is exactly the previous behaviour.
     */
    private var oovCandidates: OovCandidates? = null

    /** Character LM for ranking gap candidates (#44). Null until the service loads it off
     *  the main thread, and null forever if the asset is missing — either way the blank
     *  just offers the placeholder alone. */
    private var charLm: CharLm? = null
    private var suggestionsEnabled: () -> Boolean = { false }
    var deinflector: Deinflector? = null
    var dictionaryProvider: DictionaryProvider? = null
    var gson: Gson? = null

    var currentScale = 1f
    var currentTransX = 0f
    var currentTransY = 0f
    var currentWordLength = 0

    /** Re-decode all lines with a new [blankThreshold] from cached per-timestep
     * alternatives — no model re-run (see `OcrEngine.reDecodeLineResult`).
     * Keeps the tapped character anchored by position, then rebuilds lookup data. */
    fun refreshLinesWithThreshold(ocrEngine: OcrEngine, blankThreshold: Float = 0f) {
        val oldTappedBoxCenter = if (currentTappedLineIdx != -1 && currentTappedCharIdxInLine != -1) {
            activeLineResults.getOrNull(currentTappedLineIdx)?.charBoxes?.getOrNull(currentTappedCharIdxInLine)?.let {
                Pair(it.centerX(), it.centerY())
            }
        } else null

        activeLineResults.forEachIndexed { i, line ->
            line?.let { oldLine ->
                if (oldLine.rawAlternatives.isNotEmpty()) {
                    // Re-decode from cached logits — no model re-run
                    val newLine = ocrEngine.reDecodeLineResult(oldLine, blankThreshold)
                    activeLineResults[i] = newLine
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

    /**
     * True when the character at [lineIdx]/[charIdx] is the reversible blank placeholder
     * (#44 Feature 2). [lookup] returns null for a placeholder, so the tap path branches on
     * this and opens the alternatives panel instead of doing dictionary work.
     */
    fun isBlankAt(lineIdx: Int, charIdx: Int): Boolean =
        activeLineResults.getOrNull(lineIdx)?.text?.getOrNull(charIdx) == OcrEngine.GAP_CHAR

    /**
     * Anchor the tapped position on a blank without running a lookup. The alternatives state
     * then carries the placeholder (marked selected) plus the manual input entry, which is
     * how the ground-truth character gets typed in; filling it writes an ordinary override,
     * so it reverts like any other correction.
     */
    fun selectBlankPosition(lineIdx: Int, charIdx: Int) {
        currentTappedIdx = getGlobalIdx(lineIdx, charIdx)
        currentTappedLineIdx = lineIdx
        currentTappedCharIdxInLine = charIdx
    }

    suspend fun lookup(lineIdx: Int, charIdx: Int): Result? {
        val deinf = deinflector ?: return null
        val provider = dictionaryProvider ?: return null
        val g = gson ?: return null

        val line = activeLineResults.getOrNull(lineIdx) ?: return null
        // A placeholder has no definition. The tap path routes it to the alternatives panel
        // instead (see [isBlankAt] / [selectBlankPosition]), where the manual IME fills it.
        if (line.text.getOrNull(charIdx) == OcrEngine.GAP_CHAR) return null

        val globalIdx = getGlobalIdx(lineIdx, charIdx)
        currentTappedIdx = globalIdx
        currentTappedLineIdx = lineIdx
        currentTappedCharIdxInLine = charIdx

        val tappedBox = line.charBoxes.getOrNull(charIdx) ?: JpDictRect(0, 0, 0, 0)

        val endIdx = kotlin.math.min(globalIdx + 20, activeAllChars.size)
        val followingText = activeAllChars.subList(globalIdx, endIdx).joinToString("")

        val (allTermsToSearch, candidatesByLength) = prepareSearchCandidates(followingText, deinf)
        val dbResults = provider.findByTexts(allTermsToSearch.toList())
        // dictionaryId → display name for per-entry source captions. Missing
        // map = captions omitted, entries still split per dictionary.
        val dictNames = try { provider.dictionaryNames() } catch (_: Exception) { emptyMap() }
        val (uniqueMatches, maxLen) = processResults(dbResults, candidatesByLength, allTermsToSearch, followingText)

        // ── Redirect pass (#65): JMdict pointer entries (variant spellings)
        // carry only ?query= links and render as dead "⟶, X" text. Resolve
        // them breadth-first (visited set + hop cap, so A→B→A cycles always
        // terminate), then splice each target directly below its source entry
        // (depth-first) so a redirect reads as pointer → target instead of
        // stranding the target at the end of the popup.
        val redirectVia = mutableMapOf<String, String>()
        val resolvedByTerm = mutableMapOf<String, TermMatch>()
        val childrenOf = mutableMapOf<String, MutableList<String>>()
        if (uniqueMatches.isNotEmpty()) {
            val visited = uniqueMatches.map { it.term }.toMutableSet()
            val queue = ArrayDeque<TermMatch>()
            uniqueMatches.forEach { queue.add(it) }
            var hops = 0
            while (queue.isNotEmpty() && hops < 3) {
                repeat(queue.size) {
                    val match = queue.removeFirst()
                    for (entry in match.entries) {
                        for (target in DictionaryRedirects.extractTargets(entry.definitions)) {
                            if (!visited.add(target)) continue
                            val targetResults = provider.findByTexts(listOf(target))
                            if (targetResults.isEmpty()) continue
                            redirectVia[target] = match.term
                            val resolved = TermMatch(target, targetResults.distinctBy { it.id })
                            resolvedByTerm[target] = resolved
                            childrenOf.getOrPut(match.term) { mutableListOf() }.add(target)
                            queue.add(resolved)
                        }
                    }
                }
                hops++
            }
        }
        val resolvedMatches = mutableListOf<TermMatch>()
        fun emit(match: TermMatch) {
            resolvedMatches.add(match)
            childrenOf[match.term]?.forEach { child ->
                resolvedByTerm[child]?.let { emit(it) }
            }
        }
        uniqueMatches.forEach { emit(it) }

        val formatted = formatDictionaryResults(resolvedMatches, g, dictNames).toMutableList()
        for (i in formatted.indices) {
            redirectVia[formatted[i].term]?.let { via ->
                // The redirect hop joins the deinflection chain ("via → term
                // · redirect") instead of a separate caption. Redirect targets
                // never carry a chain of their own (real entries with glosses
                // never redirect), but guard anyway.
                if (formatted[i].deinflection == null) {
                    formatted[i] = formatted[i].copy(
                        deinflection = DeinflectionChain(surface = via, steps = listOf("redirect"))
                    )
                }
            }
        }
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
                val formattedKanji = formatDictionaryResults(listOf(TermMatch(kanjiStr, kanjiOnly)), g, dictNames)
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

    fun getAlternativesUiState(
        lineIdx: Int = currentTappedLineIdx,
        charIdx: Int = currentTappedCharIdxInLine,
    ): AlternativesUiState? {
        // Indices are parameters, not just the controller's own fields: the neighbour panel
        // opens the alternatives for a character it was handed, and without a lookup first
        // those fields are stale — which showed up as a completely blank list (#44).
        val line = activeLineResults.getOrNull(lineIdx) ?: return null
        val candidates = alternativeCharsFor(line, charIdx) ?: return null

        return AlternativesUiState(candidates, showManualInput = true)
    }

    /**
     * The popup list for one character (#44): the head's own top-15, plus — when the
     * component table has loaded and the setting is on — component neighbours and variant
     * forms of that character. Both the panel and keyboard navigation read this, so they
     * can never disagree about what the list contains.
     */
    private fun alternativeCharsFor(line: LineResult, cIdx: Int): List<AlternativeChar>? {
        val current = line.text.getOrNull(cIdx)
        // A blank is not a character to expand by components: its list is the placeholder
        // plus the evidence-ranked candidates, and it is checked *before* the alternatives
        // table because the table does have an entry for the placeholder — an earlier version
        // handled only the case where it did not, so the ranked candidates were unreachable
        // and the list came back as the dotted circle alone (#44).
        if (current == OcrEngine.GAP_CHAR) return gapCandidates(line, cIdx)
        val alts = line.alternatives.getOrNull(cIdx) ?: return null
        val head = alts.take(15).map { it.first }
        val suggestions = if (suggestionsEnabled() && oovCandidates != null && current != null) {
            OovSuggestions.assemble(current, head, oovCandidates)
        } else {
            head.map { OovSuggestions.Suggestion(it, OovSuggestions.Source.HEAD) }
        }
        return suggestions.map {
            AlternativeChar(it.char, isSelected = it.char == current, source = it.source)
        }
    }

    /**
     * The blank's list: the placeholder itself (so the entry is selectable and carries the
     * manual IME) followed by the LM-ranked kanji the recogniser offered along this line.
     */
    private fun gapCandidates(line: LineResult, cIdx: Int): List<AlternativeChar> {
        val out = mutableListOf(
            AlternativeChar(OcrEngine.GAP_CHAR, isSelected = true, source = OovSuggestions.Source.HEAD))
        val ranked = if (suggestionsEnabled()) {
            GapCandidates.generate(line.text, line.rawAlternatives, cIdx, charLm)
        } else {
            emptyList()
        }
        // Evidence first, then the punctuation and kana a gap most often holds. A blank with
        // nothing to choose from is worse than a guess, so this list is never just the
        // placeholder; even the fallback is ordered by context when the model is loaded.
        val alternatives = ranked.ifEmpty { GapCandidates.fallback(line.text, cIdx, charLm) }
        alternatives.forEach { out.add(AlternativeChar(it, isSelected = false, source = OovSuggestions.Source.LM)) }
        return out
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
        val currentChar = line.text.getOrNull(currentTappedCharIdxInLine) ?: return null
        val candidates = alternativeCharsFor(line, currentTappedCharIdxInLine)?.map { it.char }
            ?: return null
        val currentIndex = candidates.indexOf(currentChar)

        if (currentIndex != -1) {
            val newIndex = (currentIndex + diff).coerceIn(0, candidates.size - 1)
            if (newIndex != currentIndex) {
                return candidates[newIndex]
            }
        }
        return null
    }

    /**
     * Component-derived suggestions (#44). The service loads the 266 KB component table off
     * the main thread and calls this once it lands; until then the panel shows the head's
     * own list, exactly as before. [enabled] is read per call so the setting takes effect
     * without reinstalling.
     */
    fun installOovSuggestions(candidates: OovCandidates, enabled: () -> Boolean) {
        oovCandidates = candidates
        suggestionsEnabled = enabled
    }

    /**
     * The character LM the blank's candidates are ranked with (#44). Loaded by the service
     * off the main thread like the component table; until it lands, and if the asset is
     * missing, a blank offers the placeholder and the manual IME and nothing else.
     */
    fun installCharLm(lm: CharLm?) {
        charLm = lm
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
    ): Pair<Set<String>, List<Pair<Int, List<SearchCandidate>>>> {
        val allTermsToSearch = mutableSetOf<String>()
        val candidatesByLength = mutableListOf<Pair<Int, List<SearchCandidate>>>()

        for (len in followingText.length downTo 1) {
            val queryTextRaw = followingText.substring(0, len)
            val queryText = JapaneseUtil.normalize(queryTextRaw)

            // #75: pre-reform orthography. The modern form of the prefix is searched
            // as one more variant — the raw prefix is still in the list, so this can
            // only add a reachable headword, never take one away. Displayed text is
            // untouched: only this query string is rewritten.
            val modernised = KanaOrthography.modernise(queryText)

            // The RAW prefix is searched alongside its folded form. The fold is a
            // substitution, so folding alone replaced the queried form outright: an old
            // form the head can emit (摑) resolved to the modern headword and the old
            // form's OWN entries — a kanjidic row, a dictionary that indexes 摑 — were
            // never looked up at all (#44). Searching both keeps the redirect *and* the
            // entries the old form has in dictionaries that carry it.
            val variants = listOf(
                queryTextRaw,
                queryText,
                JapaneseUtil.katakanaToHiragana(queryText),
                JapaneseUtil.collapseEmphatic(queryText),
                modernised,
                JapaneseUtil.katakanaToHiragana(modernised)
            ).distinct()

            val deinflections = deinflector.deinflect(queryText)
            // The deinflection rules are modern orthography (ちゃう, った, かった). A
            // legacy surface has to be normalised before they can fire at all, so the
            // modern form is deinflected as well — additive, like the variant above.
            val modernisedDeinflections =
                if (modernised != queryText) deinflector.deinflect(modernised) else emptyList()
            val lengthCandidates = mutableListOf<SearchCandidate>()
            variants.forEach { lengthCandidates.add(SearchCandidate(it, null, null)); allTermsToSearch.add(it) }
            deinflections.forEach {
                if (it.term != queryText && it.reasons.isNotEmpty()) {
                    lengthCandidates.add(SearchCandidate(it.term, it.type, DeinflectionChain(queryTextRaw, it.reasons)))
                    allTermsToSearch.add(it.term)
                }
            }
            modernisedDeinflections.forEach {
                if (it.term != modernised && it.reasons.isNotEmpty()) {
                    lengthCandidates.add(SearchCandidate(it.term, it.type, DeinflectionChain(queryTextRaw, it.reasons)))
                    allTermsToSearch.add(it.term)
                }
            }
            candidatesByLength.add(len to lengthCandidates)
        }
        return Pair(allTermsToSearch, candidatesByLength)
    }

    fun processResults(
        dbResults: List<DictionaryEntry>,
        candidatesByLength: List<Pair<Int, List<SearchCandidate>>>,
        allTermsToSearch: Set<String>,
        followingText: String
    ): Pair<List<TermMatch>, Int> {
        val resultsByTerm = mutableMapOf<String, MutableList<DictionaryEntry>>()
        dbResults.forEach { entry ->
            if (entry.kanji in allTermsToSearch) resultsByTerm.getOrPut(entry.kanji) { mutableListOf() }.add(entry)
            if (entry.reading in allTermsToSearch) resultsByTerm.getOrPut(entry.reading) { mutableListOf() }.add(entry)
        }

        val matches = mutableListOf<TermMatch>()
        var maxLen = 0
        for ((len, candidates) in candidatesByLength) {
            var found = false
            for ((term, requiredTypes, chain) in candidates) {
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
                    matches.add(TermMatch(term, filteredResults.distinctBy { it.id }, chain))
                    found = true
                }
            }
            if (found && maxLen == 0) maxLen = len
        }
        return matches.distinctBy { it.term } to maxLen
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
            line.charBoxes.map { it.height() }.maxOrNull() ?: 0
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
        matches: List<TermMatch>,
        gson: Gson,
        dictNames: Map<Int, String> = emptyMap()
    ): List<FormattedEntry> {
        // One entry per (term, dictionary): JMdict and KANJIDIC rows must
        // never merge into a single block. findByTexts returns priority
        // order, so groupBy preserves dictionary ranking.
        return matches.flatMap { (term, entries, chain) ->
            // #43: pitch rows (from any imported pitch dictionary — ours is a
            // built-in) are data, not entries, so they are split out and keyed
            // by reading rather than rendered. Reading-keyed means a kana
            // form's pitch still lands on the matching group.
            val (pitchEntries, termEntries) = entries.partition {
                PitchAccent.positionsOf(it.definitions) != null
            }
            val pitchByReading = pitchEntries
                .groupBy { PitchAccent.readingOf(it.definitions) ?: it.reading }
                .mapValues { (_, rows) ->
                    rows.flatMap { PitchAccent.positionsOf(it.definitions).orEmpty() }
                        .distinct()
                        .sorted()
                }
            if (termEntries.isEmpty()) return@flatMap emptyList()

            // One entry per (term, dictionary): JMdict and KANJIDIC rows must
            // never merge into a single block. findByTexts returns priority
            // order, so groupBy preserves dictionary ranking.
            termEntries.groupBy { it.dictionaryId }.map { (dictId, dictEntries) ->
            val readingGroups = dictEntries.groupBy { it.reading }.map { (reading, readingEntries) ->
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

                FormattedReadingGroup(
                    reading,
                    headwords,
                    senseGroups,
                    isKanjiEntry,
                    pitchPositions = pitchByReading[reading].orEmpty()
                )
            }
            FormattedEntry(
                term,
                readingGroups,
                deinflection = chain,
                dictionaryName = dictNames[dictId]
            )
            }
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
