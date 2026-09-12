package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.LegacyOrthographyGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-reform orthography gate (#44).
 *
 * This is the safety gate on the kana size correction. 旧仮名 writes 促音 with a large つ, the
 * recogniser reads it correctly, and the model then "corrects" it into っ — 66.9% accuracy on
 * 1,346 legacy big-side positions against 98.4% on modern ones.
 *
 * The gate suppresses only on positive evidence and **defaults to modern** — a short line, too
 * few kana, or a single disagreement all allow correction. The thresholds are the measured
 * ones: the fingerprint rate separates 25.7-25.9 hits per 100 kana on legacy lines from
 * 0.06-0.29 on modern, so a 2/100 threshold has roughly 7x margin below and 13x above.
 *
 * The rate threshold is pinned by [thin modern evidence is not a legacy signal] — the earlier
 * value was 100x too low and every test here passed anyway, because none of them exercised a
 * rate that sat between the two constants.
 */
class LegacyOrthographyGateTest {

    private fun gate() = LegacyOrthographyGate()

    @Test
    fun `modern text with no disagreement allows correction`() {
        val g = gate()
        g.observeLine("きょうはいいてんきですね。つくえのうえにほんがある。わたしはがっこうへいく。")
        // the OCR read 大つ and the model agrees it is big - no fingerprint
        repeat(3) { g.observePosition('つ', 0.98f) }
        assertFalse(g.isLegacy())
        assertTrue(g.allowsCorrection())
    }

    @Test
    fun `a legacy pattern suppresses correction`() {
        val g = gate()
        // Enough kana to judge (the floor is 60), and repeated 大つ the model insists are small.
        g.observeLine("かれはだつた。それでよかつた。まつてくれ。だれもこなかつた。それがよかつたのだ。".repeat(3))
        repeat(4) { g.observePosition('つ', 0.002f) }
        assertTrue(g.isLegacy())
        assertFalse(g.allowsCorrection())
    }

    @Test
    fun `an isolated disagreement does not`() {
        val g = gate()
        g.observeLine("かれはだつた。それでよかつた。まつてくれ。だれもこなかつた。それがよかつたのだ。".repeat(3))
        g.observePosition('つ', 0.002f)
        assertFalse("one hit among enough kana is inside the modern range", g.isLegacy())
    }

    /**
     * Pins the hit bar. Three hits on ~108 kana is 2.8 per 100 - above the rate threshold - so
     * under the earlier `minHits = 2` this suppressed correction, and one marginal extra hit was
     * enough to swing a screen-sized page. Legacy text produces dozens of hits, so requiring four
     * costs nothing there and removes the coin-flip.
     */
    @Test
    fun `three hits is not yet evidence of pre-reform text`() {
        val g = gate()
        g.observeLine("かれはだつた。それでよかつた。まつてくれ。だれもこなかつた。それがよかつたのだ。".repeat(3))
        repeat(3) { g.observePosition('つ', 0.002f) }
        assertFalse(g.isLegacy())
        assertTrue(g.allowsCorrection())
    }

    @Test
    fun `decisive kana fire on their own, with any amount of evidence`() {
        val g = gate()
        g.observeLine("ゐろは")
        assertTrue(g.isLegacy())
        assertFalse(g.allowsCorrection())
    }

    @Test
    fun `too little evidence to judge defaults to allowing correction`() {
        val g = gate()
        g.observeLine("つ")                       // 1 kana, below the sample floor
        g.observePosition('つ', 0.001f)
        assertFalse(g.isLegacy())
        assertTrue(g.allowsCorrection())
    }

    @Test
    fun `a page below the kana floor defaults to allowing correction`() {
        val g = gate()
        g.observeLine("あ".repeat(LegacyOrthographyGate.MIN_KANA - 1))
        repeat(5) { g.observePosition('つ', 0.001f) }
        assertFalse("five hits on 29 kana is too little to judge", g.isLegacy())
        assertTrue(g.allowsCorrection())
    }

    /**
     * Pins [LegacyOrthographyGate]'s rate threshold: 2 hits per 200 kana is a *modern* rate, so
     * it must not suppress. At the old 0.02/100 constant this read as legacy, which would have
     * withheld the correction from ordinary modern pages.
     */
    @Test
    fun `thin modern evidence is not a legacy signal`() {
        val g = gate()
        g.observeLine("あ".repeat(200))
        repeat(2) { g.observePosition('つ', 0.001f) }
        assertFalse("2 hits per 200 kana = 1.0 per 100 is inside the modern band", g.isLegacy())
        assertTrue(g.allowsCorrection())
    }

    /** The other side of the same threshold: a legacy *rate* still trips it. */
    @Test
    fun `a legacy rate on a long page still suppresses`() {
        val g = gate()
        g.observeLine("あ".repeat(100))
        repeat(8) { g.observePosition('つ', 0.001f) }
        assertTrue("8 hits per 100 kana is far above the modern band", g.isLegacy())
        assertFalse(g.allowsCorrection())
    }

    @Test
    fun `katakana big tsu counts as a hit too`() {
        val g = gate()
        g.observeLine("ベツドとポケツトをかう。それがいいとおもうよ。わたしはそうおもうのだ。".repeat(3))
        repeat(5) { g.observePosition('ツ', 0.001f) }
        assertTrue(g.isLegacy())
    }

    @Test
    fun `reset clears the accumulated evidence`() {
        val g = gate()
        g.observeLine("ゐろは")
        assertTrue(g.isLegacy())
        g.reset()
        assertFalse(g.isLegacy())
        assertTrue(g.allowsCorrection())
    }
}
