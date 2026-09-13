package com.holopengin.instantjpdict.util

import android.content.Context
import android.util.Log
import com.holopengin.instantjpdict.KanaSizeNcnn
import com.holopengin.instantjpdict.LineResult
import com.holopengin.instantjpdict.OcrEngine

/**
 * Kana size correction (#44): let the byte-CNN decide the small/big form of a confusable
 * position, but only where the orthography allows it.
 *
 * ## The rule
 *
 * For each position whose character is a member of a size pair, the model emits p(big):
 *
 * - flip small -> big when `p > 1 - ε`
 * - flip big -> small when `p < ε`
 * - leave the middle band alone
 *
 * ε is [EPSILON] = 0.01. The measurement behind it (7,620 confusable bench positions, the
 * grounded rule applied at three epsilon bands) reproduced the app's original +8/+10/+5 on the
 * v2 artifact exactly, which is the check that the harness is wired right.
 *
 * The shipped artifact is nb_all. Its case is era, not modern accuracy:
 *
 * - on modern rows, nb_all sits ~4 restored flips per 6,274 positions (0.06%) behind a
 *   modern-only model — below the noise of a re-render;
 * - on pre-reform text a modern-only model is confidently wrong in one direction, calling a
 *   legitimate large つ small. Measured against JMdict — the headwords a lookup actually has to
 *   reach, not the book's own orthography — that destroys 29 reachable headwords on the legacy
 *   slice and restores 2, where nb_all destroys none and restores 2.
 *
 * A pre-reform *gate* was tried and removed. It decided page-wide from a kana ratio, so a small
 * scroll could flip a whole page's correction off, and with era-inclusive training the pattern it
 * keyed on no longer occurs. Choosing the artifact replaces it.
 *
 * The numbers above belong to the artifact that shipped; re-derive them with the bench if that
 * changes.
 *
 * ## Reversibility
 *
 * A flip writes the same `overrides[i]` entry a manual correction writes, so it is visible in
 * the text and undoable exactly like any other correction.
 */
object KanaSizeFix {
    const val PREF_ENABLED = "kana_size_fix_enabled"

    /**
     * Active by default, tuned for modern Japanese. It rewrites recognised text, so the setting
     * exists to compare with and without rather than to be opt-in.
     */
    const val DEF_ENABLED = true

    /** Certainty required to flip; the middle band is deliberately left untouched. */
    const val EPSILON = 0.01f

    /** Tunable copy of [EPSILON], so the threshold can be found on-device without a rebuild. */
    const val PREF_EPSILON = "kana_size_epsilon"
    const val DEF_EPSILON = EPSILON

    fun epsilon(ctx: Context): Float =
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(PREF_EPSILON, DEF_EPSILON)

    private const val TAG = "KanaSizeFix"

    /** Big form -> small form, inverted from the encoder's map. */
    private val SMALL_OF: Map<Char, Char> =
        KanaSizeEncoder.SMALL_TO_BIG.entries.associate { (small, big) -> big to small }

    /** Human-readable outcome of the last run, for the in-app diagnostics. */
    @Volatile
    var lastSummary: String = "kana fix: idle"
        private set

    /**
     * The positions the model declined to change, lowest confidence first, as
     * `L<line>@<index> <char> p=<p>`. Deliberately no surrounding text: this is copied out of the
     * app and shared, so it must not carry the book's own words.
     */
    @Volatile
    var lastDeclined: String = ""
        private set

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, DEF_ENABLED)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, enabled).apply()
    }

    /** Apply the correction to a whole page when the setting is on, else return it unchanged. */
    fun applyIfEnabled(ctx: Context, lines: List<LineResult>): List<LineResult> {
        if (!isEnabled(ctx)) {
            // Recorded rather than left stale, so the diagnostics can say "off" instead of
            // showing the result of some earlier run.
            lastSummary = "kana fix: off"
            return lines
        }
        return try {
            val model = KanaSizeNcnn.load(ctx)
            if (model == null) {
                lastSummary = "kana fix: model unavailable"
                lines
            } else {
                val corrected = apply(
                    lines,
                    score = { wins, bases -> model.logits(wins, bases) },
                    epsilon = epsilon(ctx))
                Log.d(TAG, lastSummary)
                corrected
            }
        } catch (t: Throwable) {
            // Throwable, not Exception: an UnsatisfiedLinkError from the native library is an
            // Error, and catching only Exception would let it take down recognition.
            Log.e(TAG, "kana size correction failed", t)
            lastSummary = "kana fix: failed (${t.javaClass.simpleName}: ${t.message})"
            lines
        }
    }

    /**
     * The policy, with scoring injected so it is testable on the JVM without the native layer.
     * [score] takes `n * 40` window bytes and `n` pair indices and returns `n` logits.
     */
    internal fun apply(
        lines: List<LineResult>,
        score: (IntArray, IntArray) -> FloatArray?,
        /** Certainty required to flip; [epsilon] from the settings, or the measured default. */
        epsilon: Float = EPSILON,
    ): List<LineResult> {
        data class Cand(val line: Int, val index: Int, val char: Char, val base: Int)

        val cands = ArrayList<Cand>()
        for ((li, line) in lines.withIndex()) {
            for (i in line.text.indices) {
                val ch = line.text[i]
                val base = KanaSizeEncoder.baseIndexOf(ch) ?: continue
                cands.add(Cand(li, i, ch, base))
            }
        }
        if (cands.isEmpty()) {
            lastSummary = "kana fix: no candidates"
            return lines
        }

        val wins = IntArray(cands.size * KanaSizeNcnn.WINDOW_BYTES)
        val bases = IntArray(cands.size)
        for ((k, c) in cands.withIndex()) {
            KanaSizeEncoder.window(lines[c.line].text, c.index)
                .copyInto(wins, k * KanaSizeNcnn.WINDOW_BYTES)
            bases[k] = c.base
        }

        val logits = score(wins, bases)
        if (logits == null || logits.size != cands.size) {
            // No Android logging here: `apply` stays dependency-free so the policy is testable
            // on a plain JVM. The caller reports [lastSummary].
            lastSummary = "kana fix: scorer returned ${logits?.size ?: "null"} for ${cands.size}"
            return lines
        }

        val flips = HashMap<Int, MutableList<Pair<Int, Pair<Char, Float>>>>()
        var flipped = 0
        val declined = ArrayList<Pair<Triple<Int, Int, Char>, Float>>()
        for ((k, c) in cands.withIndex()) {
            val p = probBig(logits[k])
            val isSmall = KanaSizeEncoder.isSmall(c.char)
            val target = (if (isSmall) KanaSizeEncoder.bigFormOf(c.char) else SMALL_OF[c.char]) ?: continue
            val flipsIt = if (isSmall) p > 1f - epsilon else p < epsilon
            if (!flipsIt) {
                // The closest few by name: this tells a threshold problem apart from the model
                // simply agreeing with the recogniser. No near-threshold count any more - the
                // p values here say it better, and the status line stays short.
                declined.add(Triple(c.line, c.index, c.char) to (if (isSmall) 1f - p else p))
                continue
            }
            flipped++
            flips.getOrPut(c.line) { mutableListOf() }.add(c.index to (target to p))
        }
        lastDeclined = declined.sortedBy { it.second }.take(5).joinToString(" / ") {
            "L%d@%d %s p=%.3f".format(it.first.first, it.first.second, it.first.third, it.second)
        }

        if (flips.isEmpty()) {
            lastSummary = "kana fix: 0 of %d flipped".format(cands.size)
            return lines
        }

        val out = lines.toMutableList()
        for ((li, edits) in flips) {
            val line = lines[li]
            val chars = line.text.toCharArray()
            val overrides = LinkedHashMap(line.overrides)
            // Each entry is (character index, the override to record) — the same
            // `overrides[i] = char to p` shape a manual correction writes.
            for ((index, override) in edits) {
                if (index !in chars.indices) continue
                chars[index] = override.first
                overrides[index] = override
            }
            out[li] = line.copy(text = String(chars), overrides = overrides)
        }
        lastSummary = "kana fix: %d of %d flipped".format(flipped, cands.size)
        return out
    }

    private fun probBig(logit: Float): Float = 1f / (1f + kotlin.math.exp(-logit).toFloat())
}
