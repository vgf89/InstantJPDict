package com.holopengin.instantjpdict.util

/**
 * Aligns a dictionary reading against its headword so ruby is shown only over
 * kanji spans, with okurigana/kana rendered as plain base text (#55).
 *
 * Pure Kotlin (no Android deps) so it is unit-testable. Returns null when the
 * reading cannot be unambiguously aligned — callers must fall back to
 * full-reading ruby rendering in that case.
 *
 * Examples:
 * - 食べる / たべる → [食:た][べる:—]
 * - 大きい / おおきい → [大:おお][きい:—]
 * - 申し込む / もうしこむ → [申:もう][し:—][込:こ][む:—]
 * - 今日 / きょう → [今日:きょう] (no kana anchor: whole ruby, as before)
 */
object FuriganaAligner {
    /** One render run: [base] surface text with optional [ruby] above it (null = plain). */
    data class Segment(val base: String, val ruby: String?)

    fun align(term: String, reading: String): List<Segment>? {
        if (term.isEmpty() || reading.isEmpty()) return null
        if (term == reading) return listOf(Segment(term, null))
        // katakanaToHiragana is 1:1 per char, so indices transfer to [reading].
        val normReading = JapaneseUtil.katakanaToHiragana(reading)
        if (normReading.length != reading.length) return null

        val segments = mutableListOf<Segment>()
        var ti = 0
        var ri = 0
        while (ti < term.length) {
            val c = term[ti]
            if (!isKanjiChar(c)) {
                // Kana literal: must match the reading at the current position.
                if (ri >= normReading.length) return null
                if (JapaneseUtil.katakanaToHiragana(c.toString()) != normReading[ri].toString()) return null
                val last = segments.lastOrNull()
                if (last != null && last.ruby == null) {
                    segments[segments.size - 1] = last.copy(base = last.base + c)
                } else {
                    segments.add(Segment(c.toString(), null))
                }
                ti++
                ri++
            } else {
                // Kanji run: ruby is everything up to the next kana anchor.
                var tj = ti
                while (tj < term.length && isKanjiChar(term[tj])) tj++
                if (tj < term.length) {
                    val anchor = JapaneseUtil.katakanaToHiragana(term[tj].toString())
                    val idx = normReading.indexOf(anchor, ri)
                    if (idx < 0) return null
                    val ruby = reading.substring(ri, idx)
                    if (ruby.isEmpty()) return null
                    segments.add(Segment(term.substring(ti, tj), ruby))
                    ri = idx
                } else {
                    val ruby = reading.substring(ri)
                    if (ruby.isEmpty()) return null
                    segments.add(Segment(term.substring(ti, tj), ruby))
                    ri = reading.length
                }
                ti = tj
            }
        }
        if (ri != normReading.length) return null
        return segments
    }

    private fun isKanjiChar(c: Char): Boolean {
        return c == '々' || c == '〻' ||
            c in '\u3400'..'\u4DBF' ||
            c in '\u4E00'..'\u9FFF' ||
            c in '\uF900'..'\uFAFF'
    }
}
