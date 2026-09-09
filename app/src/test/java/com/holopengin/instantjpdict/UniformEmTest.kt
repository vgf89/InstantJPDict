package com.holopengin.instantjpdict

import org.junit.Test

import org.junit.Assert.*

// #49: em must be estimated from width-normalized pitches so ASCII-majority
// mixed lines don't drag em to ~0.5x and shrink kanji. True em in all cases
// below is 20px.
class UniformEmTest {
    @Test
    fun mixed_ascii_majority_recovers_full_em() {
        // A(5) B(15) 日(30) 本(50) C(65) D(75): raw pitches 10/15/20/15/10,
        // raw median 15 (0.75x — kanji boxes shrink); normalized all 20.
        assertEquals(
            20f,
            OcrEngine.estimateEm("AB日本CD", listOf(5f, 15f, 30f, 50f, 65f, 75f)),
            0.001f
        )
    }

    @Test
    fun pure_cjk_unchanged() {
        assertEquals(20f, OcrEngine.estimateEm("日本語", listOf(10f, 30f, 50f)), 0.001f)
    }

    @Test
    fun pure_ascii_recovers_full_em() {
        // 10px pitches are 0.5em advances → em 20.
        assertEquals(20f, OcrEngine.estimateEm("ABCD", listOf(5f, 15f, 25f, 35f)), 0.001f)
    }

    @Test
    fun fullwidth_latin_counts_as_full() {
        assertEquals(20f, OcrEngine.estimateEm("ＡＢ", listOf(10f, 30f)), 0.001f)
    }

    @Test
    fun halfwidth_katakana_counts_as_half() {
        assertEquals(20f, OcrEngine.estimateEm("ｱｲ", listOf(5f, 15f)), 0.001f)
    }

    @Test
    fun unestimable_returns_zero() {
        assertEquals(0f, OcrEngine.estimateEm("あ", listOf(10f)), 0.001f)
        assertEquals(0f, OcrEngine.estimateEm("", emptyList()), 0.001f)
        assertEquals(0f, OcrEngine.estimateEm("あい", listOf(10f)), 0.001f)
    }
}
