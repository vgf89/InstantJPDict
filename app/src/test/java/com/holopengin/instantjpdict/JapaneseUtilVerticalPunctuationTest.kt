package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.JapaneseUtil
import org.junit.Test

import org.junit.Assert.*

// #56: vertical lines normalize ASCII `?` to fullwidth `？` in OCR
// post-processing (ASCII has no `vert` alternate and mis-centers).
class JapaneseUtilVerticalPunctuationTest {
    @Test
    fun replaces_ascii_question() {
        assertEquals("か？", JapaneseUtil.verticalPunctuation("か?"))
    }

    @Test
    fun replaces_all_occurrences_leaves_rest() {
        assertEquals("？なに？これ", JapaneseUtil.verticalPunctuation("?なに?これ"))
        assertEquals("あ！？", JapaneseUtil.verticalPunctuation("あ！?"))
    }

    @Test
    fun idempotent_and_noop_without_ascii() {
        assertEquals("か？", JapaneseUtil.verticalPunctuation("か？"))
        assertEquals("", JapaneseUtil.verticalPunctuation(""))
    }

    @Test
    fun char_mapping() {
        assertEquals('？', JapaneseUtil.verticalPunctuationChar('?'))
        assertEquals('？', JapaneseUtil.verticalPunctuationChar('？'))
        assertEquals('あ', JapaneseUtil.verticalPunctuationChar('あ'))
        assertEquals('!', JapaneseUtil.verticalPunctuationChar('!'))
    }

    @Test
    fun lookup_safe_normalize_folds_back() {
        // Dictionary lookup runs normalize(), which folds ？ back to ? —
        // so the substitution must not change lookup results.
        assertEquals(
            JapaneseUtil.normalize("か?"),
            JapaneseUtil.normalize(JapaneseUtil.verticalPunctuation("か?"))
        )
    }
}
