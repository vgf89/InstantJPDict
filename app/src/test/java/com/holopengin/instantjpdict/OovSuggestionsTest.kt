package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.ComponentTable
import com.holopengin.instantjpdict.util.OovCandidates
import com.holopengin.instantjpdict.util.OovSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The popup list assembly (#44 step 1).
 *
 * The fixture is sized so the arithmetic is real, not incidental: 50 kanji, `化` carried
 * by two of them and `中` by twenty, which puts a candidate sharing only `化` at
 * ln(50/2)/(ln(50/2)+ln(50/20)) ≈ 0.78 — clear of the measured 0.7 tier — while a
 * candidate sharing only the common `中` sits at ≈ 0.22 and must be excluded. A smaller
 * fixture would exclude both and the test would pass for the wrong reason.
 */
class OovSuggestionsTest {

    private fun fixtureTable(): ComponentTable {
        val sb = StringBuilder()
        sb.append("仲:化 中\n")          // the measured substitution pair
        sb.append("伜:化 九 十\n")       // shares 化, which is the rare half of 仲
        for (i in 0 until 19) sb.append("%c:中\n".format(0x4E00 + i))
        for (i in 0 until 29) sb.append("%c:水\n".format(0x5E00 + i))
        return ComponentTable.parse(sb.toString())
    }

    private val oov = OovCandidates(fixtureTable())

    @Test
    fun component_neighbour_is_appended_after_the_head_list() {
        val out = OovSuggestions.assemble('仲', listOf('仲'), oov) { emptyList() }
        assertEquals(listOf('仲', '伜'), out.map { it.char })
        assertEquals(
            listOf(OovSuggestions.Source.HEAD, OovSuggestions.Source.COMPONENTS),
            out.map { it.source },
        )
    }

    @Test
    fun head_order_is_preserved_and_a_repeated_candidate_is_not_appended_twice() {
        val out = OovSuggestions.assemble('仲', listOf('伜', '仲'), oov) { emptyList() }
        assertEquals(listOf('伜', '仲'), out.map { it.char })
        assertTrue(out.all { it.source == OovSuggestions.Source.HEAD })
    }

    @Test
    fun the_variant_group_walks_the_form_space_from_the_current_selection() {
        // Suggestions are assembled from whatever character is *current*, so choosing a
        // generated entry rebuilds the candidates around it: 摑 -> 掴 -> 摑 is reachable by
        // tapping through the popup, which is what makes the kanji form space explorable
        // (#44). Pinned so a refactor cannot quietly make the list depend on the character
        // the recogniser originally emitted instead of the current selection.
        val fromOld = OovSuggestions.assemble('摑', listOf('摑'), null) { listOf('掴') }
        assertEquals(OovSuggestions.Source.VARIANT, fromOld.last().source)
        assertEquals('掴', fromOld.last().char)
        val fromModern = OovSuggestions.assemble('掴', listOf('掴'), null) { listOf('摑') }
        assertEquals(OovSuggestions.Source.VARIANT, fromModern.last().source)
        assertEquals('摑', fromModern.last().char)
    }

    @Test
    fun a_single_component_character_gets_no_component_suggestions() {
        // The IDF fraction is relative to the emitted character, so a one-component
        // character makes every one of its carriers a full match (fraction 1.0): the tier
        // would admit hundreds of unrelated characters and the cap would then choose
        // between them by codepoint. No discriminating evidence, no suggestions.
        val oneComponent = 0x4E00.toChar()          // carries 中 only
        val out = OovSuggestions.assemble(oneComponent, listOf(oneComponent), oov) { emptyList() }
        assertEquals(listOf(oneComponent), out.map { it.char })
    }

    @Test
    fun variant_forms_are_offered_last_and_capped() {
        val forms = (0 until 5).map { (0x5F00 + it).toChar() }
        val out = OovSuggestions.assemble('仲', listOf('仲'), oov) { forms }
        val variantEntries = out.filter { it.source == OovSuggestions.Source.VARIANT }
        assertEquals(OovSuggestions.MAX_VARIANT_CANDIDATES, variantEntries.size)
        assertEquals(OovSuggestions.MAX_VARIANT_CANDIDATES, out.lastIndex - out.indexOfFirst {
            it.source == OovSuggestions.Source.VARIANT
        } + 1)
    }

    @Test
    fun component_group_is_capped() {
        // Emitted 仲 = 化 + 中, with 化 carried by 7 of 31 kanji and 中 by 25: a candidate
        // sharing only 化 scores ln(31/7)/(ln(31/7)+ln(31/25)) ≈ 0.87, clear of the tier, so
        // six of them qualify and the cap — not the tier — is what bounds the list.
        val wide = StringBuilder()
        wide.append("仲:化 中\n")
        for (i in 0 until 6) wide.append("%c:化\n".format(0x6C00 + i))
        for (i in 0 until 24) wide.append("%c:中\n".format(0x4E00 + i))
        val out = OovSuggestions.assemble(
            '仲', listOf('仲'), OovCandidates(ComponentTable.parse(wide.toString()))
        ) { emptyList() }
        val generated = out.count { it.source == OovSuggestions.Source.COMPONENTS }
        assertEquals(OovSuggestions.MAX_COMPONENT_CANDIDATES, generated)
    }

    @Test
    fun without_a_component_table_the_list_is_the_head_list_unchanged() {
        val head = listOf('あ', '仲', 'い')
        val out = OovSuggestions.assemble('仲', head, null) { emptyList() }
        assertEquals(head, out.map { it.char })
        assertTrue(out.all { it.source == OovSuggestions.Source.HEAD })
    }

    @Test
    fun the_current_character_is_present_even_when_the_head_list_omits_it() {
        // an override can put a character in the text that the head never ranked here
        val out = OovSuggestions.assemble('伜', listOf('仲'), oov) { emptyList() }
        assertTrue("current character must be listed", out.any { it.char == '伜' })
        assertEquals('仲', out.first().char)
    }
}
