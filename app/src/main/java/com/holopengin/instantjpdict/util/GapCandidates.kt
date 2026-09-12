package com.holopengin.instantjpdict.util

import com.holopengin.instantjpdict.OcrEngine

/**
 * What to offer for a blank (#44, Feature 2).
 *
 * A gap is only worth filling when there is evidence for what went there, so the pool is
 * the set of characters the recogniser itself offered along the line — its per-character
 * top-K — restricted to kanji, and the language model then ranks that pool in the line's
 * own context. Shape evidence proposes, the text prior disposes: the component ordering
 * over an unrestricted pool is degenerate, which is why the LM earns its asset here.
 *
 * Deliberately narrow: the pool is the line's own character space, not the whole component
 * closure. Widening it to `ComponentTable` carriers is a later step, and it needs its own
 * measurement before it ships.
 */
object GapCandidates {
    const val MAX = 15

    private const val CJK_UNIFIED_START = 0x4E00
    private const val CJK_UNIFIED_END = 0x9FFF
    private const val CJK_EXT_A_START = 0x3400
    private const val CJK_EXT_A_END = 0x4DBF

    /** True for the kanji blocks the app's dictionary and component table cover. */
    fun isKanji(ch: Char): Boolean =
        ch.code in CJK_UNIFIED_START..CJK_UNIFIED_END || ch.code in CJK_EXT_A_START..CJK_EXT_A_END

    /**
     * Whether a character the recogniser proposed is worth offering. Kanji, kana and
     * punctuation alike: the gap in vertical Japanese text is very often a 読点 or a bracket
     * (a line's own evidence for it is punctuation), so a kanji-only pool comes back empty
     * exactly where the evidence was there.
     */
    fun isOfferable(ch: Char): Boolean =
        ch != OcrEngine.GAP_CHAR && !ch.isWhitespace() && !ch.isISOControl()

    /**
     * Candidates for the blank at [index], best first. [alternatives] is the line's
     * per-character top-K (anything else is ignored), and [lm] reorders the pool; without
     * one the pool keeps its discovery order.
     */
    fun generate(
        text: String,
        alternatives: List<List<Pair<Char, Float>>>,
        index: Int,
        lm: CharLm?,
        limit: Int = MAX,
    ): List<Char> {
        val pool = LinkedHashSet<Char>()
        for (alts in alternatives) {
            for ((ch, _) in alts) {
                if (isOfferable(ch)) pool.add(ch)
            }
        }
        if (pool.isEmpty()) return emptyList()
        val ordered = lm?.rank(contextBefore(text, index), pool.toList()) ?: pool.toList()
        return ordered.take(limit)
    }

    /**
     * The characters before the gap, which is the context the back-off chain can use: the
     * model is order 4, so anything longer is ignored and the placeholder itself is dropped
     * rather than read as a real character.
     */
    fun contextBefore(text: String, index: Int): CharSequence {
        val end = index.coerceIn(0, text.length)
        var start = end
        while (start > 0 && text[start - 1] != OcrEngine.GAP_CHAR && end - start < CharLm.MAX_ORDER - 1) start--
        return text.substring(start, end)
    }
}
