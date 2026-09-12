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
 * ε is [EPSILON], and the *gated* measurement behind it (over 7,620 confusable bench positions)
 * is +8 net at ε=0.01 (12 fixed / 4 broken), +10 at 0.03, +5 at 0.10. The ungated rule read
 * -241: the model is modern-trained by design, so on pre-reform text it sees a modern small-kana
 * context and confidently calls a legitimate large つ small. Hence the gate below.
 *
 * ## Why the gate needs the whole page
 *
 * [LegacyOrthographyGate] detects pre-reform text from a *pattern* — big つ/ツ the model calls
 * small with high confidence, at more than [LegacyOrthographyGate.MIN_KANA]-kana-worth of
 * evidence and above its rate threshold. One line is never enough evidence, and a page's lines
 * arrive together, so this runs over the whole page before any flip is applied.
 *
 * The gate's default is **modern**: thin evidence, a short page, or a single disagreement all
 * allow correction. It suppresses only on a decisive kana or a repeated pattern. That is
 * deliberate — almost all text the overlay sees is modern, and pre-reform text announces itself
 * as a pattern, so a gate that withheld by default would hide the correction exactly where it
 * is wanted.
 *
 * ## Reversibility
 *
 * A flip writes the same `overrides[i]` entry a manual correction writes, so it is visible in
 * the text and undoable exactly like any other correction.
 */
object KanaSizeFix {
    const val PREF_ENABLED = "kana_size_fix_enabled"

    /**
     * Off by default. It rewrites recognised text, so it is opt-in — the same posture as the
     * other text-changing features, and the measured net effect is small enough that a user
     * should be able to compare with and without.
     */
    const val DEF_ENABLED = false

    /** Certainty required to flip; the middle band is deliberately left untouched. */
    const val EPSILON = 0.01f

    private const val TAG = "KanaSizeFix"

    /** Big form -> small form, inverted from the encoder's map. */
    private val SMALL_OF: Map<Char, Char> =
        KanaSizeEncoder.SMALL_TO_BIG.entries.associate { (small, big) -> big to small }

    /** Human-readable outcome of the last run, for the in-app diagnostics. */
    @Volatile
    var lastSummary: String = "kana fix: idle"
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
                val corrected = apply(lines) { wins, bases -> model.logits(wins, bases) }
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
            lastSummary = "kana fix: no confusable positions"
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

        // Era gate: every line contributes its kana count, and every position its disagreement.
        val gate = LegacyOrthographyGate()
        for (line in lines) gate.observeLine(line.text)
        for ((k, c) in cands.withIndex()) gate.observePosition(c.char, probBig(logits[k]))
        if (!gate.allowsCorrection()) {
            lastSummary = "kana fix: pre-reform text (%.1f per 100 kana) - withheld".format(gate.rate())
            return lines
        }

        val flips = HashMap<Int, MutableList<Pair<Int, Pair<Char, Float>>>>()
        var small = 0
        var big = 0
        for ((k, c) in cands.withIndex()) {
            val p = probBig(logits[k])
            val isSmall = KanaSizeEncoder.isSmall(c.char)
            // Parenthesised so `?:` covers both branches: a nullable target here would have
            // propagated into the override map's type and failed to compile.
            val target = (if (isSmall) KanaSizeEncoder.bigFormOf(c.char) else SMALL_OF[c.char]) ?: continue
            val flipsIt = if (isSmall) p > 1f - EPSILON else p < EPSILON
            if (!flipsIt) continue
            if (isSmall) small++ else big++
            flips.getOrPut(c.line) { mutableListOf() }.add(c.index to (target to p))
        }

        if (flips.isEmpty()) {
            lastSummary = "kana fix: %d positions considered, none certain enough".format(cands.size)
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
        lastSummary = "kana fix: %d positions, %d small->big, %d big->small on %d lines"
            .format(cands.size, small, big, flips.size)
        return out
    }

    private fun probBig(logit: Float): Float = 1f / (1f + kotlin.math.exp(-logit).toFloat())
}
