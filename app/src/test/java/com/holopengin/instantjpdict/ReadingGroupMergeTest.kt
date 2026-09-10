package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.ReadingGroupMerge
import org.junit.Test

import org.junit.Assert.*

// #69: consecutive reading groups sharing headword kanji merge into one
// comma-reading group; everything else passes through untouched.
class ReadingGroupMergeTest {
    private fun group(
        reading: String,
        vararg kanji: String,
        senses: Int = 1,
        kanjiEntry: Boolean = false
    ) = FormattedReadingGroup(
        reading = reading,
        headwords = kanji.map { FormattedHeadword(it, null, null) },
        senseGroups = listOf(
            FormattedSenseGroup(
                emptyList(),
                (1..senses).map { FormattedSense(it, emptyList()) },
                false
            )
        ),
        isKanjiEntry = kanjiEntry
    )

    @Test
    fun merges_consecutive_same_kanji() {
        val out = ReadingGroupMerge.mergeSameKanji(
            listOf(group("きみ", "君", senses = 2), group("くん", "君"), group("ぎみ", "君"))
        )
        assertEquals(1, out.size)
        assertEquals("きみ、くん、ぎみ", out[0].reading)
        assertEquals(listOf("君"), out[0].headwords.map { it.kanji })
        // 2 + 1 + 1 senses renumbered 1..4.
        assertEquals(listOf(1, 2, 3, 4), out[0].senseGroups.flatMap { sg -> sg.senses.map { it.index } })
    }

    @Test
    fun distinct_kanji_untouched() {
        val groups = listOf(group("た", "他"), group("た", "多"), group("た", "田"))
        val out = ReadingGroupMerge.mergeSameKanji(groups)
        assertEquals(3, out.size)
        assertEquals(listOf("他", "多", "田"), out.flatMap { g -> g.headwords.map { it.kanji } })
    }

    @Test
    fun non_consecutive_runs_stay_ordered() {
        val out = ReadingGroupMerge.mergeSameKanji(
            listOf(group("きみ", "君"), group("た", "他"), group("くん", "君"))
        )
        assertEquals(3, out.size)
        assertEquals("きみ", out[0].reading)
        assertEquals("くん", out[2].reading)
    }

    @Test
    fun kanji_entries_never_merge() {
        val out = ReadingGroupMerge.mergeSameKanji(
            listOf(group("クン", "君", kanjiEntry = true), group("クン", "君", kanjiEntry = true))
        )
        assertEquals(2, out.size)
    }

    @Test
    fun single_and_empty_pass_through() {
        val one = listOf(group("きみ", "君"))
        assertEquals(one, ReadingGroupMerge.mergeSameKanji(one))
        assertEquals(emptyList<FormattedReadingGroup>(), ReadingGroupMerge.mergeSameKanji(emptyList()))
    }
}
