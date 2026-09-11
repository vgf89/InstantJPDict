package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.RubyHeading
import org.junit.Test

import org.junit.Assert.*

// #69: long merged multi-reading ruby rows left-align the kanji; single
// readings and short rows stay centered.
class RubyHeadingTest {
    @Test
    fun long_multi_reading_aligns_start() {
        assertTrue(RubyHeading.shouldAlignStart("君", "きみ、くん、ぎみ、きんじ"))
    }

    @Test
    fun single_reading_stays_centered_even_if_long() {
        assertFalse(RubyHeading.shouldAlignStart("承", "うけたまわる"))
        assertFalse(RubyHeading.shouldAlignStart("漢", "かん"))
    }

    @Test
    fun short_multi_reading_stays_centered() {
        assertFalse(RubyHeading.shouldAlignStart("他", "た、ほか"))
    }

    @Test
    fun empty_inputs_never_align_start() {
        assertFalse(RubyHeading.shouldAlignStart("", "きみ、くん、ぎみ、きんじ"))
        assertFalse(RubyHeading.shouldAlignStart("君", ""))
    }

    @Test
    fun ascii_comma_is_not_a_multi_separator() {
        // Only the JP comma marks a merged row.
        assertFalse(RubyHeading.shouldAlignStart("君", "きみ, くん, ぎみ, きんじ"))
    }
}
