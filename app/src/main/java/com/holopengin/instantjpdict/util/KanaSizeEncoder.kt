package com.holopengin.instantjpdict.util

/**
 * Window encoder for the small/large kana size model (#44).
 *
 * The model decides, for one kana position, whether it should be the BIG form (つ) or the small
 * one (っ). Its input is the surrounding text only — the target character itself is *not* in the
 * window, and the pair identity travels through the base index instead. That is deliberate: if
 * the target were fed in, the model would mostly agree with whatever the OCR already emitted,
 * which is useless exactly where the OCR is wrong.
 *
 * Layout (40 bytes), exactly as the model's interface document specifies:
 *   cells 0..4  left context, leftmost first:  L5 L4 L3 L2 L1
 *   cells 5..9  right context, nearest first:  R1 R2 R3 R4 R5
 *   each cell = the character's UTF-8 bytes, left-aligned, zero-padded to 4
 *   an absent cell (past the start or end of the line) is four zero bytes
 *
 * Verified byte-exact against the model's published validation vectors; see
 * `KanaSizeEncoderTest`.
 */
object KanaSizeEncoder {

    /** Pair index. The BIG form is the base, hiragana then katakana. */
    val BASE_ORDER: List<Char> = listOf(
        'あ', 'い', 'う', 'え', 'お', 'つ', 'や', 'ゆ', 'よ', 'わ',
        'ア', 'イ', 'ウ', 'エ', 'オ', 'ツ', 'ヤ', 'ユ', 'ヨ', 'ワ',
    )

    /** Small form -> big form. The app's own copy; the model's table is the authority. */
    val SMALL_TO_BIG: Map<Char, Char> = mapOf(
        'ぁ' to 'あ', 'ぃ' to 'い', 'ぅ' to 'う', 'ぇ' to 'え', 'ぉ' to 'お', 'ゎ' to 'わ',
        'っ' to 'つ', 'ゃ' to 'や', 'ゅ' to 'ゆ', 'ょ' to 'よ',
        'ァ' to 'ア', 'ィ' to 'イ', 'ゥ' to 'ウ', 'ェ' to 'エ', 'ォ' to 'オ', 'ヮ' to 'ワ',
        'ッ' to 'ツ', 'ャ' to 'ヤ', 'ュ' to 'ユ', 'ョ' to 'ヨ',
    )

    private val INDEX: Map<Char, Int> = BASE_ORDER.withIndex().associate { (i, c) -> c to i }

    /** The canonical big form for either member of a pair, or null if [ch] is not one. */
    fun bigFormOf(ch: Char): Char? = SMALL_TO_BIG[ch] ?: if (INDEX.containsKey(ch)) ch else null

    /**
     * Pair index for the position holding [ch], or null when [ch] is not part of a size pair.
     * Both っ and つ map to the same index: the pair is the class, the size is the decision.
     */
    fun baseIndexOf(ch: Char): Int? = bigFormOf(ch)?.let { INDEX[it] }

    /** Whether [ch] is the small member of a pair, i.e. what the model would be correcting. */
    fun isSmall(ch: Char): Boolean = SMALL_TO_BIG.containsKey(ch)

    private const val RADIUS = 5
    private const val CELL = 4
    const val WINDOW_BYTES = 2 * RADIUS * CELL

    private fun cell(ch: Char): IntArray {
        val bytes = ch.toString().toByteArray(Charsets.UTF_8)
        val out = IntArray(CELL)
        for (i in 0 until minOf(bytes.size, CELL)) out[i] = bytes[i].toInt() and 0xFF
        return out
    }

    private val EMPTY = IntArray(CELL)

    /**
     * The 40-byte window for the position at [index] in [text]. The character at [index] is
     * excluded; context runs off the ends as zero padding, which is what the app itself sees,
     * since it recognises line by line and has no text beyond the line's edges.
     */
    fun window(text: CharSequence, index: Int): IntArray {
        val out = IntArray(WINDOW_BYTES)
        var w = 0
        for (k in RADIUS downTo 1) {                       // L5..L1, leftmost first
            val j = index - k
            val src = if (j in text.indices) cell(text[j]) else EMPTY
            src.copyInto(out, w); w += CELL
        }
        for (k in 1..RADIUS) {                             // R1..R5, nearest first
            val j = index + k
            val src = if (j in text.indices) cell(text[j]) else EMPTY
            src.copyInto(out, w); w += CELL
        }
        return out
    }
}
