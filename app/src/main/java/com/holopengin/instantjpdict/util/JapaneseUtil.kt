package com.holopengin.instantjpdict.util

object JapaneseUtil {
    private val HALFWIDTH_KANA_MAPPING = mapOf(
        '｡' to "。", '｢' to "「", '｣' to "」", '､' to "、", '･' to "・",
        'ｦ' to "ヲ", 'ｧ' to "ァ", 'ｨ' to "ィ", 'ｩ' to "ゥ", 'ｪ' to "ェ", 'ｫ' to "ォ",
        'ｬ' to "ャ", 'ｭ' to "ュ", 'ｮ' to "ョ", 'ｯ' to "ッ", 'ｰ' to "ー",
        'ｱ' to "ア", 'ｲ' to "イ", 'ｳ' to "ウ", 'ｴ' to "エ", 'ｵ' to "オ",
        'ｶ' to "カ", 'ｷ' to "キ", 'ｸ' to "ク", 'ｹ' to "ケ", 'ｺ' to "コ",
        'ｻ' to "サ", 'ｼ' to "シ", 'ｽ' to "ス", 'ｾ' to "セ", 'ｿ' to "ソ",
        'ﾀ' to "タ", 'ﾁ' to "チ", 'ﾂ' to "ツ", 'ﾃ' to "テ", 'ﾄ' to "ト",
        'ﾅ' to "ナ", 'ﾆ' to "ニ", 'ﾇ' to "ヌ", 'ﾈ' to "ネ", 'ﾉ' to "ノ",
        'ﾊ' to "ハ", 'ﾋ' to "ヒ", 'ﾌ' to "フ", 'ﾍ' to "ヘ", 'ﾎ' to "ホ",
        'ﾏ' to "マ", 'ﾐ' to "ミ", 'ﾑ' to "ム", 'ﾒ' to "メ", 'ﾓ' to "モ",
        'ﾔ' to "ヤ", 'ﾕ' to "ユ", 'ﾖ' to "ヨ",
        'ﾗ' to "ラ", 'ﾘ' to "リ", 'ﾙ' to "ル", 'ﾚ' to "レ", 'ﾛ' to "ロ",
        'ﾜ' to "ワ", 'ﾝ' to "ン"
    )

    private val HALFWIDTH_VOICED_MAPPING = mapOf(
        'ｶ' to "ガ", 'ｷ' to "ギ", 'ｸ' to "グ", 'ｹ' to "ゲ", 'ｺ' to "ゴ",
        'ｻ' to "ザ", 'ｼ' to "ジ", 'ｽ' to "ズ", 'ｾ' to "ゼ", 'ｿ' to "ゾ",
        'ﾀ' to "ダ", 'ﾁ' to "ヂ", 'ﾂ' to "ヅ", 'ﾃ' to "デ", 'ﾄ' to "ド",
        'ﾊ' to "バ", 'ﾋ' to "ビ", 'ﾌ' to "ブ", 'ﾍ' to "ベ", 'ﾎ' to "ボ",
        'ｳ' to "ヴ"
    )

    private val HALFWIDTH_SEMI_VOICED_MAPPING = mapOf(
        'ﾊ' to "パ", 'ﾋ' to "ピ", 'ﾌ' to "プ", 'ﾍ' to "ペ", 'ﾎ' to "ポ"
    )

    /**
     * Fold an OCR line to the form dictionary lookup expects: width/combining
     * normalisation plus [#44] lookup-variant folds (iteration kana, obsolete
     * kana, Roman numerals, the Chinese-only forms the recogniser emits).
     *
     * Query-side only. [OcrOverlayStateController] calls this to build search
     * keys from the raw line prefix, so a fold may change the *length* of the
     * key without affecting what is displayed or which prefix the match
     * corresponds to — the caller keeps using the raw prefix length.
     */
    fun normalize(text: String): String {
        return normalizeCombiningCharacters(
            foldLookupVariants(convertWidth(text))
        )
    }

    /** Vertical-line punctuation (#56, #63): PP-OCR emits ASCII `?` where JP
     * text wants fullwidth `？`, and horizontal `…`/`‥` where vertical text
     * wants the vertical presentation forms `︙`/`︰`. ASCII `?` and `…` have
     * no `vert` alternate and mis-center in the vertical em box; `？`/`︙`/`︰`
     * center. Horizontal lines keep the originals. Lookup-safe: [normalize]
     * folds `？` back to `?` and `︙`/`︰` back to `…`/`‥`, so dictionary
     * search is unaffected. ASCII period runs (`...`) are deliberately left
     * untouched — an N:1 fold would break char-box/alternative alignment. */
    fun verticalPunctuation(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(verticalPunctuationChar(c))
        return sb.toString()
    }

    fun verticalPunctuationChar(c: Char): Char = when (c) {
        '?' -> '？'
        '…' -> '︙' // U+2026 → U+FE19 vertical ellipsis (#63)
        '‥' -> '︰' // U+2035 → U+FE30 vertical two-dot leader (#63)
        else -> c
    }

    /**
     * Split a KANJIDIC kana list ("きみ -ぎみ", "クン キン") into readings.
     * Entries are whitespace-separated; a leading ASCII hyphen marks an
     * okurigana-less stem ("-ぎみ" → "ぎみ") and is stripped. Shared by the
     * #69 classifier and the kanji-branch renderer so both agree.
     */
    fun splitKanaList(raw: String): List<String> =
        raw.split(" ", "　").map { it.trimStart('-') }.filter { it.isNotEmpty() }

    private fun convertWidth(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else null
            
            when {
                next == 'ﾞ' && HALFWIDTH_VOICED_MAPPING.containsKey(c) -> {
                    sb.append(HALFWIDTH_VOICED_MAPPING[c])
                    i += 2
                }
                next == 'ﾟ' && HALFWIDTH_SEMI_VOICED_MAPPING.containsKey(c) -> {
                    sb.append(HALFWIDTH_SEMI_VOICED_MAPPING[c])
                    i += 2
                }
                HALFWIDTH_KANA_MAPPING.containsKey(c) -> {
                    sb.append(HALFWIDTH_KANA_MAPPING[c])
                    i++
                }
                c in '\uFF01'..'\uFF5E' -> { // Full-width to standard
                    sb.append((c.code - 0xFEE0).toChar())
                    i++
                }
                // Vertical presentation forms (#63) fold back to the
                // horizontal forms the recognizer emits, so lookup of a
                // vertical line matches dictionary text. Outside the
                // fullwidth-ASCII range, so they need explicit cases.
                c == '︙' -> { // U+FE19 → U+2026
                    sb.append('…')
                    i++
                }
                c == '︰' -> { // U+FE30 → U+2035
                    sb.append('‥')
                    i++
                }
                c == '\u3000' -> { // Ideographic space
                    sb.append(' ')
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** Kana and their voiced counterparts, for the iteration marks `ゞ`/`ヾ` (#44). */
    private val HIRAGANA_VOICED = mapOf(
        'か' to 'が', 'き' to 'ぎ', 'く' to 'ぐ', 'け' to 'げ', 'こ' to 'ご',
        'さ' to 'ざ', 'し' to 'じ', 'す' to 'ず', 'せ' to 'ぜ', 'そ' to 'ぞ',
        'た' to 'だ', 'ち' to 'ぢ', 'つ' to 'づ', 'て' to 'で', 'と' to 'ど',
        'は' to 'ば', 'ひ' to 'び', 'ふ' to 'ぶ', 'へ' to 'べ', 'ほ' to 'ぼ',
        'う' to 'ゔ'
    )

    private val KATAKANA_VOICED = mapOf(
        'カ' to 'ガ', 'キ' to 'ギ', 'ク' to 'グ', 'ケ' to 'ゲ', 'コ' to 'ゴ',
        'サ' to 'ザ', 'シ' to 'ジ', 'ス' to 'ズ', 'セ' to 'ゼ', 'ソ' to 'ゾ',
        'タ' to 'ダ', 'チ' to 'ヂ', 'ツ' to 'ヅ', 'テ' to 'デ', 'ト' to 'ド',
        'ハ' to 'バ', 'ヒ' to 'ビ', 'フ' to 'ブ', 'ヘ' to 'ベ', 'ホ' to 'ボ',
        'ウ' to 'ヴ'
    )

    /**
     * Unihan variant forms that real Japanese text uses, folded onto the form the
     * shipped dictionary carries (#44). Same direction rule as [KanjiVariants] and
     * `tools/build_kanji_variants.py`: canonical = the side present in
     * `PP-OCRv6_small_ncnn/vocab.json`, variant = the side that is not.
     *
     * Unlike the curated entries below, **these characters have no class in the
     * shipped head at all**, so an OCR line can never contain one. They are not dead
     * entries, they are the other direction: lookup also runs over text that did not
     * come from the model (a character typed into a manual override, dictionary-side
     * text), and there the obsolete form is exactly what needs normalising. On model
     * *output* a variant the head cannot emit still fails as a deletion and needs the
     * proposal layer, not a fold.
     *
     * Subset rule — measured, not guessed: a pair ships only if its variant side
     * actually occurs in real text. 112 of the table's 593 pairs qualify, measured
     * over the whole Aozora Bunko corpus as streamed from the HF clean mirror
     * (16,950 works, 230,196,565 characters): 囘 1,493; 欝 1,431; 壜 1,322; 劒 387;
     * 慙 357; 厶 305; 噐 74; 齅 30; the rest tail off to a single occurrence.
     *
     * Where Unihan offers several canonical candidates for one variant (15 of the
     * 112) the fold takes the form that dominates that same corpus rather than an
     * arbitrary first: 葢→蓋 (蓋 6,811 vs 盖 92), 悋→吝 (760 vs 恡 0), 冫→氷 (10,435
     * vs 冰 88), 秇→藝 (7,036), 穪→稱 (2,032), 﨑→崎 (15,233 vs 埼 431). Every pair
     * here is also present in `variants/kanji_variants.txt` with the same direction —
     * JapaneseUtilVariantFoldTest checks that — and no canonical is itself a key, so
     * the fold stays idempotent. All pairs are single-character, so unlike the Roman
     * numerals below they never change query length.
     */
    internal val MEASURED_VARIANT_FOLD: Map<Char, String> = mapOf(
        '㕞' to "刷", '㘅' to "啣", '㝵' to "碍", '䖟' to "蝱",
        '䙝' to "褻", '䬒' to "颼", '䯻' to "髻", '䰗' to "鬮",
        '亻' to "人", '冩' to "寫", '冫' to "氷", '凴' to "憑",
        '凾' to "函", '刋' to "刊", '劒' to "劍", '勹' to "包",
        '匳' to "奩", '匵' to "櫝", '卭' to "卬", '厶' to "某",
        '噐' to "器", '囘' to "回", '堭' to "隍", '壜' to "罈",
        '娬' to "嫵", '崪' to "崒", '巤' to "鬣", '帋' to "紙",
        '帒' to "袋", '悋' to "吝", '慙' to "慚", '懜' to "懵",
        '捬' to "撫", '攅' to "攢", '攵' to "攴", '朙' to "明",
        '槖' to "橐", '樷' to "叢", '欝' to "鬱", '氵' to "水",
        '涶' to "唾", '濵' to "濱", '犭' to "犬", '甎' to "磚",
        '甤' to "蕤", '畄' to "留", '畆' to "畝", '瘂' to "啞",
        '皃' to "貌", '皡' to "皞", '眎' to "視", '瞹' to "曖",
        '碯' to "瑙", '礟' to "礮", '秇' to "藝", '秌' to "秋",
        '穪' to "稱", '竆' to "窮", '竒' to "奇", '糓' to "穀",
        '纎' to "纖", '缻' to "缶", '羮' to "羹", '耼' to "聃",
        '膓' to "腸", '艪' to "櫓", '苢' to "苡", '葢' to "蓋",
        '蘯' to "蕩", '蚦' to "蚺", '蜹' to "蚋", '襍' to "雜",
        '覉' to "羇", '覊' to "羈", '覔' to "覓", '覰' to "覷",
        '觧' to "解", '誐' to "哦", '賍' to "贓", '賷' to "齎",
        '趦' to "趑", '躱' to "躲", '軆' to "体", '輙' to "輒",
        '辶' to "辵", '迯' to "逃", '遉' to "偵", '鍫' to "鍬",
        '鏁' to "鎖", '閙' to "鬧", '隂' to "陰", '隖' to "塢",
        '霡' to "霢", '韈' to "襪", '頣' to "頤", '顖' to "囟",
        '飃' to "飄", '飇' to "飆", '駞' to "駝", '髗' to "顱",
        '髠' to "髡", '髩' to "鬢", '鬂' to "鬢", '鬭' to "鬥",
        '鮧' to "鯷", '鵶' to "鴉", '鶽' to "隼", '鸎' to "鶯",
        '麄' to "粗", '麕' to "麇", '齅' to "嗅", '﨑' to "崎",
    )

    /**
     * Characters the recogniser *can* emit that dictionaries do not use, folded
     * to the form lookup expects (#44, layer 1). Measured over 225M characters of
     * Aozora plus the calibration benches.
     *
     * Absent on purpose from *this curated half*: characters the quantised head has
     * **no class for** — `ゐ`, `ヱ`, `─`, `｜`, `〳`, `〴`, `〻`, `〃`, fullwidth ASCII,
     * the Ainu small katakana. They can never appear in the model's output, so for
     * *model output* an entry would do nothing; those lines fail as *deletions* and
     * no fold can restore them. [MEASURED_VARIANT_FOLD] below is the deliberate
     * exception, because it serves text that did not come from the model.
     * `々` is absent from both: dictionary headwords contain it (`日々`), so expanding
     * it would lose matches rather than gain them.
     */
    private val LOOKUP_VARIANT_MAP: Map<Char, String> = mapOf(
        // Roman numerals (NFKC behaviour; the benches show `Ⅶ` where text has `VII`)
        'Ⅰ' to "I", 'Ⅱ' to "II", 'Ⅲ' to "III", 'Ⅳ' to "IV", 'Ⅴ' to "V",
        'Ⅵ' to "VI", 'Ⅶ' to "VII", 'Ⅷ' to "VIII", 'Ⅸ' to "IX", 'Ⅹ' to "X",
        'Ⅺ' to "XI", 'Ⅻ' to "XII",
        'ⅰ' to "i", 'ⅱ' to "ii", 'ⅲ' to "iii", 'ⅳ' to "iv", 'ⅴ' to "v",
        'ⅵ' to "vi", 'ⅶ' to "vii", 'ⅷ' to "viii", 'ⅸ' to "ix", 'ⅹ' to "x",
        // Compatibility form
        '℃' to "°C",
        // Obsolete kana the head *can* emit (8,430 and 1,585 occurrences in Aozora)
        'ゑ' to "え", 'ヰ' to "イ",
        // Chinese-only forms the head emits in place of the Japanese one (benches:
        // `調査` → `調査` with 查 for 査). Only *emittable* pairs belong here: 调
        // looks like it belongs (調) but has no Unihan Japanese reading, so
        // tools/prune_ctc_head.py cut it and the head cannot produce it — an entry
        // for it is dead code. Check membership against rec_remap.txt, not
        // vocab.json, which still lists the 5,517 classes we pruned.
        '况' to "況", '查' to "査",
        // The Unihan-derived half of the table — see [MEASURED_VARIANT_FOLD] for the
        // direction rule and the corpus measurement that selects the pairs.
    ) + MEASURED_VARIANT_FOLD

    /**
     * Fold the variant characters of [LOOKUP_VARIANT_MAP] and expand the iteration
     * marks `ゝ`/`ゞ`/`ヽ`/`ヾ`, which repeat the preceding kana (`こゝろ` → `こころ`,
     * `たゞ` → `ただ`) — 139,270 occurrences in Aozora, all emittable, and a query
     * containing one of them matches nothing in a modern dictionary.
     *
     * `ゞ`/`ヾ` voice the repeat when the preceding kana has a voiced form and fall
     * back to a plain repeat otherwise (`まゞ` → `まま`, 537 real occurrences), which
     * is also what an already-voiced kana needs (`がゞ` → `がが`). A mark whose
     * preceding character is not kana of the matching script (line-initial, after a
     * kanji or punctuation) is left as-is rather than folded into a guess: 1,067 of
     * 139,270 real occurrences, so 99.23% fold, measured across the Aozora corpus.
     */
    fun foldLookupVariants(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            val last = sb.lastOrNull()
            val isHira = last != null && last in '\u3041'..'\u3096'
            val isKata = last != null && last in '\u30A1'..'\u30F6'
            when (c) {
                'ゝ' -> sb.append(if (isHira) last!! else c)
                'ゞ' -> sb.append(if (isHira) HIRAGANA_VOICED[last!!] ?: last!! else c)
                'ヽ' -> sb.append(if (isKata) last!! else c)
                'ヾ' -> sb.append(if (isKata) KATAKANA_VOICED[last!!] ?: last!! else c)
                else -> {
                    val mapped = LOOKUP_VARIANT_MAP[c]
                    if (mapped != null) sb.append(mapped) else sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    fun normalizeCombiningCharacters(text: String): String {
        return text.replace("\u304B\u3099", "が")
            .replace("\u304D\u3099", "ぎ")
            .replace("\u304F\u3099", "ぐ")
            .replace("\u3051\u3099", "げ")
            .replace("\u3053\u3099", "ご")
            .replace("\u3055\u3099", "ざ")
            .replace("\u3057\u3099", "じ")
            .replace("\u3059\u3099", "ず")
            .replace("\u305B\u3099", "ぜ")
            .replace("\u305D\u3099", "ぞ")
            .replace("\u305F\u3099", "だ")
            .replace("\u3061\u3099", "ぢ")
            .replace("\u3064\u3099", "づ")
            .replace("\u3066\u3099", "で")
            .replace("\u3068\u3099", "ど")
            .replace("\u306F\u3099", "ば")
            .replace("\u3072\u3099", "び")
            .replace("\u3075\u3099", "ぶ")
            .replace("\u3078\u3099", "べ")
            .replace("\u307B\u3099", "ぼ")
            .replace("\u306F\u309A", "ぱ")
            .replace("\u3072\u309A", "ぴ")
            .replace("\u3075\u309A", "ぷ")
            .replace("\u3078\u309A", "ぺ")
            .replace("\u307B\u309A", "ぽ")
    }

    fun katakanaToHiragana(text: String): String {
        val sb = StringBuilder()
        for (i in text.indices) {
            val c = text[i]
            if (c in '\u30A1'..'\u30F6') {
                sb.append((c.code - 0x60).toChar())
            } else if (c == 'ー' && i > 0) {
                sb.append(getProlongedHiragana(text[i - 1]))
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun getProlongedHiragana(prev: Char): Char {
        return when (prev) {
            'あ', 'か', 'さ', 'た', 'な', 'は', 'ま', 'や', 'ら', 'わ', 'ァ', 'カ', 'サ', 'タ', 'ナ', 'ハ', 'マ', 'ヤ', 'ラ', 'ワ' -> 'あ'
            'い', 'き', 'し', 'ち', 'に', 'ひ', 'み', 'り', 'ィ', 'キ', 'シ', 'チ', 'ニ', 'ヒ', 'ミ', 'リ' -> 'い'
            'う', 'く', 'す', 'つ', 'ぬ', 'ふ', 'む', 'ゆ', 'る', 'ゥ', 'ク', 'ス', 'ツ', 'ヌ', 'フ', 'ム', 'ユ', 'ル', 'ヴ' -> 'う'
            'え', 'け', 'せ', 'て', 'ね', 'へ', 'め', 'れ', 'ェ', 'ケ', 'セ', 'テ', 'ネ', 'ヘ', 'メ', 'レ' -> 'え'
            'お', 'こ', 'そ', 'と', 'の', 'ほ', 'も', 'よ', 'ろ', 'ォ', 'コ', 'ソ', 'ト', 'ノ', 'ホ', 'モ', 'ヨ', 'ロ' -> 'う'
            else -> 'う'
        }
    }
    
    fun collapseEmphatic(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder().append(text[0])
        for (i in 1 until text.length) {
            val c = text[i]
            val last = sb[sb.length - 1]
            if ((c == 'っ' || c == 'ッ' || c == 'ー' || c == '～') && c == last) continue
            sb.append(c)
        }
        return sb.toString()
    }
}
