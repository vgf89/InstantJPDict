package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.FuriganaAligner
import org.junit.Test

import org.junit.Assert.*

class FuriganaAlignerTest {
    @Test
    fun okurigana_trailing() {
        assertEquals(
            listOf(
                FuriganaAligner.Segment("食", "た"),
                FuriganaAligner.Segment("べる", null)
            ),
            FuriganaAligner.align("食べる", "たべる")
        )
    }

    @Test
    fun okurigana_splitStem() {
        assertEquals(
            listOf(
                FuriganaAligner.Segment("大", "おお"),
                FuriganaAligner.Segment("きい", null)
            ),
            FuriganaAligner.align("大きい", "おおきい")
        )
    }

    @Test
    fun infixed_kana() {
        assertEquals(
            listOf(
                FuriganaAligner.Segment("申", "もう"),
                FuriganaAligner.Segment("し", null),
                FuriganaAligner.Segment("込", "こ"),
                FuriganaAligner.Segment("む", null)
            ),
            FuriganaAligner.align("申し込む", "もうしこむ")
        )
    }

    @Test
    fun leading_kana() {
        assertEquals(
            listOf(
                FuriganaAligner.Segment("お", null),
                FuriganaAligner.Segment("母", "かあ"),
                FuriganaAligner.Segment("さん", null)
            ),
            FuriganaAligner.align("お母さん", "おかあさん")
        )
    }

    @Test
    fun allKanji_wholeRuby() {
        assertEquals(
            listOf(FuriganaAligner.Segment("今日", "きょう")),
            FuriganaAligner.align("今日", "きょう")
        )
    }

    @Test
    fun kanaOnly_plain() {
        assertEquals(
            listOf(FuriganaAligner.Segment("たべる", null)),
            FuriganaAligner.align("たべる", "たべる")
        )
    }

    @Test
    fun katakanaReading_normalized() {
        assertEquals(
            listOf(
                FuriganaAligner.Segment("食", "タ"),
                FuriganaAligner.Segment("べる", null)
            ),
            FuriganaAligner.align("食べる", "タベル")
        )
    }

    @Test
    fun iterationMark_staysInKanjiRun() {
        assertEquals(
            listOf(FuriganaAligner.Segment("人々", "ひとびと")),
            FuriganaAligner.align("人々", "ひとびと")
        )
    }

    @Test
    fun readingTooShort_returnsNull() {
        assertNull(FuriganaAligner.align("食べる", "たべ"))
    }

    @Test
    fun readingTooLong_returnsNull() {
        assertNull(FuriganaAligner.align("食べる", "たべるる"))
    }

    @Test
    fun anchorMissing_returnsNull() {
        assertNull(FuriganaAligner.align("食べる", "たばさ"))
    }

    @Test
    fun empty_returnsNull() {
        assertNull(FuriganaAligner.align("", "たべる"))
        assertNull(FuriganaAligner.align("食べる", ""))
    }
}
