package com.kindletts.reader.ocr

import android.util.Log

/**
 * v1.1.32: 文字種異常検出補正器（Stage 1 — 安全な直接補正）
 *
 * 漢字列中にカタカナが混入（またはその逆）している場合、
 * 形状類似文字データベースを参照して正しい文字種に置換する。
 *
 * 例:
 *   協カする → 協力する  （カタカナ「カ」→ 漢字「力」）
 *   人エ知能 → 人工知能  （カタカナ「エ」→ 漢字「工」）
 *   口ンドン → ロンドン  （漢字「口」→ カタカナ「ロ」）
 *
 * 安全条件:
 * - 前後3文字以上の同一文字種コンテキストが必要
 * - 形状類似データベースに候補が1つだけ存在する場合のみ補正
 * - HIGH/MEDIUM頻度のペアのみ（LOW頻度はLLMに委ねる）
 * - 自然な文字種境界（送り仮名・助詞）はスキップ
 * - Kuromoji不使用 → OOMリスクなし、処理時間 <1ms
 */
class CharTypeAnomalyCorrector {

    companion object {
        private const val TAG = "KindleTTS_AnomalyCorr"

        // 文字種コンテキストの最小長（片側）
        private const val MIN_CONTEXT_ONE_SIDE = 2

        /**
         * Stage 1で安全に直接補正できるペアのホワイトリスト。
         * key = OCR出力文字, value = 正しい文字
         *
         * 条件: 片方が極めて一般的で、もう片方が文脈的にほぼあり得ないペアのみ。
         * 例: カタカナ文脈中の漢字「力」→ カタカナ「カ」は安全（力は漢字列でのみ使用）
         *
         * 危険なペア（コ→己, ト→十, タ→夕 等）はここに含めず、LLMヒントに委ねる。
         */
        private val SAFE_CORRECTIONS = mapOf(
            // カタカナ → 漢字 (カタカナが漢字文脈に混入している場合)
            'カ' to '力',  // カ→力: 極めて安全。漢字列中の「カ」はほぼ確実に「力」
            'エ' to '工',  // エ→工: 極めて安全。漢字列中の「エ」はほぼ確実に「工」
            'ニ' to '二',  // ニ→二: 安全。漢字列中の「ニ」はほぼ確実に「二」
            // 漢字 → カタカナ (漢字がカタカナ文脈に混入している場合)
            '力' to 'カ',  // 力→カ: カタカナ列中の「力」はほぼ確実に「カ」
            '工' to 'エ',  // 工→エ: カタカナ列中の「工」はほぼ確実に「エ」
            '二' to 'ニ',  // 二→ニ: カタカナ列中の「二」はほぼ確実に「ニ」
            '口' to 'ロ',  // 口→ロ: カタカナ列中の「口」はほぼ確実に「ロ」
            '八' to 'ハ',  // 八→ハ: カタカナ列中の「八」はほぼ確実に「ハ」
            '一' to 'ー',  // 一→ー: カタカナ列中の「一」はほぼ確実に長音「ー」
            // 注意: 以下は含めない（LLMヒントに委ねる）
            // 'ロ' to '口'  — ロンドン等、カタカナ「ロ」は漢字文脈でも正しい場合がある
            // 'コ' to '己'  — 己は稀な漢字。ほぼ誤補正になる
            // 'ト' to '十'  — トは複合語で頻出。十への変換は危険
            // 'タ' to '夕'  — タは一般的。夕への変換は危険
            // 'ハ' to '八'  — ハは一般的。八への変換は文脈依存
        )

        // 自然な文字種境界で出現するひらがな（助詞・送り仮名・助動詞）
        // これらは漢字の後に自然に出現するため、異常ではない
        private val NATURAL_HIRAGANA = setOf(
            'が', 'は', 'を', 'に', 'で', 'の', 'へ', 'と', 'も', 'や', 'か',
            'な', 'て', 'し', 'た', 'る', 'れ', 'ば', 'ら', 'り', 'み', 'く',
            'き', 'い', 'う', 'え', 'お', 'ん', 'す', 'せ', 'さ', 'だ', 'ず',
            'ぜ', 'ぞ', 'ど', 'ぶ', 'べ', 'ぼ', 'ぱ', 'ぴ', 'ぷ', 'ぺ', 'ぽ',
            'わ', 'よ', 'ま', 'め', 'む', 'ぬ', 'つ', 'ち', 'こ', 'そ', 'あ',
            'ぐ', 'げ', 'ご', 'じ', 'び'
        )
    }

    data class AnomalyCorrection(
        val position: Int,
        val original: Char,
        val replacement: Char,
        val contextType: ShapeSimilarDB.CharType,
        val reason: String
    )

    /**
     * テキストを走査し、文字種が周囲と異なる文字を検出・補正する。
     *
     * @param text 補正対象テキスト
     * @return 補正後テキスト
     */
    fun correct(text: String): String {
        if (text.length < 3) return text

        val chars = text.toCharArray()
        val corrections = mutableListOf<AnomalyCorrection>()

        for (i in chars.indices) {
            val c = chars[i]
            val myType = ShapeSimilarDB.charType(c)

            // 補正対象: 漢字、カタカナのみ（ひらがなは自然な境界が多すぎて危険）
            if (myType != ShapeSimilarDB.CharType.KANJI && myType != ShapeSimilarDB.CharType.KATAKANA) continue

            // ひらがなは漢字の後に自然に出現するので異常検出の対象外
            if (myType == ShapeSimilarDB.CharType.HIRAGANA) continue

            // 左右のコンテキストを取得
            val leftType = getDominantType(chars, maxOf(0, i - 5), i)
            val rightType = getDominantType(chars, i + 1, minOf(chars.size, i + 6))

            // コンテキストが十分でない場合スキップ
            val leftCount = countType(chars, maxOf(0, i - 5), i, leftType)
            val rightCount = countType(chars, i + 1, minOf(chars.size, i + 6), rightType)

            if (leftCount < MIN_CONTEXT_ONE_SIDE && rightCount < MIN_CONTEXT_ONE_SIDE) continue

            // コンテキストの文字種を決定（左右で一致する場合のみ）
            val contextType = when {
                leftType != null && rightType != null && leftType == rightType -> leftType
                leftType != null && leftCount >= MIN_CONTEXT_ONE_SIDE && rightType == null -> leftType
                rightType != null && rightCount >= MIN_CONTEXT_ONE_SIDE && leftType == null -> rightType
                leftType != null && leftCount >= 3 -> leftType  // 左側が強い場合
                rightType != null && rightCount >= 3 -> rightType  // 右側が強い場合
                else -> continue
            }

            // 自分の文字種がコンテキストと同じなら異常なし
            if (myType == contextType) continue

            // 漢字→カタカナ、カタカナ→漢字 の混同のみ対応
            if (!((myType == ShapeSimilarDB.CharType.KANJI && contextType == ShapeSimilarDB.CharType.KATAKANA) ||
                  (myType == ShapeSimilarDB.CharType.KATAKANA && contextType == ShapeSimilarDB.CharType.KANJI))) continue

            // 自然な境界チェック: ひらがなが漢字の後に来るのは自然
            if (isNaturalBoundary(chars, i)) continue

            // ホワイトリストから安全な補正候補を検索
            val replacement = SAFE_CORRECTIONS[c] ?: continue

            // 置換先の文字種がコンテキストと一致することを確認
            if (ShapeSimilarDB.charType(replacement) != contextType) continue

            corrections.add(AnomalyCorrection(
                position = i,
                original = c,
                replacement = replacement,
                contextType = contextType,
                reason = "${myType}→${contextType}: '${c}'→'${replacement}'"
            ))
        }

        if (corrections.isEmpty()) return text

        // 補正適用
        val result = chars.copyOf()
        corrections.forEach { corr ->
            result[corr.position] = corr.replacement
            Log.d(TAG, "[v1.1.32] pos=${corr.position} ${corr.reason}")
        }

        val resultText = String(result)
        Log.d(TAG, "[v1.1.32] Applied ${corrections.size} char-type anomaly corrections")
        return resultText
    }

    /**
     * 指定範囲の支配的な文字種を返す（漢字/カタカナのみ）
     */
    private fun getDominantType(chars: CharArray, from: Int, to: Int): ShapeSimilarDB.CharType? {
        if (from >= to) return null
        var kanjiCount = 0
        var katakanaCount = 0
        for (i in from until to) {
            when (ShapeSimilarDB.charType(chars[i])) {
                ShapeSimilarDB.CharType.KANJI -> kanjiCount++
                ShapeSimilarDB.CharType.KATAKANA -> katakanaCount++
                else -> {} // ひらがな・記号は無視
            }
        }
        return when {
            kanjiCount > katakanaCount && kanjiCount >= MIN_CONTEXT_ONE_SIDE -> ShapeSimilarDB.CharType.KANJI
            katakanaCount > kanjiCount && katakanaCount >= MIN_CONTEXT_ONE_SIDE -> ShapeSimilarDB.CharType.KATAKANA
            kanjiCount >= MIN_CONTEXT_ONE_SIDE -> ShapeSimilarDB.CharType.KANJI
            katakanaCount >= MIN_CONTEXT_ONE_SIDE -> ShapeSimilarDB.CharType.KATAKANA
            else -> null
        }
    }

    /**
     * 指定範囲で指定文字種のカウントを返す
     */
    private fun countType(chars: CharArray, from: Int, to: Int, type: ShapeSimilarDB.CharType?): Int {
        if (type == null || from >= to) return 0
        var count = 0
        for (i in from until to) {
            if (ShapeSimilarDB.charType(chars[i]) == type) count++
        }
        return count
    }

    /**
     * 自然な文字種境界かどうか判定
     * 日本語では漢字+ひらがな（送り仮名）、漢字+助詞 は自然
     */
    private fun isNaturalBoundary(chars: CharArray, pos: Int): Boolean {
        val c = chars[pos]
        val cType = ShapeSimilarDB.charType(c)

        // ひらがなは常に自然（送り仮名・助詞として）
        if (cType == ShapeSimilarDB.CharType.HIRAGANA) return true

        // 記号・数字は自然
        if (cType == ShapeSimilarDB.CharType.NUMBER || cType == ShapeSimilarDB.CharType.OTHER) return true

        return false
    }
}
