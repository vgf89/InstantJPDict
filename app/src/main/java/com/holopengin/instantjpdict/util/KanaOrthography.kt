package com.holopengin.instantjpdict.util

import android.content.Context

/**
 * Pre-reform kana orthography (#75): the lookup-query normaliser for text written
 * before the 1946 spelling reform (旧仮名遣い), loaded from
 * `variants/kana_variants.txt` (source, licence and SHA-256 are in the
 * `kana_variants.PROVENANCE.txt` beside it; regenerate with
 * `tools/build_kana_variants.py`).
 *
 * **Query-side only, exactly like the kanji variant fold (#44).** The reader sees
 * the book's own orthography; only the string handed to the dictionary is
 * normalised, and the raw form is searched alongside it, so a fold can add a
 * reachable headword but can never take one away.
 *
 * **Why a size-only corrector could not do this.** [KanaSizeFix] flips a kana
 * between its small and large form on OCR output, and 76 of `deinflect.json`'s 569
 * rules mention small kana — but none maps a size. On pre-reform text the change
 * needed is never only a size: `いつしよ` -> `いっしょ` needs `つ`->`っ` *and*
 * `よ`->`ょ`; `しゆつぱつ` -> `しゅっぱつ` needs `つ`->`っ` *and* `ゆ`->`ゅ`. A model
 * that flips the subset it is confident about leaves a hybrid that matches neither
 * the book nor the dictionary. This pass rewrites the whole query onto the modern
 * form instead, which is what a reader of old text and the imported dictionary
 * both want.
 *
 * **Direction and source.** The table's pairs come from JMdict's own kana
 * cross-references — readings of one entry that differ at a single position on a
 * historical-kana row (`かつかざん`/`かっかざん`, `あはれ`/`あわれ`,
 * `あづま`/`あずま`) — and the direction is the row rule `variant -> modern`. See
 * `tools/build_kana_variants.py` for the extraction and the per-pair citation in
 * the PROVENANCE file.
 *
 * **What is deliberately NOT folded.** Measurement, not taste (2,000-line Aozora
 * bench slice, 350 lines of 旧仮名; counts in the #75 commit message):
 *
 *  - `を`/`ヲ` -> `お`/`オ`: the ordinary modern accusative particle. JMdict
 *    attests the alternation only inside a few lexical items (`をことてん`,
 *    `みやこをどり`); folding it row-wide would rewrite correct modern queries
 *    (`本を読む` -> `本お読む`) and break the lookups it is meant to help.
 *  - the 小書き row (`あ`->`ぁ` … `わ`->`ゎ`, and katakana): not in the table at
 *    all. Its variant side is an ordinary modern kana, and shipping it changed
 *    1,220 positions on the modern slice — 977 of them clipping a reachable
 *    headword — while repairing *nothing* extra on the legacy one (118 either
 *    way). The alternation is gairaigo spelling, not historical kana.
 *  - `づ` after `つ`/`ち` and `ぢ` after `つ`/`ち`: 連濁, where `つづく`/`ちぢむ`
 *    are the modern forms themselves. Folding those would break correct lookups.
 *  - `つ` before a か行 kana (`つくえ`, `つかう`): measured to clip more modern
 *    words than it repaired, so the 促音 rule fires only before た行/さ行/ぱ行.
 */
object KanaOrthography {
    const val ASSET_PATH = "variants/kana_variants.txt"

    @Volatile
    private var table: Table = Table.EMPTY

    /** Parse the committed asset and install it. Idempotent. */
    fun install(context: Context) {
        install(parse(context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }))
    }

    /** Install an already-parsed table (tests, or a table from another source). */
    fun install(parsed: Table) {
        table = parsed
    }

    fun parse(text: String): Table = Table.parse(text)

    /** Number of distinct variants installed; 0 when nothing is loaded. */
    val entryCount: Int get() = table.size

    /** The modern form of [variant], or [variant] itself when the table has none. */
    fun canonical(variant: Char): Char = table.canonical(variant)

    /**
     * Rewrite a lookup query from pre-reform orthography onto the modern form the
     * imported dictionary keys on. Never throws and never returns null; the
     * identity when nothing in the query is a variant. Query-side only — callers
     * keep displaying the raw text.
     *
     * Deterministic: one pass, no lookahead beyond one character, no dependence on
     * how much of the line is one era. (A previous page-ratio gate here made a
     * small scroll change the result; a lookup must not depend on that.)
     *
     * `を`/`ヲ` are left alone, and the 小書き row is not applied — see the class
     * doc for the measurement behind both.
     */
    fun modernise(query: String): String {
        if (query.isEmpty()) return query
        val t = table
        val sb = StringBuilder(query.length)
        for (i in query.indices) {
            val c = query[i]
            val mapped = t.canonical(c)
            if (mapped == c) {
                sb.append(c)
                continue
            }
            val prev = if (i > 0) query[i - 1] else null
            val next = if (i + 1 < query.length) query[i + 1] else null
            sb.append(if (applies(c, mapped, prev, next)) mapped else c)
        }
        return sb.toString()
    }

    /**
     * The context a row member needs, from the rows the table attests:
     *
     *  - ワ行 (`ゐゑヰヱ`): unconditional — the characters are not modern orthography.
     *  - だ行 (`ぢづヂヅ`): unless preceded by `つ`/`ち` (連濁: `つづく`, `ちぢむ`).
     *  - 促音 (`つ`/`ツ`): only before a kana the sokuon can precede (`った`, `っぱ`,
     *    `っさ`) — not before か行, see the class doc.
     *  - 拗音 (`やゆよ`/`ヤユヨ`): only after an i-column kana (`しよ`->`しょ`,
     *    `ちや`->`ちゃ`), which is the 旧仮名遣い yōon.
     *
     * A row member the table does not carry is left alone, so an unknown character
     * and a character whose variant is ordinary modern usage (`を`, `あ`) both fold
     * to themselves.
     */
    private fun applies(variant: Char, canonical: Char, prev: Char?, next: Char?): Boolean = when {
        variant in WAGYOU -> true
        variant in DAKUGYOU -> prev !in DAKUTEN_PREV
        variant in SOKUON -> next in SOKUON_TRIGGERS
        variant in YOON -> prev in YOON_BASE
        else -> false
    }

    private val WAGYOU = "ゐゑヰヱ".toCharSet()
    private val DAKUGYOU = "ぢづヂヅ".toCharSet()
    private val SOKUON = "つツ".toCharSet()
    private val YOON = "やゆよヤユヨ".toCharSet()
    private val DAKUTEN_PREV = "つちツチ".toCharSet()
    private val SOKUON_TRIGGERS = "たちつてとさしすせそぱぴぷぺぽタチツテトサシスセソパピプペポ".toCharSet()
    private val YOON_BASE = "きしちにひみりぎじびぴキシチニヒミリギジビピ".toCharSet()

    private fun String.toCharSet(): Set<Char> = toSet()

    /**
     * An immutable parsed table: one `variant<TAB>canonical` pair per line, `#`
     * comments and blank lines ignored, malformed lines skipped — the same shape as
     * `variants/kanji_variants.txt`, so the two read alike.
     */
    class Table internal constructor(pairs: List<Pair<Char, Char>>) {
        private val canonicalByVariant: Map<Char, Char> =
            LinkedHashMap<Char, Char>().apply {
                for ((variant, canonical) in pairs) putIfAbsent(variant, canonical)
            }

        /** Number of distinct variants in this table. */
        val size: Int get() = canonicalByVariant.size

        fun canonical(variant: Char): Char = canonicalByVariant[variant] ?: variant

        override fun toString(): String = "KanaOrthography.Table($size variants)"

        companion object {
            val EMPTY = Table(emptyList())

            fun parse(text: String): Table {
                val pairs = mutableListOf<Pair<Char, Char>>()
                for (raw in text.lineSequence()) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val parts = line.split('\t')
                    if (parts.size != 2) continue
                    val variant = parts[0].singleOrNull() ?: continue
                    val canonical = parts[1].singleOrNull() ?: continue
                    if (variant == canonical) continue
                    pairs.add(variant to canonical)
                }
                return Table(pairs)
            }
        }
    }
}
