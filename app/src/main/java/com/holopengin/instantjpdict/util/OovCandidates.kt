package com.holopengin.instantjpdict.util

/**
 * Component-level candidate policy for out-of-vocabulary characters (#44).
 *
 * Two shapes of the OOV failure are served here, both measured against the vendored
 * KRADFILE table ([ComponentTable]) rather than guessed:
 *
 *  - **deletion** — the head emits blank where a character should be, e.g. `と呟いて`
 *    -> `といて`. The head's top-K at that timestep is radical-consistent
 *    (`咳 咬 啦 哮 眩`), and [majorityComponents] turns that free evidence into the
 *    components a candidate must carry.
 *  - **substitution** — the head is confidently wrong and emits a component-space
 *    neighbour (`壜` -> `曇`, `俥` -> `庫`): 45 of 51 measured substitutions (88%)
 *    share a component with the truth. [neighboursOf] generates that neighbour set
 *    from the character the head actually emitted.
 *
 * Ranking is deliberately *not* done here — a text n-gram cannot separate `と呟いて`
 * from `と咲いて`, so the ordering this class exposes is component evidence only, and
 * the caller adds whatever LM it has. Do not quote a rank from this list without the
 * pool size beside it: the neighbour pool is large (median ~2.5 k when only one
 * component is shared), which is exactly why the IDF weighting exists.
 */
class OovCandidates(private val table: ComponentTable) {

    /**
     * A character that could stand where [char] was emitted, with the strength of the
     * visual relation to the emitted character.
     *
     * @param char the candidate character
     * @param idfFraction share of the emitted character's component information (IDF
     *   mass) that this candidate also carries, in `[0, 1]`. Intersected, never the
     *   candidate's whole mass — see [neighboursOf].
     * @param sharesAllComponents the candidate carries **every** component of the
     *   emitted character — the near-identity relation (measured: ~4 candidates,
     *   right character first in 9/9 cases, the only configuration where
     *   auto-insertion was defensible, with n = 9)
     */
    data class Candidate(
        val char: Char,
        val idfFraction: Float,
        val sharesAllComponents: Boolean,
    )

    /**
     * Candidate characters for a character the head **emitted** (the substitution
     * mode), ordered by [Candidate.idfFraction] descending, ties broken by codepoint
     * so the list is deterministic.
     *
     * The pool is every kanji sharing **at least one** component with [emitted]
     * (excluding [emitted] itself). That is the measured pair of rules — "share any
     * component" covers 23/26 cases at ~2,455 candidates, and "share the rarest
     * component" trades coverage for a ~120-candidate list — so this method returns
     * the wide set and lets the caller gate on [Candidate.idfFraction] or
     * [Candidate.sharesAllComponents]. Sharing more *components* is the wrong
     * yardstick: of 51 substitutions 28 share exactly one and 6 share none, while
     * sharing `車` is not the evidence sharing `一` is.
     *
     * **The fraction MUST intersect.** The numerator is the IDF of the components the
     * candidate *shares* with the emitted character; the denominator is the emitted
     * character's whole IDF mass. An earlier version used the candidate's own total
     * component mass instead, which rewards carrying many common components and
     * scored 0/45 on the ranking task — meaningless. Signature detail kept from the
     * reference `idf_frac(a, b)`: a duplicate component in the emitted character's
     * decomposition would be summed twice in the denominator, so both are consumed
     * as the table stores them.
     *
     * An unknown character, or one whose components carry no IDF mass, yields an
     * empty list rather than an exception.
     */
    fun neighboursOf(emitted: Char): List<Candidate> {
        val emittedComponents = table.componentsOf(emitted)
        if (emittedComponents.isEmpty()) return emptyList()
        val emittedSet = emittedComponents.toSet()
        val denominator = idfMassOf(emittedComponents)
        if (denominator <= 0.0) return emptyList()

        // Component -> kanji is a precomputed posting list, so the pool is the union
        // of a handful of lists, not a scan of the 12,156-entry table.
        val pool = LinkedHashSet<Char>()
        for (component in emittedSet) pool.addAll(table.kanjiWith(listOf(component)))

        val out = ArrayList<Candidate>(pool.size)
        for (k in pool) {
            if (k == emitted) continue
            val comps = table.componentsOf(k).toSet()
            val shared = sharedIdf(comps, emittedSet)
            out.add(
                Candidate(
                    char = k,
                    idfFraction = (shared / denominator).toFloat(),
                    sharesAllComponents = emittedSet.all { it in comps },
                )
            )
        }
        out.sortWith(compareByDescending<Candidate> { it.idfFraction }.thenBy { it.char })
        return out
    }

    /**
     * The components a top-K of characters **agree on**, by majority vote: a component
     * carried by at least [needFraction] of [topK] (at least one character). Ordered
     * strongest first — by how many of the top-K carry it, then by codepoint.
     *
     * **Majority voting, never a strict intersection.** For the measured top-5
     * `咳 咬 啦 哮 眩` the strict intersection is empty — `眩` (亠 幺 玄 目) has no 口 —
     * so intersecting all five filters every candidate away and the recovery silently
     * does nothing. The vote keeps 口 (4/5) and 亠 (3/5), and both are components of
     * the dropped `呟` (亠 口 幺 玄).
     *
     * `counts` counts *characters*, not component occurrences: a character repeating a
     * component in its decomposition still contributes one vote, mirroring
     * `set(table.get(c, []))` in the reference `shared_components`.
     *
     * If the vote leaves nothing (a high [needFraction], or a top-K with no agreement)
     * the single strongest component is returned rather than an empty set — the caller
     * then has a one-component filter instead of no filter at all. An empty [topK], or
     * one whose characters are all unknown, returns an empty list.
     *
     * @param needFraction share of [topK] that must carry a component; the default 0.5
     *   is the measured threshold.
     */
    fun majorityComponents(topK: List<Char>, needFraction: Double = 0.5): List<Char> {
        if (topK.isEmpty()) return emptyList()
        val counts = LinkedHashMap<Char, Int>()
        for (ch in topK) {
            for (component in table.componentsOf(ch).toSet()) {
                counts[component] = (counts[component] ?: 0) + 1
            }
        }
        if (counts.isEmpty()) return emptyList()
        // `int(len(chars) * need_frac + 0.9999)` in the reference: ceil for the usual
        // fractional thresholds (5 * 0.5 -> 3), truncation for exact integers.
        val need = maxOf(1, (topK.size * needFraction + 0.9999).toInt())
        var picked = counts.filterValues { it >= need }.keys.toList()
        if (picked.isEmpty()) {
            // Unreachable for needFraction <= 1 (every component has at least one
            // vote and need >= 1) but kept because the reference falls back here.
            val strongest = counts.maxByOrNull { it.value } ?: return emptyList()
            picked = listOf(strongest.key)
        }
        return picked.sortedWith(compareByDescending<Char> { counts[it] ?: 0 }.thenBy { it })
    }

    /** Sum of the IDF of [comps] as stored — duplicates counted, like the reference sum. */
    private fun idfMassOf(comps: List<Char>): Double {
        var total = 0.0
        for (c in comps) total += table.idfOf(c).toDouble()
        return total
    }

    /** IDF mass of the components in [a] that also appear in [b] — the intersection. */
    private fun sharedIdf(a: Set<Char>, b: Set<Char>): Double {
        var total = 0.0
        for (c in a) if (c in b) total += table.idfOf(c).toDouble()
        return total
    }
}
