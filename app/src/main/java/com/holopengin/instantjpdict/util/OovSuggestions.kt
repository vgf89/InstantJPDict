package com.holopengin.instantjpdict.util

import android.content.Context
import com.holopengin.instantjpdict.OcrEngine

/**
 * Component-derived alternatives for a recognised character (#44, step 1).
 *
 * The head can only emit characters it has a class for, so its own top-15 can never
 * contain the character that was actually in the book when that character is outside its
 * vocabulary (`呟` 6,930 occurrences, `伜`, `壜` — measured: 86% of the emittable gap is
 * upstream of our prune, so no re-export reaches them). At the measured tier — neighbours
 * sharing IDF mass ≥ 0.7 with the emitted character — the right character lands in a
 * ~14-entry list for 12 of 59 measured substitutions with the truth top-3 in all 12, and
 * deletions get a pool covering 28 of 38.
 *
 * **Nothing here changes recognised text.** These are extra entries in a popup the user
 * already opens, so there is no over-correction risk; the measured over-correction budget
 * governs *auto-apply*, which is parked (see `docs/ocr-oov-correction-plan.md`).
 */
object OovSuggestions {
    const val PREF_ENABLED = "oov_suggestions_enabled"
    const val DEF_ENABLED = true

    /** IDF mass a candidate must share with the emitted character (measured tier). */
    const val MIN_IDF_FRACTION = 0.7f
    const val MAX_COMPONENT_CANDIDATES = 5
    const val MAX_VARIANT_CANDIDATES = 3

    /** Where a popup entry came from. The panel tints non-HEAD entries. */
    enum class Source { HEAD, COMPONENTS, VARIANT }

    data class Suggestion(val char: Char, val source: Source)

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, DEF_ENABLED)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, enabled).apply()
    }

    /**
     * The popup list for one character: the head's own ranking first and unchanged (it is
     * preferred whenever it is right), then component neighbours by descending IDF mass,
     * then the obsolete variant forms of the character itself (offered, never applied —
     * a fold replaces the lookup key, so the variant direction is guarded upstream).
     *
     * Duplicates are dropped across groups, the current character is always present so the
     * panel can mark it, and each generated group is capped.
     */
    fun assemble(
        current: Char,
        headAlternatives: List<Char>,
        oov: OovCandidates?,
        variantForms: (Char) -> List<Char> = { KanjiVariants.obsoleteFormsOf(it) },
    ): List<Suggestion> {
        val out = ArrayList<Suggestion>(headAlternatives.size + MAX_COMPONENT_CANDIDATES + 1)
        val seen = HashSet<Char>(headAlternatives.size * 2 + 8)

        for (c in headAlternatives) {
            if (seen.add(c)) out.add(Suggestion(c, Source.HEAD))
        }
        // An override can put a character in the text that the head never ranked here, and
        // the panel needs it present to show which entry is current.
        if (seen.add(current)) out.add(Suggestion(current, Source.HEAD))

        if (oov != null && oov.hasDiscriminatingComponents(current)) {
            var added = 0
            for (candidate in oov.neighboursOf(current)) {
                if (added >= MAX_COMPONENT_CANDIDATES) break
                // neighboursOf is ordered by descending IDF mass, so the first candidate
                // below the tier ends the group.
                if (candidate.idfFraction < MIN_IDF_FRACTION) break
                if (seen.add(candidate.char)) {
                    out.add(Suggestion(candidate.char, Source.COMPONENTS))
                    added++
                }
            }
        }

        var variants = 0
        for (form in variantForms(current)) {
            if (variants >= MAX_VARIANT_CANDIDATES) break
            if (seen.add(form)) {
                out.add(Suggestion(form, Source.VARIANT))
                variants++
            }
        }
        return out
    }
}
