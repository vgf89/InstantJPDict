package com.holopengin.instantjpdict.util

/**
 * Headword alignment for the ruby-above layout (#69).
 *
 * A merged multi-reading row ("きみ、くん、ぎみ、きんじ") wraps across several
 * lines; centering the kanji under it reads as ragged, so those rows align
 * left instead. Single readings and multi-headword rows stay centered — the
 * latter render one ruby per headword in a flow, where centering is what
 * keeps the layer tidy.
 */
object RubyHeading {
    /** Readings at or above this length wrap in practice at popup widths. */
    const val LONG_RUBY_CHARS = 8
    /** JP comma separating merged readings. */
    private const val MULTI_SEPARATOR = "\u3001" // 、

    /**
     * True when [term]'s ruby line is a long merged multi-reading row and the
     * kanji should therefore sit at the left edge of the wrapped reading.
     * Caller must also confirm a single headword — a flow of several headwords
     * stays centered regardless.
     */
    fun shouldAlignStart(term: String, reading: String): Boolean {
        if (term.isEmpty() || reading.isEmpty()) return false
        if (!reading.contains(MULTI_SEPARATOR)) return false
        return reading.length >= LONG_RUBY_CHARS
    }
}
