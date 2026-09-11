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
    fun pattern_heiban() {
        // 0 = no downstep: L then H, none of the word falls.
        assertEquals(listOf(false, true, true), PitchAccent.pattern(3, 0))
        assertEquals(listOf(false), PitchAccent.pattern(1, 0))
    }

    @Test
    fun pattern_atamadaka() {
        // 1 = fall right after the first mora.
        assertEquals(listOf(true, false, false), PitchAccent.pattern(3, 1))
    }

    @Test
    fun pattern_nakadaka_and_odaka() {
        // L H L for a 3-mora word accented on mora 2.
        assertEquals(listOf(false, true, false), PitchAccent.pattern(3, 2))
        // Position >= mora count renders like heiban within the word.
        assertEquals(listOf(false, true, true), PitchAccent.pattern(3, 3))
        assertEquals(listOf(false, true, true), PitchAccent.pattern(3, 9))
    }

    @Test
    fun pattern_empty() {
        assertEquals(emptyList<Boolean>(), PitchAccent.pattern(0, 1))
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
