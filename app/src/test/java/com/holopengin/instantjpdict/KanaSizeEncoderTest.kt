package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.KanaSizeEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The small/large kana model's window encoder (#44).
 *
 * The two windows below are the model author's published validation vectors, verbatim. They are
 * the only external check on this code: a wrong cell order, a wrong padding convention or the
 * target character leaking into its own window would all produce plausible-looking windows that
 * silently degrade every prediction the model makes.
 */
class KanaSizeEncoderTest {

    @Test
    fun `base index follows the model's table, big form first, hiragana then katakana`() {
        assertEquals(20, KanaSizeEncoder.BASE_ORDER.size)
        assertEquals(20, KanaSizeEncoder.BASE_ORDER.toSet().size)
        assertEquals(0, KanaSizeEncoder.baseIndexOf('あ'))
        assertEquals(5, KanaSizeEncoder.baseIndexOf('つ'))
        assertEquals(8, KanaSizeEncoder.baseIndexOf('よ'))
        assertEquals(10, KanaSizeEncoder.baseIndexOf('ア'))
        assertEquals(15, KanaSizeEncoder.baseIndexOf('ツ'))
        assertNull(KanaSizeEncoder.baseIndexOf('か'))
    }

    @Test
    fun `both members of a pair share one index - the pair is the class, the size is the decision`() {
        assertEquals(KanaSizeEncoder.baseIndexOf('つ'), KanaSizeEncoder.baseIndexOf('っ'))
        assertEquals(KanaSizeEncoder.baseIndexOf('よ'), KanaSizeEncoder.baseIndexOf('ょ'))
        assertEquals(KanaSizeEncoder.baseIndexOf('ツ'), KanaSizeEncoder.baseIndexOf('ッ'))
        assertTrue(KanaSizeEncoder.isSmall('っ'))
        assertTrue(!KanaSizeEncoder.isSmall('つ'))
    }

    @Test
    fun `published vector A - vector B - match byte for byte`() {
        // "かれはいっとう。", target っ at index 4, base 5
        val a = KanaSizeEncoder.window("かれはいっとう。", 4)
        assertArrayEquals(
            intArrayOf(
                0, 0, 0, 0, 227, 129, 139, 0, 227, 130, 140, 0, 227, 129, 175, 0,
                227, 129, 132, 0, 227, 129, 168, 0, 227, 129, 134, 0, 227, 128, 130, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
            ),
            a,
        )
        // "きょうはいいてんきですね、まつ。", target つ at index 14, base 5
        val b = KanaSizeEncoder.window("きょうはいいてんきですね、まつ。", 14)
        assertArrayEquals(
            intArrayOf(
                227, 129, 167, 0, 227, 129, 153, 0, 227, 129, 173, 0, 227, 128, 129, 0,
                227, 129, 190, 0, 227, 128, 130, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
            ),
            b,
        )
    }

    @Test
    fun `the target character is not in its own window`() {
        // Same neighbours, different target: the windows must be identical, or the model would
        // be shown the OCR's answer at the one position it is supposed to be deciding.
        val withSmall = KanaSizeEncoder.window("かれはっとう。", 3)
        val withBig = KanaSizeEncoder.window("かれはつとう。", 3)
        assertArrayEquals(withBig, withSmall)
    }

    @Test
    fun `context runs off the line's ends as zeros`() {
        val w = KanaSizeEncoder.window("あい", 0)
        assertEquals(KanaSizeEncoder.WINDOW_BYTES, w.size)
        // L5..L1 all absent, then R1='い', R2..R5 absent
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            w.copyOfRange(0, 20))
        assertArrayEquals(intArrayOf(227, 129, 132, 0), w.copyOfRange(20, 24))
    }
}
