package com.holopengin.instantjpdict.util

import android.content.Context

/**
 * Kanji variant table (#44): the vendored Unihan `kSemanticVariant`/`kZVariant`
 * pairs, loaded from `variants/kanji_variants.txt` (source, licence and SHA-256
 * are in the `PROVENANCE.txt` beside it; regenerate with
 * `tools/build_kanji_variants.py`).
 *
 * **Direction:** `variant -> canonical`, where the *canonical* side is the one
 * the shipped recogniser's dictionary carries (`PP-OCRv6_small_ncnn/vocab.json`)
 * and the *variant* is the one it does not. A fold exists to map a form the
 * pipeline cannot handle onto one it can, so the target must be the form the
 * dictionary already keys on. Both-sides-known and both-sides-unknown pairs are
 * dropped by the generator; only BMP CJK pairs are in the file, because this API
 * is `Char`-based (one UTF-16 code unit).
 *
 * The lookup fold in [JapaneseUtil.foldLookupVariants] carries only the subset of
 * these pairs whose variant side was measured to occur in real Japanese text. This
 * loader exposes the whole table, in both directions, for callers that want it —
 * e.g. offering the obsolete forms of a character as alternatives.
 *
 * The table is plain committed text, parsed once. [install] is the entry point;
 * until it is called every lookup is the identity function (a missing entry is not
 * an error — an unknown character folds to itself).
 */
object KanjiVariants {
    const val ASSET_PATH = "variants/kanji_variants.txt"

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

    /**
     * The canonical form of [ch], or [ch] itself when the table has no entry for
     * it. Never throws, including before [install].
     */
    fun canonical(ch: Char): Char = table.canonical(ch)

    /** The variant forms that fold to [ch], sorted; empty when there are none. */
    fun obsoleteFormsOf(ch: Char): List<Char> = table.obsoleteFormsOf(ch)

    /**
     * Every canonical candidate the table lists for [ch], sorted; empty when there
     * are none. Only useful where Unihan gives a variant several candidates — the
     * fold in [JapaneseUtil.MEASURED_VARIANT_FOLD] resolves those on the corpus.
     */
    fun canonicalsOf(ch: Char): List<Char> = table.canonicalsOf(ch)

    /**
     * An immutable parsed table: one `variant<TAB>canonical` pair per line, `#`
     * comments and blank lines ignored, malformed lines skipped. Every pair in the
     * file is kept — Unihan gives 59 of the variants more than one canonical
     * candidate, and [canonicalsOf] exposes them — while [canonical] answers with the
     * **first** line for that variant (the generator sorts by variant then canonical,
     * so first is the lowest codepoint: deterministic, but arbitrary, which is why the
     * measured fold in [JapaneseUtil.MEASURED_VARIANT_FOLD] makes its own choice).
     */
    class Table internal constructor(pairs: List<Pair<Char, Char>>) {
        private val canonicalByVariant: Map<Char, Char> =
            LinkedHashMap<Char, Char>().apply {
                for ((variant, canonical) in pairs) putIfAbsent(variant, canonical)
            }

        private val canonicalsByVariant: Map<Char, List<Char>> =
            pairs.groupBy({ it.first }, { it.second })
                .mapValues { (_, canonicals) -> canonicals.distinct().sorted() }

        private val variantsByCanonical: Map<Char, List<Char>> =
            pairs.groupBy({ it.second }, { it.first })
                .mapValues { (_, variants) -> variants.distinct().sorted() }

        /** Number of distinct variants in this table. */
        val size: Int get() = canonicalByVariant.size

        fun canonical(ch: Char): Char = canonicalByVariant[ch] ?: ch

        /**
         * Every canonical candidate Unihan lists for [ch], sorted; empty when the
         * variant is not in the table. 59 of the table's variants have more than one
         * — [canonical] then returns the first by codepoint, which is arbitrary, so a
         * caller that cares picks from this list instead.
         */
        fun canonicalsOf(ch: Char): List<Char> = canonicalsByVariant[ch] ?: emptyList()

        fun obsoleteFormsOf(ch: Char): List<Char> = variantsByCanonical[ch] ?: emptyList()

        override fun toString(): String = "KanjiVariants.Table($size variants)"

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
