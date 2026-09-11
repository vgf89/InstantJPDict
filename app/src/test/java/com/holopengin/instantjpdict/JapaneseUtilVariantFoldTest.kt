package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.JapaneseUtil
import org.junit.Test

import org.junit.Assert.*

// #44 layer 1: fold characters the recogniser *can* emit but dictionaries do not
// carry, so lookup succeeds. Query-side only — displayed OCR text is untouched.
//
// Scoped from measurement: 225M characters of Aozora plus the calibration benches.
// Characters the quantised head has no class for (`ゐ`, `ヱ`, `─`, `〳`, `〴`, `〻`,
// fullwidth ASCII, Ainu small katakana) are deliberately NOT folded — they can
// never appear in the model's output, so an entry would be dead code. Those lines
// fail as deletions and need the proposal layer, not a table.
class JapaneseUtilVariantFoldTest {

    @Test
    fun expands_hiragana_iteration_mark() {
        assertEquals("こころ", JapaneseUtil.foldLookupVariants("こゝろ"))
        assertEquals("ここ", JapaneseUtil.foldLookupVariants("こゝ"))
        assertEquals("ああ", JapaneseUtil.foldLookupVariants("あゝ"))
    }

    @Test
    fun expands_voiced_iteration_mark() {
        assertEquals("ただ", JapaneseUtil.foldLookupVariants("たゞ"))
        assertEquals("かが", JapaneseUtil.foldLookupVariants("かゞ"))
        assertEquals("はば", JapaneseUtil.foldLookupVariants("はゞ"))
        // no voiced form (or already voiced): the mark is still a plain repeat
        assertEquals("まま", JapaneseUtil.foldLookupVariants("まゞ"))
        assertEquals("がが", JapaneseUtil.foldLookupVariants("がゞ"))
        assertEquals("ナナ", JapaneseUtil.foldLookupVariants("ナヾ"))
    }

    @Test
    fun expands_katakana_iteration_marks() {
        assertEquals("カカ", JapaneseUtil.foldLookupVariants("カヽ"))
        assertEquals("カガ", JapaneseUtil.foldLookupVariants("カヾ"))
        assertEquals("ハバ", JapaneseUtil.foldLookupVariants("ハヾ"))
    }

    @Test
    fun repeats_run_of_marks() {
        assertEquals("こここ", JapaneseUtil.foldLookupVariants("こゝゝ"))
    }

    @Test
    fun leaves_mark_that_cannot_be_repeated() {
        // line-initial, after a kanji, after punctuation
        assertEquals("ゝあ", JapaneseUtil.foldLookupVariants("ゝあ"))
        assertEquals("日ゝ", JapaneseUtil.foldLookupVariants("日ゝ"))
        assertEquals("、ゝ", JapaneseUtil.foldLookupVariants("、ゝ"))
        assertEquals("。ヾ", JapaneseUtil.foldLookupVariants("。ヾ"))
        // iteration marks do not cross scripts
        assertEquals("カゝ", JapaneseUtil.foldLookupVariants("カゝ"))
        assertEquals("あヽ", JapaneseUtil.foldLookupVariants("あヽ"))
    }

    @Test
    fun folds_obsolete_kana_the_head_can_emit() {
        assertEquals("こえ", JapaneseUtil.foldLookupVariants("こゑ"))
        assertEquals("イロ", JapaneseUtil.foldLookupVariants("ヰロ"))
    }

    @Test
    fun folds_roman_numerals() {
        assertEquals("VII", JapaneseUtil.foldLookupVariants("Ⅶ"))
        assertEquals("VIII", JapaneseUtil.foldLookupVariants("Ⅷ"))
        assertEquals("第XII章", JapaneseUtil.foldLookupVariants("第Ⅻ章"))
        assertEquals("ix", JapaneseUtil.foldLookupVariants("ⅸ"))
    }

    @Test
    fun folds_compatibility_and_chinese_only_forms() {
        assertEquals("20°C", JapaneseUtil.foldLookupVariants("20℃"))
        assertEquals("状況", JapaneseUtil.foldLookupVariants("状况"))
        assertEquals("調査", JapaneseUtil.foldLookupVariants("调查"))
        assertEquals("調べる", JapaneseUtil.foldLookupVariants("调べる"))
    }

    @Test
    fun leaves_kanji_iteration_mark_alone() {
        // dictionary headwords contain 々 (日々), so expanding would lose matches
        assertEquals("日々", JapaneseUtil.foldLookupVariants("日々"))
        assertEquals("日々", JapaneseUtil.normalize("日々"))
    }

    @Test
    fun normalize_applies_the_fold() {
        assertEquals("こころ", JapaneseUtil.normalize("こゝろ"))
        assertEquals("ただ", JapaneseUtil.normalize("たゞ"))
        assertEquals("VII", JapaneseUtil.normalize("Ⅶ"))
        assertEquals("状況", JapaneseUtil.normalize("状况"))
    }

    @Test
    fun halfwidth_kana_is_widened_before_folding() {
        // order matters: convertWidth runs first, so the mark sees fullwidth カ
        assertEquals("カカ", JapaneseUtil.normalize("ｶヽ"))
    }

    @Test
    fun fold_is_idempotent_and_noop_on_plain_text() {
        val plain = "日本語のテキストです。"
        assertEquals(plain, JapaneseUtil.foldLookupVariants(plain))
        assertEquals(plain, JapaneseUtil.normalize(plain))
        for (s in listOf("こゝろ", "たゞ", "Ⅶ", "状况", "カヾ")) {
            assertEquals(JapaneseUtil.normalize(s), JapaneseUtil.normalize(JapaneseUtil.normalize(s)))
        }
    }

    @Test
    fun existing_normalize_behaviour_is_unchanged() {
        // fullwidth ASCII folds to ASCII, ideographic space to space, vertical
        // presentation forms back to their horizontal forms
        assertEquals("?", JapaneseUtil.normalize("？"))
        assertEquals("A1", JapaneseUtil.normalize("Ａ１"))
        assertEquals("あ…", JapaneseUtil.normalize("あ︙"))
        assertEquals("あ い", JapaneseUtil.normalize("あ　い"))
    }
}
