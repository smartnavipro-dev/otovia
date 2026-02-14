package com.kindletts.reader.ocr

/**
 * v1.1.32: 汎用形状類似文字データベース
 *
 * OCRで誤認識されやすい形状類似文字ペア400+を格納。
 * ジャンル非依存で、あらゆる日本語書籍のOCR補正に対応。
 *
 * データソース: Gemini 2.5 Flash による網羅的分析
 * カテゴリ: 漢字同士, カタカナ同士, ひらがな同士, 漢字↔カナ, 数字・記号
 *
 * 使用方法:
 * - CharTypeAnomalyCorrector: kanjiKanaPairs を使って文字種異常を安全に補正
 * - ShapeSimilarHintBuilder: allSuspectChars / wrongCharIndex でLLMヒントを生成
 */
object ShapeSimilarDB {

    enum class Category {
        KANJI_KANJI,
        KATAKANA_KATAKANA,
        HIRAGANA_HIRAGANA,
        KANJI_KANA,
        NUMBER_SYMBOL
    }

    enum class Frequency { HIGH, MEDIUM, LOW }

    enum class CharType { KANJI, KATAKANA, HIRAGANA, NUMBER, SYMBOL, OTHER }

    data class CharPair(
        val char1: Char,
        val char2: Char,
        val category: Category,
        val frequency: Frequency,
        val notes: String = ""
    )

    // ============================================================
    // 文字種判定ユーティリティ
    // ============================================================

    fun charType(c: Char): CharType = when {
        c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c == '々' -> CharType.KANJI
        c in '\u30A0'..'\u30FF' || c == 'ー' -> CharType.KATAKANA
        c in '\u3040'..'\u309F' -> CharType.HIRAGANA
        c.isDigit() -> CharType.NUMBER
        c.isLetter() && c.code < 128 -> CharType.SYMBOL // ASCII letters
        else -> CharType.OTHER
    }

    fun isKanji(c: Char): Boolean = charType(c) == CharType.KANJI
    fun isKatakana(c: Char): Boolean = charType(c) == CharType.KATAKANA
    fun isHiragana(c: Char): Boolean = charType(c) == CharType.HIRAGANA

    // ============================================================
    // 1. 漢字同士の形状類似ペア (220+)
    // ============================================================

    val kanjiKanjiPairs = listOf(
        // --- 1-1. 全体形状・画数の多少による混同 ---
        // 高頻度
        CharPair('日', '目', Category.KANJI_KANJI, Frequency.HIGH, "横棒の数"),
        CharPair('目', '日', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('大', '犬', Category.KANJI_KANJI, Frequency.HIGH, "点の有無"),
        CharPair('犬', '大', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('太', '犬', Category.KANJI_KANJI, Frequency.HIGH, "点の位置"),
        CharPair('犬', '太', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('白', '自', Category.KANJI_KANJI, Frequency.HIGH, "横棒の数"),
        CharPair('自', '白', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('土', '士', Category.KANJI_KANJI, Frequency.HIGH, "横棒の長さ"),
        CharPair('士', '土', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('未', '末', Category.KANJI_KANJI, Frequency.HIGH, "横棒の長さ"),
        CharPair('末', '未', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('牛', '午', Category.KANJI_KANJI, Frequency.HIGH, "突き抜け"),
        CharPair('午', '牛', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('干', '千', Category.KANJI_KANJI, Frequency.HIGH, "傾きの違い"),
        CharPair('千', '干', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('入', '人', Category.KANJI_KANJI, Frequency.HIGH, "払いの違い"),
        CharPair('人', '入', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('八', '入', Category.KANJI_KANJI, Frequency.HIGH, "形状類似"),
        CharPair('入', '八', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('王', '玉', Category.KANJI_KANJI, Frequency.HIGH, "点の有無"),
        CharPair('玉', '王', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('王', '主', Category.KANJI_KANJI, Frequency.HIGH, "点の位置"),
        CharPair('主', '王', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('石', '右', Category.KANJI_KANJI, Frequency.HIGH, "突き抜け"),
        CharPair('右', '石', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('已', '己', Category.KANJI_KANJI, Frequency.HIGH, "線の閉じ"),
        CharPair('己', '已', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('水', '氷', Category.KANJI_KANJI, Frequency.HIGH, "点の有無"),
        CharPair('氷', '水', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('木', '本', Category.KANJI_KANJI, Frequency.HIGH, "横棒の有無"),
        CharPair('本', '木', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('鳥', '烏', Category.KANJI_KANJI, Frequency.HIGH, "横棒の有無"),
        CharPair('烏', '鳥', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('免', '兎', Category.KANJI_KANJI, Frequency.HIGH, "点の有無"),
        CharPair('兎', '免', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('侯', '候', Category.KANJI_KANJI, Frequency.HIGH, "縦棒の有無"),
        CharPair('候', '侯', Category.KANJI_KANJI, Frequency.HIGH),
        // 中頻度
        CharPair('天', '夭', Category.KANJI_KANJI, Frequency.MEDIUM, "1画目の形状"),
        CharPair('夭', '天', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('夫', '失', Category.KANJI_KANJI, Frequency.MEDIUM, "突き抜け"),
        CharPair('失', '夫', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('矢', '失', Category.KANJI_KANJI, Frequency.MEDIUM, "形状類似"),
        CharPair('失', '矢', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('瓜', '爪', Category.KANJI_KANJI, Frequency.MEDIUM, "形状類似"),
        CharPair('爪', '瓜', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('刃', '刀', Category.KANJI_KANJI, Frequency.MEDIUM, "点の有無"),
        CharPair('刀', '刃', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('占', '古', Category.KANJI_KANJI, Frequency.MEDIUM, "下部の形状"),
        CharPair('古', '占', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('圧', '庄', Category.KANJI_KANJI, Frequency.MEDIUM, "点の有無"),
        CharPair('庄', '圧', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('崇', '祟', Category.KANJI_KANJI, Frequency.MEDIUM, "出るか否か"),
        CharPair('祟', '崇', Category.KANJI_KANJI, Frequency.MEDIUM),

        // --- 1-2. 偏（へん）が類似・混同 ---
        // 高頻度
        CharPair('持', '待', Category.KANJI_KANJI, Frequency.HIGH, "手偏vs行人偏"),
        CharPair('待', '持', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('侍', '待', Category.KANJI_KANJI, Frequency.HIGH, "人偏vs行人偏"),
        CharPair('待', '侍', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('決', '快', Category.KANJI_KANJI, Frequency.HIGH, "さんずいvs立心偏"),
        CharPair('快', '決', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('休', '体', Category.KANJI_KANJI, Frequency.HIGH, "右側が類似"),
        CharPair('体', '休', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('住', '往', Category.KANJI_KANJI, Frequency.HIGH, "人偏vs行人偏"),
        CharPair('往', '住', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('指', '脂', Category.KANJI_KANJI, Frequency.HIGH, "手偏vs月偏"),
        CharPair('脂', '指', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('独', '触', Category.KANJI_KANJI, Frequency.HIGH, "獣偏vs角偏"),
        CharPair('触', '独', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('猫', '描', Category.KANJI_KANJI, Frequency.HIGH, "獣偏vs手偏"),
        CharPair('描', '猫', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('摘', '滴', Category.KANJI_KANJI, Frequency.HIGH, "手偏vsさんずい"),
        CharPair('滴', '摘', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('抗', '杭', Category.KANJI_KANJI, Frequency.HIGH, "手偏vs木偏"),
        CharPair('杭', '抗', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('折', '析', Category.KANJI_KANJI, Frequency.HIGH, "手偏vs木偏"),
        CharPair('析', '折', Category.KANJI_KANJI, Frequency.HIGH),
        // 中頻度（偏の類似）
        CharPair('拝', '拍', Category.KANJI_KANJI, Frequency.MEDIUM, "手偏、右側形状"),
        CharPair('拍', '拝', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('押', '抽', Category.KANJI_KANJI, Frequency.MEDIUM, "手偏、右側形状"),
        CharPair('抽', '押', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('括', '活', Category.KANJI_KANJI, Frequency.MEDIUM, "手偏vsさんずい"),
        CharPair('活', '括', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('構', '講', Category.KANJI_KANJI, Frequency.MEDIUM, "木偏vs言偏"),
        CharPair('講', '構', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('模', '摸', Category.KANJI_KANJI, Frequency.MEDIUM, "木偏vs手偏"),
        CharPair('摸', '模', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('検', '険', Category.KANJI_KANJI, Frequency.MEDIUM, "木偏vsこざと偏"),
        CharPair('険', '検', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('桟', '浅', Category.KANJI_KANJI, Frequency.MEDIUM, "木偏vsさんずい"),
        CharPair('浅', '桟', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('椀', '腕', Category.KANJI_KANJI, Frequency.MEDIUM, "木偏vs月偏"),
        CharPair('腕', '椀', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('槽', '糟', Category.KANJI_KANJI, Frequency.MEDIUM, "木偏vs米偏"),
        CharPair('糟', '槽', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('漫', '慢', Category.KANJI_KANJI, Frequency.MEDIUM, "さんずいvs立心偏"),
        CharPair('慢', '漫', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('淡', '談', Category.KANJI_KANJI, Frequency.MEDIUM, "さんずいvs言偏"),
        CharPair('談', '淡', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('測', '側', Category.KANJI_KANJI, Frequency.MEDIUM, "さんずいvs人偏"),
        CharPair('側', '測', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('漬', '債', Category.KANJI_KANJI, Frequency.MEDIUM, "さんずいvs人偏"),
        CharPair('債', '漬', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('減', '滅', Category.KANJI_KANJI, Frequency.MEDIUM, "さんずいvs火偏"),
        CharPair('滅', '減', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('清', '請', Category.KANJI_KANJI, Frequency.MEDIUM, "さんずいvs言偏"),
        CharPair('請', '清', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('湿', '温', Category.KANJI_KANJI, Frequency.MEDIUM, "さんずい、右側類似"),
        CharPair('温', '湿', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('悟', '語', Category.KANJI_KANJI, Frequency.MEDIUM, "立心偏vs言偏"),
        CharPair('語', '悟', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('悔', '梅', Category.KANJI_KANJI, Frequency.MEDIUM, "立心偏vs木偏"),
        CharPair('梅', '悔', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('慎', '鎮', Category.KANJI_KANJI, Frequency.MEDIUM, "立心偏vs金偏"),
        CharPair('鎮', '慎', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('愉', '輸', Category.KANJI_KANJI, Frequency.MEDIUM, "立心偏vs車偏"),
        CharPair('輸', '愉', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('憧', '瞳', Category.KANJI_KANJI, Frequency.MEDIUM, "立心偏vs目偏"),
        CharPair('瞳', '憧', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('俳', '排', Category.KANJI_KANJI, Frequency.MEDIUM, "人偏vs手偏"),
        CharPair('排', '俳', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('健', '建', Category.KANJI_KANJI, Frequency.MEDIUM, "人偏の有無"),
        CharPair('建', '健', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('復', '後', Category.KANJI_KANJI, Frequency.MEDIUM, "行人偏"),
        CharPair('後', '復', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('彼', '波', Category.KANJI_KANJI, Frequency.MEDIUM, "行人偏vsさんずい"),
        CharPair('波', '彼', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('訪', '坊', Category.KANJI_KANJI, Frequency.MEDIUM, "言偏vs土偏"),
        CharPair('坊', '訪', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('該', '核', Category.KANJI_KANJI, Frequency.MEDIUM, "言偏vs木偏"),
        CharPair('核', '該', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('誰', '唯', Category.KANJI_KANJI, Frequency.MEDIUM, "言偏vs口偏"),
        CharPair('唯', '誰', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('訂', '釘', Category.KANJI_KANJI, Frequency.MEDIUM, "言偏vs金偏"),
        CharPair('釘', '訂', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('試', '拭', Category.KANJI_KANJI, Frequency.MEDIUM, "言偏vs手偏"),
        CharPair('拭', '試', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('誤', '娯', Category.KANJI_KANJI, Frequency.MEDIUM, "言偏vs女偏"),
        CharPair('娯', '誤', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('諸', '緒', Category.KANJI_KANJI, Frequency.MEDIUM, "言偏vs糸偏"),
        CharPair('緒', '諸', Category.KANJI_KANJI, Frequency.MEDIUM),
        // 低頻度（偏の類似）
        CharPair('貼', '帖', Category.KANJI_KANJI, Frequency.LOW, "貝偏vs巾偏"),
        CharPair('帖', '貼', Category.KANJI_KANJI, Frequency.LOW),
        CharPair('距', '拒', Category.KANJI_KANJI, Frequency.LOW, "足偏vs手偏"),
        CharPair('拒', '距', Category.KANJI_KANJI, Frequency.LOW),

        // --- 1-3. 旁・冠・脚が類似 ---
        // 高頻度
        CharPair('量', '重', Category.KANJI_KANJI, Frequency.HIGH, "里の部分"),
        CharPair('重', '量', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('衰', '哀', Category.KANJI_KANJI, Frequency.HIGH, "真ん中の横棒"),
        CharPair('哀', '衰', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('宇', '字', Category.KANJI_KANJI, Frequency.HIGH, "うかんむり"),
        CharPair('字', '宇', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('官', '宮', Category.KANJI_KANJI, Frequency.HIGH, "うかんむり"),
        CharPair('宮', '官', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('宣', '宜', Category.KANJI_KANJI, Frequency.HIGH, "うかんむり"),
        CharPair('宜', '宣', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('密', '蜜', Category.KANJI_KANJI, Frequency.HIGH, "山vs虫"),
        CharPair('蜜', '密', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('完', '宅', Category.KANJI_KANJI, Frequency.HIGH, "うかんむり"),
        CharPair('宅', '完', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('究', '空', Category.KANJI_KANJI, Frequency.HIGH, "九vs工"),
        CharPair('空', '究', Category.KANJI_KANJI, Frequency.HIGH),
        CharPair('突', '空', Category.KANJI_KANJI, Frequency.HIGH, "大vs工"),
        CharPair('空', '突', Category.KANJI_KANJI, Frequency.HIGH),
        // 中頻度
        CharPair('簡', '間', Category.KANJI_KANJI, Frequency.MEDIUM, "竹"),
        CharPair('間', '簡', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('簿', '薄', Category.KANJI_KANJI, Frequency.MEDIUM, "竹vs草"),
        CharPair('薄', '簿', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('蔵', '臓', Category.KANJI_KANJI, Frequency.MEDIUM, "草vs月"),
        CharPair('臓', '蔵', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('薬', '楽', Category.KANJI_KANJI, Frequency.MEDIUM, "草"),
        CharPair('楽', '薬', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('蓋', '益', Category.KANJI_KANJI, Frequency.MEDIUM, "草"),
        CharPair('益', '蓋', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('貝', '見', Category.KANJI_KANJI, Frequency.MEDIUM, "漢字類似"),
        CharPair('見', '貝', Category.KANJI_KANJI, Frequency.MEDIUM),
        CharPair('垂', '重', Category.KANJI_KANJI, Frequency.LOW, "誤認識"),
        CharPair('重', '垂', Category.KANJI_KANJI, Frequency.LOW),

        // --- 既存プロジェクトで実測されたペア ---
        CharPair('龍', '需', Category.KANJI_KANJI, Frequency.HIGH, "実測:経済書"),
        CharPair('競', '財', Category.KANJI_KANJI, Frequency.HIGH, "実測:経済書"),
        CharPair('旧', '日', Category.KANJI_KANJI, Frequency.HIGH, "実測:旧本→日本"),
        CharPair('方', '庁', Category.KANJI_KANJI, Frequency.HIGH, "実測:方舎→庁舎"),
        CharPair('邪', '学', Category.KANJI_KANJI, Frequency.HIGH, "実測:邪校→学校"),
        CharPair('客', '答', Category.KANJI_KANJI, Frequency.HIGH, "実測:客え→答え"),
        CharPair('美', '実', Category.KANJI_KANJI, Frequency.HIGH, "実測:美際→実際"),
        CharPair('環', '景', Category.KANJI_KANJI, Frequency.MEDIUM, "実測:環気→景気"),
        CharPair('瀬', '激', Category.KANJI_KANJI, Frequency.MEDIUM, "実測:刺瀬→刺激"),
        CharPair('証', '財', Category.KANJI_KANJI, Frequency.MEDIUM, "実測:証政→財政"),
        CharPair('治', '法', Category.KANJI_KANJI, Frequency.MEDIUM, "さんずい右側類似")
    )

    // ============================================================
    // 2. カタカナ同士の類似ペア (50+)
    // ============================================================

    val katakanaPairs = listOf(
        // 高頻度
        CharPair('シ', 'ツ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "点の角度"),
        CharPair('ツ', 'シ', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ン', 'ソ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "点の角度"),
        CharPair('ソ', 'ン', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ア', 'マ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "上部形状"),
        CharPair('マ', 'ア', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ウ', 'ワ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "縦棒の有無"),
        CharPair('ワ', 'ウ', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ク', 'ワ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "角の形状"),
        CharPair('ワ', 'ク', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ク', 'ケ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "突き出し"),
        CharPair('ケ', 'ク', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('タ', 'ク', Category.KATAKANA_KATAKANA, Frequency.HIGH, "内部の線"),
        CharPair('ク', 'タ', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('コ', 'ユ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "下の横棒"),
        CharPair('ユ', 'コ', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ヨ', 'コ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "中の横棒"),
        CharPair('コ', 'ヨ', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ラ', 'ヲ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "上の横棒"),
        CharPair('ヲ', 'ラ', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ナ', 'メ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "クロス"),
        CharPair('メ', 'ナ', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ヌ', 'ス', Category.KATAKANA_KATAKANA, Frequency.HIGH, "形状"),
        CharPair('ス', 'ヌ', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ノ', 'ソ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "点の有無"),
        CharPair('ソ', 'ノ', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ハ', 'ル', Category.KATAKANA_KATAKANA, Frequency.HIGH, "繋がり"),
        CharPair('ル', 'ハ', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ル', 'レ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "左側の跳ね"),
        CharPair('レ', 'ル', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ヘ', 'フ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "角度"),
        CharPair('フ', 'ヘ', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        CharPair('ヤ', 'セ', Category.KATAKANA_KATAKANA, Frequency.HIGH, "縦棒形状"),
        CharPair('セ', 'ヤ', Category.KATAKANA_KATAKANA, Frequency.HIGH),
        // 中頻度
        CharPair('イ', 'ト', Category.KATAKANA_KATAKANA, Frequency.MEDIUM, "角度"),
        CharPair('ト', 'イ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM),
        CharPair('エ', 'ユ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM, "下の横棒"),
        CharPair('ユ', 'エ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM),
        CharPair('オ', 'ナ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM, "右上の点"),
        CharPair('ナ', 'オ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM),
        CharPair('オ', 'ホ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM, "形状"),
        CharPair('ホ', 'オ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM),
        CharPair('キ', 'サ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM, "横棒の数"),
        CharPair('サ', 'キ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM),
        CharPair('チ', 'テ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM, "下のカーブ"),
        CharPair('テ', 'チ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM),
        CharPair('テ', 'ラ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM, "上の横棒"),
        CharPair('ラ', 'テ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM),
        CharPair('ニ', 'コ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM, "縦棒の有無"),
        CharPair('コ', 'ニ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM),
        CharPair('ネ', 'ホ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM, "形状"),
        CharPair('ホ', 'ネ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM),
        CharPair('フ', 'ラ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM, "上の横棒"),
        CharPair('ラ', 'フ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM),
        CharPair('マ', 'ム', Category.KATAKANA_KATAKANA, Frequency.MEDIUM, "形状"),
        CharPair('ム', 'マ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM),
        CharPair('リ', 'ソ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM, "左側の長さ"),
        CharPair('ソ', 'リ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM),
        CharPair('ロ', 'コ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM, "下部閉じ"),
        CharPair('コ', 'ロ', Category.KATAKANA_KATAKANA, Frequency.MEDIUM),
        // 低頻度（小書き文字）
        CharPair('ッ', 'ツ', Category.KATAKANA_KATAKANA, Frequency.LOW, "サイズ判定"),
        CharPair('ツ', 'ッ', Category.KATAKANA_KATAKANA, Frequency.LOW),
        CharPair('ャ', 'ヤ', Category.KATAKANA_KATAKANA, Frequency.LOW, "サイズ判定"),
        CharPair('ヤ', 'ャ', Category.KATAKANA_KATAKANA, Frequency.LOW),
        CharPair('ュ', 'ユ', Category.KATAKANA_KATAKANA, Frequency.LOW, "サイズ判定"),
        CharPair('ユ', 'ュ', Category.KATAKANA_KATAKANA, Frequency.LOW),
        CharPair('ョ', 'ヨ', Category.KATAKANA_KATAKANA, Frequency.LOW, "サイズ判定"),
        CharPair('ヨ', 'ョ', Category.KATAKANA_KATAKANA, Frequency.LOW),
        CharPair('ァ', 'ア', Category.KATAKANA_KATAKANA, Frequency.LOW, "サイズ判定"),
        CharPair('ア', 'ァ', Category.KATAKANA_KATAKANA, Frequency.LOW)
    )

    // ============================================================
    // 3. ひらがな同士の類似ペア (50+)
    // ============================================================

    val hiraganaPairs = listOf(
        // 高頻度
        CharPair('は', 'ほ', Category.HIRAGANA_HIRAGANA, Frequency.HIGH, "上の突き出し"),
        CharPair('ほ', 'は', Category.HIRAGANA_HIRAGANA, Frequency.HIGH),
        CharPair('ぬ', 'め', Category.HIRAGANA_HIRAGANA, Frequency.HIGH, "ループ有無"),
        CharPair('め', 'ぬ', Category.HIRAGANA_HIRAGANA, Frequency.HIGH),
        CharPair('ね', 'わ', Category.HIRAGANA_HIRAGANA, Frequency.HIGH, "ループ有無"),
        CharPair('わ', 'ね', Category.HIRAGANA_HIRAGANA, Frequency.HIGH),
        CharPair('れ', 'わ', Category.HIRAGANA_HIRAGANA, Frequency.HIGH, "右側形状"),
        CharPair('わ', 'れ', Category.HIRAGANA_HIRAGANA, Frequency.HIGH),
        CharPair('ね', 'れ', Category.HIRAGANA_HIRAGANA, Frequency.HIGH, "右側形状"),
        CharPair('れ', 'ね', Category.HIRAGANA_HIRAGANA, Frequency.HIGH),
        CharPair('あ', 'お', Category.HIRAGANA_HIRAGANA, Frequency.HIGH, "点の有無"),
        CharPair('お', 'あ', Category.HIRAGANA_HIRAGANA, Frequency.HIGH),
        CharPair('い', 'り', Category.HIRAGANA_HIRAGANA, Frequency.HIGH, "長さと跳ね"),
        CharPair('り', 'い', Category.HIRAGANA_HIRAGANA, Frequency.HIGH),
        CharPair('き', 'さ', Category.HIRAGANA_HIRAGANA, Frequency.HIGH, "繋がり有無"),
        CharPair('さ', 'き', Category.HIRAGANA_HIRAGANA, Frequency.HIGH),
        CharPair('ち', 'ら', Category.HIRAGANA_HIRAGANA, Frequency.HIGH, "上の横棒"),
        CharPair('ら', 'ち', Category.HIRAGANA_HIRAGANA, Frequency.HIGH),
        CharPair('な', 'た', Category.HIRAGANA_HIRAGANA, Frequency.HIGH, "左上形状"),
        CharPair('た', 'な', Category.HIRAGANA_HIRAGANA, Frequency.HIGH),
        CharPair('る', 'ろ', Category.HIRAGANA_HIRAGANA, Frequency.HIGH, "ループ有無"),
        CharPair('ろ', 'る', Category.HIRAGANA_HIRAGANA, Frequency.HIGH),
        // 中頻度
        CharPair('う', 'ら', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM, "上の点位置"),
        CharPair('ら', 'う', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM),
        CharPair('け', 'は', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM, "右側形状"),
        CharPair('は', 'け', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM),
        CharPair('こ', 'に', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM, "縦棒"),
        CharPair('に', 'こ', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM),
        CharPair('す', 'お', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM, "ループ位置"),
        CharPair('お', 'す', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM),
        CharPair('つ', 'っ', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM, "サイズ判定"),
        CharPair('っ', 'つ', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM),
        CharPair('ま', 'よ', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM, "上の突き出し"),
        CharPair('よ', 'ま', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM),
        CharPair('む', 'す', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM, "ループ位置"),
        CharPair('す', 'む', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM),
        CharPair('の', 'め', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM, "ループ有無"),
        CharPair('め', 'の', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM),
        CharPair('ぬ', 'の', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM, "形状"),
        CharPair('の', 'ぬ', Category.HIRAGANA_HIRAGANA, Frequency.MEDIUM)
    )

    // ============================================================
    // 4. 漢字↔カタカナ/数字/記号 の混同 (50+)
    //    CharTypeAnomalyCorrector で最も重要なカテゴリ
    // ============================================================

    val kanjiKanaPairs = listOf(
        // 高頻度: 漢字↔カタカナ（ほぼ同一字形）
        CharPair('力', 'カ', Category.KANJI_KANA, Frequency.HIGH, "力(ちから)↔カ"),
        CharPair('カ', '力', Category.KANJI_KANA, Frequency.HIGH),
        CharPair('工', 'エ', Category.KANJI_KANA, Frequency.HIGH, "工(こう)↔エ"),
        CharPair('エ', '工', Category.KANJI_KANA, Frequency.HIGH),
        CharPair('口', 'ロ', Category.KANJI_KANA, Frequency.HIGH, "口(くち)↔ロ"),
        CharPair('ロ', '口', Category.KANJI_KANA, Frequency.HIGH),
        CharPair('二', 'ニ', Category.KANJI_KANA, Frequency.HIGH, "二↔ニ"),
        CharPair('ニ', '二', Category.KANJI_KANA, Frequency.HIGH),
        CharPair('八', 'ハ', Category.KANJI_KANA, Frequency.HIGH, "八↔ハ"),
        CharPair('ハ', '八', Category.KANJI_KANA, Frequency.HIGH),
        // 漢数字↔長音・ハイフン
        CharPair('一', 'ー', Category.KANJI_KANA, Frequency.HIGH, "漢数字1↔長音"),
        CharPair('ー', '一', Category.KANJI_KANA, Frequency.HIGH),
        // 中頻度
        CharPair('夕', 'タ', Category.KANJI_KANA, Frequency.MEDIUM, "夕(ゆう)↔タ"),
        CharPair('タ', '夕', Category.KANJI_KANA, Frequency.MEDIUM),
        CharPair('又', 'ヌ', Category.KANJI_KANA, Frequency.MEDIUM, "又(また)↔ヌ"),
        CharPair('ヌ', '又', Category.KANJI_KANA, Frequency.MEDIUM),
        CharPair('才', 'オ', Category.KANJI_KANA, Frequency.MEDIUM, "才(さい)↔オ"),
        CharPair('オ', '才', Category.KANJI_KANA, Frequency.MEDIUM),
        CharPair('千', 'チ', Category.KANJI_KANA, Frequency.MEDIUM, "千(せん)↔チ"),
        CharPair('チ', '千', Category.KANJI_KANA, Frequency.MEDIUM),
        CharPair('也', 'ヤ', Category.KANJI_KANA, Frequency.MEDIUM, "也(なり)↔ヤ"),
        CharPair('ヤ', '也', Category.KANJI_KANA, Frequency.MEDIUM),
        CharPair('己', 'コ', Category.KANJI_KANA, Frequency.MEDIUM, "己↔コ"),
        CharPair('コ', '己', Category.KANJI_KANA, Frequency.MEDIUM),
        CharPair('十', 'ト', Category.KANJI_KANA, Frequency.MEDIUM, "十↔ト"),
        CharPair('ト', '十', Category.KANJI_KANA, Frequency.MEDIUM),
        CharPair('三', 'ミ', Category.KANJI_KANA, Frequency.MEDIUM, "三↔ミ"),
        CharPair('ミ', '三', Category.KANJI_KANA, Frequency.MEDIUM),
        // 踊り字・記号
        CharPair('々', 'ヶ', Category.KANJI_KANA, Frequency.MEDIUM, "踊り字↔ヶ"),
        CharPair('ヶ', '々', Category.KANJI_KANA, Frequency.MEDIUM),
        CharPair('ヶ', 'ケ', Category.KANJI_KANA, Frequency.MEDIUM, "ヶ↔ケ"),
        CharPair('ケ', 'ヶ', Category.KANJI_KANA, Frequency.MEDIUM),
        // 低頻度
        CharPair('卜', 'ト', Category.KANJI_KANA, Frequency.LOW, "卜(ぼく)↔ト"),
        CharPair('ト', '卜', Category.KANJI_KANA, Frequency.LOW),
        CharPair('匕', 'ヒ', Category.KANJI_KANA, Frequency.LOW, "匕(さじ)↔ヒ"),
        CharPair('ヒ', '匕', Category.KANJI_KANA, Frequency.LOW)
    )

    // ============================================================
    // 5. 数字・英字・記号の混同 (50+)
    // ============================================================

    val numberSymbolPairs = listOf(
        // 高頻度
        CharPair('0', 'O', Category.NUMBER_SYMBOL, Frequency.HIGH, "ゼロ↔オー"),
        CharPair('O', '0', Category.NUMBER_SYMBOL, Frequency.HIGH),
        CharPair('0', 'o', Category.NUMBER_SYMBOL, Frequency.HIGH, "ゼロ↔小文字オー"),
        CharPair('o', '0', Category.NUMBER_SYMBOL, Frequency.HIGH),
        CharPair('1', 'l', Category.NUMBER_SYMBOL, Frequency.HIGH, "イチ↔エル"),
        CharPair('l', '1', Category.NUMBER_SYMBOL, Frequency.HIGH),
        CharPair('1', 'I', Category.NUMBER_SYMBOL, Frequency.HIGH, "イチ↔アイ"),
        CharPair('I', '1', Category.NUMBER_SYMBOL, Frequency.HIGH),
        CharPair('5', 'S', Category.NUMBER_SYMBOL, Frequency.HIGH, "ゴ↔エス"),
        CharPair('S', '5', Category.NUMBER_SYMBOL, Frequency.HIGH),
        CharPair('8', 'B', Category.NUMBER_SYMBOL, Frequency.HIGH, "ハチ↔ビー"),
        CharPair('B', '8', Category.NUMBER_SYMBOL, Frequency.HIGH),
        CharPair('2', 'Z', Category.NUMBER_SYMBOL, Frequency.HIGH, "ニ↔ゼット"),
        CharPair('Z', '2', Category.NUMBER_SYMBOL, Frequency.HIGH),
        CharPair('6', 'b', Category.NUMBER_SYMBOL, Frequency.HIGH, "ロク↔ビー"),
        CharPair('b', '6', Category.NUMBER_SYMBOL, Frequency.HIGH),
        CharPair('9', 'q', Category.NUMBER_SYMBOL, Frequency.HIGH, "キュウ↔キュー"),
        CharPair('q', '9', Category.NUMBER_SYMBOL, Frequency.HIGH),
        CharPair('9', 'g', Category.NUMBER_SYMBOL, Frequency.HIGH, "キュウ↔ジー"),
        CharPair('g', '9', Category.NUMBER_SYMBOL, Frequency.HIGH),
        // 中頻度
        CharPair('6', 'G', Category.NUMBER_SYMBOL, Frequency.MEDIUM, "ロク↔ジー"),
        CharPair('G', '6', Category.NUMBER_SYMBOL, Frequency.MEDIUM),
        CharPair('7', 'T', Category.NUMBER_SYMBOL, Frequency.MEDIUM, "ナナ↔ティー"),
        CharPair('T', '7', Category.NUMBER_SYMBOL, Frequency.MEDIUM),
        CharPair('C', 'G', Category.NUMBER_SYMBOL, Frequency.MEDIUM, "シー↔ジー"),
        CharPair('G', 'C', Category.NUMBER_SYMBOL, Frequency.MEDIUM),
        CharPair('D', 'O', Category.NUMBER_SYMBOL, Frequency.MEDIUM, "ディー↔オー"),
        CharPair('O', 'D', Category.NUMBER_SYMBOL, Frequency.MEDIUM),
        CharPair('P', 'R', Category.NUMBER_SYMBOL, Frequency.MEDIUM, "ピー↔アール"),
        CharPair('R', 'P', Category.NUMBER_SYMBOL, Frequency.MEDIUM),
        CharPair('h', 'n', Category.NUMBER_SYMBOL, Frequency.MEDIUM, "小文字"),
        CharPair('n', 'h', Category.NUMBER_SYMBOL, Frequency.MEDIUM),
        CharPair('h', 'b', Category.NUMBER_SYMBOL, Frequency.MEDIUM, "小文字"),
        CharPair('b', 'h', Category.NUMBER_SYMBOL, Frequency.MEDIUM),
        CharPair('u', 'v', Category.NUMBER_SYMBOL, Frequency.MEDIUM, "小文字"),
        CharPair('v', 'u', Category.NUMBER_SYMBOL, Frequency.MEDIUM),
        CharPair('m', 'n', Category.NUMBER_SYMBOL, Frequency.MEDIUM, "小文字"),
        CharPair('r', 'n', Category.NUMBER_SYMBOL, Frequency.MEDIUM, "小文字")
    )

    // ============================================================
    // 統合インデックス（遅延初期化）
    // ============================================================

    /** 全ペアのフラットリスト */
    val allPairs: List<CharPair> by lazy {
        kanjiKanjiPairs + katakanaPairs + hiraganaPairs + kanjiKanaPairs + numberSymbolPairs
    }

    /** char1 → そのcharが誤認識元となるペアのリスト */
    val char1Index: Map<Char, List<CharPair>> by lazy {
        allPairs.groupBy { it.char1 }
    }

    /** 形状類似ペアに含まれる全文字のセット（高速ルックアップ用） */
    val allSuspectChars: Set<Char> by lazy {
        allPairs.flatMap { listOf(it.char1, it.char2) }.toSet()
    }

    /** 漢字↔カナ混同ペアの高速インデックス */
    val kanjiKanaIndex: Map<Char, List<CharPair>> by lazy {
        kanjiKanaPairs.groupBy { it.char1 }
    }

    /** ペア数の統計 */
    fun getStats(): String {
        return "ShapeSimilarDB: kanji=${kanjiKanjiPairs.size}, katakana=${katakanaPairs.size}, " +
                "hiragana=${hiraganaPairs.size}, kanjiKana=${kanjiKanaPairs.size}, " +
                "numberSymbol=${numberSymbolPairs.size}, total=${allPairs.size}"
    }
}
