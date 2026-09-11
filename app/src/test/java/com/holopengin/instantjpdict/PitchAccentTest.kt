package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.PitchAccent
import org.junit.Test

import org.junit.Assert.*

// #43: mora splitting, Tokyo contour math and pitch-payload detection.
class PitchAccentTest {
    @Test
    fun morae_basic() {
        assertEquals(listOf("き", "み"), PitchAccent.moraeOf("きみ"))
        assertEquals(listOf("う", "た"), PitchAccent.moraeOf("うた"))
    }

    @Test
    fun morae_small_kana_fuse_but_sokuon_and_long_vowel_stay() {
        // 拗音 fuses: きょ is one mora.
        assertEquals(listOf("きょ", "う"), PitchAccent.moraeOf("きょう"))
        assertEquals(listOf("しゃ", "し", "ん"), PitchAccent.moraeOf("しゃしん"))
        // ー and っ are their own morae.
        assertEquals(listOf("コ", "ー", "ヒ", "ー"), PitchAccent.moraeOf("コーヒー"))
        assertEquals(listOf("が", "っ", "こ", "う"), PitchAccent.moraeOf("がっこう"))
        // ん is its own mora.
        assertEquals(listOf("し", "ん", "ぶ", "ん"), PitchAccent.moraeOf("しんぶん"))
    }

    @Test
    fun morae_empty() {
        assertEquals(emptyList<String>(), PitchAccent.moraeOf(""))
    }

    @Test
    fun mark_none_for_heiban_and_first_mora_for_atamadaka() {
        // Heiban carries no mark — the absence is the encoding.
        assertNull(PitchAccent.markIndex(3, 0))
        // Accent 1 falls after mora 1 → mark sits on mora index 0.
        assertEquals(0, PitchAccent.markIndex(3, 1))
    }

    @Test
    fun mark_sits_on_the_mora_before_the_fall() {
        // Accent 2 of 3 morae: fall after the 2nd mora → index 1.
        assertEquals(1, PitchAccent.markIndex(3, 2))
        // Odaka (position == mora count): fall after the last mora.
        assertEquals(2, PitchAccent.markIndex(3, 3))
        // Out-of-range position (one known bad row) clamps to the last mora
        // instead of dropping the mark.
        assertEquals(2, PitchAccent.markIndex(3, 9))
    }

    @Test
    fun mark_absent_for_empty_mora_count() {
        assertNull(PitchAccent.markIndex(0, 1))
    }

    @Test
    fun heiban_and_odaka_are_distinguishable_only_by_mark() {
        // The verification behind the single-mark design: both are L H…H
        // in-word, so the mark is the only thing that separates them.
        assertNull(PitchAccent.markIndex(2, 0))
        assertEquals(1, PitchAccent.markIndex(2, 2))
    }

    @Test
    fun format_position_circled_then_plain() {
        assertEquals("⓪", PitchAccent.formatPosition(0))
        assertEquals("①", PitchAccent.formatPosition(1))
        assertEquals("④", PitchAccent.formatPosition(4))
        assertEquals("⑨", PitchAccent.formatPosition(9))
        assertEquals("12", PitchAccent.formatPosition(12))
    }

    @Test
    fun positions_parsed_from_kanjium_payload() {
        assertEquals(
            listOf(0, 2),
            PitchAccent.positionsOf("""{"reading":"ひと","pitches":[{"position":0},{"position":2}]}""")
        )
        // Integer-valued floats (Gson round-trip) still parse.
        assertEquals(
            listOf(1),
            PitchAccent.positionsOf("""{"reading":"きみ","pitches":[{"position":1.0}]}""")
        )
        // Duplicates collapse, order normalized.
        assertEquals(
            listOf(0, 3),
            PitchAccent.positionsOf("""{"reading":"あ","pitches":[{"position":3},{"position":0},{"position":3}]}""")
        )
    }

    @Test
    fun non_pitch_payloads_are_rejected() {
        assertNull(PitchAccent.positionsOf("\"just a gloss\""))
        assertNull(PitchAccent.positionsOf("""{"glossary":"x"}"""))
        // pitches present but no reading → not a pitch payload
        assertNull(PitchAccent.positionsOf("""{"pitches":[{"position":1}]}"""))
        // reading present but no pitches → not pitch data
        assertNull(PitchAccent.positionsOf("""{"reading":"きみ"}"""))
        assertNull(PitchAccent.positionsOf("not json{["))
        assertNull(PitchAccent.positionsOf(""))
    }

    @Test
    fun reading_extracted() {
        assertEquals("きみ", PitchAccent.readingOf("""{"reading":"きみ","pitches":[{"position":1}]}"""))
        assertNull(PitchAccent.readingOf("""{"pitches":[]}"""))
        assertNull(PitchAccent.readingOf("nope"))
    }
}
