package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.ComponentTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #44: the vendored KRADFILE component table — decomposition, the inverted index and
 * the IDF weights, asserted against the shipped asset rather than a fixture.
 *
 * The facts here are the measured ones (see the `instantjpdict-ocr-accuracy` skill):
 * `呟` is the character whose deletion the whole OOV pipeline was built for, and its
 * decomposition `亠 口 幺 玄` is what makes the component filter selective.
 */
class ComponentTableTest {

    private val table: ComponentTable by lazy { ComponentTable.parse(asset().readText()) }

    /**
     * The real asset, read from disk. The JVM unit-test working directory is the
     * module directory, so the module-relative path is the one that resolves; the
     * repo-relative fallback keeps the test runnable from an IDE that uses the root.
     */
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

    @Test
    fun components_of_fukan_are_exactly_the_measured_set() {
        // 呟:亠 口 幺 玄 — order-insensitive: the asset's order is not part of the API.
        assertEquals(setOf('亠', '口', '幺', '玄'), table.componentsOf('呟').toSet())
        assertEquals(4, table.componentsOf('呟').size)
    }

    @Test
    fun kanji_with_uses_the_inverted_index_and_requires_every_component() {
        val both = table.kanjiWith(listOf('口', '亠'))
        assertTrue("呟 carries both 口 and 亠", '呟' in both)
        assertTrue("the pair is selective, not vacuous", both.size in 2 until table.kanjiCount)
        for (k in both) {
            assertTrue(
                "$k returned for 口+亠 but does not carry both",
                table.componentsOf(k).containsAll(listOf('口', '亠')),
            )
        }
        // Codepoint order, so the result is deterministic.
        assertEquals(both, both.sorted())
        // Repeating a component is the same requirement, not a stronger one.
        assertEquals(both, table.kanjiWith(listOf('口', '亠', '口')))
        // No evidence -> nothing; an unknown component -> nothing.
        assertEquals(emptyList<Char>(), table.kanjiWith(emptyList()))
        assertEquals(emptyList<Char>(), table.kanjiWith(listOf('口', '\uE000')))
    }

    @Test
    fun idf_weighs_a_rare_component_above_a_common_one() {
        // 車 is carried by a small minority of the table, 一 by a large one — sharing
        // 車 is the stronger evidence, which is the whole point of the weighting.
        assertTrue(table.idfOf('車') > table.idfOf('一'))
        assertTrue(table.idfOf('一') > 0f)
        // Unknown component: no weight, never NaN.
        assertEquals(0f, table.idfOf('\uE000'), 0f)
    }

    @Test
    fun unknown_characters_are_empty_rather_than_exceptional() {
        assertFalse(table.hasComponents('\uE000'))
        assertEquals(emptyList<Char>(), table.componentsOf('\uE000'))
        assertEquals(emptyList<Char>(), table.componentsOf('あ'))
    }

    @Test
    fun parse_dedupes_repeated_components_and_keeps_the_first_entry() {
        val parsed = ComponentTable.parse(
            listOf(
                "一:一 一",       // repeated component: deduped
                "丁:一 亅",
                "not a table line",
                " :口",           // non-kanji key: kept in the table, excluded from the index
                "ニ:一 二",       // katakana key: same
                "一:口 亅",       // duplicate key: the generator's setdefault keeps the first
            ).joinToString("\n")
        )
        assertEquals(listOf('一'), parsed.componentsOf('一'))
        assertEquals(listOf('一', '亅'), parsed.componentsOf('丁'))
        assertEquals(listOf('口'), parsed.componentsOf(' '))
        // Only the kanji keys form the IDF population and the inverted index.
        assertEquals(4, parsed.entryCount)
        assertEquals(2, parsed.kanjiCount)
        assertEquals(listOf('一', '丁'), parsed.kanjiWith(listOf('一')))
        assertEquals(emptyList<Char>(), parsed.kanjiWith(listOf('口')))
        // ln(2 kanji / 1 carrier) for 亅; ln(2/2) = 0 for the component every kanji has.
        assertEquals(0.6931472f, parsed.idfOf('亅'), 1e-5f)
        assertEquals(0f, parsed.idfOf('一'), 1e-6f)
    }

    @Test
    fun the_bundled_asset_still_has_12156_entries() {
        val file = asset()
        val lines = file.readLines()
        // PROVENANCE.txt pins the entry count and the SHA-256; a truncated or
        // regenerated-differently asset must fail loudly here, not silently degrade
        // every candidate set downstream.
        assertEquals("entry count in components/krad_components.txt", 12156, lines.size)
        lines.forEachIndexed { i, line ->
            assertTrue(
                "line ${i + 1} is not 'kanji:components': ${line.take(40)}",
                line.length >= 3 && line[1] == ':' && line[2] != ' '
            )
        }
        // Every line must survive parsing — a line the parser skips would shrink the
        // table below the line count above.
        assertEquals(12156, table.entryCount)
        assertEquals(12156, table.kanjiCount)
        // First line of the codepoint-sorted asset.
        assertEquals("一:一", lines.first())
    }
}
