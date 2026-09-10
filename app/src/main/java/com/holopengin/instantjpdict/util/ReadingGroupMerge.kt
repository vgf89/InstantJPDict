package com.holopengin.instantjpdict.util

import com.holopengin.instantjpdict.FormattedReadingGroup

/**
 * Cross-group headword merging (#69).
 *
 * One matched term can yield several reading groups sharing identical
 * headword kanji (君 → きみ/くん/ぎみ/きんじ); the popup then repeats the
 * same headword block once per group, stacked. Merge consecutive runs with
 * identical headword kanji into one group: readings comma-joined (ruby
 * falls back to full-reading, which is exactly the requested comma row),
 * senses concatenated and renumbered 1..N. Pure Kotlin so JVM-unit-testable.
 */
object ReadingGroupMerge {
    fun mergeSameKanji(groups: List<FormattedReadingGroup>): List<FormattedReadingGroup> {
        if (groups.size < 2) return groups
        val out = mutableListOf<FormattedReadingGroup>()
        var i = 0
        while (i < groups.size) {
            var j = i + 1
            while (j < groups.size && sameHeadwords(groups[i], groups[j])) j++
            out.add(if (j == i + 1) groups[i] else mergeRun(groups.subList(i, j)))
            i = j
        }
        return out
    }

    private fun sameHeadwords(a: FormattedReadingGroup, b: FormattedReadingGroup): Boolean {
        // Kanji-entry branch renders kanji + ON/KUN rows, never ruby — leave it.
        if (a.isKanjiEntry || b.isKanjiEntry) return false
        return a.headwords.map { it.kanji } == b.headwords.map { it.kanji }
    }

    private fun mergeRun(run: List<FormattedReadingGroup>): FormattedReadingGroup {
        val first = run.first()
        var n = 0
        val senses = run.flatMap { g ->
            g.senseGroups.map { sg ->
                sg.copy(senses = sg.senses.map { s -> s.copy(index = ++n) })
            }
        }
        return FormattedReadingGroup(
            reading = run.joinToString("、") { it.reading },
            headwords = first.headwords,
            senseGroups = senses,
            isKanjiEntry = false
        )
    }
}
