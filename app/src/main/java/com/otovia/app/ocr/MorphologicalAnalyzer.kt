package com.otovia.app.ocr

import android.util.Log
import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Phase 2 (v1.0.34): 文脈ベース形態素解析補正
 * 未知語や品詞不整合を検出し、前後の文脈を考慮して最適な候補を選択
 */
class MorphologicalAnalyzer {

    companion object {
        private const val TAG = "Otovia_MorphAnalyzer"

        // 形態素解析器（シングルトン）
        private val tokenizer: Tokenizer by lazy {
            Log.d(TAG, "[Phase2] Initializing Kuromoji tokenizer...")
            val startTime = System.currentTimeMillis()
            val t = Tokenizer()
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "[Phase2] Kuromoji initialized in ${duration}ms")
            t
        }

        /**
         * 視覚的に類似した文字のマッピング（双方向）
         * v1.0.36: 実機テスト結果に基づき拡張
         */
        private val visuallySimilarChars = mapOf(
            '需' to listOf('講', '書', '霜', '艦', '能'),
            '能' to listOf('需'),  // v1.0.36追加
            '価' to listOf('再', '洒', '偏', '海', '済', '梅', '恒'),
            '梅' to listOf('価'),  // v1.0.36追加
            '恒' to listOf('価'),  // v1.0.36追加
            '値' to listOf('植', '催'),
            '効' to listOf('祝', '勅', '勃'),
            '果' to listOf('歌', '呆'),
            '供' to listOf('共'),
            '給' to listOf('靖', '絵'),
            '済' to listOf('演', '潜', '雑', '価'),
            '格' to listOf('将', '済', '終'),
            '終' to listOf('格'),  // v1.0.36追加
            '土' to listOf('士'),
            '地' to listOf('坪'),
            '狭' to listOf('挟'),
            '弾' to listOf('無'),
            '期' to listOf('舞'),
            '影' to listOf('絵'),
            '減' to listOf('械'),
            '捨' to listOf('拾'),
            '間' to listOf('問'),
            '問' to listOf('間'),
            '育' to listOf('資'),
            '質' to listOf('資'),
            '資' to listOf('育', '質'),
            '市' to listOf('斉'),
            '斉' to listOf('市'),
            '場' to listOf('堵'),
            '堵' to listOf('場'),
            '頼' to listOf('類'),
            '瀬' to listOf('類'),
            '類' to listOf('頼', '瀬'),
            '法' to listOf('洪'),
            '洪' to listOf('法'),
            '則' to listOf('測'),
            '測' to listOf('則'),
            '経' to listOf('雑', '稲'),
            '雑' to listOf('経', '済'),
            '稲' to listOf('経'),
            '学' to listOf('単'),
            '割' to listOf('新', '都', '刻'),  // v1.0.36追加
            '新' to listOf('割'),  // v1.0.36追加
            '都' to listOf('割'),  // v1.0.36追加
            '象' to listOf('豪', '家'),  // v1.0.36追加
            '豪' to listOf('象'),  // v1.0.36追加
            '方' to listOf('万'),  // v1.0.36追加
            '万' to listOf('方'),  // v1.0.36追加
            'ポ' to listOf('ボ'),
            'ボ' to listOf('ポ')
        )
    }

    /**
     * テキストをトークン化する
     */
    fun tokenize(text: String): List<Token> {
        return tokenizer.tokenize(text)
    }

    /**
     * 文脈ベース形態素解析補正を適用
     */
    fun applyContextualCorrection(text: String): Pair<String, List<String>> {
        val tokens = tokenizer.tokenize(text)
        var result = text
        val corrections = mutableListOf<String>()

        Log.d(TAG, "[Phase2] Analyzing ${tokens.size} tokens")

        tokens.forEachIndexed { index, token ->
            // 未知語や不自然なトークンを検出
            if (isLikelyMisrecognition(token, tokens, index)) {
                val prevToken = tokens.getOrNull(index - 1)
                val nextToken = tokens.getOrNull(index + 1)

                Log.d(TAG, "[Phase2] Suspicious token: '${token.surface}' (pos=${token.partOfSpeechLevel1}, reading=${token.reading})")

                // 文脈ベース候補生成
                val candidates = generateContextualCandidates(
                    token.surface,
                    prevToken?.surface,
                    nextToken?.surface
                )

                if (candidates.isNotEmpty()) {
                    val best = candidates.first()
                    Log.d(TAG, "[Phase2] Best candidate: '${best.candidate}' (score=${String.format("%.2f", best.score)})")

                    if (best.score > 0.6 && result.contains(token.surface)) {
                        result = result.replaceFirst(token.surface, best.candidate)
                        corrections.add("${token.surface}→${best.candidate}(score=${String.format("%.2f", best.score)})")
                    }
                }
            }
        }

        if (corrections.isNotEmpty()) {
            Log.d(TAG, "[Phase2] Applied ${corrections.size} contextual corrections")
        }

        return Pair(result, corrections)
    }

    /**
     * トークンが誤認識の可能性があるかチェック
     */
    private fun isLikelyMisrecognition(token: Token, allTokens: List<Token>, index: Int): Boolean {
        val surface = token.surface
        val pos = token.partOfSpeechLevel1
        val reading = token.reading

        // 1文字や記号はスキップ
        if (surface.length < 2 || !surface.any { it.isLetterOrDigit() }) {
            return false
        }

        // 視覚的類似文字を含むか
        val hasSimilarChars = surface.any { char ->
            visuallySimilarChars.containsKey(char)
        }

        if (!hasSimilarChars) {
            return false
        }

        // 未知語（読みがない）
        if (reading == "*" || reading == null) {
            Log.d(TAG, "[Phase2] Unknown word detected: '$surface' (no reading)")
            return true
        }

        // 名詞・サ変接続だが一般的でない（辞書にあっても怪しい）
        if (pos == "名詞" && token.partOfSpeechLevel2 == "サ変接続") {
            // 経済学用語っぽい形（○○学、○○則など）
            if (surface.endsWith("学") || surface.endsWith("則") || surface.endsWith("済")) {
                Log.d(TAG, "[Phase2] Suspicious academic term: '$surface'")
                return true
            }
        }

        return false
    }

    /**
     * 文脈を考慮して候補を生成
     */
    private fun generateContextualCandidates(
        word: String,
        prevWord: String?,
        nextWord: String?
    ): List<ContextualCandidate> {
        val candidates = mutableListOf<ContextualCandidate>()

        // 各文字について視覚的類似文字で置換
        word.forEachIndexed { charIndex, char ->
            visuallySimilarChars[char]?.forEach { similarChar ->
                val candidate = word.replaceRange(charIndex, charIndex + 1, similarChar.toString())

                if (candidate != word) {
                    // 候補が有効な単語かチェック
                    val tokens = tokenizer.tokenize(candidate)
                    if (tokens.size == 1 && tokens[0].surface == candidate) {
                        val token = tokens[0]

                        // 品詞が適切か
                        if (token.partOfSpeechLevel1 in listOf("名詞", "動詞", "形容詞", "副詞")) {
                            val score = scoreInContext(candidate, prevWord, nextWord, token)

                            if (score > 0.0) {
                                candidates.add(
                                    ContextualCandidate(
                                        original = word,
                                        candidate = candidate,
                                        score = score,
                                        reason = "$char→$similarChar (pos=${token.partOfSpeechLevel1})"
                                    )
                                )
                                Log.d(TAG, "[Phase2]   Candidate: '$candidate' (score=${String.format("%.2f", score)}, $char→$similarChar)")
                            }
                        }
                    }
                }
            }
        }

        return candidates.sortedByDescending { it.score }.take(3)
    }

    /**
     * 文脈を考慮してスコアリング
     */
    private fun scoreInContext(
        word: String,
        prevWord: String?,
        nextWord: String?,
        token: Token
    ): Double {
        var score = 0.0

        // 1. 基本スコア: 辞書に存在する有効な単語
        score += 0.4

        // 2. 品詞スコア
        when (token.partOfSpeechLevel1) {
            "名詞" -> score += 0.3
            "動詞" -> score += 0.2
            "形容詞" -> score += 0.2
            else -> score += 0.1
        }

        // 3. 読みがある（未知語でない）
        if (token.reading != "*" && token.reading != null) {
            score += 0.2
        }

        // 4. 文脈との相性（簡易版）
        if (prevWord != null || nextWord != null) {
            val pos = token.partOfSpeechLevel1

            // 前後が助詞なら名詞が自然
            if (pos == "名詞") {
                val prevTokens = prevWord?.let { tokenizer.tokenize(it) }
                val nextTokens = nextWord?.let { tokenizer.tokenize(it) }

                if (prevTokens?.lastOrNull()?.partOfSpeechLevel1 == "助詞" ||
                    nextTokens?.firstOrNull()?.partOfSpeechLevel1 == "助詞") {
                    score += 0.1
                }
            }
        }

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * 文脈候補のデータクラス
     */
    data class ContextualCandidate(
        val original: String,
        val candidate: String,
        val score: Double,
        val reason: String
    )
}
