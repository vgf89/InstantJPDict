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

    fun normalize(text: String): String {
        return normalizeCombiningCharacters(
            convertWidth(text)
        )
    }

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
