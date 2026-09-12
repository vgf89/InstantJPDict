package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.KanaSizeEncoder
import com.holopengin.instantjpdict.util.KanaSizeFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kana size correction policy (#44).
 *
 * Scoring is injected, so this exercises the whole policy on the JVM: the ε flip rule, the pair
 * set, and the declined-position diagnostics. The native model itself is checked separately
 * against the author's published logits ([KanaSizeNcnn.selfCheck]).
 *
 * Candidates arrive in text order, and the pair set is wider than it looks — `あ` belongs to the
 * あ/ぁ pair, so a line of plain hiragana can still present candidates. [logitsFor] builds the
 * result array in that order so a test's intent is not silently offset.
 */
class KanaSizeFixTest {

    private fun line(text: String, overrides: MutableMap<Int, Pair<Char, Float>> = mutableMapOf()) =
        LineResult(
            text = text,
            charBoxes = emptyList(),
            alternatives = MutableList(text.length) { mutableListOf(text[it] to 1f) },
            overrides = overrides,
        )

    /** Logits in candidate order: one entry per size-pair position, in text order. */
    private fun logitsFor(vararg texts: String, f: (Char) -> Float): FloatArray {
        val out = ArrayList<Float>()
        for (t in texts) for (c in t) if (KanaSizeEncoder.baseIndexOf(c) != null) out.add(f(c))
        return out.toFloatArray()
    }

    private class Stub(private val logits: FloatArray) {
        var called = 0
        var bases: IntArray? = null
        var winsLen = 0
        fun score(): (IntArray, IntArray) -> FloatArray? = { wins, bases ->
            called++
            this.bases = bases
            winsLen = wins.size
            logits
        }
    }

    // か/き are not size pairs, so these lines present exactly one candidate: the っ or つ.
    private val smallText = "かっき"
    private val bigText = "かつき"

    @Test
    fun `flips a small position the model is sure is big`() {
        val stub = Stub(logitsFor(smallText) { 10f })       // p(big) ~ 1.0
        val out = KanaSizeFix.apply(listOf(line(smallText)), stub.score())
        assertEquals("かつき", out[0].text)
        assertEquals("the flip is recorded as an ordinary override", 'つ', out[0].overrides[1]!!.first)
        assertEquals(1, stub.called)
        assertEquals("one window of 40 byte values", 40, stub.winsLen)
        assertEquals("っ travels as the つ pair index", 5, stub.bases!![0])
    }

    @Test
    fun `flips a big position the model is sure is small`() {
        val out = KanaSizeFix.apply(listOf(line(bigText)), Stub(logitsFor(bigText) { -10f }).score())
        assertEquals("かっき", out[0].text)
        assertNotNull(out[0].overrides[1])
    }

    @Test
    fun `leaves the middle band alone`() {
        // p(big) = 0.5 sits between ε and 1-ε, so neither direction fires.
        val out = KanaSizeFix.apply(listOf(line(smallText)), Stub(logitsFor(smallText) { 0f }).score())
        assertEquals(smallText, out[0].text)
        assertTrue(out[0].overrides.isEmpty())
    }

    /**
     * The posture the gate must have: a page too short to judge reads as modern, so the
     * correction still applies.
     */
    @Test
    fun `a page below the kana floor is still corrected`() {
        val out = KanaSizeFix.apply(listOf(line(bigText)), Stub(logitsFor(bigText) { -10f }).score())
        assertEquals("かっき", out[0].text)
    }

    @Test
    fun `corrects a legacy-looking page, because era handling lives in the artifact`() {
        val text = "あ".repeat(30) + "つ" + "あ".repeat(30) + "つ" + "あ".repeat(30) + "つ" + "あ".repeat(10) + "つ" + "あ".repeat(20)
        val out = KanaSizeFix.apply(listOf(line(text)), Stub(logitsFor(text) { if (it == 'つ') -10f else 0f }).score())
        assertEquals("every large つ is flipped, since nothing withholds it", text.replace('つ', 'っ'), out[0].text)
        assertEquals(4, out[0].overrides.size)
    }

    @Test
    fun `does not mutate the input lines`() {
        val input = line(smallText)
        KanaSizeFix.apply(listOf(input), Stub(logitsFor(smallText) { 10f }).score())
        assertEquals("the caller's line is untouched", smallText, input.text)
        assertTrue(input.overrides.isEmpty())
    }

    @Test
    fun `positions that are not size pairs are never scored`() {
        val stub = Stub(FloatArray(0))
        val text = "かきくけこさしすせそなにぬねの"
        val out = KanaSizeFix.apply(listOf(line(text)), stub.score())
        assertEquals("no pair members in this line", 0, stub.called)
        assertEquals(text, out[0].text)
    }

    /**
     * The threshold is tunable, and this pins that it actually acts: p(big) = 0.05 is far above
     * the 0.01 default and comfortably inside a 0.10 setting.
     */
    @Test
    fun `a looser epsilon flips a marginal position`() {
        val logit = kotlin.math.ln(0.05f / 0.95f)
        val tight = KanaSizeFix.apply(listOf(line(bigText)), Stub(logitsFor(bigText) { logit }).score())
        assertEquals("the 0.01 default declines this", bigText, tight[0].text)
        val loose = KanaSizeFix.apply(
            listOf(line(bigText)), Stub(logitsFor(bigText) { logit }).score(), epsilon = 0.10f)
        assertEquals("a 0.10 setting flips it", "かっき", loose[0].text)
    }

    @Test
    fun `declined positions are reported with their confidence`() {
        KanaSizeFix.apply(listOf(line(bigText)), Stub(logitsFor(bigText) { 0f }).score())
        val d = KanaSizeFix.lastDeclined
        assertTrue("should name line and index: $d", d.contains("L0@1"))
        assertTrue("should give a confidence: $d", d.contains("p=0.500"))
        assertFalse("must not carry book text: $d", d.contains("か"))
    }

    @Test
    fun `a failed scorer leaves the page untouched`() {
        val out = KanaSizeFix.apply(listOf(line(smallText)), score = { _, _ -> null })
        assertEquals(smallText, out[0].text)
        assertTrue(out[0].overrides.isEmpty())
    }

    @Test
    fun `a short result array is rejected rather than half-applied`() {
        val out = KanaSizeFix.apply(listOf(line(smallText)), score = { _, bases -> FloatArray(bases.size + 1) { 10f } })
        assertEquals(smallText, out[0].text)
        assertTrue(out[0].overrides.isEmpty())
    }

    @Test
    fun `reports a summary for the in-app diagnostics`() {
        KanaSizeFix.apply(listOf(line(smallText)), Stub(logitsFor(smallText) { 10f }).score())
        val summary = KanaSizeFix.lastSummary
        assertTrue("summary should be human-readable: $summary", summary.startsWith("kana fix:"))
        assertTrue("summary should count the flips: $summary", summary.contains("small->big"))
    }

    @Test
    fun `a small flip and a big flip coexist in one page`() {
        val text = "きっゃと"
        val out = KanaSizeFix.apply(listOf(line(text)), Stub(logitsFor(text) { 10f }).score())
        assertEquals("both are flipped towards big", "きつやと", out[0].text)
    }

}
