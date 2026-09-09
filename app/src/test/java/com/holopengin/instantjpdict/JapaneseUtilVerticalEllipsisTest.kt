package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.JapaneseUtil
import org.junit.Test

import org.junit.Assert.*

// #63: vertical lines normalize horizontal `…`/`‥` to the vertical
// presentation forms `︙`/`︰` in OCR post-processing (`…` has no `vert`
// alternate and lies sideways in the column). Same pattern as #56.
class JapaneseUtilVerticalEllipsisTest {
    @Test
    fun replaces_horizontal_ellipsis() {
        assertEquals("あ︙", JapaneseUtil.verticalPunctuation("あ…"))
    }

    @Test
    fun replaces_two_dot_leader() {
        assertEquals("あ︰", JapaneseUtil.verticalPunctuation("あ‥"))
    }

    @Test
    fun replaces_all_occurrences_leaves_rest() {
        assertEquals("︙なに︙これ", JapaneseUtil.verticalPunctuation("…なに…これ"))
        assertEquals("あ︙？", JapaneseUtil.verticalPunctuation("あ…?"))
    }

    @Test
    fun ascii_periods_untouched() {
        // Runs of ASCII periods are deliberately NOT folded: an N:1 fold
        // would break char-box/alternative alignment.
        assertEquals("あ...", JapaneseUtil.verticalPunctuation("あ..."))
        assertEquals("あ.", JapaneseUtil.verticalPunctuation("あ."))
    }

    @Test
    fun idempotent_and_noop() {
        assertEquals("あ︙", JapaneseUtil.verticalPunctuation("あ︙"))
        assertEquals("あ︰", JapaneseUtil.verticalPunctuation("あ︰"))
        assertFalse(JapaneseUtil.verticalPunctuation("あ…").contains('…'))
        assertEquals("", JapaneseUtil.verticalPunctuation(""))
    }

    @Test
    fun char_mapping() {
        assertEquals('︙', JapaneseUtil.verticalPunctuationChar('…'))
        assertEquals('︙', JapaneseUtil.verticalPunctuationChar('︙'))
        assertEquals('︰', JapaneseUtil.verticalPunctuationChar('‥'))
        assertEquals('︰', JapaneseUtil.verticalPunctuationChar('︰'))
        assertEquals('.', JapaneseUtil.verticalPunctuationChar('.'))
        assertEquals('あ', JapaneseUtil.verticalPunctuationChar('あ'))
    }

    @Test
    fun lookup_safe_normalize_folds_back() {
        // Dictionary lookup runs normalize(), which must fold the vertical
        // forms back — the substitution must not change lookup results.
        assertEquals(
            JapaneseUtil.normalize("あ…"),
            JapaneseUtil.normalize(JapaneseUtil.verticalPunctuation("あ…"))
        )
        assertEquals(
            JapaneseUtil.normalize("あ‥"),
            JapaneseUtil.normalize(JapaneseUtil.verticalPunctuation("あ‥"))
        )
        assertEquals("あ…", JapaneseUtil.normalize("あ︙"))
        assertEquals("あ‥", JapaneseUtil.normalize("あ︰"))
    }
}
