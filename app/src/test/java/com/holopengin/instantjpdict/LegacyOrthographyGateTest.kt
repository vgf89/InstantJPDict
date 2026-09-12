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
 * 1,346 legacy big-side positions against 98.4% on modern ones. Applying the correction to
 * legacy text corrupts a correct reading, so the gate defaults to withholding.
 *
 * The thresholds here are the measured ones: the fingerprint rate separates 25.7-25.9 hits per
 * 100 kana on legacy lines from 0.06-0.29 on modern, so a 2/100 threshold has roughly 7x margin
 * below and 13x above.
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
        // enough kana to judge, and repeated 大つ the model insists are small
        g.observeLine("かれはだつた。それでよかつた。まつてくれ。だれもこなかつた。それがよかつたのだ。")
        repeat(4) { g.observePosition('つ', 0.002f) }
        assertTrue(g.isLegacy())
        assertFalse(g.allowsCorrection())
    }

    @Test
    fun `an isolated disagreement does not`() {
        val g = gate()
        g.observeLine("かれはだつた。それでよかつた。まつてくれ。だれもこなかつた。それがよかつたのだ。")
        g.observePosition('つ', 0.002f)
        assertFalse("one hit in 36 kana is inside the modern range", g.isLegacy())
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
    fun `katakana big tsu counts as a hit too`() {
        val g = gate()
        g.observeLine("ベツドとポケツトをかう。それがいいとおもうよ。わたしはそうおもうのだ。")
        repeat(4) { g.observePosition('ツ', 0.001f) }
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
