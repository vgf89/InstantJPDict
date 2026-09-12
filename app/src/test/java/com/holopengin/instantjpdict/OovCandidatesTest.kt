package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.ComponentTable
import com.holopengin.instantjpdict.util.OovCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #44: the component policy asserted against the measured cases, on the real asset.
 *
 * The two numbers that carry the design:
 *  - the top-5 at the `呟` gap is `咳 咬 啦 哮 眩`, whose **strict intersection is
 *    empty** (`眩` has no 口) but whose majority is `口 亠` — the components of `呟`;
 *  - the IDF fraction must be the **intersected** share of the emitted character's
 *    mass, the rule that replaced a variant scoring 0/45.
 */
class OovCandidatesTest {

    private val table: ComponentTable by lazy { ComponentTable.parse(asset().readText()) }
    private val candidates: OovCandidates by lazy { OovCandidates(table) }

    /** See [ComponentTableTest.asset] for why the path is module-relative. */
    private fun asset(): File {
        val candidates = listOf(
            File("src/main/assets/components/krad_components.txt"),
            File("app/src/main/assets/components/krad_components.txt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "krad_components.txt not found from ${File(".").absolutePath} — " +
                    "expected the unit-test working directory to be the module dir"
            )
    }

    /** The measured top-5 the recogniser emitted at the deleted `呟` (all 口/亠 chars). */
    private val measuredTop5 = listOf('咳', '咬', '啦', '哮', '眩')

    @Test
    fun majority_components_of_the_measured_top5_matches_fukan() {
        // 口 is carried by 4 of 5 (咳 咬 啦 哮), 亠 by 3 (咳 咬 眩) — both >= ceil(5/2),
        // and both are components of the dropped 呟 (亠 口 幺 玄).
        assertEquals(listOf('口', '亠'), candidates.majorityComponents(measuredTop5))
        assertEquals(setOf('口', '亠'), candidates.majorityComponents(measuredTop5).toSet())
        // The strict intersection really is empty: this is what the vote replaces.
        val strict = measuredTop5
            .map { table.componentsOf(it).toSet() }
            .reduce { a, b -> a intersect b }
        assertEquals(emptySet<Char>(), strict)
    }

    @Test
    fun majority_components_falls_back_to_the_strongest_and_handles_empty_input() {
        // needFraction 1.0 demands unanimity, which this top-K does not have; the
        // fallback is the single strongest component (口, 4 of 5) — never an empty
        // filter, which would silently filter every candidate away.
        assertEquals(listOf('口'), candidates.majorityComponents(measuredTop5, needFraction = 1.0))
        assertEquals(emptyList<Char>(), candidates.majorityComponents(emptyList()))
        // Characters with no decomposition agree on nothing.
        assertEquals(emptyList<Char>(), candidates.majorityComponents(listOf('\uE000', '\uE001')))
    }

    @Test
    fun neighbours_of_emitted_char_find_the_measured_substitution() {
        // 曇 (二 厶 日 雨) -> 壜 (二 厶 土 日 雨): the head's component-space neighbour
        // from the Aozora bench. 壜 carries all four of 曇's components, so it is the
        // near-identity relation, with full IDF fraction.
        val neighbours = candidates.neighboursOf('曇')
        val ban = neighbours.first { it.char == '壜' }
        assertTrue(ban.sharesAllComponents)
        assertEquals(1f, ban.idfFraction, 1e-6f)

        // And the relation is discriminating: 但 (一 化 日) shares only the common 日.
        val ta = neighbours.first { it.char == '但' }
        assertFalse(ta.sharesAllComponents)
        assertEquals(0.1798134f, ta.idfFraction, 1e-4f)

        // Exactly the two kanji carrying every component of 曇, ordered by codepoint
        // when the evidence ties: 壜 < 罎.
        assertEquals(listOf('壜', '罎'), neighbours.filter { it.sharesAllComponents }.map { it.char })
        assertEquals('壜', neighbours.first().char)
    }

    @Test
    fun idf_fraction_intersects_the_emitted_characters_components() {
        // 呟 = 亠 口 幺 玄 (13.064 nats); 咳 = ノ 丶 亠 人 口 shares 亠 + 口 only.
        // (4.2185 / 13.0642) = 0.32291. The rejected variant divided by *咳's* whole
        // mass instead, giving 0.35765 — that variant scored 0/45 on the ranking task
        // because it rewards carrying many common components.
        val kai = candidates.neighboursOf('呟').first { it.char == '咳' }
        assertEquals(0.3229068f, kai.idfFraction, 1e-4f)
        assertFalse(kai.sharesAllComponents) // 呟's 幺 and 玄 are missing

        // The emitted character itself is not its own candidate: it competes under the
        // caller's keep-prior, not as a neighbour.
        assertTrue(candidates.neighboursOf('呟').none { it.char == '呟' })
    }

    @Test
    fun neighbours_are_sorted_by_evidence_and_never_contain_the_emitted_character() {
        val neighbours = candidates.neighboursOf('曇')
        assertTrue("the neighbour pool is the wide 'shares any component' rule", neighbours.size > 1000)
        neighbours.zipWithNext { a, b -> assertTrue(a.idfFraction >= b.idfFraction) }
        neighbours.forEach {
            assertTrue(it.char != '曇')
            assertTrue(it.idfFraction > 0f && it.idfFraction <= 1f)
            assertTrue(table.componentsOf(it.char).any { c -> c in table.componentsOf('曇') })
        }
    }

    @Test
    fun unknown_characters_yield_no_candidates_and_no_majority() {
        assertEquals(emptyList<OovCandidates.Candidate>(), candidates.neighboursOf('\uE000'))
        assertEquals(emptyList<OovCandidates.Candidate>(), candidates.neighboursOf('あ'))
        assertEquals(emptyList<Char>(), candidates.majorityComponents(listOf('あ')))
    }
}
