package com.holopengin.instantjpdict.util

import com.holopengin.instantjpdict.FormattedReadingGroup

/** On/kun/other reading split for a merged headword row (#69). */
data class SplitReadings(
    val kun: List<String>,
    val on: List<String>,
    /** Readings in neither KANJIDIC list (e.g. name readings) — shown, never mislabeled. */
    val other: List<String>
)

/** KANJIDIC on/kun lists for one kanji, hiragana-normalized. */
data class KunOn(val on: Set<String>, val kun: Set<String>)

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
    fun mergeSameKanji(
        groups: List<FormattedReadingGroup>,
        kunOn: Map<String, KunOn> = emptyMap()
    ): List<FormattedReadingGroup> {
        if (groups.size < 2) return groups
        val out = mutableListOf<FormattedReadingGroup>()
        var i = 0
        while (i < groups.size) {
            var j = i + 1
            while (j < groups.size && sameHeadwords(groups[i], groups[j])) j++
            out.add(if (j == i + 1) groups[i] else mergeRun(groups.subList(i, j), kunOn))
            i = j
        }
        return out
    }

    private fun sameHeadwords(a: FormattedReadingGroup, b: FormattedReadingGroup): Boolean {
        // Kanji-entry branch renders kanji + ON/KUN rows, never ruby — leave it.
        if (a.isKanjiEntry || b.isKanjiEntry) return false
        return a.headwords.map { it.kanji } == b.headwords.map { it.kanji }
    }

    private fun mergeRun(
        run: List<FormattedReadingGroup>,
        kunOn: Map<String, KunOn>
    ): FormattedReadingGroup {
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
            isKanjiEntry = false,
            splitReadings = buildSplit(run, kunOn[first.headwords.firstOrNull()?.kanji])
        )
    }

    /**
     * Split a merged run's readings into 訓/音 rows via the kanji's KANJIDIC
     * lists. Null when unsplittable (no KANJIDIC data, or nothing classifies)
     * — callers fall back to the comma ruby row, so a missing dictionary
     * degrades to today's rendering instead of a wrong one.
     */
    private fun buildSplit(
        run: List<FormattedReadingGroup>,
        ko: KunOn?
    ): SplitReadings? {
        if (ko == null) return null
        val kun = mutableListOf<String>()
        val on = mutableListOf<String>()
        val other = mutableListOf<String>()
        for (g in run) {
            when (g.reading) {
                in ko.kun -> kun.add(g.reading)
                in ko.on -> on.add(g.reading)
                else -> other.add(g.reading)
            }
        }
        if (kun.isEmpty() && on.isEmpty()) return null
        return SplitReadings(kun, on, other)
    }
}
