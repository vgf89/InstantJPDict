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
        // 查 folds (emittable) while 调 does not (we pruned it) — same word, and
        // only the emittable half of the pair is worth an entry
        assertEquals("调査④", JapaneseUtil.foldLookupVariants("调查④"))
    }

    @Test
    fun does_not_fold_characters_we_pruned_ourselves() {
        // 调 has no Unihan Japanese reading, so prune_ctc_head cut it: the head
        // cannot emit it, and an entry would be dead code. Emittability is checked
        // against rec_remap.txt, never vocab.json, which still lists pruned classes.
        assertEquals("调", JapaneseUtil.foldLookupVariants("调"))
    }

    @Test
    fun leaves_kanji_iteration_mark_alone() {
        // dictionary headwords contain 々 (日々), so expanding would lose matches
        assertEquals("日々", JapaneseUtil.foldLookupVariants("日々"))
        assertEquals("日々", JapaneseUtil.normalize("日々"))
    }

    // ── #44: the Unihan-derived half of the table ────────────────────────────────
    // Direction is variant -> canonical (canonical = the side in vocab.json), and a
    // pair ships only if its variant side occurs in real text (112 of 593, measured
    // over 230M characters of Aozora). These characters have no class in the shipped
    // head, so the fold fires on text that did not come from the model — a manual
    // override, dictionary-side text — never on OCR output.

    @Test
    fun folds_measured_unihan_variants() {
        // the pair the direction rule was validated on, and the corpus's most
        // frequent variant forms
        assertEquals("回", JapaneseUtil.foldLookupVariants("囘"))
        assertEquals("鬱", JapaneseUtil.foldLookupVariants("欝"))
        assertEquals("罈", JapaneseUtil.foldLookupVariants("壜"))
        assertEquals("劍", JapaneseUtil.foldLookupVariants("劒"))
        assertEquals("慚", JapaneseUtil.foldLookupVariants("慙"))
        // and inside a word, which is how the fold is actually reached
        assertEquals("回想", JapaneseUtil.foldLookupVariants("囘想"))
        assertEquals("鬱々", JapaneseUtil.foldLookupVariants("欝々"))
        assertEquals("慚愧", JapaneseUtil.foldLookupVariants("慙愧"))
        assertEquals("逃げる", JapaneseUtil.foldLookupVariants("迯げる"))
        assertEquals("器械", JapaneseUtil.foldLookupVariants("噐械"))
        assertEquals("逃", JapaneseUtil.foldLookupVariants("迯"))
    }

    @Test
    fun unihan_fold_picks_the_corpus_dominant_canonical() {
        // 15 of the 112 variants have several canonical candidates in Unihan; the fold
        // takes the form that dominates the same Aozora corpus, not an arbitrary first
        // (葢 -> 蓋 6,811 vs 盖 92; 悋 -> 吝 760 vs 恡 0; 冫 -> 氷 10,435 vs 冰 88)
        assertEquals("蓋", JapaneseUtil.foldLookupVariants("葢"))
        assertEquals("吝", JapaneseUtil.foldLookupVariants("悋"))
        assertEquals("氷", JapaneseUtil.foldLookupVariants("冫"))
        assertEquals("藝", JapaneseUtil.foldLookupVariants("秇"))
        assertEquals("崎", JapaneseUtil.foldLookupVariants("﨑"))
    }

    @Test
    fun unihan_fold_does_not_run_backwards() {
        // the canonical side is what the dictionary already keys on: folding it would
        // move the query to a form the recogniser cannot emit
        assertEquals("回", JapaneseUtil.foldLookupVariants("回"))
        assertEquals("鬱", JapaneseUtil.foldLookupVariants("鬱"))
        assertEquals("蓋", JapaneseUtil.foldLookupVariants("蓋"))
        assertEquals("回想", JapaneseUtil.normalize("回想"))
    }

    @Test
    fun unihan_fold_is_idempotent_and_leaves_unknown_characters_alone() {
        val plain = "日本語のテキストです。"
        assertEquals(plain, JapaneseUtil.foldLookupVariants(plain))
        for (s in listOf("囘想", "欝々", "迯げる", "噐械", "壜", "﨑", "囘囘回")) {
            val once = JapaneseUtil.normalize(s)
            assertEquals(once, JapaneseUtil.normalize(once))
        }
    }

    @Test
    fun measured_variant_fold_matches_the_committed_asset() {
        // Drift guard: every pair folded here must exist in variants/kanji_variants.txt
        // in the same direction (and no canonical may itself be a key, or the fold
        // would not be idempotent). Multi-candidate variants are checked against the
        // asset's *whole* candidate set, because the fold's choice among them is a
        // corpus measurement the asset does not carry (e.g. 冫 -> 氷, while the asset
        // also offers 冰, its lowest-codepoint candidate).
        val asset = mutableMapOf<Char, MutableSet<Char>>()
        for (line in TestAssets.variantsFile().readText().lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size != 2 || parts[0].length != 1 || parts[1].length != 1) continue
            asset.getOrPut(parts[0][0]) { mutableSetOf() }.add(parts[1][0])
        }
        assertEquals(112, JapaneseUtil.MEASURED_VARIANT_FOLD.size)
        assertEquals(523, asset.size)
        for ((variant, canonical) in JapaneseUtil.MEASURED_VARIANT_FOLD) {
            val target = canonical.single()
            val candidates = asset[variant]
            assertNotNull("'$variant' is not in kanji_variants.txt", candidates)
            assertTrue("asset has '$variant' -> $candidates, not '$target'",
                target in candidates!!)
            assertFalse("canonical '$target' is itself a variant — fold would chain",
                asset.containsKey(target))
        }
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
