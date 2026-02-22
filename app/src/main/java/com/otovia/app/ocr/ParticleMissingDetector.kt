package com.otovia.app.ocr

import android.util.Log
import com.atilika.kuromoji.unidic.Token
import com.atilika.kuromoji.unidic.Tokenizer

/**
 * Phase 3 (v1.0.64): UniDicを使用した助詞脱落検出
 *
 * OCR誤認識により助詞が脱落しやすい箇所を検出し、適切な助詞を補完する。
 * UniDicの詳細な品詞情報を活用して、文脈に適した助詞を推薦する。
 */
class ParticleMissingDetector {

    companion object {
        private const val TAG = "Otovia_ParticleDetector"

        // v1.0.71: UniDic形態素解析器（Phase3SharedTokenizerを使用）
        private val unidicTokenizer get() = Phase3SharedTokenizer.unidicTokenizer

        /**
         * 助詞の種類と使用頻度
         * OCRで脱落しやすい助詞を優先順位順に並べる
         */
        private val commonParticles = listOf(
            // 格助詞（主要）
            "の" to 0.30,  // 所有・修飾（最頻出）
            "を" to 0.15,  // 対象格
            "に" to 0.15,  // 場所・時間
            "が" to 0.12,  // 主格
            "で" to 0.10,  // 場所・手段
            "と" to 0.08,  // 並列・引用
            "は" to 0.05,  // 主題
            "へ" to 0.03,  // 方向
            "から" to 0.02  // 起点
        )

        /**
         * 助詞脱落のパターン
         * 名詞の連続など、助詞が期待される箇所
         */
        private data class ParticleMissingPattern(
            val condition: (Token, Token?) -> Boolean,
            val suggestedParticles: List<String>,
            val reason: String
        )

        private val missingPatterns = listOf(
            // パターン1: 名詞 + 名詞（「の」が脱落）
            ParticleMissingPattern(
                condition = { token, next ->
                    token.partOfSpeechLevel1 == "名詞" &&
                    next?.partOfSpeechLevel1 == "名詞"
                },
                suggestedParticles = listOf("の", "と", "が"),
                reason = "名詞連続（所有・並列）"
            ),

            // パターン2: 名詞 + 動詞（格助詞が脱落）
            ParticleMissingPattern(
                condition = { token, next ->
                    token.partOfSpeechLevel1 == "名詞" &&
                    next?.partOfSpeechLevel1 == "動詞"
                },
                suggestedParticles = listOf("を", "が", "に"),
                reason = "名詞＋動詞（格助詞）"
            ),

            // パターン3: 名詞 + 形容詞（「が」「は」が脱落）
            ParticleMissingPattern(
                condition = { token, next ->
                    token.partOfSpeechLevel1 == "名詞" &&
                    next?.partOfSpeechLevel1 == "形容詞"
                },
                suggestedParticles = listOf("が", "は", "も"),
                reason = "名詞＋形容詞（主格）"
            ),

            // パターン4: 名詞 + 助動詞（「が」「は」が脱落）
            ParticleMissingPattern(
                condition = { token, next ->
                    token.partOfSpeechLevel1 == "名詞" &&
                    next?.partOfSpeechLevel1 == "助動詞"
                },
                suggestedParticles = listOf("が", "は", "も"),
                reason = "名詞＋助動詞（主格）"
            )
        )
    }

    /**
     * 助詞脱落を検出して補正候補を提案
     */
    fun detectAndSuggest(text: String): ParticleCorrectionResult {
        val tokens = unidicTokenizer.tokenize(text)
        val suggestions = mutableListOf<ParticleSuggestion>()

        Log.d(TAG, "[Phase3] Analyzing ${tokens.size} tokens for particle missing")

        tokens.forEachIndexed { index, token ->
            val nextToken = tokens.getOrNull(index + 1)

            // 助詞脱落パターンをチェック
            missingPatterns.forEach { pattern ->
                if (pattern.condition(token, nextToken)) {
                    val position = calculatePosition(tokens, index, text)
                    val context = extractContext(tokens, index)

                    // 最適な助詞を選択
                    val bestParticle = selectBestParticle(
                        token,
                        nextToken,
                        pattern.suggestedParticles,
                        context
                    )

                    if (bestParticle != null) {
                        suggestions.add(
                            ParticleSuggestion(
                                position = position,
                                afterWord = token.surface,
                                beforeWord = nextToken?.surface ?: "",
                                suggestedParticle = bestParticle.particle,
                                confidence = bestParticle.confidence,
                                reason = pattern.reason,
                                context = context
                            )
                        )

                        Log.d(TAG, "[Phase3] Particle missing: '${token.surface}【${bestParticle.particle}】${nextToken?.surface}' (conf=${String.format("%.2f", bestParticle.confidence)}, ${pattern.reason})")
                    }
                }
            }
        }

        return ParticleCorrectionResult(
            originalText = text,
            suggestions = suggestions,
            confidence = calculateOverallConfidence(suggestions)
        )
    }

    /**
     * 文脈に基づいて最適な助詞を選択
     */
    private fun selectBestParticle(
        token: Token,
        nextToken: Token?,
        candidates: List<String>,
        context: TokenContext
    ): ScoredParticle? {
        if (nextToken == null) return null

        val scores = candidates.map { particle ->
            var score = 0.0

            // 1. 基本頻度スコア
            val frequencyScore = commonParticles.find { it.first == particle }?.second ?: 0.05
            score += frequencyScore * 0.4

            // 2. 品詞適合スコア
            val posScore = scoreByPartOfSpeech(particle, token, nextToken)
            score += posScore * 0.3

            // 3. 文脈適合スコア
            val contextScore = scoreByContext(particle, context)
            score += contextScore * 0.2

            // 4. セマンティックスコア（意味的妥当性）
            val semanticScore = scoreBySemantics(particle, token, nextToken)
            score += semanticScore * 0.1

            ScoredParticle(particle, score.coerceIn(0.0, 1.0))
        }

        return scores.maxByOrNull { it.confidence }?.takeIf { it.confidence > 0.4 }
    }

    /**
     * 品詞に基づくスコアリング
     */
    private fun scoreByPartOfSpeech(particle: String, token: Token, nextToken: Token): Double {
        return when (particle) {
            "の" -> when (nextToken.partOfSpeechLevel1) {
                "名詞" -> 0.9  // 名詞の名詞（最適）
                "動詞", "形容詞" -> 0.5  // 可能だが頻度は低い
                else -> 0.3
            }
            "を" -> when (nextToken.partOfSpeechLevel1) {
                "動詞" -> 0.9  // 名詞を動詞（最適）
                else -> 0.2
            }
            "が" -> when (nextToken.partOfSpeechLevel1) {
                "動詞", "形容詞", "助動詞" -> 0.8  // 主格（適切）
                else -> 0.3
            }
            "に" -> when (nextToken.partOfSpeechLevel1) {
                "動詞", "形容詞" -> 0.7  // 場所・時間（適切）
                else -> 0.4
            }
            "で" -> when (nextToken.partOfSpeechLevel1) {
                "動詞", "形容詞" -> 0.7  // 手段・場所（適切）
                else -> 0.3
            }
            "と" -> 0.6  // 並列・引用（比較的汎用）
            "は" -> when (nextToken.partOfSpeechLevel1) {
                "動詞", "形容詞", "名詞" -> 0.7  // 主題（広範）
                else -> 0.4
            }
            else -> 0.5
        }
    }

    /**
     * 文脈に基づくスコアリング
     */
    private fun scoreByContext(particle: String, context: TokenContext): Double {
        var score = 0.5  // ベーススコア

        // 前後の助詞パターンを考慮
        if (context.prevParticle != null) {
            // 同じ助詞の連続を避ける
            if (context.prevParticle == particle) {
                score *= 0.5
            }

            // 特定の助詞の後に来やすいパターン
            if (context.prevParticle == "は" && particle in listOf("の", "が")) {
                score *= 1.2
            }
        }

        // ジャンル特有のパターン（経済学的表現）
        if (context.hasEconomicTerms && particle == "の") {
            score *= 1.1  // 経済学では「の」が多用される
        }

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * 意味的妥当性スコアリング
     */
    private fun scoreBySemantics(particle: String, token: Token, nextToken: Token): Double {
        val tokenSurface = token.surface
        val nextSurface = nextToken.surface

        // 専門用語パターン（経済学・法則など）
        if (particle == "の") {
            if (tokenSurface.endsWith("学") || nextSurface.endsWith("法則") ||
                tokenSurface == "需要" || tokenSurface == "供給") {
                return 0.9  // 「需要の法則」など
            }
        }

        // 動作の対象
        if (particle == "を") {
            if (nextToken.partOfSpeechLevel1 == "動詞" &&
                (nextSurface.contains("する") || nextSurface.contains("行う"))) {
                return 0.8  // 「分析を行う」など
            }
        }

        return 0.5  // デフォルト
    }

    /**
     * テキスト内の位置を計算
     */
    private fun calculatePosition(tokens: List<Token>, index: Int, originalText: String): Int {
        var pos = 0
        for (i in 0 until index) {
            pos += tokens[i].surface.length
        }
        return pos + tokens[index].surface.length  // 助詞を挿入する位置（単語の後）
    }

    /**
     * 文脈情報を抽出
     */
    private fun extractContext(tokens: List<Token>, index: Int): TokenContext {
        val prevToken = tokens.getOrNull(index - 1)
        val prevParticle = tokens.take(index).lastOrNull { it.partOfSpeechLevel1 == "助詞" }?.surface
        val nextToken = tokens.getOrNull(index + 1)

        // 経済学用語を含むか
        val economicTerms = listOf("需要", "供給", "価格", "市場", "法則", "経済", "財", "生産", "消費")
        val hasEconomicTerms = tokens.any { token ->
            economicTerms.any { term -> token.surface.contains(term) }
        }

        return TokenContext(
            prevToken = prevToken,
            prevParticle = prevParticle,
            nextToken = nextToken,
            hasEconomicTerms = hasEconomicTerms
        )
    }

    /**
     * 全体の信頼度を計算
     */
    private fun calculateOverallConfidence(suggestions: List<ParticleSuggestion>): Double {
        if (suggestions.isEmpty()) return 0.0
        return suggestions.map { it.confidence }.average()
    }

    /**
     * 助詞補完を適用
     */
    fun applyCorrections(result: ParticleCorrectionResult, minConfidence: Double = 0.6): String {
        if (result.suggestions.isEmpty()) {
            return result.originalText
        }

        var correctedText = result.originalText
        var offset = 0  // 挿入によるオフセット

        // 信頼度の高い提案のみを適用（位置順にソート）
        result.suggestions
            .filter { it.confidence >= minConfidence }
            .sortedBy { it.position }
            .forEach { suggestion ->
                val insertPos = suggestion.position + offset

                if (insertPos <= correctedText.length) {
                    correctedText = correctedText.substring(0, insertPos) +
                                   suggestion.suggestedParticle +
                                   correctedText.substring(insertPos)
                    offset += suggestion.suggestedParticle.length

                    Log.d(TAG, "[Phase3] Applied: position=${insertPos}, particle='${suggestion.suggestedParticle}', conf=${String.format("%.2f", suggestion.confidence)}")
                }
            }

        Log.d(TAG, "[Phase3] Corrections applied: ${result.suggestions.filter { it.confidence >= minConfidence }.size}/${result.suggestions.size}")
        return correctedText
    }

    /**
     * 助詞提案のデータクラス
     */
    data class ParticleSuggestion(
        val position: Int,                // 挿入位置
        val afterWord: String,            // 直前の単語
        val beforeWord: String,           // 直後の単語
        val suggestedParticle: String,    // 提案する助詞
        val confidence: Double,           // 信頼度 (0.0-1.0)
        val reason: String,               // 理由
        val context: TokenContext         // 文脈情報
    )

    /**
     * 補正結果
     */
    data class ParticleCorrectionResult(
        val originalText: String,
        val suggestions: List<ParticleSuggestion>,
        val confidence: Double
    )

    /**
     * スコア付き助詞
     */
    private data class ScoredParticle(
        val particle: String,
        val confidence: Double
    )

    /**
     * 文脈情報
     */
    data class TokenContext(
        val prevToken: Token?,
        val prevParticle: String?,
        val nextToken: Token?,
        val hasEconomicTerms: Boolean
    )
}
