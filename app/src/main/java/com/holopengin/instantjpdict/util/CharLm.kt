package com.holopengin.instantjpdict.util

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Character n-gram language model (#44, Feature 2): the text prior that ranks candidates
 * where shape alone cannot — the deletion pools are ~767 candidates on median and the
 * component ordering over them is degenerate, so the LM is the only ranker available.
 *
 * ## File format (`assets/lm/char_lm.bin`, written by `tools/pack_char_lm.py`)
 *
 * ```
 * header  12 bytes  magic "CLM1", entry count (u32), max order (u32)   — little endian
 * record  10 bytes  n-gram: up to 4 UTF-16 code units, zero-padded on the right,
 *                   then the count saturated at 65535
 * ```
 *
 * Records are sorted by the n-gram's own code-unit order (zero-padded), so a shorter
 * n-gram sorts immediately before its extensions and one binary search finds an entry of
 * any order. U+0000 never occurs in Japanese text, which is what makes the padding
 * unambiguous. Counts are saturated at 16 bits because they are only ever used as ratios.
 */
class CharLm private constructor(
    private val data: ByteBuffer,
    private val entries: Int,
    /** Corpus length, from the header: the unigram prior needs it and a scan to recover
     *  it would cost 1.4M records per candidate. */
    private val unigramMass: Int,
) {
    /** Occurrences of [ngram], or 0 when it is unknown (or longer than the model's order). */
    fun count(ngram: CharSequence): Int {
        val wanted = key(ngram) ?: return 0
        var lo = 0
        var hi = entries - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val base = HEADER + mid * RECORD
            var cmp = 0
            for (i in 0 until MAX_ORDER) {
                val got = data.getShort(base + i * 2).toInt() and 0xFFFF
                cmp = got - wanted[i]
                if (cmp != 0) break
            }
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return data.getShort(base + 8).toInt() and 0xFFFF
            }
        }
        return 0
    }

    /**
     * log P([ch] | [context]): the longest context the model knows, backed off one character
     * at a time, and a unigram prior when nothing longer is known. Unknown characters score
     * [UNSEEN] so they sort last rather than tie with real evidence.
     */
    fun logProb(context: CharSequence, ch: Char): Float {
        val maxCtx = MAX_ORDER - 1
        val ctx = if (context.length > maxCtx) context.subSequence(context.length - maxCtx, context.length)
                  else context
        for (len in ctx.length downTo 1) {
            val ngram = ctx.subSequence(ctx.length - len, ctx.length).toString() + ch
            val joint = count(ngram)
            if (joint > 0) {
                val denom = count(ctx.subSequence(ctx.length - len, ctx.length))
                if (denom > 0) return ln(joint.toFloat() / denom.toFloat())
            }
        }
        val uni = count(ch.toString())
        return if (uni > 0 && unigramMass > 0) ln(uni.toFloat() / unigramMass.toFloat()) else UNSEEN
    }

    /**
     * [candidates] ordered by how well [context] predicts them, best first. Ties keep the
     * input order, so a caller's own ranking still breaks them deterministically.
     */
    fun rank(context: CharSequence, candidates: List<Char>): List<Char> =
        candidates.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Char>> { logProb(context, it.value) }
                .thenBy { it.index })
            .map { it.value }

    companion object {
        private const val HEADER = 16
        private const val RECORD = 10
        const val MAX_ORDER = 4
        private const val MAGIC = 0x314D4C43      // "CLM1" little endian
        private const val UNSEEN = -30f

        private fun ln(x: Float): Float = kotlin.math.ln(x)

        /** Null when [ngram] is empty or longer than the model's order. */
        private fun key(ngram: CharSequence): IntArray? {
            val n = ngram.length
            if (n == 0 || n > MAX_ORDER) return null
            val out = IntArray(MAX_ORDER)
            for (i in 0 until n) out[i] = ngram[i].code
            return out
        }

        /** Wrap packed bytes. Null when the header or length does not describe a table. */
        fun fromBytes(bytes: ByteArray): CharLm? {
            if (bytes.size < HEADER) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.getInt(0)
            val count = buf.getInt(4)
            val order = buf.getInt(8)
            val mass = buf.getInt(12)
            if (magic != MAGIC || order !in 1..MAX_ORDER || count <= 0 || mass <= 0) return null
            if (bytes.size < HEADER + count * RECORD) return null
            return CharLm(buf, count, mass)
        }

        /** Load the shipped model, or null when the asset is missing or malformed. */
        fun load(context: Context): CharLm? = try {
            context.assets.open("lm/char_lm.bin").use { fromBytes(it.readBytes()) }
        } catch (e: Exception) {
            null
        }
    }
}
