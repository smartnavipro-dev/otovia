package com.otovia.app.ocr

import android.util.Log
import com.atilika.kuromoji.unidic.Token
import com.atilika.kuromoji.unidic.Tokenizer

/**
 * v1.0.65: UniDicを使用した送り仮名誤認識補正
 *
 * OCRでよく発生する送り仮名の誤認識（ひらがな→漢字）を検出・補正する。
 * 例: "読みあげる" → "読み上げる"
 *     "動きだす" → "動き出す"
 */
class OkuriganaCorrector {

    companion object {
        private const val TAG = "Otovia_OkuriganaCorrector"

        // v1.0.71: UniDic形態素解析器（Phase3SharedTokenizerを使用）
        private val unidicTokenizer get() = Phase3SharedTokenizer.unidicTokenizer

        /**
         * 補助動詞の送り仮名パターン（ひらがな → 漢字）
         * OCRで頻出する20パターン
         */
        private val auxiliaryVerbPatterns = mapOf(
            // 高頻度パターン
            "あげる" to AuxPattern("上げる", 0.9, "動作の完了・上昇"),
            "だす" to AuxPattern("出す", 0.9, "動作の開始"),
            "あがる" to AuxPattern("上がる", 0.9, "上昇"),
            "こむ" to AuxPattern("込む", 0.85, "内部への移動"),
            "とる" to AuxPattern("取る", 0.85, "捕捉・獲得"),
            "はじめる" to AuxPattern("始める", 0.85, "動作の開始"),
            "おえる" to AuxPattern("終える", 0.85, "動作の完了"),
            "おわる" to AuxPattern("終わる", 0.85, "動作の完了"),
            "つける" to AuxPattern("付ける", 0.8, "付加"),
            "かえる" to AuxPattern("帰る", 0.8, "戻る動作"),  // 文脈依存: 帰る/返る

            // 中頻度パターン
            "さげる" to AuxPattern("下げる", 0.75, "下降"),
            "さがる" to AuxPattern("下がる", 0.75, "下降"),
            "かける" to AuxPattern("掛ける", 0.7, "動作の途中"),
            "すぎる" to AuxPattern("過ぎる", 0.7, "過度"),
            "なおす" to AuxPattern("直す", 0.7, "修正"),
            "かえす" to AuxPattern("返す", 0.7, "返却"),
            "まわる" to AuxPattern("回る", 0.65, "回転"),
            "かわる" to AuxPattern("変わる", 0.65, "変化"),  // 文脈依存: 変わる/代わる

            // 低頻度パターン
            "わたる" to AuxPattern("渡る", 0.6, "移動"),
            "わける" to AuxPattern("分ける", 0.6, "分割")
        )

        /**
         * 高頻出の動詞+補助動詞パターン（信頼度ブースト用）
         */
        private val commonVerbPatterns = setOf(
            "読みあげる", "動きだす", "立ちあがる", "取りくむ", "申しこむ",
            "聞きとる", "持ちかえる", "引きさがる", "読みはじめる", "書きなおす"
        )
    }

    /**
     * 送り仮名誤認識を検出して補正候補を提案
     */
    fun detectAndCorrect(text: String): OkuriganaResult {
        val tokens = unidicTokenizer.tokenize(text)
        val suggestions = mutableListOf<OkuriganaSuggestion>()

        Log.d(TAG, "[v1.0.65] Analyzing ${tokens.size} tokens for okurigana errors")

        tokens.forEachIndexed { index, token ->
            // 動詞の連用形を検出
            if (isRenyoukeiVerb(token)) {
                val nextToken = tokens.getOrNull(index + 1)

                // 次のトークンがひらがなの補助動詞パターンか確認
                if (nextToken != null && isHiraganaAuxiliaryVerb(nextToken)) {
                    val auxPattern = auxiliaryVerbPatterns[nextToken.surface]

                    if (auxPattern != null) {
                        val position = calculatePosition(tokens, index + 1, text)
                        val confidence = calculateConfidence(token, nextToken, auxPattern)

                        suggestions.add(
                            OkuriganaSuggestion(
                                position = position,
                                originalForm = nextToken.surface,
                                correctedForm = auxPattern.kanji,
                                verbStem = token.surface,
                                confidence = confidence,
                                reason = auxPattern.usage,
                                verbPattern = token.surface + nextToken.surface
                            )
                        )

                        Log.d(TAG, "[v1.0.65] Okurigana: '${token.surface}${nextToken.surface}' → '${token.surface}${auxPattern.kanji}' (conf=${String.format("%.2f", confidence)}, ${auxPattern.usage})")
                    }
                }
            }
        }

        return OkuriganaResult(
            originalText = text,
            suggestions = suggestions,
            confidence = calculateOverallConfidence(suggestions)
        )
    }

    /**
     * 動詞の連用形かどうか判定
     */
    private fun isRenyoukeiVerb(token: Token): Boolean {
        return token.partOfSpeechLevel1 == "動詞" &&
               (token.conjugationForm?.contains("連用") == true ||
                token.conjugationForm == "連用形-一般" ||
                token.conjugationForm == "連用形-促音便" ||
                token.conjugationForm == "連用形-撥音便")
    }

    /**
     * ひらがなの補助動詞パターンかどうか判定
     */
    private fun isHiraganaAuxiliaryVerb(token: Token): Boolean {
        // すべてひらがなであり、補助動詞リストに含まれる
        return token.surface.all { it in 'ぁ'..'ん' } &&
               auxiliaryVerbPatterns.containsKey(token.surface)
    }

    /**
     * 信頼度を計算
     */
    private fun calculateConfidence(
        verbToken: Token,
        auxToken: Token,
        auxPattern: AuxPattern
    ): Double {
        var score = 0.0

        // 1. 補助動詞の基本頻度スコア（40%）
        score += auxPattern.baseFrequency * 0.4

        // 2. 動詞の活用形スコア（30%）
        val conjugationScore = when (verbToken.conjugationForm) {
            "連用形-一般" -> 1.0
            "連用形-促音便", "連用形-撥音便" -> 0.8
            else -> 0.6
        }
        score += conjugationScore * 0.3

        // 3. 動詞+補助動詞の頻出パターンスコア（20%）
        val pattern = verbToken.surface + auxToken.surface
        val patternScore = if (commonVerbPatterns.contains(pattern)) {
            1.0  // 高頻出パターン
        } else {
            0.5  // 一般的なパターン
        }
        score += patternScore * 0.2

        // 4. 動詞の一般性スコア（10%）
        val verbCommonScore = if (isCommonVerb(verbToken.surface)) 1.0 else 0.5
        score += verbCommonScore * 0.1

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * 一般的な動詞かどうか判定
     */
    private fun isCommonVerb(verb: String): Boolean {
        val commonVerbs = setOf(
            "読み", "書き", "見", "聞き", "話し", "言", "思", "考え",
            "動き", "立ち", "座り", "歩き", "走り", "飛び",
            "取り", "持ち", "置き", "開け", "閉め", "入れ", "出し",
            "申し", "引き", "押し", "引っ張り", "振り"
        )
        return commonVerbs.contains(verb)
    }

    /**
     * テキスト内の位置を計算
     */
    private fun calculatePosition(tokens: List<Token>, index: Int, originalText: String): Int {
        var pos = 0
        for (i in 0 until index) {
            pos += tokens[i].surface.length
        }
        return pos
    }

    /**
     * 全体の信頼度を計算
     */
    private fun calculateOverallConfidence(suggestions: List<OkuriganaSuggestion>): Double {
        if (suggestions.isEmpty()) return 0.0
        return suggestions.map { it.confidence }.average()
    }

    /**
     * 送り仮名補正を適用
     */
    fun applyCorrections(result: OkuriganaResult, minConfidence: Double = 0.6): String {
        if (result.suggestions.isEmpty()) {
            return result.originalText
        }

        var correctedText = result.originalText
        var offset = 0  // 補正によるオフセット

        // 信頼度の高い提案のみを適用（位置順にソート）
        result.suggestions
            .filter { it.confidence >= minConfidence }
            .sortedBy { it.position }
            .forEach { suggestion ->
                val replacePos = suggestion.position + offset

                if (replacePos <= correctedText.length) {
                    val endPos = replacePos + suggestion.originalForm.length

                    if (endPos <= correctedText.length) {
                        // ひらがな補助動詞を漢字に置換
                        correctedText = correctedText.substring(0, replacePos) +
                                       suggestion.correctedForm +
                                       correctedText.substring(endPos)

                        // オフセット更新（漢字の方が文字数が少ない場合が多い）
                        val lengthDiff = suggestion.correctedForm.length - suggestion.originalForm.length
                        offset += lengthDiff

                        Log.d(TAG, "[v1.0.65] Applied: '${suggestion.verbStem}${suggestion.originalForm}' → '${suggestion.verbStem}${suggestion.correctedForm}' (conf=${String.format("%.2f", suggestion.confidence)})")
                    }
                }
            }

        Log.d(TAG, "[v1.0.65] Okurigana corrections applied: ${result.suggestions.filter { it.confidence >= minConfidence }.size}/${result.suggestions.size}")
        return correctedText
    }

    /**
     * 補助動詞パターン
     */
    private data class AuxPattern(
        val kanji: String,          // 漢字表記
        val baseFrequency: Double,  // 基本頻度 (0.0-1.0)
        val usage: String           // 用法説明
    )

    /**
     * 送り仮名補正の提案
     */
    data class OkuriganaSuggestion(
        val position: Int,              // 補正位置
        val originalForm: String,       // 元の形（ひらがな）
        val correctedForm: String,      // 補正後の形（漢字）
        val verbStem: String,           // 動詞語幹
        val confidence: Double,         // 信頼度 (0.0-1.0)
        val reason: String,             // 理由
        val verbPattern: String         // 動詞パターン（例: "読みあげる"）
    )

    /**
     * 補正結果
     */
    data class OkuriganaResult(
        val originalText: String,
        val suggestions: List<OkuriganaSuggestion>,
        val confidence: Double
    )
}
