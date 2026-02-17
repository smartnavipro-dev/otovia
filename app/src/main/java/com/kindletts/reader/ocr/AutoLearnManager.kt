package com.kindletts.reader.ocr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * v1.1.33: 自動学習マネージャー
 * LLM補正の差分を蓄積し、同じ誤りパターンを自動適用するシステム
 *
 * v1.1.34: 安全性強化 (isSafePattern)
 * v1.1.36: 信頼度スコアリング + 適応パイプライン
 *   - confidence: LLM同意率ベースの信頼度 (0.0-1.0)
 *   - llmAgreement/Disagreement: LLMとの一致/不一致カウント
 *   - getAdaptiveSkipRecommendation(): LLMスキップ判定用データ提供
 *   - confirmWithLLM(): LLM結果との照合で信頼度を更新
 */
class AutoLearnManager private constructor(context: Context) {

    companion object {
        private const val TAG = "KindleTTS_AutoLearn"
        private const val PREFS_NAME = "auto_learn_patterns_v3"  // v1.1.36: 信頼度付き新キー
        private const val KEY_PATTERNS = "patterns"
        private const val PROMOTION_THRESHOLD = 3   // N回以上で自動適用に昇格
        private const val MAX_PATTERNS = 500        // 最大保存パターン数
        private const val MAX_PATTERN_LENGTH = 12   // from/toの最大文字数
        private const val MAX_DIFF_INPUT = 500      // diff計算の入力上限
        private const val MIN_FROM_LENGTH = 3       // v1.1.34: from最低文字数
        private const val MAX_LENGTH_RATIO = 2.0    // v1.1.34: to/from長さ比率上限
        private const val HIGH_CONFIDENCE = 0.8f    // v1.1.36: 高信頼度閾値
        private const val LOW_CONFIDENCE = 0.3f     // v1.1.36: 低信頼度（自動削除対象）

        @Volatile
        private var instance: AutoLearnManager? = null

        fun getInstance(context: Context): AutoLearnManager {
            return instance ?: synchronized(this) {
                instance ?: AutoLearnManager(context.applicationContext).also { instance = it }
            }
        }

        // v1.1.34: 句読点・記号セット
        private val PUNCTUATION = setOf('。', '、', '！', '？', '…', '」', '「', '）', '（', '.', ',', '!', '?')
    }

    data class LearnedPattern(
        val from: String,
        val to: String,
        var count: Int = 1,
        val firstSeen: Long = System.currentTimeMillis(),
        var lastSeen: Long = System.currentTimeMillis(),
        // v1.1.36: 信頼度スコアリング
        var confidence: Float = 0.5f,       // 初期信頼度0.5
        var llmAgreement: Int = 0,          // LLMが同じ補正をした回数
        var llmDisagreement: Int = 0,       // LLMが異なる補正をした回数
        var source: String = "LLM_DIFF"     // パターンのソース (LLM_DIFF / USER)
    )

    /**
     * v1.1.36: AutoLearnの適用結果サマリー（適応パイプライン用）
     */
    data class ApplyResult(
        val correctedText: String,
        val appliedCount: Int,              // 適用されたパターン数
        val avgConfidence: Float,           // 適用パターンの平均信頼度
        val highConfidenceCount: Int,       // 高信頼度パターン数
        val totalPromoted: Int              // 全昇格パターン数
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val lock = ReentrantReadWriteLock()
    private var patterns: MutableMap<String, LearnedPattern> = loadPatterns()

    // --- Persistence ---

    private fun loadPatterns(): MutableMap<String, LearnedPattern> {
        return try {
            val json = prefs.getString(KEY_PATTERNS, null) ?: return mutableMapOf()
            val type = object : TypeToken<MutableMap<String, LearnedPattern>>() {}.type
            gson.fromJson<MutableMap<String, LearnedPattern>>(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load patterns", e)
            mutableMapOf()
        }
    }

    private fun savePatterns() {
        try {
            val json = gson.toJson(patterns)
            prefs.edit().putString(KEY_PATTERNS, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save patterns", e)
        }
    }

    // --- Safety Checks (v1.1.34) ---

    private fun isSafePattern(from: String, to: String): Boolean {
        if (from.length < MIN_FROM_LENGTH) {
            Log.d(TAG, "[Safety] Rejected (too short): '$from' → '$to'")
            return false
        }

        if (from.length <= 3) {
            val startsWithPunct = from.first() in PUNCTUATION
            val endsWithPunct = from.last() in PUNCTUATION
            if (startsWithPunct || endsWithPunct) {
                Log.d(TAG, "[Safety] Rejected (short+punctuation): '$from' → '$to'")
                return false
            }
        }

        if (from.isNotEmpty() && to.length.toDouble() / from.length > MAX_LENGTH_RATIO) {
            Log.d(TAG, "[Safety] Rejected (length ratio ${to.length}/${from.length}): '$from' → '$to'")
            return false
        }

        val fromChars = from.filter { it !in PUNCTUATION }.toSet()
        val toChars = to.filter { it !in PUNCTUATION }.toSet()
        if (fromChars.isNotEmpty() && toChars.isNotEmpty() && fromChars.intersect(toChars).isEmpty()) {
            Log.d(TAG, "[Safety] Rejected (no char overlap): '$from' → '$to'")
            return false
        }

        if (to.isEmpty() && from.length < 4) {
            Log.d(TAG, "[Safety] Rejected (short deletion): '$from' → ''")
            return false
        }

        return true
    }

    // --- Learn ---

    fun learnFromDiff(preLLM: String, postLLM: String) {
        if (preLLM == postLLM) return

        val diffs = extractDiffs(preLLM, postLLM)
        if (diffs.isEmpty()) return

        lock.write {
            var newCount = 0
            var updatedCount = 0
            var rejectedCount = 0

            for ((from, to) in diffs) {
                if (from.length > MAX_PATTERN_LENGTH || to.length > MAX_PATTERN_LENGTH) continue
                if (from.isEmpty()) continue
                if (from == to) continue

                if (!isSafePattern(from, to)) {
                    rejectedCount++
                    continue
                }

                val key = from
                val existing = patterns[key]
                if (existing != null && existing.to == to) {
                    existing.count++
                    existing.llmAgreement++
                    existing.lastSeen = System.currentTimeMillis()
                    // v1.1.36: LLM同意で信頼度上昇
                    existing.confidence = calculateConfidence(existing)
                    updatedCount++
                } else if (existing != null && existing.to != to) {
                    // v1.1.36: 衝突 - LLMが異なる補正を提案
                    existing.llmDisagreement++
                    existing.confidence = calculateConfidence(existing)
                    Log.d(TAG, "[Conflict] '$from': existing='${existing.to}' vs new='$to' (conf=${String.format("%.2f", existing.confidence)})")
                    // 信頼度が低すぎたら入れ替え
                    if (existing.confidence < LOW_CONFIDENCE) {
                        Log.d(TAG, "[Conflict] Replacing low-confidence pattern: '$from' → '$to'")
                        patterns[key] = LearnedPattern(from = from, to = to)
                        newCount++
                    }
                } else {
                    patterns[key] = LearnedPattern(from = from, to = to)
                    newCount++
                }
            }

            // v1.1.36: 低信頼度パターンを自動削除
            val lowConfPatterns = patterns.entries.filter {
                it.value.confidence < LOW_CONFIDENCE && it.value.count >= PROMOTION_THRESHOLD
            }
            for (entry in lowConfPatterns) {
                Log.d(TAG, "[AutoPurge] Removing low-confidence: '${entry.key}' → '${entry.value.to}' (conf=${String.format("%.2f", entry.value.confidence)})")
                patterns.remove(entry.key)
            }

            // 上限超過時は古いパターンを削除
            if (patterns.size > MAX_PATTERNS) {
                val sorted = patterns.entries.sortedBy { it.value.lastSeen }
                val toRemove = patterns.size - MAX_PATTERNS
                sorted.take(toRemove).forEach { patterns.remove(it.key) }
            }

            savePatterns()

            Log.d(TAG, "Learned: +$newCount new, ↑$updatedCount updated, ✗$rejectedCount rejected, ${patterns.size} total")
            for ((from, to) in diffs) {
                val p = patterns[from]
                if (p != null) {
                    val star = if (p.count >= PROMOTION_THRESHOLD) " ★PROMOTED" else ""
                    Log.d(TAG, "  '$from' → '${p.to}' (count=${p.count}, conf=${String.format("%.2f", p.confidence)}$star)")
                }
            }
        }
    }

    // --- Confidence Calculation (v1.1.36) ---

    /**
     * LLM同意率ベースの信頼度計算
     * agreement / (agreement + disagreement) をベースに、出現回数で補正
     */
    private fun calculateConfidence(pattern: LearnedPattern): Float {
        val total = pattern.llmAgreement + pattern.llmDisagreement
        if (total == 0) return 0.5f  // データなし → 中立

        val agreementRate = pattern.llmAgreement.toFloat() / total

        // 出現回数が少ないうちは中立(0.5)に寄せる（ベイズ的平滑化）
        val smoothingFactor = minOf(total.toFloat() / 5f, 1.0f)  // 5回で完全収束
        val smoothed = 0.5f * (1f - smoothingFactor) + agreementRate * smoothingFactor

        return smoothed.coerceIn(0.0f, 1.0f)
    }

    // --- Apply (v1.1.36: ApplyResult付き) ---

    /**
     * 学習済みパターンを適用し、適用結果のサマリーを返す
     * 適応パイプラインでLLMスキップ判定に使用
     */
    fun applyLearnedPatternsWithStats(text: String): ApplyResult {
        lock.read {
            val promoted = patterns.values.filter { it.count >= PROMOTION_THRESHOLD }
            if (promoted.isEmpty()) return ApplyResult(text, 0, 0f, 0, 0)

            var result = text
            var appliedCount = 0
            val appliedConfidences = mutableListOf<Float>()
            var highConfCount = 0

            for (pattern in promoted.sortedByDescending { it.from.length }) {
                if (result.contains(pattern.from)) {
                    val before = result
                    result = result.replace(pattern.from, pattern.to)
                    if (result != before) {
                        appliedCount++
                        appliedConfidences.add(pattern.confidence)
                        if (pattern.confidence >= HIGH_CONFIDENCE) highConfCount++
                        Log.d(TAG, "Applied: '${pattern.from}' → '${pattern.to}' (count=${pattern.count}, conf=${String.format("%.2f", pattern.confidence)})")
                    }
                }
            }

            val avgConf = if (appliedConfidences.isNotEmpty()) appliedConfidences.average().toFloat() else 0f

            if (appliedCount > 0) {
                Log.d(TAG, "Applied $appliedCount patterns (avgConf=${String.format("%.2f", avgConf)}, highConf=$highConfCount)")
            }

            return ApplyResult(result, appliedCount, avgConf, highConfCount, promoted.size)
        }
    }

    /**
     * 後方互換: 文字列のみ返す旧API
     */
    fun applyLearnedPatterns(text: String): String {
        return applyLearnedPatternsWithStats(text).correctedText
    }

    // --- User Correction Learning (v1.1.37) ---

    /**
     * ユーザーの手動修正から最高品質パターンを学習
     * LCS diffで差分抽出し、confidence=1.0、即座に昇格
     *
     * @param originalSentence TTS読み上げ時の文（ユーザーが「間違い」と判断したもの）
     * @param correctedSentence ユーザーが入力した正しい文
     * @return 学習されたパターン数
     */
    fun learnFromUserCorrection(originalSentence: String, correctedSentence: String): Int {
        if (originalSentence == correctedSentence) return 0

        val diffs = extractDiffs(originalSentence, correctedSentence)
        if (diffs.isEmpty()) return 0

        lock.write {
            var learnedCount = 0
            for ((from, to) in diffs) {
                if (from.length > MAX_PATTERN_LENGTH || to.length > MAX_PATTERN_LENGTH) continue
                if (from.isEmpty() || from == to) continue

                val key = from
                patterns[key] = LearnedPattern(
                    from = from,
                    to = to,
                    count = PROMOTION_THRESHOLD,  // 即座に昇格
                    confidence = 1.0f,             // 最高信頼度
                    llmAgreement = PROMOTION_THRESHOLD,
                    source = "USER"
                )
                learnedCount++
                Log.d(TAG, "[UserCorrection] ★ '$from' → '$to' (instant promotion, conf=1.0)")
            }

            if (learnedCount > 0) {
                savePatterns()
                Log.d(TAG, "[UserCorrection] Learned $learnedCount patterns from user feedback")
            }
            return learnedCount
        }
    }

    // --- LLM Confirmation (v1.1.36) ---

    /**
     * LLM補正結果とAutoLearn適用結果を照合し、信頼度を更新
     * AutoLearnが「A→B」と補正した箇所で、LLMも「A→B」ならagreement++
     * LLMが「A→C」(B≠C)ならdisagreement++
     *
     * @param preAutoLearn AutoLearn適用前のテキスト
     * @param postAutoLearn AutoLearn適用後のテキスト
     * @param postLLM LLM補正後のテキスト
     */
    fun confirmWithLLM(preAutoLearn: String, postAutoLearn: String, postLLM: String) {
        if (preAutoLearn == postAutoLearn) return  // AutoLearnが何も変更していない

        lock.write {
            val promoted = patterns.values.filter { it.count >= PROMOTION_THRESHOLD }
            var agreed = 0
            var disagreed = 0

            for (pattern in promoted) {
                // このパターンがAutoLearnで適用されたか
                if (preAutoLearn.contains(pattern.from) && !postAutoLearn.contains(pattern.from)) {
                    // LLMの結果にも pattern.to が含まれているか
                    if (postLLM.contains(pattern.to)) {
                        pattern.llmAgreement++
                        agreed++
                    } else if (!postLLM.contains(pattern.from)) {
                        // LLMもfromを変更したが、toが異なる → disagreement
                        pattern.llmDisagreement++
                        disagreed++
                    }
                    pattern.confidence = calculateConfidence(pattern)
                }
            }

            if (agreed > 0 || disagreed > 0) {
                savePatterns()
                Log.d(TAG, "[LLM Confirm] agreed=$agreed, disagreed=$disagreed")
            }
        }
    }

    // --- Adaptive Pipeline Support (v1.1.36) ---

    /**
     * LLMスキップ推奨判定データを返す
     * TextCorrectorがこのデータを使ってLLM呼び出しをスキップするか判定
     */
    data class SkipRecommendation(
        val shouldSkip: Boolean,
        val reason: String,
        val promotedCount: Int,
        val highConfidenceRate: Float  // 高信頼度パターンの割合
    )

    fun getSkipRecommendation(applyResult: ApplyResult): SkipRecommendation {
        val promoted = applyResult.totalPromoted
        val highConfRate = if (promoted > 0) {
            lock.read {
                patterns.values
                    .filter { it.count >= PROMOTION_THRESHOLD }
                    .count { it.confidence >= HIGH_CONFIDENCE }
                    .toFloat() / promoted
            }
        } else 0f

        // スキップ条件:
        // 1. 昇格パターンが20個以上（十分な学習量）
        // 2. 高信頼度パターンの割合が50%以上
        // 3. 今回適用されたパターンの平均信頼度が0.8以上
        val shouldSkip = promoted >= 20 &&
                highConfRate >= 0.5f &&
                applyResult.appliedCount > 0 &&
                applyResult.avgConfidence >= HIGH_CONFIDENCE

        val reason = when {
            promoted < 20 -> "Not enough patterns ($promoted < 20)"
            highConfRate < 0.5f -> "Low high-confidence rate (${String.format("%.0f", highConfRate * 100)}% < 50%)"
            applyResult.appliedCount == 0 -> "No patterns applied to this text"
            applyResult.avgConfidence < HIGH_CONFIDENCE -> "Applied patterns avg confidence too low (${String.format("%.2f", applyResult.avgConfidence)})"
            else -> "High confidence coverage (promoted=$promoted, highConfRate=${String.format("%.0f", highConfRate * 100)}%, avgAppliedConf=${String.format("%.2f", applyResult.avgConfidence)})"
        }

        return SkipRecommendation(shouldSkip, reason, promoted, highConfRate)
    }

    // --- Diff Algorithm ---

    private fun extractDiffs(pre: String, post: String): List<Pair<String, String>> {
        val m = pre.length
        val n = post.length
        if (m > MAX_DIFF_INPUT || n > MAX_DIFF_INPUT) return emptyList()
        if (m == 0 && n == 0) return emptyList()

        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (pre[i - 1] == post[j - 1]) dp[i - 1][j - 1] + 1
                else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }

        val ops = mutableListOf<Triple<Int, Char?, Char?>>()
        var i = m; var j = n
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && pre[i - 1] == post[j - 1] -> {
                    ops.add(Triple(0, pre[i - 1], post[j - 1]))
                    i--; j--
                }
                j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                    ops.add(Triple(2, null, post[j - 1]))
                    j--
                }
                else -> {
                    ops.add(Triple(1, pre[i - 1], null))
                    i--
                }
            }
        }
        ops.reverse()

        val result = mutableListOf<Pair<String, String>>()
        var idx = 0
        var prePos = 0

        while (idx < ops.size) {
            if (ops[idx].first == 0) {
                prePos++
                idx++
                continue
            }

            val blockPreStart = prePos
            val fromChars = StringBuilder()
            val toChars = StringBuilder()

            while (idx < ops.size && ops[idx].first != 0) {
                val (type, preChar, postChar) = ops[idx]
                if (type == 1) { fromChars.append(preChar!!); prePos++ }
                if (type == 2) { toChars.append(postChar!!) }
                idx++
            }

            val from = fromChars.toString()
            val to = toChars.toString()

            if (from == to) continue
            if (from.isBlank() && to.isBlank()) continue

            if (from.length <= 2 || to.isEmpty()) {
                val before = if (blockPreStart > 0) pre[blockPreStart - 1].toString() else ""
                val afterIdx = blockPreStart + from.length
                val after = if (afterIdx < m) pre[afterIdx].toString() else ""
                val ctxFrom = before + from + after
                val ctxTo = before + to + after
                if (ctxFrom != ctxTo && ctxFrom.length >= MIN_FROM_LENGTH) {
                    result.add(Pair(ctxFrom, ctxTo))
                }
            } else if (from.isNotEmpty()) {
                result.add(Pair(from, to))
            }
        }

        return result
    }

    // --- Stats ---

    fun getStats(): String {
        lock.read {
            val total = patterns.size
            val promoted = patterns.values.count { it.count >= PROMOTION_THRESHOLD }
            val highConf = patterns.values.count { it.count >= PROMOTION_THRESHOLD && it.confidence >= HIGH_CONFIDENCE }
            return "AutoLearn: $total patterns ($promoted promoted, $highConf high-conf)"
        }
    }

    fun getPromotedCount(): Int {
        lock.read {
            return patterns.values.count { it.count >= PROMOTION_THRESHOLD }
        }
    }

    fun getTotalCount(): Int {
        lock.read {
            return patterns.size
        }
    }

    fun clearAll() {
        lock.write {
            patterns.clear()
            savePatterns()
            Log.d(TAG, "All patterns cleared")
        }
    }
}
