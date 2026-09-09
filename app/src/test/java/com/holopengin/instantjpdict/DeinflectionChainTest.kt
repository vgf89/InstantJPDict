package com.holopengin.instantjpdict

import com.google.gson.Gson
import com.holopengin.instantjpdict.data.DictionaryEntry
import com.holopengin.instantjpdict.util.DeinflectionChain
import com.holopengin.instantjpdict.util.Deinflector
import org.junit.Assert.*
import org.junit.Test

/** #62: deinflection chains ride the lookup pass into the popup. */
class DeinflectionChainTest {

    private fun testDeinflector() = Deinflector(
        """
        {
          "past": [{"kanaIn": "た", "kanaOut": "る", "rulesIn": [], "rulesOut": ["v1"]}],
          "causative": [{"kanaIn": "させる", "kanaOut": "る", "rulesIn": [], "rulesOut": ["v1"]}]
        }
        """.trimIndent().reader()
    )

    private fun entry(kanji: String, reading: String, rules: String = "v1") =
        DictionaryEntry(
            kanji = kanji,
            reading = reading,
            definitions = """["to eat"]""",
            rules = rules,
            popularity = 0,
            dictionaryId = 0
        )

    // ── Deinflector: reasons are readable labels, not kana fragments ──

    @Test
    fun reasons_carryRuleNames() {
        val hit = testDeinflector().deinflect("たべた").first { it.term == "たべる" }
        assertEquals(listOf("past"), hit.reasons)
    }

    @Test
    fun reasons_accumulateOutermostFirst_multiStep() {
        // たべさせた →(past)→ たべさせる →(causative)→ たべる
        val hit = testDeinflector().deinflect("たべさせた").first { it.term == "たべる" }
        assertEquals(listOf("past", "causative"), hit.reasons)
    }

    @Test
    fun identityResult_hasNoReasons() {
        val self = testDeinflector().deinflect("たべた").first { it.term == "たべた" }
        assertTrue(self.reasons.isEmpty())
    }

    // ── DeinflectionChain.label ──

    @Test
    fun label_singleStep() {
        assertEquals("たべた → たべる · past", DeinflectionChain("たべた", listOf("past")).label("たべる"))
    }

    @Test
    fun label_multiStep() {
        assertEquals(
            "たべさせた → たべる · past · causative",
            DeinflectionChain("たべさせた", listOf("past", "causative")).label("たべる")
        )
    }

    // ── prepareSearchCandidates: chain rides alongside the term ──

    @Test
    fun candidates_directVariantsHaveNoChain_deinflectedDo() {
        val controller = OcrOverlayStateController()
        val (_, byLength) = controller.prepareSearchCandidates("たべた", testDeinflector())
        val len3 = byLength.first { it.first == 3 }.second

        val direct = len3.first { it.term == "たべた" && it.requiredTypes == null }
        assertNull(direct.chain)

        val deinflected = len3.first { it.term == "たべる" }
        assertEquals(DeinflectionChain("たべた", listOf("past")), deinflected.chain)
    }

    // ── processResults: chain attaches to the matched term ──

    @Test
    fun processResults_attachesChainToDeinflectedTerm_only() {
        val controller = OcrOverlayStateController()
        val deinf = testDeinflector()
        val (terms, _) = controller.prepareSearchCandidates("たべた", deinf)
        val db = listOf(entry("たべた", "たべた"), entry("たべる", "たべる"))

        val (matches, maxLen) = controller.processResults(db, controller.prepareSearchCandidates("たべた", deinf).second, terms, "たべた")

        val byTerm = matches.associateBy { it.term }
        assertNull(byTerm.getValue("たべた").chain)
        assertEquals(DeinflectionChain("たべた", listOf("past")), byTerm.getValue("たべる").chain)
        assertEquals(3, maxLen)
    }

    // ── formatDictionaryResults: chain reaches FormattedEntry ──

    @Test
    fun formatDictionaryResults_propagatesChain_directStaysNull() {
        val controller = OcrOverlayStateController()
        val gson = Gson()
        val formatted = controller.formatDictionaryResults(
            listOf(
                TermMatch("たべた", listOf(entry("たべた", "たべた"))),
                TermMatch("たべる", listOf(entry("たべる", "たべる")), DeinflectionChain("たべた", listOf("past")))
            ),
            gson
        )

        val byTerm = formatted.associateBy { it.term }
        assertNull(byTerm.getValue("たべた").deinflection)
        assertEquals(DeinflectionChain("たべた", listOf("past")), byTerm.getValue("たべる").deinflection)
        // Reading groups unaffected by the chain.
        assertEquals(listOf("たべる"), byTerm.getValue("たべる").readingGroups.map { it.reading })
    }
}
