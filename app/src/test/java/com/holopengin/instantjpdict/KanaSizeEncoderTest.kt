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
 * The ten vectors below are the artifact's published validation vectors, verbatim - the same bytes
 * its own `validate_port.py` checks. They are the only external check on this code: a wrong cell
 * order, a wrong padding convention, a missing context clip, or the target character leaking into
 * its own window would all produce plausible-looking windows that silently degrade every
 * prediction the model makes.
 *
 * Four of the vectors carry a boundary (。 or a newline) within reach of the target, which is what
 * pins the line-domain clip.
 */
class KanaSizeEncoderTest {

    private data class Vector(val text: String, val index: Int, val win: IntArray)

    private val ZERO20 = IntArray(20)

    @Test
    fun `every published vector encodes byte for byte`() {
        for (v in VECTORS) {
            assertArrayEquals(v.text, v.win, KanaSizeEncoder.window(v.text, v.index))
        }
    }

    @Test
    fun `base index follows the model's table - pair family, big form first, hiragana then katakana`() {
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
    fun `the target character is not in its own window`() {
        // Same neighbours, different target: the windows must be identical, or the model would be
        // shown the OCR's answer at the one position it is supposed to be deciding.
        val withSmall = KanaSizeEncoder.window("かれはっとう。", 3)
        val withBig = KanaSizeEncoder.window("かれはつとう。", 3)
        assertArrayEquals(withBig, withSmall)
    }

    @Test
    fun `context stops at a full stop or a newline`() {
        // The boundary character terminates the walk and is not itself part of the window, so
        // neither the 。 nor anything before it is visible to a position after it.
        val stop = KanaSizeEncoder.window("あ。っあ", 2)
        assertArrayEquals("left of the 。 must be empty", ZERO20, stop.copyOfRange(0, 20))
        assertArrayEquals(
            "right context is the あ, then nothing",
            intArrayOf(227, 129, 130, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            stop.copyOfRange(20, 40),
        )

        // A newline clips the same way, and a character beyond it stays hidden even though the
        // window has room for it: without the clip this cell would hold the あ.
        val nl = KanaSizeEncoder.window("あ\nいっ", 3)
        assertArrayEquals(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 227, 129, 132, 0),
            nl.copyOfRange(0, 20),
        )
        assertArrayEquals(ZERO20, nl.copyOfRange(20, 40))
    }

    @Test
    fun `context runs off the line's ends as zeros`() {
        val w = KanaSizeEncoder.window("あい", 0)
        assertEquals(KanaSizeEncoder.WINDOW_BYTES, w.size)
        assertArrayEquals(ZERO20, w.copyOfRange(0, 20))
        assertArrayEquals(intArrayOf(227, 129, 132, 0), w.copyOfRange(20, 24))
    }

    private companion object {
        val VECTORS = listOf(
        Vector(
            "かれはいっとう。", 4,
            intArrayOf(
                0, 0, 0, 0, 227, 129, 139, 0, 227, 130, 140, 0, 227, 129, 175, 0, 227, 129, 132, 0,
                227, 129, 168, 0, 227, 129, 134, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
        ),
        Vector(
            "きょうはいいてんきですね、まつ。", 14,
            intArrayOf(
                227, 129, 167, 0, 227, 129, 153, 0, 227, 129, 173, 0, 227, 128, 129, 0, 227, 129, 190, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
        ),
        Vector(
            "みんなでサッカーをするつもりです。", 5,
            intArrayOf(
                227, 129, 191, 0, 227, 130, 147, 0, 227, 129, 170, 0, 227, 129, 167, 0, 227, 130, 181, 0,
                227, 130, 171, 0, 227, 131, 188, 0, 227, 130, 146, 0, 227, 129, 153, 0, 227, 130, 139, 0,
            ),
        ),
        Vector(
            "シーツをあらう。", 2,
            intArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 227, 130, 183, 0, 227, 131, 188, 0,
                227, 130, 146, 0, 227, 129, 130, 0, 227, 130, 137, 0, 227, 129, 134, 0, 0, 0, 0, 0,
            ),
        ),
        Vector(
            "きょうのてんきはいいですね。", 1,
            intArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 227, 129, 141, 0,
                227, 129, 134, 0, 227, 129, 174, 0, 227, 129, 166, 0, 227, 130, 147, 0, 227, 129, 141, 0,
            ),
        ),
        Vector(
            "キャンプにいく。", 1,
            intArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 227, 130, 173, 0,
                227, 131, 179, 0, 227, 131, 151, 0, 227, 129, 171, 0, 227, 129, 132, 0, 227, 129, 143, 0,
            ),
        ),
        Vector(
            "昌仙も、おもわず床几を立って、\n「あッ」\n　と、櫓", 12,
            intArrayOf(
                227, 129, 154, 0, 229, 186, 138, 0, 229, 135, 160, 0, 227, 130, 146, 0, 231, 171, 139, 0,
                227, 129, 166, 0, 227, 128, 129, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
        ),
        Vector(
            "なろうかと……」\n　おえつは、片手に、腕白を抱きな", 12,
            intArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 227, 128, 128, 0, 227, 129, 138, 0, 227, 129, 136, 0,
                227, 129, 175, 0, 227, 128, 129, 0, 231, 137, 135, 0, 230, 137, 139, 0, 227, 129, 171, 0,
            ),
        ),
        Vector(
            "は、変化多き世の中にもちょっと例の少ない並ならぬ三", 12,
            intArrayOf(
                227, 129, 174, 0, 228, 184, 173, 0, 227, 129, 171, 0, 227, 130, 130, 0, 227, 129, 161, 0,
                227, 129, 163, 0, 227, 129, 168, 0, 228, 190, 139, 0, 227, 129, 174, 0, 229, 176, 145, 0,
            ),
        ),
        Vector(
            "。\n　そして、ザッザ、ザッザと、草の波を分けて、押", 12,
            intArrayOf(
                227, 130, 182, 0, 227, 131, 131, 0, 227, 130, 182, 0, 227, 128, 129, 0, 227, 130, 182, 0,
                227, 130, 182, 0, 227, 129, 168, 0, 227, 128, 129, 0, 232, 141, 137, 0, 227, 129, 174, 0,
            ),
        ),
        )
    }
}
