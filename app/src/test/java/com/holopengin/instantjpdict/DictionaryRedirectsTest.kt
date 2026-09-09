package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.DictionaryRedirects
import org.junit.Test

import org.junit.Assert.*

// #65: pure JMdict pointer entries resolve to their ?query= targets;
// entries with real definitional content never redirect. Shapes below are
// verbatim from JMdict_english term banks (yomidevs).
class DictionaryRedirectsTest {
    // あかーん → あかん (real row).
    private val pureSingle = """[{"content": {"content": ["⟶", {"content": "あかん", "href": "?query=あかん&wildcards=off", "lang": "ja", "tag": "a"}], "style": {"fontSize": "130%"}, "tag": "span"}, "type": "structured-content"}]"""

    // アホんだら → あほんだら + 阿呆陀羅 (shape mirrors the real row, which
    // renders as "⟶, 阿呆陀羅, （, あほんだら, ）").
    private val pureDual = """[{"content": {"content": ["⟶", {"content": "阿呆陀羅", "href": "?query=阿呆陀羅&wildcards=off", "lang": "ja", "tag": "a"}, "（", {"content": "あほんだら", "href": "?query=あほんだら&wildcards=off", "lang": "ja", "tag": "a"}, "）"], "style": {"fontSize": "130%"}, "tag": "span"}, "type": "structured-content"}]"""

    // ヽ (real row): genuine glossary + see-also references — not a redirect.
    private val glossWithRefs = """[{"content": [{"content": {"content": "repetition mark in katakana", "tag": "li"}, "data": {"content": "glossary"}, "lang": "en", "style": {"listStyleType": "circle"}, "tag": "ul"}, {"content": {"content": ["see: ", {"content": "一の字点", "href": "?query=一の字点&wildcards=off", "lang": "ja", "tag": "a"}, {"content": " kana iteration mark", "data": {"content": "refGlosses"}, "style": {"fontSize": "65%", "verticalAlign": "middle"}, "tag": "span"}], "tag": "li"}, "data": {"content": "references"}, "lang": "en", "style": {"listStyleType": "'➡️ '"}, "tag": "ul"}], "type": "structured-content"}]"""

    @Test
    fun pure_single_resolves() {
        assertEquals(listOf("あかん"), DictionaryRedirects.extractTargets(pureSingle))
    }

    @Test
    fun pure_dual_resolves_both() {
        assertEquals(
            listOf("阿呆陀羅", "あほんだら"),
            DictionaryRedirects.extractTargets(pureDual)
        )
    }

    @Test
    fun gloss_with_see_also_does_not_redirect() {
        assertEquals(emptyList<String>(), DictionaryRedirects.extractTargets(glossWithRefs))
    }

    @Test
    fun plain_gloss_does_not_redirect() {
        assertEquals(emptyList<String>(), DictionaryRedirects.extractTargets("\"ただの定義\""))
    }

    @Test
    fun malformed_and_empty_yield_nothing() {
        assertEquals(emptyList<String>(), DictionaryRedirects.extractTargets("not json{["))
        assertEquals(emptyList<String>(), DictionaryRedirects.extractTargets(""))
        assertEquals(emptyList<String>(), DictionaryRedirects.extractTargets("[]"))
    }

    @Test
    fun target_cap_applies() {
        assertEquals(1, DictionaryRedirects.extractTargets(pureDual, maxTargets = 1).size)
    }
}
