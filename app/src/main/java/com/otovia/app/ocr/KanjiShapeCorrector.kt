package com.otovia.app.ocr

import android.util.Log
import com.atilika.kuromoji.unidic.Token
import com.atilika.kuromoji.unidic.Tokenizer
import kotlin.math.max
import kotlin.math.min

/**
 * v1.0.68: 漢字字形類似誤認識補正
 *
 * OCRでよく発生する字形が類似した漢字の誤認識を検出・補正する。
 * 例: 未→末、土→士、人→入、大→犬等
 *
 * 設計文書: kanji_shape_misrecognition_design.md
 * Phase3準備リスト項目4
 */
class KanjiShapeCorrector {

    companion object {
        private const val TAG = "Otovia_KanjiShape"

        /**
         * v1.0.71: UniDicトークナイザー（Phase3SharedTokenizerを使用）
         */
        private val unidicTokenizer get() = Phase3SharedTokenizer.unidicTokenizer

        /**
         * 漢字字形類似ペア（60ペア、120パターン）
         */
        private val kanjiShapePairs = listOf(
            // カテゴリ1: 画数1-2差（高頻度）
            KanjiShapePair('未', '末', "横線の長さ", 10, "基本"),
            KanjiShapePair('末', '未', "横線の長さ", 10, "基本"),
            KanjiShapePair('土', '士', "横線の長さ", 10, "基本"),
            KanjiShapePair('士', '土', "横線の長さ", 10, "基本"),
            KanjiShapePair('人', '入', "ノとハの違い", 9, "基本"),
            KanjiShapePair('入', '人', "ノとハの違い", 9, "基本"),
            KanjiShapePair('大', '犬', "点の有無", 9, "基本"),
            KanjiShapePair('犬', '大', "点の有無", 9, "基本"),
            KanjiShapePair('木', '本', "横線の有無", 8, "基本"),
            KanjiShapePair('本', '木', "横線の有無", 8, "基本"),
            KanjiShapePair('日', '目', "縦線の位置", 8, "基本"),
            KanjiShapePair('目', '日', "縦線の位置", 8, "基本"),
            KanjiShapePair('白', '百', "横線の有無", 7, "基本"),
            KanjiShapePair('百', '白', "横線の有無", 7, "基本"),
            KanjiShapePair('千', '干', "縦線の長さ", 7, "基本"),
            KanjiShapePair('干', '千', "縦線の長さ", 7, "基本"),

            // カテゴリ2: 部首類似（中頻度）
            KanjiShapePair('問', '間', "門の中身", 8, "部首"),
            KanjiShapePair('間', '問', "門の中身", 8, "部首"),
            KanjiShapePair('待', '持', "てへん・にんべん", 7, "部首"),
            KanjiShapePair('持', '待', "てへん・にんべん", 7, "部首"),
            KanjiShapePair('陽', '場', "こざとへん・つちへん", 7, "部首"),
            KanjiShapePair('場', '陽', "こざとへん・つちへん", 7, "部首"),
            KanjiShapePair('時', '特', "にちへん・うしへん", 6, "部首"),
            KanjiShapePair('特', '時', "にちへん・うしへん", 6, "部首"),
            KanjiShapePair('話', '詰', "ごんべん内部", 6, "部首"),
            KanjiShapePair('詰', '話', "ごんべん内部", 6, "部首"),
            KanjiShapePair('性', '姓', "りっしんべん・おんなへん", 6, "部首"),
            KanjiShapePair('姓', '性', "りっしんべん・おんなへん", 6, "部首"),

            // カテゴリ3: 複雑字形（中頻度）
            KanjiShapePair('機', '械', "木へん・いとへん", 7, "複雑"),
            KanjiShapePair('械', '機', "木へん・いとへん", 7, "複雑"),
            KanjiShapePair('給', '結', "いとへん内部", 6, "複雑"),
            KanjiShapePair('結', '給', "いとへん内部", 6, "複雑"),
            KanjiShapePair('続', '統', "いとへん内部", 6, "複雑"),
            KanjiShapePair('統', '続', "いとへん内部", 6, "複雑"),
            KanjiShapePair('様', '横', "きへん内部", 6, "複雑"),
            KanjiShapePair('横', '様', "きへん内部", 6, "複雑"),
            KanjiShapePair('県', '具', "目の有無", 5, "複雑"),
            KanjiShapePair('具', '県', "目の有無", 5, "複雑"),

            // カテゴリ4: 似た構造（低頻度）
            KanjiShapePair('己', '巳', "折れ方", 5, "構造"),
            KanjiShapePair('巳', '己', "折れ方", 5, "構造"),
            KanjiShapePair('午', '牛', "横線の位置", 5, "構造"),
            KanjiShapePair('牛', '午', "横線の位置", 5, "構造"),
            KanjiShapePair('口', '曰', "縦横比", 4, "構造"),
            KanjiShapePair('曰', '口', "縦横比", 4, "構造"),
            KanjiShapePair('刀', '力', "線の向き", 4, "構造"),
            KanjiShapePair('力', '刀', "線の向き", 4, "構造"),
            KanjiShapePair('市', '巾', "縦線の位置", 4, "構造"),
            KanjiShapePair('巾', '市', "縦線の位置", 4, "構造"),

            // カテゴリ5: 数字・記号類似
            KanjiShapePair('二', 'ニ', "カタカナと漢字", 6, "記号"),
            KanjiShapePair('ニ', '二', "カタカナと漢字", 6, "記号"),
            KanjiShapePair('十', 'ト', "カタカナと漢字", 5, "記号"),
            KanjiShapePair('ト', '十', "カタカナと漢字", 5, "記号"),
            KanjiShapePair('三', 'ミ', "カタカナと漢字", 5, "記号"),
            KanjiShapePair('ミ', '三', "カタカナと漢字", 5, "記号"),
            KanjiShapePair('力', 'カ', "カタカナと漢字", 5, "記号"),
            KanjiShapePair('カ', '力', "カタカナと漢字", 5, "記号")
        )

        /**
         * 漢字→候補マッピングインデックス
         */
        private val kanjiPairIndex: Map<Char, List<KanjiShapePair>> by lazy {
            kanjiShapePairs.groupBy { it.originalKanji }
        }

        init {
            Log.d(TAG, "[v1.0.68] KanjiShapeCorrector initialized with ${kanjiShapePairs.size} pairs (${kanjiPairIndex.size} unique kanji)")
        }
    }

    /**
     * 漢字字形類似誤認識を検出・補正候補を提案する
     */
    fun detectAndCorrect(text: String): KanjiShapeResult {
        val startTime = System.currentTimeMillis()

        return try {
            if (text.isBlank()) {
                return KanjiShapeResult(
                    originalText = text,
                    suggestions = emptyList(),
                    processingTimeMs = 0
                )
            }

            // UniDicトークン化
            val tokens = unidicTokenizer.tokenize(text)
            Log.d(TAG, "[v1.0.68] Analyzing ${tokens.size} tokens for kanji shape errors")

            val suggestions = mutableListOf<KanjiShapeSuggestion>()

            tokens.forEachIndexed { index, token ->
                val surface = token.surface

                // 漢字を含むトークンのみ対象
                if (containsKanji(surface)) {
                    // 字形類似候補を検出
                    surface.forEachIndexed { charIndex, char ->
                        val candidates = kanjiPairIndex[char] ?: emptyList()

                        candidates.forEach { pair ->
                            // 補正候補を生成
                            val correctedForm = surface.replaceRange(charIndex, charIndex + 1, pair.correctKanji.toString())

                            // 文脈ベース評価
                            val confidence = evaluateContextualFit(
                                originalToken = token,
                                candidateForm = correctedForm,
                                prevTokens = tokens.subList(max(0, index - 2), index),
                                nextTokens = tokens.subList(index + 1, min(tokens.size, index + 3)),
                                pair = pair
                            )

                            // 閾値以上の候補のみ追加
                            if (confidence >= 0.5) {
                                suggestions.add(
                                    KanjiShapeSuggestion(
                                        position = index,
                                        originalForm = surface,
                                        correctedForm = correctedForm,
                                        misrecognizedKanji = pair.originalKanji,
                                        correctKanji = pair.correctKanji,
                                        confidence = confidence,
                                        reason = pair.reason,
                                        category = pair.category
                                    )
                                )
                                Log.d(TAG, "[v1.0.68] '${surface}' → '${correctedForm}' (${pair.originalKanji}→${pair.correctKanji}) conf=${String.format("%.2f", confidence)}, ${pair.reason}")
                            }
                        }
                    }
                }
            }

            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "[v1.0.68] Found ${suggestions.size} kanji shape patterns in ${duration}ms")

            KanjiShapeResult(
                originalText = text,
                suggestions = suggestions.sortedByDescending { it.confidence },
                processingTimeMs = duration
            )

        } catch (e: Exception) {
            Log.e(TAG, "[v1.0.68] Detection failed: ${e.message}", e)
            KanjiShapeResult(
                originalText = text,
                suggestions = emptyList(),
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * 文脈ベース適合性評価
     * v1.1.28: 原形が辞書語の場合は補正しない（収入→収人 等の誤補正防止）
     */
    private fun evaluateContextualFit(
        originalToken: Token,
        candidateForm: String,
        prevTokens: List<Token>,
        nextTokens: List<Token>,
        pair: KanjiShapePair
    ): Double {
        // v1.1.28: 原形が辞書に存在する正しい語なら補正不要
        val originalDictScore = evaluateDictionaryExistence(originalToken.surface)
        if (originalDictScore >= 1.0) {
            Log.d(TAG, "[v1.1.28] Skipping '${originalToken.surface}' → '${candidateForm}': original is a valid dictionary word")
            return 0.0
        }

        var score = 0.0

        // 1. 品詞適合性（30%）
        val posScore = evaluatePOSCompatibility(originalToken, candidateForm)
        score += posScore * 0.3

        // 2. 辞書存在性（25%）
        val dictScore = evaluateDictionaryExistence(candidateForm)
        score += dictScore * 0.25

        // 3. N-gram適合性（25%）
        val ngramScore = evaluateNgramFit(candidateForm, prevTokens, nextTokens)
        score += ngramScore * 0.25

        // 4. 頻度スコア（20%）
        val freqScore = pair.frequency / 10.0
        score += freqScore * 0.2

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * 品詞適合性評価
     */
    private fun evaluatePOSCompatibility(
        originalToken: Token,
        candidateForm: String
    ): Double {
        return try {
            val originalPOS = originalToken.partOfSpeechLevel1 ?: ""

            // 候補を形態素解析
            val candidateTokens = unidicTokenizer.tokenize(candidateForm)
            if (candidateTokens.isEmpty()) return 0.3

            val candidatePOS = candidateTokens[0].partOfSpeechLevel1 ?: ""

            // 品詞一致度
            when {
                originalPOS == candidatePOS -> 1.0  // 完全一致
                originalPOS.isEmpty() || candidatePOS.isEmpty() -> 0.5  // 不明
                else -> 0.3  // 不一致
            }
        } catch (e: Exception) {
            Log.w(TAG, "[v1.0.68] POS evaluation failed: ${e.message}")
            0.5  // デフォルト中間値
        }
    }

    /**
     * 辞書存在性評価
     */
    private fun evaluateDictionaryExistence(candidateForm: String): Double {
        return try {
            val tokens = unidicTokenizer.tokenize(candidateForm)
            if (tokens.isEmpty()) return 0.3

            // 単一トークンの場合は高スコア
            if (tokens.size == 1) {
                1.0
            } else {
                0.3
            }
        } catch (e: Exception) {
            Log.w(TAG, "[v1.0.68] Dictionary check failed: ${e.message}")
            0.5  // デフォルト中間値
        }
    }

    /**
     * N-gram適合性評価（簡易版）
     */
    private fun evaluateNgramFit(
        candidateForm: String,
        prevTokens: List<Token>,
        nextTokens: List<Token>
    ): Double {
        // 簡易実装: 前後のトークンとの品詞連接を評価
        var score = 0.5  // ベーススコア

        // 前のトークンとの連接
        if (prevTokens.isNotEmpty()) {
            val prevPOS = prevTokens.last().partOfSpeechLevel1 ?: ""
            val candidateTokens = unidicTokenizer.tokenize(candidateForm)
            if (candidateTokens.isNotEmpty()) {
                val candidatePOS = candidateTokens[0].partOfSpeechLevel1 ?: ""
                // 自然な連接パターンをチェック（簡易版）
                if (isNaturalSequence(prevPOS, candidatePOS)) {
                    score += 0.3
                }
            }
        }

        // 後のトークンとの連接
        if (nextTokens.isNotEmpty()) {
            val nextPOS = nextTokens.first().partOfSpeechLevel1 ?: ""
            val candidateTokens = unidicTokenizer.tokenize(candidateForm)
            if (candidateTokens.isNotEmpty()) {
                val candidatePOS = candidateTokens[0].partOfSpeechLevel1 ?: ""
                // 自然な連接パターンをチェック（簡易版）
                if (isNaturalSequence(candidatePOS, nextPOS)) {
                    score += 0.2
                }
            }
        }

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * 自然な品詞連接判定（簡易版）
     */
    private fun isNaturalSequence(pos1: String, pos2: String): Boolean {
        // 一般的な連接パターン
        val naturalPatterns = setOf(
            "名詞-動詞",
            "名詞-助詞",
            "動詞-助動詞",
            "形容詞-名詞",
            "副詞-動詞",
            "連体詞-名詞"
        )
        return naturalPatterns.contains("$pos1-$pos2")
    }

    /**
     * 補正候補を適用する
     */
    fun applyCorrections(
        result: KanjiShapeResult,
        minConfidence: Double = 0.60
    ): String {
        if (result.suggestions.isEmpty()) {
            return result.originalText
        }

        // 信頼度でフィルタリング
        val validSuggestions = result.suggestions.filter { it.confidence >= minConfidence }

        if (validSuggestions.isEmpty()) {
            Log.d(TAG, "[v1.0.68] No suggestions above confidence threshold ${minConfidence}")
            return result.originalText
        }

        Log.d(TAG, "[v1.0.68] Applying ${validSuggestions.size} corrections (threshold=${minConfidence})")

        // トークン化して位置ベースで補正
        val tokens = unidicTokenizer.tokenize(result.originalText)
        val correctedTokens = tokens.map { it.surface }.toMutableList()

        // 信頼度順にソートして適用（高信頼度優先）
        validSuggestions.sortedByDescending { it.confidence }.forEach { suggestion ->
            if (suggestion.position < correctedTokens.size) {
                correctedTokens[suggestion.position] = suggestion.correctedForm
                Log.d(TAG, "[v1.0.68] Applied: '${suggestion.originalForm}' → '${suggestion.correctedForm}' (conf=${String.format("%.2f", suggestion.confidence)})")
            }
        }

        return correctedTokens.joinToString("")
    }

    /**
     * 漢字を含むかチェック
     */
    private fun containsKanji(text: String): Boolean {
        return text.any { char ->
            char in '\u4E00'..'\u9FFF' || // CJK統合漢字
                    char in '\u3400'..'\u4DBF' || // CJK拡張A
                    char in '\uF900'..'\uFAFF'    // CJK互換漢字
        }
    }

    /**
     * データクラス: 漢字字形類似ペア
     */
    data class KanjiShapePair(
        val originalKanji: Char,   // 誤認識される漢字
        val correctKanji: Char,    // 正しい漢字
        val reason: String,        // 類似理由
        val frequency: Int,        // 頻度（1-10）
        val category: String       // カテゴリ
    )

    /**
     * データクラス: 補正候補
     */
    data class KanjiShapeSuggestion(
        val position: Int,
        val originalForm: String,
        val correctedForm: String,
        val misrecognizedKanji: Char,
        val correctKanji: Char,
        val confidence: Double,
        val reason: String,
        val category: String
    )

    /**
     * データクラス: 検出結果
     */
    data class KanjiShapeResult(
        val originalText: String,
        val suggestions: List<KanjiShapeSuggestion>,
        val processingTimeMs: Long
    )
}
