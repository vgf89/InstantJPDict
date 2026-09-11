package com.holopengin.instantjpdict

import org.junit.Test

import org.junit.Assert.*

// #43: pitch-line layout — contour colors, separators and the fall arrow.
// segments() is Context-free, so it runs as a plain JVM test.

class PitchAccentLineTest {

    private val gray = PitchAccentLine.PLAIN_COLOR
    private val white = PitchAccentLine.ACCENT_COLOR

    @Test
    fun heiban_colors_contour_with_no_arrow() {
        // とり LH: と low, り high, and no fall anywhere means no arrow.
        val segs = PitchAccentLine.segments(listOf(PitchAccentLine.Item("とり", 0)))
        assertEquals(
            listOf(
                PitchAccentLine.Segment("と", gray),
                PitchAccentLine.Segment("り", white),
            ),
            segs,
        )
    }

    @Test
    fun odaka_appends_fall_arrow() {
        // はし② LH + fall on the particle: arrow, in unaccented gray.
        val segs = PitchAccentLine.segments(listOf(PitchAccentLine.Item("はし", 2)))
        assertEquals(
            listOf(
                PitchAccentLine.Segment("は", gray),
                PitchAccentLine.Segment("し", white),
                PitchAccentLine.Segment("↓", gray, arrow = true),
            ),
            segs,
        )
    }

    @Test
    fun nakadaka_has_no_arrow() {
        // たべる② LHL: the fall lands inside the word.
        val segs = PitchAccentLine.segments(listOf(PitchAccentLine.Item("たべる", 2)))
        assertEquals(
            listOf(
                PitchAccentLine.Segment("た", gray),
                PitchAccentLine.Segment("べ", white),
                PitchAccentLine.Segment("る", gray),
            ),
            segs,
        )
    }

    @Test
    fun items_join_with_jp_comma() {
        val segs = PitchAccentLine.segments(
            listOf(
                PitchAccentLine.Item("きみ", 0),
                PitchAccentLine.Item("くん", 0),
            )
        )
        assertEquals("きみ、くん", segs.joinToString("") { it.text })
        assertEquals(1, segs.count { it.text == "、" })
    }

    @Test
    fun empty_readings_are_dropped_with_no_stray_separators() {
        // Only とり's two morae survive (no separators around dropped items).
        val segs = PitchAccentLine.segments(
            listOf(
                PitchAccentLine.Item("", 0),
                PitchAccentLine.Item("とり", 0),
            )
        )
        assertEquals(listOf("と", "り"), segs.map { it.text })
    }
}
