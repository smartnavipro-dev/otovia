package com.otovia.app.ocr

import android.util.Log
import com.google.mlkit.vision.text.Text

/**
 * Phase 3: 信頼度ベース候補選択 (v1.0.33)
 * OCR信頼度スコアを使用して低信頼度テキストを検出・補正
 */
class ConfidenceAnalyzer {

    companion object {
        private const val TAG = "Otovia_ConfAnalyzer"
        private const val LOW_CONFIDENCE_THRESHOLD = 0.7f

        /**
         * 視覚的に類似した文字のマッピング（Phase 2と共通）
         */
        private val visuallySimilarChars = mapOf(
            '需' to listOf('講', '書', '霜', '艦'),
            '価' to listOf('再', '洒', '偏', '海'),
            '値' to listOf('植', '催'),
            '効' to listOf('祝', '勅', '勃'),
            '果' to listOf('歌', '呆'),
            '供' to listOf('共'),
            '給' to listOf('靖', '絵'),
            '済' to listOf('演', '潜'),
            '格' to listOf('将'),
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
            'ポ' to listOf('ボ'),
            'ボ' to listOf('ポ')
        )

        // 形態素解析器の遅延初期化
        private val morphAnalyzer: MorphologicalAnalyzer by lazy {
            MorphologicalAnalyzer()
        }
    }

    /**
     * OCR結果から低信頼度要素を抽出
     */
    fun extractLowConfidenceElements(ocrResult: Text): List<LowConfidenceElement> {
        val lowConfElements = mutableListOf<LowConfidenceElement>()

        ocrResult.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                // Debug: Check what elements look like
                Log.d(TAG, "[Phase3-Debug] Line has ${line.elements.size} elements")
                line.elements.forEachIndexed { idx, element ->
                    val confidence = element.confidence ?: 1.0f
                    Log.d(TAG, "[Phase3-Debug]   Element[$idx]: '${element.text}' (conf=${String.format("%.2f", confidence)}, len=${element.text.length})")

                    if (confidence < LOW_CONFIDENCE_THRESHOLD) {
                        val hasSimilarChars = element.text.any { char ->
                            visuallySimilarChars.containsKey(char)
                        }

                        if (hasSimilarChars) {
                            // For long elements (likely multi-word), extract individual words
                            if (element.text.length > 10) {
                                Log.d(TAG, "[Phase3] Element too long (${element.text.length} chars), skipping multi-word element")
                            } else {
                                lowConfElements.add(
                                    LowConfidenceElement(
                                        text = element.text,
                                        confidence = confidence,
                                        boundingBox = element.boundingBox
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        if (lowConfElements.isNotEmpty()) {
            Log.d(TAG, "[Phase3] Found ${lowConfElements.size} low-confidence elements (threshold: $LOW_CONFIDENCE_THRESHOLD)")
        }

        return lowConfElements
    }

    /**
     * 低信頼度要素に対して候補を生成
     */
    fun generateCandidatesForLowConfidence(
        element: LowConfidenceElement,
        context: String
    ): List<CorrectionCandidate> {
        val candidates = mutableListOf<CorrectionCandidate>()
        val original = element.text

        // 各文字について類似文字の候補を生成
        original.forEachIndexed { index, char ->
            val similarChars = visuallySimilarChars[char]
            if (similarChars != null) {
                similarChars.forEach { similarChar ->
                    val candidate = original.replaceRange(index, index + 1, similarChar.toString())
                    if (candidate != original) {
                        val score = evaluateCandidate(candidate, context, element.confidence)
                        if (score > 0.5) {
                            candidates.add(
                                CorrectionCandidate(
                                    original = original,
                                    candidate = candidate,
                                    score = score,
                                    reason = "Similar char replacement: $char→$similarChar"
                                )
                            )
                        }
                    }
                }
            }
        }

        // スコアでソート
        return candidates.sortedByDescending { it.score }.take(3)
    }

    /**
     * 候補のスコアを評価
     */
    private fun evaluateCandidate(candidate: String, context: String, originalConfidence: Float): Double {
        var score = 0.0

        // 1. 形態素解析で単語として有効かチェック
        val isValidWord = try {
            val tokens = morphAnalyzer.tokenize(candidate)
            tokens.size == 1 && (tokens[0].partOfSpeechLevel1 in listOf("名詞", "動詞", "形容詞"))
        } catch (e: Exception) {
            false
        }

        if (isValidWord) {
            score += 0.6
        }

        // 2. 文脈内での出現頻度（簡易版）
        val occurrencesInContext = context.split(candidate).size - 1
        if (occurrencesInContext > 0) {
            score += 0.2
        }

        // 3. 元の信頼度が低いほど候補の価値が高い
        score += (1.0 - originalConfidence) * 0.2

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * テキスト全体に対して信頼度ベースの補正を適用
     */
    fun applyConfidenceBasedCorrection(
        text: String,
        lowConfElements: List<LowConfidenceElement>
    ): Pair<String, List<String>> {
        var result = text
        val corrections = mutableListOf<String>()

        lowConfElements.forEach { element ->
            Log.d(TAG, "[Phase3] Processing low-conf element: '${element.text}' (conf=${String.format("%.2f", element.confidence)})")

            val candidates = generateCandidatesForLowConfidence(element, text)

            Log.d(TAG, "[Phase3] Generated ${candidates.size} candidates for '${element.text}'")
            candidates.take(3).forEach { cand ->
                Log.d(TAG, "[Phase3]   - ${cand.candidate} (score=${String.format("%.2f", cand.score)}, reason=${cand.reason})")
            }

            if (candidates.isNotEmpty()) {
                val bestCandidate = candidates.first()
                if (result.contains(element.text)) {
                    result = result.replace(element.text, bestCandidate.candidate)
                    corrections.add("${element.text}→${bestCandidate.candidate}(conf:${String.format("%.2f", element.confidence)},score:${String.format("%.2f", bestCandidate.score)})")
                    Log.d(TAG, "[Phase3] Applied correction: ${element.text}→${bestCandidate.candidate}")
                } else {
                    Log.d(TAG, "[Phase3] Text '${element.text}' not found in result")
                }
            } else {
                Log.d(TAG, "[Phase3] No valid candidates generated for '${element.text}'")
            }
        }

        return Pair(result, corrections)
    }

    /**
     * 低信頼度要素のデータクラス
     */
    data class LowConfidenceElement(
        val text: String,
        val confidence: Float,
        val boundingBox: android.graphics.Rect?
    )

    /**
     * 補正候補のデータクラス
     */
    data class CorrectionCandidate(
        val original: String,
        val candidate: String,
        val score: Double,
        val reason: String
    )
}
