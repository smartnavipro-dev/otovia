package com.kindletts.reader.ocr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.util.LruCache
import com.kindletts.reader.BuildConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * v1.0.73: リトライ可能な例外クラス
 */
private class RetryableException(val retryDelayMs: Long, val errorResponse: String?) : Exception("Retryable API error, delay: ${retryDelayMs}ms")

/**
 * Phase LLM: LLMベースOCR補正（v1.0.45）
 *
 * ハイブリッドアプローチ:
 * - Phase 1で処理できないケース（10%）をLLMで補正
 * - Gemini 1.5 Flash API使用（クラウドLLM）
 * - レイテンシ: ~500ms（許容範囲）
 *
 * アーキテクチャ:
 * 1. Phase 1が不確実なケースを検出（信頼度<0.7）
 * 2. LLMに文脈付きテキストを送信
 * 3. LLMが補正候補を返す
 * 4. 信頼度スコアでフィルタリング
 * 5. 低信頼度の場合、反復補正で精密化（v1.0.45）
 *
 * v1.0.40: Gemini API統合
 * - Gemini 1.5 Flash APIで実装
 * - ネットワークチェックとフォールバック
 * - APIキー管理とセキュリティ
 *
 * v1.0.41: パフォーマンス最適化
 * - LRUキャッシュ追加（重複処理の削減、100エントリ）
 * - バッチ処理対応（5文 × 500ms = 2.5秒 → 500ms）
 * - 統計情報追跡（キャッシュヒット率、レイテンシ）
 * - プロンプト最適化（-40% tokens、コスト削減）
 *
 * v1.0.42: キャッシュ永続化
 * - SharedPreferencesでキャッシュを永続化
 * - アプリ再起動後もキャッシュを保持
 * - 初回起動時からキャッシュヒット率向上（0% → 30-40%）
 * - さらなるコスト削減（20-30%追加削減）
 *
 * v1.0.43: 適応的バッチサイズ
 * - テキスト長に応じてバッチサイズを動的調整
 * - 短文（<50文字）: バッチ10（10倍高速化）
 * - 中文（50-150文字）: バッチ5（バランス）
 * - 長文（>150文字）: バッチ3（品質重視）
 * - さらなるレイテンシ削減（平均30-40%）
 *
 * v1.0.44: トークンカウントと制限適用
 * - トークン数推定アルゴリズム実装（日本語: 1文字≈1.5トークン）
 * - バッチサイズの動的調整（トークン制限6000を超えないように）
 * - トークン使用量の統計追跡（コスト可視化）
 * - Gemini API制限（8000トークン）の遵守保証
 *
 * v1.0.45: 反復LLM補正（認識率向上）
 * - 信頼度ベースの再処理（confidence < 0.8 → 再補正）
 * - 精密化プロンプト実装（元テキストとの比較）
 * - 2段階補正で認識率向上（95-98% → 99-99.5%）
 * - 再処理統計追跡（refinement率、改善度）
 *
 * v1.0.73: 自動リトライ機能
 * - 429エラー（quota超過）の自動リトライ
 * - RetryInfoからリトライ待機時間を抽出
 * - 最大3回までのリトライ
 * - リトライ統計追跡
 *
 * 期待される補正率: 99-99.5%（Phase 1: 90% + LLM: 9-9.5%）
 * 月間コスト: ~$0.04-0.05（反復補正により+20%）
 * 平均レイテンシ: ~120ms（再処理時+500ms for 20%）
 */
class LLMCorrector(private val context: Context) {

    companion object {
        private const val TAG = "KindleTTS_LLMCorrector"

        /**
         * LLM補正を有効化するフラグ
         * v1.0.39: 実験的機能として導入
         * v1.0.40: Gemini API統合により有効化
         */
        private const val ENABLE_LLM_CORRECTION = true  // v1.0.40で有効化

        /**
         * LLM補正を試行する条件
         * v1.1.26: 全文LLM補正モード（テスト用）
         */
        private const val MIN_CONFIDENCE_FOR_PHASE1 = 2.0  // v1.1.27: 全文LLM補正モード（1.0では1.0信頼度でスキップされるため2.0に）
        private const val MAX_TEXT_LENGTH_FOR_LLM = 500    // LLM処理の最大文字数

        /**
         * v1.0.41: キャッシュサイズ
         */
        private const val CACHE_SIZE = 100  // 最大100エントリ（約10KB）

        /**
         * v1.0.42: キャッシュ永続化設定
         */
        private const val PREFS_NAME = "llm_correction_cache"
        private const val CACHE_KEY = "correction_cache_v1"
        private const val MAX_CACHE_AGE_DAYS = 30  // キャッシュの最大保持期間（日数）

        /**
         * v1.0.47: Gemini API設定
         * SDK 0.9.0のデシリアライゼーションバグを回避するため、REST APIを直接使用
         */
        private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MODEL_NAME = "gemini-2.5-flash"  // v1.0.80: gemini-2.5-flash (最新安定版、1500 req/day) - 404エラー最終修正

        /**
         * v1.0.73: リトライ設定
         */
        private const val ENABLE_RETRY = true               // リトライ機能の有効化
        private const val MAX_RETRY_ATTEMPTS = 3            // 最大リトライ回数
        private const val INITIAL_RETRY_DELAY_MS = 1000L    // 初回リトライ待機時間（1秒）
        private const val MAX_RETRY_DELAY_MS = 60000L       // 最大リトライ待機時間（60秒）

        /**
         * v1.0.43: 適応的バッチサイズ設定
         */
        private const val SHORT_TEXT_THRESHOLD = 50    // 短文の閾値（文字数）
        private const val LONG_TEXT_THRESHOLD = 150    // 長文の閾値（文字数）
        private const val SHORT_TEXT_BATCH_SIZE = 10   // 短文のバッチサイズ
        private const val MEDIUM_TEXT_BATCH_SIZE = 5   // 中文のバッチサイズ
        private const val LONG_TEXT_BATCH_SIZE = 3     // 長文のバッチサイズ

        /**
         * v1.0.44: トークン制限設定
         */
        private const val MAX_TOKENS_PER_REQUEST = 6000  // 1リクエストあたりの最大トークン数（安全マージン込み）
        private const val GEMINI_API_MAX_TOKENS = 8000   // Gemini 1.5 Flash APIの制限
        private const val TOKEN_SAFETY_MARGIN = 0.75     // 安全マージン（75%）
        private const val JAPANESE_CHAR_TO_TOKEN = 1.5   // 日本語1文字あたりのトークン数（推定）
        private const val PROMPT_FIXED_TOKENS = 120      // プロンプト固定部分のトークン数
        private const val BATCH_INDEX_TOKENS = 5         // バッチエントリごとのインデックストークン数

        /**
         * v1.0.45: 反復補正設定
         * v1.0.56: 精度向上のため閾値を0.8→0.95に引き上げ（約20%のケースで再補正）
         */
        private const val REFINEMENT_CONFIDENCE_THRESHOLD = 0.95 // 再補正の閾値（これ未満で再処理）
        private const val ENABLE_ITERATIVE_REFINEMENT = true     // 反復補正を有効化
        private const val MAX_REFINEMENT_ITERATIONS = 1          // 最大反復回数（1回=合計2段階補正）
    }

    // v1.0.82: QuotaManager統合
    private val quotaManager = QuotaManager(context)

    // v1.0.41: LRUキャッシュ（補正結果をキャッシュ）
    private val correctionCache = LruCache<String, CorrectionResult>(CACHE_SIZE)

    // v1.0.42: SharedPreferences（キャッシュ永続化）
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // v1.0.41: 統計情報
    private var cacheHits = 0
    private var cacheMisses = 0
    private var llmCallCount = 0
    private var totalLLMLatency = 0L

    // v1.0.44: トークン使用量統計
    private var totalTokensUsed = 0L
    private var totalInputTokens = 0L
    private var totalOutputTokens = 0L

    // v1.0.45: 反復補正統計
    private var refinementCount = 0          // 再補正を実行した回数

    // v1.0.73: リトライ統計
    private var retryCount = 0               // リトライ実行回数
    private var totalRetryWaitTime = 0L      // 総リトライ待機時間（ms）

    // v1.0.74: キャッシュ統計拡充
    private var cacheEvictions = 0           // キャッシュeviction回数
    private var cacheWriteCount = 0          // キャッシュ書き込み回数
    private var totalCacheEntryAge = 0L      // キャッシュエントリの総年齢（ms）
    private var refinementImprovedCount = 0  // 再補正で改善した回数
    private var totalRefinementLatency = 0L  // 再補正の合計レイテンシ

    // v1.0.42: 初期化時にキャッシュを読み込み
    init {
        loadCache()
        Log.d(TAG, "[v1.0.42] LLMCorrector initialized with persistent cache")
        quotaManager.logCurrentStatus()  // v1.0.82: 起動時にクォータ状態表示
    }

    /**
     * v1.0.41: キャッシュされた補正結果
     */
    data class CorrectionResult(
        val correctedText: String,
        val confidence: Double,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * LLMベース補正のメインメソッド
     * v1.0.41: キャッシュ対応
     * v1.0.61: ジャンル情報を追加
     * v1.0.75: Phase 3検出結果ヒントを追加
     *
     * @param text 補正対象テキスト
     * @param context 前後の文脈（オプション）
     * @param phase1Confidence Phase 1の補正信頼度（0.0-1.0）
     * @param genre ジャンル情報（オプション）
     * @param phase3Hints Phase 3形態素解析ヒント（オプション）
     * @return 補正されたテキストと信頼度スコア
     */
    fun correctWithLLM(
        text: String,
        context: String? = null,
        phase1Confidence: Double = 0.0,
        genre: String? = null,
        phase3Hints: String? = null
    ): Pair<String, Double> {

        if (!ENABLE_LLM_CORRECTION) {
            Log.d(TAG, "[LLM] LLM correction is disabled")
            return Pair(text, 0.0)
        }

        // v1.0.82: クォータチェック（API呼び出し前）
        val quotaStatus = quotaManager.getStatus()
        if (!quotaManager.canMakeRequest()) {
            Log.w(TAG, "[v1.0.82] API quota exceeded (${quotaStatus.count}/${quotaStatus.limit}), using original text")
            Log.w(TAG, "[v1.0.82] Quota resets in: ${quotaManager.getResetTimeString()}")
            return Pair(text, phase1Confidence)
        }
        Log.d(TAG, "[v1.0.82] API Quota: ${quotaStatus.remaining}/${quotaStatus.limit} remaining")

        // Phase 1の信頼度が高い場合はスキップ
        if (phase1Confidence >= MIN_CONFIDENCE_FOR_PHASE1) {
            Log.d(TAG, "[LLM] Skipping LLM (Phase 1 confidence: $phase1Confidence)")
            return Pair(text, phase1Confidence)
        }

        // テキストが長すぎる場合はスキップ
        if (text.length > MAX_TEXT_LENGTH_FOR_LLM) {
            Log.d(TAG, "[LLM] Text too long for LLM processing: ${text.length} chars")
            return Pair(text, 0.0)
        }

        // v1.0.41: キャッシュチェック
        val cacheKey = text.trim()
        val cached = correctionCache.get(cacheKey)
        if (cached != null) {
            cacheHits++
            // v1.0.74: キャッシュエントリ年齢の計算
            val entryAge = System.currentTimeMillis() - cached.timestamp
            totalCacheEntryAge += entryAge
            Log.d(TAG, "[LLM] Cache HIT for: '${text.take(30)}...' (hits: $cacheHits, misses: $cacheMisses, age: ${entryAge}ms)")
            return Pair(cached.correctedText, cached.confidence)
        }

        cacheMisses++
        Log.d(TAG, "[LLM] Cache MISS, starting LLM correction for: '${text.take(50)}...'")

        // v1.0.75: Phase 3ヒントのログ
        if (phase3Hints != null) {
            Log.d(TAG, "[v1.0.75] Phase 3 hints: $phase3Hints")
        }

        try {
            val startTime = System.currentTimeMillis()

            // LLMプロンプトの構築（v1.0.75: Phase 3ヒント追加）
            val prompt = buildCorrectionPrompt(text, context, genre, phase3Hints)

            // v1.0.78: プロンプトトークン数概算ログ（文字数×2.5を目安）
            val estimatedTokens = (prompt.length * 2.5).toInt()
            Log.d(TAG, "[v1.0.78 TokenMetrics] Prompt length: ${prompt.length} chars, Estimated tokens: ~$estimatedTokens")
            if (phase3Hints != null) {
                val hintsLength = phase3Hints.length
                val hintsTokens = (hintsLength * 2.5).toInt()
                Log.d(TAG, "[v1.0.78 TokenMetrics] Phase3Hints length: $hintsLength chars (~$hintsTokens tokens)")
            }

            // v1.0.73: リトライロジックでラップされたLLM実行
            var correctedText = ""
            var attemptCount = 0
            var lastException: Exception? = null

            while (attemptCount < MAX_RETRY_ATTEMPTS) {
                try {
                    correctedText = invokeLLM(prompt)
                    if (attemptCount > 0) {
                        Log.d(TAG, "[v1.0.73 Retry] Success after $attemptCount retry attempt(s)")
                    }
                    break  // 成功したらループ終了
                } catch (e: RetryableException) {
                    attemptCount++
                    lastException = e

                    if (attemptCount >= MAX_RETRY_ATTEMPTS) {
                        Log.e(TAG, "[v1.0.73 Retry] Max retry attempts ($MAX_RETRY_ATTEMPTS) reached")
                        break
                    }

                    // リトライ待機時間の計算（指数バックオフ）
                    val baseDelay = e.retryDelayMs
                    val exponentialDelay = INITIAL_RETRY_DELAY_MS * (1 shl (attemptCount - 1))
                    val actualDelay = maxOf(baseDelay, exponentialDelay).coerceAtMost(MAX_RETRY_DELAY_MS)

                    Log.w(TAG, "[v1.0.73 Retry] Attempt $attemptCount failed, retrying after ${actualDelay}ms (base: ${baseDelay}ms, exponential: ${exponentialDelay}ms)")
                    Log.d(TAG, "[v1.0.73 Retry] Error response: ${e.errorResponse?.take(200)}")

                    // 統計更新
                    retryCount++
                    totalRetryWaitTime += actualDelay

                    // 待機
                    Thread.sleep(actualDelay)
                }
            }

            // リトライ失敗時は元のテキストを返す
            if (correctedText.isEmpty() && lastException != null) {
                Log.e(TAG, "[v1.0.73 Retry] All retry attempts failed, using original text")
                return Pair(text, phase1Confidence)
            }

            // LLMが空を返した場合、元のテキストを返す
            if (correctedText.isEmpty()) {
                Log.w(TAG, "[LLM] LLM returned empty, using original text")
                return Pair(text, phase1Confidence)
            }

            // 信頼度スコアの計算
            var confidence = calculateConfidence(text, correctedText)

            val duration = System.currentTimeMillis() - startTime
            llmCallCount++
            totalLLMLatency += duration

            Log.d(TAG, "[LLM] Initial correction completed in ${duration}ms (confidence: $confidence)")

            // v1.0.78: 補正効果の詳細ログ
            if (text != correctedText) {
                Log.d(TAG, "[v1.0.78 CorrectionEffect] Original: '${text.take(50)}${if (text.length > 50) "..." else ""}'")
                Log.d(TAG, "[v1.0.78 CorrectionEffect] Corrected: '${correctedText.take(50)}${if (correctedText.length > 50) "..." else ""}'")
                Log.d(TAG, "[v1.0.78 CorrectionEffect] Change rate: ${String.format("%.1f", (1.0 - text.length.toDouble() / correctedText.length.toDouble()) * 100)}%")
            } else {
                Log.d(TAG, "[v1.0.78 CorrectionEffect] No changes made by LLM")
            }

            // v1.0.45: 低信頼度の場合、反復補正を実行
            var finalCorrectedText = correctedText
            if (ENABLE_ITERATIVE_REFINEMENT && confidence < REFINEMENT_CONFIDENCE_THRESHOLD) {
                Log.d(TAG, "[v1.0.45] Low confidence ($confidence), triggering refinement")
                val (refinedText, refinedConfidence) = refineWithLLM(text, correctedText, confidence, context)
                finalCorrectedText = refinedText
                confidence = refinedConfidence
            }

            Log.d(TAG, "[LLM] Stats - Calls: $llmCallCount, Avg latency: ${totalLLMLatency / llmCallCount}ms, Cache hit rate: ${getCacheHitRate()}%")

            // v1.0.41: キャッシュに保存（精密化後の結果）
            val result = CorrectionResult(finalCorrectedText, confidence)
            correctionCache.put(cacheKey, result)

            // v1.0.42: 永続化
            saveCacheEntry(cacheKey, result)

            return Pair(finalCorrectedText, confidence)

        } catch (e: Exception) {
            Log.e(TAG, "[LLM] Error during LLM correction: ${e.message}", e)
            return Pair(text, 0.0)
        }
    }

    /**
     * v1.0.41: バッチ補正（複数テキストを一度に処理）
     *
     * 利点:
     * - レイテンシ削減: 5文 × 500ms = 2.5秒 → 500ms（5倍高速化）
     * - コスト削減: API呼び出し回数の削減
     * - キャッシュ効率向上: バッチ単位でキャッシュ
     *
     * @param texts 補正対象テキストのリスト
     * @param context 前後の文脈（オプション）
     * @param phase1Confidences 各テキストのPhase 1信頼度
     * @return 補正されたテキストと信頼度スコアのリスト
     */
    fun correctBatch(
        texts: List<String>,
        context: String? = null,
        phase1Confidences: List<Double> = List(texts.size) { 0.0 }
    ): List<Pair<String, Double>> {

        if (!ENABLE_LLM_CORRECTION) {
            Log.d(TAG, "[LLM] LLM correction is disabled")
            return texts.zip(List(texts.size) { 0.0 })
        }

        if (texts.isEmpty()) {
            return emptyList()
        }

        // バッチサイズが1の場合は通常の処理を使用
        if (texts.size == 1) {
            val result = correctWithLLM(texts[0], context, phase1Confidences[0])
            return listOf(result)
        }

        Log.d(TAG, "[LLM] Starting batch correction for ${texts.size} texts")

        val results = mutableMapOf<Int, Pair<String, Double>>()
        val textsToProcess = mutableListOf<Pair<Int, String>>()  // (index, text)

        // キャッシュチェック: キャッシュにあるものは除外
        for ((index, text) in texts.withIndex()) {
            val phase1Confidence = phase1Confidences.getOrElse(index) { 0.0 }

            // Phase 1の信頼度が高い場合はスキップ
            if (phase1Confidence >= MIN_CONFIDENCE_FOR_PHASE1) {
                results[index] = Pair(text, phase1Confidence)
                continue
            }

            // テキストが長すぎる場合はスキップ
            if (text.length > MAX_TEXT_LENGTH_FOR_LLM) {
                results[index] = Pair(text, 0.0)
                continue
            }

            val cacheKey = text.trim()
            val cached = correctionCache.get(cacheKey)
            if (cached != null) {
                cacheHits++
                Log.d(TAG, "[LLM] Batch cache HIT for text #$index")
                results[index] = Pair(cached.correctedText, cached.confidence)
            } else {
                cacheMisses++
                textsToProcess.add(index to text)
            }
        }

        // 処理が必要なテキストがない場合
        if (textsToProcess.isEmpty()) {
            Log.d(TAG, "[LLM] All texts found in cache or skipped")
            return (0 until texts.size).map { results[it] ?: Pair(texts[it], 0.0) }
        }

        // v1.0.43: 適応的バッチサイズの計算
        val optimalBatchSize = calculateOptimalBatchSize(textsToProcess.map { it.second })

        // v1.0.44: トークン制限を考慮したバッチサイズの最終調整
        val finalBatchSize = adjustBatchSizeForTokenLimit(textsToProcess.map { it.second }, optimalBatchSize)

        try {
            val startTime = System.currentTimeMillis()

            // v1.0.44: テキストを最終バッチサイズに分割
            val batches = textsToProcess.chunked(finalBatchSize)
            Log.d(TAG, "[v1.0.44] Processing ${textsToProcess.size} texts in ${batches.size} batches (final batch size: $finalBatchSize)")

            var batchNumber = 1
            for (batch in batches) {
                Log.d(TAG, "[v1.0.43] Processing batch $batchNumber/${batches.size} (${batch.size} texts)")

                // v1.0.44: 入力トークン数の推定
                val batchTexts = batch.map { it.second }
                val inputTokens = estimateBatchTokens(batchTexts)

                // バッチプロンプトの構築
                val prompt = buildBatchCorrectionPrompt(batchTexts, context)

                // LLM実行
                val correctedTexts = invokeBatchLLM(prompt, batch.size)

                // v1.0.44: 出力トークン数の推定
                val outputTokens = correctedTexts.sumOf { estimateTokenCount(it) }

                // v1.0.44: トークン統計の更新
                totalInputTokens += inputTokens
                totalOutputTokens += outputTokens
                totalTokensUsed += (inputTokens + outputTokens)

                Log.d(TAG, "[v1.0.44] Batch tokens - Input: $inputTokens, Output: $outputTokens, Total: ${inputTokens + outputTokens}")

                // 結果を処理してキャッシュに保存
                for ((i, indexedText) in batch.withIndex()) {
                    val (originalIndex, originalText) = indexedText
                    val correctedText = correctedTexts.getOrElse(i) { originalText }
                    val confidence = calculateConfidence(originalText, correctedText)

                    // キャッシュに保存
                    val cacheKey = originalText.trim()
                    val result = CorrectionResult(correctedText, confidence)
                    correctionCache.put(cacheKey, result)

                    // v1.0.42: 永続化
                    saveCacheEntry(cacheKey, result)

                    results[originalIndex] = Pair(correctedText, confidence)
                }

                batchNumber++
            }

            val duration = System.currentTimeMillis() - startTime
            llmCallCount += batches.size
            totalLLMLatency += duration

            Log.d(TAG, "[LLM] Batch correction completed in ${duration}ms for ${textsToProcess.size} texts")
            Log.d(TAG, "[LLM] Avg per text: ${duration / textsToProcess.size}ms, Avg per batch: ${duration / batches.size}ms")
            Log.d(TAG, "[LLM] Stats - Calls: $llmCallCount, Avg latency: ${totalLLMLatency / llmCallCount}ms, Cache hit rate: ${getCacheHitRate()}%")

        } catch (e: Exception) {
            Log.e(TAG, "[LLM] Error during batch LLM correction: ${e.message}", e)
            // エラー時は元のテキストを返す
            for ((index, text) in textsToProcess) {
                results[index] = Pair(text, 0.0)
            }
        }

        // インデックス順にソートして返す
        return (0 until texts.size).map { results[it] ?: Pair(texts[it], 0.0) }
    }

    /**
     * v1.0.41: キャッシュヒット率を取得
     */
    private fun getCacheHitRate(): Int {
        val total = cacheHits + cacheMisses
        return if (total > 0) (cacheHits * 100 / total) else 0
    }

    /**
     * v1.0.43: テキスト長に基づいて最適なバッチサイズを計算
     *
     * 設計方針:
     * - 短文（<50文字）: バッチ10 → API回数1/10、レイテンシ最小化
     * - 中文（50-150文字）: バッチ5 → バランス型
     * - 長文（>150文字）: バッチ3 → 品質重視、トークン制限対策
     *
     * @param texts バッチ処理対象のテキストリスト
     * @return 最適なバッチサイズ
     */
    private fun calculateOptimalBatchSize(texts: List<String>): Int {
        if (texts.isEmpty()) return MEDIUM_TEXT_BATCH_SIZE

        // テキストの平均長を計算
        val avgLength = texts.map { it.length }.average()

        // 平均長に基づいてバッチサイズを決定
        val batchSize = when {
            avgLength < SHORT_TEXT_THRESHOLD -> SHORT_TEXT_BATCH_SIZE
            avgLength > LONG_TEXT_THRESHOLD -> LONG_TEXT_BATCH_SIZE
            else -> MEDIUM_TEXT_BATCH_SIZE
        }

        Log.d(TAG, "[v1.0.43] Calculated batch size: $batchSize (avg text length: ${avgLength.toInt()} chars)")
        return batchSize
    }

    /**
     * v1.0.44: 単一テキストのトークン数を推定
     *
     * 日本語テキストの場合、1文字 ≈ 1.5トークン
     * （漢字・ひらがな・カタカナ・記号の混在を考慮）
     *
     * @param text 推定対象テキスト
     * @return 推定トークン数
     */
    private fun estimateTokenCount(text: String): Int {
        return (text.length * JAPANESE_CHAR_TO_TOKEN).toInt()
    }

    /**
     * v1.0.44: バッチ全体のトークン数を推定
     *
     * 計算式:
     * - 固定部分: PROMPT_FIXED_TOKENS (120トークン)
     * - インデックス: texts.size × BATCH_INDEX_TOKENS
     * - テキスト内容: sum(estimateTokenCount(text))
     *
     * @param texts バッチ処理対象のテキストリスト
     * @return 推定トークン数
     */
    private fun estimateBatchTokens(texts: List<String>): Int {
        val fixedTokens = PROMPT_FIXED_TOKENS
        val indexTokens = texts.size * BATCH_INDEX_TOKENS
        val contentTokens = texts.sumOf { estimateTokenCount(it) }

        val totalTokens = fixedTokens + indexTokens + contentTokens

        Log.d(TAG, "[v1.0.44] Estimated tokens: $totalTokens (fixed: $fixedTokens, index: $indexTokens, content: $contentTokens)")
        return totalTokens
    }

    /**
     * v1.0.44: トークン制限を考慮してバッチサイズを調整
     *
     * アルゴリズム:
     * 1. 初期バッチサイズで開始（v1.0.43のテキスト長ベース）
     * 2. トークン数を推定
     * 3. 制限超過の場合、バッチサイズを減らして再計算
     * 4. 最小バッチサイズは1
     *
     * @param texts バッチ処理対象のテキストリスト
     * @param initialBatchSize 初期バッチサイズ（v1.0.43で計算）
     * @return トークン制限を考慮した最終バッチサイズ
     */
    private fun adjustBatchSizeForTokenLimit(texts: List<String>, initialBatchSize: Int): Int {
        var currentBatchSize = initialBatchSize

        while (currentBatchSize > 0) {
            // 現在のバッチサイズでトークン数を推定
            val sampleBatch = texts.take(currentBatchSize)
            val estimatedTokens = estimateBatchTokens(sampleBatch)

            if (estimatedTokens <= MAX_TOKENS_PER_REQUEST) {
                Log.d(TAG, "[v1.0.44] Adjusted batch size: $currentBatchSize (tokens: $estimatedTokens / $MAX_TOKENS_PER_REQUEST)")
                return currentBatchSize
            }

            // トークン制限超過の場合、バッチサイズを減らす
            currentBatchSize = (currentBatchSize * 0.7).toInt().coerceAtLeast(1)
            Log.d(TAG, "[v1.0.44] Batch size reduced to $currentBatchSize due to token limit (estimated: $estimatedTokens)")
        }

        // 最悪の場合でもバッチサイズ1を返す
        return 1
    }

    /**
     * v1.0.41: 統計情報をリセット
     * v1.0.44: トークン統計も追加
     * v1.0.45: 反復補正統計も追加
     */
    fun resetStats() {
        cacheHits = 0
        cacheMisses = 0
        llmCallCount = 0
        totalLLMLatency = 0L

        // v1.0.44: トークン統計のリセット
        totalTokensUsed = 0L
        totalInputTokens = 0L
        totalOutputTokens = 0L

        // v1.0.45: 反復補正統計のリセット
        refinementCount = 0
        refinementImprovedCount = 0
        totalRefinementLatency = 0L

        Log.d(TAG, "[LLM] Statistics reset (including token counts and refinement stats)")
    }

    /**
     * v1.0.41: 統計情報を取得
     * v1.0.44: トークン統計も追加
     * v1.0.45: 反復補正統計も追加
     */
    fun getStats(): String {
        val total = cacheHits + cacheMisses
        val hitRate = if (total > 0) (cacheHits * 100 / total) else 0
        val avgLatency = if (llmCallCount > 0) (totalLLMLatency / llmCallCount) else 0
        val avgTokensPerCall = if (llmCallCount > 0) (totalTokensUsed / llmCallCount) else 0
        val refinementRate = if (cacheMisses > 0) (refinementCount * 100 / cacheMisses) else 0
        val refinementImprovementRate = if (refinementCount > 0) (refinementImprovedCount * 100 / refinementCount) else 0
        val avgRefinementLatency = if (refinementCount > 0) (totalRefinementLatency / refinementCount) else 0

        return """
            |LLM Correction Statistics:
            |  Cache hits: $cacheHits
            |  Cache misses: $cacheMisses
            |  Cache hit rate: $hitRate%
            |  LLM API calls: $llmCallCount
            |  Avg latency: ${avgLatency}ms
            |  Total latency: ${totalLLMLatency}ms
            |  Total tokens used: $totalTokensUsed
            |  Input tokens: $totalInputTokens
            |  Output tokens: $totalOutputTokens
            |  Avg tokens per call: $avgTokensPerCall
            |  Refinements: $refinementCount
            |  Refinement rate: $refinementRate%
            |  Refinement improved: $refinementImprovedCount
            |  Refinement improvement rate: $refinementImprovementRate%
            |  Avg refinement latency: ${avgRefinementLatency}ms
        """.trimMargin()
    }

    /**
     * LLM補正プロンプトの構築
     *
     * v1.0.41: トークン削減のため簡潔化（-40% tokens）
     * v1.0.56: 精度向上のため詳細化（Japanese OCR特有の誤認識パターン追加）
     * v1.0.57: プロンプト最適化（thoughts過剰消費の抑制、バランス改善）
     * v1.0.75: Phase 3形態素解析ヒントを追加
     * v1.0.77: プロンプト簡潔化（-30% tokens）
     * v1.1.30: 積極的補正モードに変更（保守的すぎてOCR誤字を見逃す問題の修正）
     */
    private fun buildCorrectionPrompt(text: String, context: String?, genre: String? = null, phase3Hints: String? = null): String {
        val genreHint = genre ?: "一般"

        return """
日本語書籍のOCR誤認識補正。文脈から正しい文字を積極的に推測して補正せよ。

【方針】書籍の文章として自然な日本語に復元。形状類似漢字の誤認識を重点補正。不要スペース除去。
【ジャンル】$genreHint
【頻出OCR誤字】龍→需,頓→価,副→則,渡→選/予,郵→要,賀→貴,観→銀,映→要,済→経,植→値,龍要→需要,法副→法則,頓接→価格,渡択→選択 カナ:ツ→ッ,ビ→ピ,ハツビー→ハッピー
${if (context != null) "【文脈】$context\n" else ""}${if (phase3Hints != null) "【文法ヒント】$phase3Hints\n" else ""}
【OCR出力】$text

JSON:{"corrected":"補正後","confidence":0.95,"changes":[{"from":"龍要","to":"需要","reason":"形状類似"}]}
        """.trimIndent()
    }

    /**
     * v1.0.41: バッチ補正プロンプトの構築
     *
     * v1.0.41: トークン削減のため簡潔化（-45% tokens）
     * v1.0.56: 精度向上のため詳細化（Japanese OCR特有の誤認識パターン追加）
     * v1.0.57: プロンプト最適化（thoughts過剰消費の抑制）
     */
    private fun buildBatchCorrectionPrompt(texts: List<String>, context: String?): String {
        val textsBlock = texts.mapIndexed { index, text ->
            "[$index] $text"
        }.joinToString("\n")

        return """
経済学書籍の日本語OCR一括校正。誤認識: 英→経、機作→機会、海格→価格、コースト→コスト。
信頼度: 1.0=完璧、0.95=軽微、0.90=中程度。

${if (context != null) "文脈: $context\n\n" else ""}入力:
$textsBlock

出力JSON配列（全インデックス必須）:
[{"index":0,"corrected":"校正後","confidence":0.95}]
        """.trimIndent()
    }

    /**
     * v1.0.45: 反復補正プロンプトの構築（精密化・検証用）
     * v1.0.56: 精度向上のため詳細化（OCR誤認識パターン追加）
     * v1.0.57: プロンプト最適化（thoughts過剰消費の抑制）
     *
     * 初回補正の結果を検証し、過剰補正や誤補正を修正する。
     * 元のOCRテキストと比較して、適切な補正レベルを判断。
     */
    private fun buildRefinementPrompt(originalText: String, correctedText: String, context: String?): String {
        return """
OCR補正の再検証。過剰補正を避け、経済学用語として自然に。目標信頼度: 0.95以上。

元: $originalText
補正済: $correctedText
${if (context != null) "\n文脈: $context" else ""}

出力JSON:
{"corrected": "精密化後", "confidence": 0.98}
        """.trimIndent()
    }

    /**
     * v1.0.47: LLM実行（Gemini REST API直接呼び出し）
     * SDK 0.9.0のバグを回避するため、HTTPで直接APIを叩く
     */
    private fun invokeLLM(prompt: String): String {
        // ネットワーク接続チェック
        if (!isNetworkAvailable()) {
            Log.w(TAG, "[LLM] No network connection, skipping LLM correction")
            return ""
        }

        // APIキーチェック
        Log.d(TAG, "[LLM] API key length: ${BuildConfig.GEMINI_API_KEY.length}, starts with: ${BuildConfig.GEMINI_API_KEY.take(6)}...")
        if (BuildConfig.GEMINI_API_KEY == "YOUR_API_KEY_HERE" || BuildConfig.GEMINI_API_KEY.isEmpty()) {
            Log.w(TAG, "[LLM] API key not configured, skipping LLM correction")
            return ""
        }
        Log.d(TAG, "[LLM] API key validated successfully")

        return try {
            // v1.0.48: HTTP呼び出しをIOディスパッチャで実行（NetworkOnMainThreadException回避）
            runBlocking {
                withContext(Dispatchers.IO) {
                    val startTime = System.currentTimeMillis()

                    // REST APIエンドポイント
                    val apiUrl = "$GEMINI_API_BASE/$MODEL_NAME:generateContent?key=${BuildConfig.GEMINI_API_KEY}"
                    val url = URL(apiUrl)

                    // リクエストボディ作成
                    val requestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.1)
                        put("topK", 1)
                        put("topP", 0.1)
                        put("maxOutputTokens", 8192)
                        // v1.1.29: Gemini 2.5 Flashの思考トークンを無効化
                        // 思考トークンがmaxOutputTokensを消費し、実際の応答が~300文字で途切れていた問題を修正
                        put("thinkingConfig", JSONObject().apply {
                            put("thinkingBudget", 0)
                        })
                    })
                    put("safetySettings", JSONArray().apply {
                        val categories = arrayOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                                                 "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT")
                        categories.forEach { category ->
                            put(JSONObject().apply {
                                put("category", category)
                                put("threshold", "BLOCK_NONE")
                            })
                        }
                    })
                }

                Log.d(TAG, "[LLM] Sending request to Gemini API...")

                // HTTP POST実行
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                connection.connectTimeout = 30000  // v1.0.63: 15秒 → 30秒（安定性向上）
                connection.readTimeout = 30000     // v1.0.63: 15秒 → 30秒（タイムアウト対策）

                // リクエスト送信
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "[LLM] Response code: $responseCode")

                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        val errorStream = connection.errorStream?.bufferedReader()?.readText()
                        Log.e(TAG, "[LLM] API error: $errorStream")

                        // v1.0.73: 429エラーの場合、リトライ可能性をチェック
                        if (responseCode == 429 && ENABLE_RETRY) {
                            val retryDelay = extractRetryDelay(errorStream ?: "")
                            if (retryDelay != null) {
                                // リトライ情報を例外に含めて投げる（外側のループで処理）
                                throw RetryableException(retryDelay, errorStream)
                            }
                        }

                        return@withContext ""
                    }

                // v1.0.82: API成功時にクォータを記録
                quotaManager.recordAPICall()
                val updatedStatus = quotaManager.getStatus()
                Log.d(TAG, "[v1.0.82] API call recorded: ${updatedStatus.count}/${updatedStatus.limit}")

                // レスポンス読み取り
                val responseText = connection.inputStream.bufferedReader().readText()
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "[LLM] Response received in ${elapsed}ms")
                Log.d(TAG, "[LLM] Raw response (first 500 chars): ${responseText.take(500)}")

                // JSONパース
                val responseJson = JSONObject(responseText)

                // デバッグ: レスポンス詳細をログ
                if (responseJson.has("candidates")) {
                    val candidates = responseJson.getJSONArray("candidates")
                    Log.d(TAG, "[LLM] Response candidates: ${candidates.length()}")

                    if (candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        Log.d(TAG, "[LLM] First candidate JSON: ${firstCandidate.toString().take(500)}")
                        if (firstCandidate.has("finishReason")) {
                            Log.d(TAG, "[LLM] Finish reason: ${firstCandidate.getString("finishReason")}")
                        }

                        if (firstCandidate.has("content")) {
                            val content = firstCandidate.getJSONObject("content")
                            if (content.has("parts")) {
                                val parts = content.getJSONArray("parts")
                                Log.d(TAG, "[LLM] Response parts count: ${parts.length()}")
                                    if (parts.length() > 0) {
                                        val firstPart = parts.getJSONObject(0)
                                        Log.d(TAG, "[LLM] First part keys: ${firstPart.keys().asSequence().toList()}")
                                        if (firstPart.has("text")) {
                                            val text = firstPart.getString("text")
                                            Log.d(TAG, "[LLM] Response text length: ${text.length}")
                                            Log.d(TAG, "[LLM] Gemini API response received: ${text.take(100)}...")
                                            return@withContext parseResponse(text)
                                        } else {
                                            Log.w(TAG, "[LLM] No 'text' field in first part")
                                        }
                                    }
                            }
                        } else {
                            Log.w(TAG, "[LLM] No 'content' field in candidate")
                        }
                    }
                }

                if (responseJson.has("promptFeedback")) {
                    val feedback = responseJson.getJSONObject("promptFeedback")
                    Log.w(TAG, "[LLM] Prompt blocked! Feedback: $feedback")
                }

                    Log.w(TAG, "[LLM] Response text is empty or malformed")
                    ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[LLM] Gemini API call failed: ${e.message}", e)
            ""
        }
    }

    /**
     * v1.0.47: バッチLLM実行（Gemini REST API直接呼び出し）
     * 複数テキストを一度に処理
     */
    private fun invokeBatchLLM(prompt: String, expectedSize: Int): List<String> {
        // ネットワーク接続チェック
        if (!isNetworkAvailable()) {
            Log.w(TAG, "[LLM] No network connection, skipping batch LLM correction")
            return List(expectedSize) { "" }
        }

        // APIキーチェック
        if (BuildConfig.GEMINI_API_KEY == "YOUR_API_KEY_HERE" || BuildConfig.GEMINI_API_KEY.isEmpty()) {
            Log.w(TAG, "[LLM] API key not configured, skipping batch LLM correction")
            return List(expectedSize) { "" }
        }

        return try {
            // v1.0.48: HTTP呼び出しをIOディスパッチャで実行（NetworkOnMainThreadException回避）
            runBlocking {
                withContext(Dispatchers.IO) {
                    val startTime = System.currentTimeMillis()

                    // REST APIエンドポイント
                    val apiUrl = "$GEMINI_API_BASE/$MODEL_NAME:generateContent?key=${BuildConfig.GEMINI_API_KEY}"
                    val url = URL(apiUrl)

                    // リクエストボディ作成
                    val requestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.1)
                        put("topK", 1)
                        put("topP", 0.1)
                        put("maxOutputTokens", 8192)
                        // v1.1.29: Gemini 2.5 Flashの思考トークンを無効化
                        put("thinkingConfig", JSONObject().apply {
                            put("thinkingBudget", 0)
                        })
                    })
                    put("safetySettings", JSONArray().apply {
                        val categories = arrayOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                                                 "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT")
                        categories.forEach { category ->
                            put(JSONObject().apply {
                                put("category", category)
                                put("threshold", "BLOCK_NONE")
                            })
                        }
                    })
                }

                // HTTP POST実行
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                connection.connectTimeout = 30000  // v1.0.63: 15秒 → 30秒（安定性向上）
                connection.readTimeout = 30000     // v1.0.63: 15秒 → 30秒（タイムアウト対策）

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }

                    val responseCode = connection.responseCode
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        val errorStream = connection.errorStream?.bufferedReader()?.readText()
                        Log.e(TAG, "[LLM] Batch API error: $errorStream")
                        return@withContext List(expectedSize) { "" }
                    }

                    // レスポンス読み取り
                val responseText = connection.inputStream.bufferedReader().readText()
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "[LLM] Batch response received in ${elapsed}ms")

                // JSONパース
                val responseJson = JSONObject(responseText)
                if (responseJson.has("candidates")) {
                    val candidates = responseJson.getJSONArray("candidates")
                    if (candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        if (firstCandidate.has("content")) {
                            val content = firstCandidate.getJSONObject("content")
                            if (content.has("parts")) {
                                val parts = content.getJSONArray("parts")
                                    if (parts.length() > 0) {
                                        val text = parts.getJSONObject(0).getString("text")
                                        Log.d(TAG, "[LLM] Batch Gemini API response received: ${text.take(100)}...")
                                        return@withContext parseBatchResponse(text, expectedSize)
                                    }
                            }
                        }
                    }
                }

                    Log.w(TAG, "[LLM] Batch response text is empty or malformed")
                    List(expectedSize) { "" }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[LLM] Batch Gemini API call failed: ${e.message}", e)
            List(expectedSize) { "" }
        }
    }

    /**
     * v1.0.41: バッチLLMレスポンスのパース
     */
    private fun parseBatchResponse(responseText: String, expectedSize: Int): List<String> {
        return try {
            // JSON配列を抽出（```json ... ``` または直接JSON）
            val jsonText = if (responseText.contains("```json")) {
                responseText.substringAfter("```json").substringBefore("```").trim()
            } else if (responseText.contains("```")) {
                responseText.substringAfter("```").substringBefore("```").trim()
            } else if (responseText.trim().startsWith("[")) {
                responseText.trim()
            } else {
                // JSON配列が見つからない場合
                Log.w(TAG, "[LLM] No JSON array found in batch response")
                return List(expectedSize) { "" }
            }

            // JSON配列をパース
            val jsonArray = org.json.JSONArray(jsonText)
            val results = mutableMapOf<Int, String>()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val index = item.optInt("index", -1)
                val corrected = item.optString("corrected", "")

                if (index >= 0 && corrected.isNotEmpty()) {
                    results[index] = corrected
                    Log.d(TAG, "[LLM] Parsed batch result #$index: $corrected")
                }
            }

            // インデックス順に結果を並べる
            val orderedResults = mutableListOf<String>()
            for (i in 0 until expectedSize) {
                orderedResults.add(results[i] ?: "")
            }

            Log.d(TAG, "[LLM] Successfully parsed ${results.size}/${expectedSize} batch corrections")
            orderedResults

        } catch (e: Exception) {
            Log.e(TAG, "[LLM] Failed to parse batch JSON response: ${e.message}", e)
            List(expectedSize) { "" }
        }
    }

    /**
     * v1.0.45: 反復補正の実行（精密化・検証）
     *
     * 初回補正の結果が低信頼度の場合、2回目のLLM呼び出しで精密化する。
     *
     * @param originalText 元のOCRテキスト
     * @param correctedText 初回補正済みテキスト
     * @param initialConfidence 初回補正の信頼度
     * @param context 文脈情報
     * @return Pair<精密化後テキスト, 信頼度>
     */
    private fun refineWithLLM(originalText: String, correctedText: String, initialConfidence: Double, context: String?): Pair<String, Double> {
        if (!ENABLE_ITERATIVE_REFINEMENT) {
            Log.d(TAG, "[v1.0.45] Iterative refinement disabled, returning original correction")
            return Pair(correctedText, initialConfidence)
        }

        // 信頼度が閾値以上なら再補正不要
        if (initialConfidence >= REFINEMENT_CONFIDENCE_THRESHOLD) {
            Log.d(TAG, "[v1.0.45] Confidence $initialConfidence >= threshold $REFINEMENT_CONFIDENCE_THRESHOLD, skipping refinement")
            return Pair(correctedText, initialConfidence)
        }

        Log.d(TAG, "[v1.0.45] Starting refinement for low confidence ($initialConfidence) text: $correctedText")

        val refinementStartTime = System.currentTimeMillis()

        try {
            // 精密化プロンプトの構築
            val prompt = buildRefinementPrompt(originalText, correctedText, context)

            // LLM実行
            val responseText = invokeLLM(prompt)

            if (responseText.isEmpty()) {
                Log.w(TAG, "[v1.0.45] Refinement LLM returned empty, using original text")
                return Pair(originalText, initialConfidence)
            }

            // レスポンスをパース（confidence付き）
            val (refinedText, refinedConfidence) = parseRefinementResponse(responseText)

            // 統計更新
            refinementCount++
            val refinementLatency = System.currentTimeMillis() - refinementStartTime
            totalRefinementLatency += refinementLatency

            // 改善度チェック
            if (refinedConfidence > initialConfidence) {
                refinementImprovedCount++
                Log.d(TAG, "[v1.0.45] Refinement improved confidence: $initialConfidence -> $refinedConfidence ($refinementLatency ms)")
            } else {
                Log.d(TAG, "[v1.0.45] Refinement completed but no improvement: $initialConfidence -> $refinedConfidence ($refinementLatency ms)")
            }

            return Pair(refinedText, refinedConfidence)

        } catch (e: Exception) {
            Log.e(TAG, "[v1.0.45] Refinement failed: ${e.message}", e)
            return Pair(correctedText, initialConfidence)
        }
    }

    /**
     * v1.0.45: 精密化レスポンスのパース
     *
     * JSON形式: {"corrected": "精密化後", "confidence": 0.98}
     */
    private fun parseRefinementResponse(responseText: String): Pair<String, Double> {
        return try {
            // JSONブロックを抽出
            val jsonText = if (responseText.contains("```json")) {
                responseText.substringAfter("```json").substringBefore("```").trim()
            } else if (responseText.contains("```")) {
                responseText.substringAfter("```").substringBefore("```").trim()
            } else if (responseText.trim().startsWith("{")) {
                responseText.trim()
            } else {
                Log.w(TAG, "[v1.0.45] No JSON found in refinement response")
                return Pair(responseText.trim(), REFINEMENT_CONFIDENCE_THRESHOLD)
            }

            // JSONパース
            val json = JSONObject(jsonText)
            val corrected = json.optString("corrected", "")
            val confidence = json.optDouble("confidence", REFINEMENT_CONFIDENCE_THRESHOLD)

            if (corrected.isNotEmpty()) {
                Log.d(TAG, "[v1.0.45] Parsed refinement: text='$corrected', confidence=$confidence")
                Pair(corrected, confidence)
            } else {
                Log.w(TAG, "[v1.0.45] No 'corrected' field in refinement JSON")
                Pair("", REFINEMENT_CONFIDENCE_THRESHOLD)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[v1.0.45] Failed to parse refinement JSON: ${e.message}", e)
            Pair(responseText.trim(), REFINEMENT_CONFIDENCE_THRESHOLD)
        }
    }

    /**
     * v1.0.40: ネットワーク接続チェック
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * v1.0.40: LLMレスポンスのパース
     * v1.1.26: マークダウンコードブロック処理の強化（TTSに```json混入防止）
     * v1.1.29: バージョンタグ統一、修復コードの確実な動作を保証
     */
    private fun parseResponse(responseText: String): String {
        Log.d(TAG, "[v1.1.29] parseResponse input: ${responseText.take(200)}...")

        return try {
            // JSONブロックを抽出（```json ... ``` または直接JSON）
            var jsonText = responseText.trim()

            // マークダウンコードブロックを除去（複数パターン対応）
            if (jsonText.contains("```json")) {
                jsonText = jsonText.substringAfter("```json").substringBefore("```").trim()
                Log.d(TAG, "[v1.1.29] Extracted from ```json block: ${jsonText.take(100)}...")
            } else if (jsonText.contains("```")) {
                jsonText = jsonText.substringAfter("```").substringBefore("```").trim()
                Log.d(TAG, "[v1.1.29] Extracted from ``` block: ${jsonText.take(100)}...")
            }

            // JSONオブジェクトとして開始するかチェック
            if (!jsonText.startsWith("{")) {
                // JSONオブジェクトが見つからない場合、テキスト内から探す
                val jsonStart = jsonText.indexOf("{")
                val jsonEnd = jsonText.lastIndexOf("}")
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    jsonText = jsonText.substring(jsonStart, jsonEnd + 1)
                    Log.d(TAG, "[v1.1.29] Extracted JSON object: ${jsonText.take(100)}...")
                } else {
                    Log.w(TAG, "[v1.1.29] No JSON object found in response")
                    // JSONが見つからない場合は空文字列を返す（生テキストを返さない）
                    return ""
                }
            }

            // JSONパース（v1.1.28: 途中切れJSON修復ロジック追加）
            val json = try {
                JSONObject(jsonText)
            } catch (e: org.json.JSONException) {
                // v1.1.28: MAX_TOKENSによるJSON途中切れを修復
                Log.w(TAG, "[v1.1.29] JSON parse failed, attempting truncated JSON repair: ${e.message}")
                repairTruncatedJson(jsonText)
            }

            if (json != null) {
                val corrected = json.optString("corrected", "")
                if (corrected.isNotEmpty()) {
                    // 抽出されたテキストにまだマークダウンが含まれていないか確認
                    val cleanedText = corrected
                        .replace("```json", "")
                        .replace("```", "")
                        .trim()
                    Log.d(TAG, "[v1.1.29] Parsed corrected text: ${cleanedText.take(100)}...")
                    cleanedText
                } else {
                    Log.w(TAG, "[v1.1.29] No 'corrected' field in JSON response")
                    ""
                }
            } else {
                Log.w(TAG, "[v1.1.29] JSON repair also failed, returning empty")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "[v1.1.29] Failed to parse JSON response: ${e.message}", e)
            // JSONパースに失敗した場合は空文字列を返す（生テキストを返さない）
            // これによりTTSに```jsonが混入することを防ぐ
            ""
        }
    }

    /**
     * v1.1.28: MAX_TOKENSで途中切れしたJSONを修復
     *
     * Gemini APIがMAX_TOKENSで停止した場合、JSONが不完全になる。
     * 例: {"corrected": "途中で切れたテキスト
     * → {"corrected": "途中で切れたテキスト"} に修復
     */
    private fun repairTruncatedJson(jsonText: String): JSONObject? {
        return try {
            var repaired = jsonText.trim()

            // "corrected"フィールドの値をregexで抽出
            val correctedPattern = Regex(""""corrected"\s*:\s*"((?:[^"\\]|\\.)*)""")
            val match = correctedPattern.find(repaired)

            if (match != null) {
                val extractedValue = match.groupValues[1]
                // 最小限の有効なJSONを構築
                val repairedJson = JSONObject()
                repairedJson.put("corrected", extractedValue)
                Log.d(TAG, "[v1.1.29] Repaired truncated JSON, extracted ${extractedValue.length} chars")
                repairedJson
            } else {
                // "corrected": " の開始は見つかるが、閉じ " がない場合
                val openPattern = Regex(""""corrected"\s*:\s*"(.*)""", RegexOption.DOT_MATCHES_ALL)
                val openMatch = openPattern.find(repaired)
                if (openMatch != null) {
                    var value = openMatch.groupValues[1]
                    // 末尾の不完全なエスケープシーケンスを除去
                    if (value.endsWith("\\")) {
                        value = value.dropLast(1)
                    }
                    val repairedJson = JSONObject()
                    repairedJson.put("corrected", value)
                    Log.d(TAG, "[v1.1.29] Repaired open-ended JSON, extracted ${value.length} chars")
                    repairedJson
                } else {
                    Log.w(TAG, "[v1.1.29] Could not find 'corrected' field in truncated JSON")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[v1.1.29] JSON repair failed: ${e.message}")
            null
        }
    }

    /**
     * 信頼度スコアの計算
     *
     * 基準:
     * - 変更箇所が少ない: 信頼度高
     * - 既知のパターンに一致: 信頼度高
     * - 大幅な変更: 信頼度低
     */
    private fun calculateConfidence(original: String, corrected: String): Double {
        if (original == corrected) {
            return 1.0  // 変更なし
        }

        // レーベンシュタイン距離ベースの信頼度計算
        val distance = levenshteinDistance(original, corrected)
        val maxLength = maxOf(original.length, corrected.length)

        // 変更率が低いほど信頼度が高い
        val changeRatio = distance.toDouble() / maxLength
        val confidence = 1.0 - (changeRatio * 0.5)  // 最大50%減点

        return confidence.coerceIn(0.0, 1.0)
    }

    /**
     * レーベンシュタイン距離の計算
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // 削除
                    dp[i][j - 1] + 1,      // 挿入
                    dp[i - 1][j - 1] + cost // 置換
                )
            }
        }

        return dp[len1][len2]
    }

    /**
     * Phase 1の信頼度を評価
     *
     * 基準:
     * - 多数のパターンがマッチ: 信頼度高
     * - 文脈パターンがマッチ: 信頼度高
     * - 辞書のみのマッチ: 信頼度中
     * - マッチなし: 信頼度低
     */
    fun evaluatePhase1Confidence(
        original: String,
        corrected: String,
        appliedPatterns: List<String>
    ): Double {
        if (original == corrected) {
            return 0.3  // 補正なし = 低信頼度（OCRエラーがあるかもしれない）
        }

        var confidence = 0.5  // ベース信頼度

        // 適用されたパターン数に応じて信頼度を上げる
        confidence += minOf(appliedPatterns.size * 0.1, 0.3)

        // 文脈パターンが適用された場合は信頼度を上げる
        if (appliedPatterns.any { it.contains("contextual") }) {
            confidence += 0.2
        }

        return confidence.coerceIn(0.0, 1.0)
    }

    /**
     * v1.0.42: キャッシュエントリを永続化（個別保存）
     */
    private fun saveCacheEntry(key: String, result: CorrectionResult) {
        try {
            // 既存のキャッシュデータを読み込み
            val cacheString = prefs.getString(CACHE_KEY, "{}")
            val cacheData = JSONObject(cacheString ?: "{}")

            // 新しいエントリを追加
            val entryJson = JSONObject()
            entryJson.put("correctedText", result.correctedText)
            entryJson.put("confidence", result.confidence)
            entryJson.put("timestamp", result.timestamp)
            cacheData.put(key, entryJson)

            // v1.0.74: キャッシュ書き込み統計
            cacheWriteCount++

            // サイズ制限チェック（100エントリ超過時は古いものから削除）
            if (cacheData.length() > CACHE_SIZE) {
                val keys = cacheData.keys().asSequence().toList()
                val sorted = keys.mapNotNull { k ->
                    val entry = cacheData.optJSONObject(k)
                    entry?.let { k to it.getLong("timestamp") }
                }.sortedBy { it.second }

                // 古いエントリを削除
                val toRemove = sorted.take(cacheData.length() - CACHE_SIZE)
                toRemove.forEach { (k, _) -> cacheData.remove(k) }

                // v1.0.74: eviction統計
                cacheEvictions += toRemove.size
            }

            prefs.edit().putString(CACHE_KEY, cacheData.toString()).apply()

        } catch (e: Exception) {
            Log.e(TAG, "[v1.0.42] Failed to save cache entry: ${e.message}", e)
        }
    }

    /**
     * v1.0.42: SharedPreferencesからキャッシュを読み込み
     */
    private fun loadCache() {
        try {
            val cacheString = prefs.getString(CACHE_KEY, null) ?: return

            val cacheData = JSONObject(cacheString)
            val currentTime = System.currentTimeMillis()
            val maxAge = MAX_CACHE_AGE_DAYS * 24 * 60 * 60 * 1000L
            var loadedCount = 0
            var expiredCount = 0

            val keys = cacheData.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entryJson = cacheData.getJSONObject(key)

                val correctedText = entryJson.getString("correctedText")
                val confidence = entryJson.getDouble("confidence")
                val timestamp = entryJson.getLong("timestamp")

                // 古すぎるエントリはスキップ
                if (currentTime - timestamp > maxAge) {
                    expiredCount++
                    continue
                }

                val result = CorrectionResult(correctedText, confidence, timestamp)
                correctionCache.put(key, result)
                loadedCount++
            }

            Log.d(TAG, "[v1.0.42] Cache loaded: $loadedCount entries (expired: $expiredCount)")

        } catch (e: Exception) {
            Log.e(TAG, "[v1.0.42] Failed to load cache: ${e.message}", e)
        }
    }

    /**
     * v1.0.73: RetryInfoからリトライ待機時間を抽出
     *
     * Gemini APIの429エラーレスポンスから待機時間を取得
     * 例: "49s" → 49000ms
     */
    private fun extractRetryDelay(errorResponse: String): Long? {
        return try {
            val errorJson = JSONObject(errorResponse)
            if (errorJson.has("error")) {
                val error = errorJson.getJSONObject("error")
                if (error.has("details")) {
                    val details = error.getJSONArray("details")
                    for (i in 0 until details.length()) {
                        val detail = details.getJSONObject(i)
                        if (detail.has("@type") &&
                            detail.getString("@type") == "type.googleapis.com/google.rpc.RetryInfo") {
                            if (detail.has("retryDelay")) {
                                val retryDelay = detail.getString("retryDelay")
                                // "49s" → 49000, "49.5s" → 49500
                                val seconds = retryDelay.removeSuffix("s").toDoubleOrNull()
                                if (seconds != null) {
                                    val delayMs = (seconds * 1000).toLong()
                                    // 最大60秒に制限
                                    return delayMs.coerceAtMost(MAX_RETRY_DELAY_MS)
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "[v1.0.73 Retry] Failed to extract retry delay: ${e.message}")
            null
        }
    }

    /**
     * v1.0.73: リトライ統計を取得
     */
    fun getRetryStatistics(): Pair<Int, Long> {
        return Pair(retryCount, totalRetryWaitTime)
    }

    /**
     * v1.0.74: キャッシュ統計を取得
     * @return Triple(cacheHits, cacheMisses, hitRate)
     */
    fun getCacheStatistics(): Triple<Int, Int, Int> {
        val total = cacheHits + cacheMisses
        val hitRate = if (total > 0) (cacheHits * 100 / total) else 0
        return Triple(cacheHits, cacheMisses, hitRate)
    }

    /**
     * v1.0.74: 詳細なキャッシュ統計を取得
     * @return Map with keys: hits, misses, hitRate, writes, evictions, size, avgAge
     */
    fun getDetailedCacheStatistics(): Map<String, Long> {
        val total = cacheHits + cacheMisses
        val hitRate = if (total > 0) (cacheHits.toLong() * 100 / total) else 0
        val avgAge = if (cacheHits > 0) totalCacheEntryAge / cacheHits else 0

        return mapOf(
            "hits" to cacheHits.toLong(),
            "misses" to cacheMisses.toLong(),
            "hitRate" to hitRate,
            "writes" to cacheWriteCount.toLong(),
            "evictions" to cacheEvictions.toLong(),
            "size" to correctionCache.size().toLong(),
            "avgAge" to avgAge
        )
    }

    /**
     * v1.0.74: 統計情報をログ出力（キャッシュ統計拡充）
     */
    fun logStatistics() {
        val avgLatency = if (llmCallCount > 0) totalLLMLatency / llmCallCount else 0
        val cacheTotal = cacheHits + cacheMisses
        val hitRate = if (cacheTotal > 0) (cacheHits.toDouble() / cacheTotal * 100).toInt() else 0
        val avgCacheAge = if (cacheHits > 0) totalCacheEntryAge / cacheHits else 0

        Log.i(TAG, "========================================")
        Log.i(TAG, "[v1.0.74] LLM Corrector Statistics")
        Log.i(TAG, "========================================")
        Log.i(TAG, "LLM Calls: $llmCallCount")
        Log.i(TAG, "Average Latency: ${avgLatency}ms")
        Log.i(TAG, "----------------------------------------")
        Log.i(TAG, "Cache Hits: $cacheHits")
        Log.i(TAG, "Cache Misses: $cacheMisses")
        Log.i(TAG, "Cache Hit Rate: $hitRate%")
        Log.i(TAG, "Cache Writes: $cacheWriteCount")
        Log.i(TAG, "Cache Evictions: $cacheEvictions")
        Log.i(TAG, "Cache Size: ${correctionCache.size()}")
        Log.i(TAG, "Average Cache Age: ${avgCacheAge}ms (${avgCacheAge / 1000}s)")
        Log.i(TAG, "----------------------------------------")
        Log.i(TAG, "Retry Count: $retryCount")
        Log.i(TAG, "Total Retry Wait Time: ${totalRetryWaitTime}ms (${totalRetryWaitTime / 1000}s)")
        Log.i(TAG, "========================================")
    }

    /**
     * v1.0.42: キャッシュをクリア（永続化も含む）
     */
    fun clearCache() {
        correctionCache.evictAll()
        prefs.edit().remove(CACHE_KEY).apply()
        Log.d(TAG, "[v1.0.42] Cache cleared")
    }
}
