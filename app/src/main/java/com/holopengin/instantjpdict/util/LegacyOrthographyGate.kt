package com.holopengin.instantjpdict.util

/**
 * Pre-reform orthography gate for the kana size correction (#44).
 *
 * The size model was trained on modern orthography, and 旧仮名 writes 促音 with a LARGE つ:
 * 「〜だつた」 (= だった), 「懸つた」, 「ベツド」. The rendered glyph genuinely is 大つ, so the
 * recogniser reads it correctly and the model confidently "corrects" it to っ — measured at
 * 66.9% accuracy on 1,346 legacy big-side positions against 98.4% on modern ones. So the
 * correction must be suppressed on legacy text; this is the load-bearing safety gate, not an
 * optimisation.
 *
 * Two signals, one cheap and one statistical:
 *
 *  - **ゐ ゑ ヰ ヱ** never occur in modern orthography, so one is decisive. But they are not
 *    sufficient: the works that actually break the model need not contain any.
 *  - **The model's disagreement pattern.** On legacy pages the OCR emits 大つ while the model
 *    is extremely confident "small" (p < 0.03), repeatedly. Measured per rendered line on the
 *    bench, hits per 100 kana separate by roughly 100x:
 *
 *      legacy   25.71 (horizontal)  25.85 (vertical)
 *      modern    0.06               0.29
 *
 *    so a threshold near 2/100 sits about 7x above the worst modern case and 13x below legacy.
 *
 * Accumulate over whatever the overlay currently has loaded — a page's worth of lines, not a
 * single line, because a legacy line may simply contain no 促音 — and reset when new text
 * arrives. The gate is fail-safe in the direction that matters: suppressing correction only
 * withholds a fix, whereas applying it to legacy text corrupts a correct reading.
 */
class LegacyOrthographyGate(
    private val confidentSmall: Float = 0.03f,
    private val rateThreshold: Float = 0.02f,
    private val minHits: Int = 2,
    private val minKana: Int = 30,
) {
    /** Characters that cannot occur in modern orthography. */
    private val decisive = setOf('ゐ', 'ゑ', 'ヰ', 'ヱ')

    private var kana = 0
    private var hits = 0
    private var decisiveSeen = false

    /** Reset for a new page of detected text. */
    fun reset() {
        kana = 0
        hits = 0
        decisiveSeen = false
    }

    /** Note a line of recognised text; its kana count is the denominator. */
    fun observeLine(recognised: CharSequence) {
        for (c in recognised) {
            if (c in decisive) decisiveSeen = true
            if (c in KANA) kana++
        }
    }

    /**
     * Note one position the recogniser emitted. [emittedBigTsu] is the character the OCR chose;
     * a big つ/ツ that the model calls small with high confidence is the legacy fingerprint.
     */
    fun observePosition(emitted: Char, pBig: Float) {
        if ((emitted == 'つ' || emitted == 'ツ') && pBig < confidentSmall) hits++
    }

    /** Hits per 100 kana seen so far. */
    fun rate(): Float = if (kana == 0) 0f else 100f * hits / kana

    /**
     * Whether the loaded text looks pre-reform.
     *
     * Needs a *repeated* disagreement, not one: a single hit on a short page already reads as
     * 2.9 per 100 kana and would suppress correction on modern text, whereas the legacy signal
     * is a pattern (legacy lines average 25.8 per 100 with many hits). With too little evidence
     * to judge at all this also says no - the decisive markers still fire on their own.
     */
    fun isLegacy(): Boolean =
        decisiveSeen || (hits >= minHits && kana >= minKana && rate() > rateThreshold)

    /** Whether the kana size correction may be applied to the currently loaded text. */
    fun allowsCorrection(): Boolean = !isLegacy()

    private companion object {
        val KANA = ('\u3041'..'\u309f').toSet() + ('\u30a0'..'\u30ff').toSet()
    }
}
