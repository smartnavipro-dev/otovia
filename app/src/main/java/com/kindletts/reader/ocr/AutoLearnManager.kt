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
 * - learnFromDiff(): LLM補正前後の文字列差分からパターンを抽出・保存
 * - applyLearnedPatterns(): 学習済みパターンを適用（PROMOTION_THRESHOLD回以上のみ）
 *
 * アルゴリズム: LCS (Longest Common Subsequence) ベースの文字レベルdiff
 *
 * v1.1.34: 安全性強化
 * - from最低3文字（コンテキスト付加後）
 * - 長さ比率チェック（to/from ≤ 2.0）
 * - 文字重複チェック（fromとtoに共通文字が必要）
 * - 句読点のみパターンをブロック
 * - 短パターン(≤2文字)にもコンテキスト付加
 */
class AutoLearnManager private constructor(context: Context) {

    companion object {
        private const val TAG = "KindleTTS_AutoLearn"
        private const val PREFS_NAME = "auto_learn_patterns_v2"  // v1.1.34: 安全版で新キー
        private const val KEY_PATTERNS = "patterns"
        private const val PROMOTION_THRESHOLD = 3   // N回以上で自動適用に昇格
        private const val MAX_PATTERNS = 500        // 最大保存パターン数
        private const val MAX_PATTERN_LENGTH = 12   // from/toの最大文字数
        private const val MAX_DIFF_INPUT = 500      // diff計算の入力上限
        private const val MIN_FROM_LENGTH = 3       // v1.1.34: from最低文字数
        private const val MAX_LENGTH_RATIO = 2.0    // v1.1.34: to/from長さ比率上限

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
        var lastSeen: Long = System.currentTimeMillis()
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

    /**
     * v1.1.34: パターンの安全性を検証
     * 危険なパターン（短すぎ、句読点のみ、長さ比率異常、文字重複なし）を除外
     */
    private fun isSafePattern(from: String, to: String): Boolean {
        // 1. from最低文字数チェック
        if (from.length < MIN_FROM_LENGTH) {
            Log.d(TAG, "[Safety] Rejected (too short): '$from' → '$to'")
            return false
        }

        // 2. fromが句読点で始まるまたは終わる短パターンはブロック
        if (from.length <= 3) {
            val startsWithPunct = from.first() in PUNCTUATION
            val endsWithPunct = from.last() in PUNCTUATION
            if (startsWithPunct || endsWithPunct) {
                Log.d(TAG, "[Safety] Rejected (short+punctuation): '$from' → '$to'")
                return false
            }
        }

        // 3. 長さ比率チェック（toがfromの2倍を超えたら危険）
        if (from.isNotEmpty() && to.length.toDouble() / from.length > MAX_LENGTH_RATIO) {
            Log.d(TAG, "[Safety] Rejected (length ratio ${to.length}/${from.length}): '$from' → '$to'")
            return false
        }

        // 4. 文字重複チェック（fromとtoに少なくとも1文字の共通非句読点文字が必要）
        val fromChars = from.filter { it !in PUNCTUATION }.toSet()
        val toChars = to.filter { it !in PUNCTUATION }.toSet()
        if (fromChars.isNotEmpty() && toChars.isNotEmpty() && fromChars.intersect(toChars).isEmpty()) {
            Log.d(TAG, "[Safety] Rejected (no char overlap): '$from' → '$to'")
            return false
        }

        // 5. to が空の場合（削除パターン）、fromが短すぎないかチェック
        if (to.isEmpty() && from.length < 4) {
            Log.d(TAG, "[Safety] Rejected (short deletion): '$from' → ''")
            return false
        }

        return true
    }

    // --- Learn ---

    /**
     * LLM補正前後の差分からパターンを学習
     * @param preLLM  LLM補正前のテキスト（Phase1等の補正済み）
     * @param postLLM LLM補正後のテキスト
     */
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

                // v1.1.34: 安全性チェック
                if (!isSafePattern(from, to)) {
                    rejectedCount++
                    continue
                }

                val key = from
                val existing = patterns[key]
                if (existing != null && existing.to == to) {
                    existing.count++
                    existing.lastSeen = System.currentTimeMillis()
                    updatedCount++
                } else if (existing == null) {
                    patterns[key] = LearnedPattern(from = from, to = to)
                    newCount++
                }
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
                    Log.d(TAG, "  '$from' → '${p.to}' (count=${p.count}$star)")
                }
            }
        }
    }

    // --- Apply ---

    /**
     * 学習済みパターンを適用（閾値以上のもののみ）
     * @return 補正後テキスト
     */
    fun applyLearnedPatterns(text: String): String {
        lock.read {
            val promoted = patterns.values.filter { it.count >= PROMOTION_THRESHOLD }
            if (promoted.isEmpty()) return text

            var result = text
            var appliedCount = 0

            // 長いパターンを先に適用（部分マッチの誤爆防止）
            for (pattern in promoted.sortedByDescending { it.from.length }) {
                if (result.contains(pattern.from)) {
                    val before = result
                    result = result.replace(pattern.from, pattern.to)
                    if (result != before) {
                        appliedCount++
                        Log.d(TAG, "Applied: '${pattern.from}' → '${pattern.to}' (count=${pattern.count})")
                    }
                }
            }

            if (appliedCount > 0) {
                Log.d(TAG, "Applied $appliedCount learned patterns to text")
            }
            return result
        }
    }

    // --- Diff Algorithm ---

    /**
     * LCSベースの文字レベルdiff抽出
     *
     * 1. LCS（最長共通部分列）のDPテーブルを構築
     * 2. バックトラックで編集操作列(Match/Delete/Insert)を取得
     * 3. 連続する非Match操作をグループ化してdiffブロックに
     * 4. 短いパターン(≤2文字)にはコンテキスト文字を付加 (v1.1.34: 拡張)
     *
     * @return (from, to) ペアのリスト
     */
    private fun extractDiffs(pre: String, post: String): List<Pair<String, String>> {
        val m = pre.length
        val n = post.length
        if (m > MAX_DIFF_INPUT || n > MAX_DIFF_INPUT) return emptyList()
        if (m == 0 && n == 0) return emptyList()

        // LCS DPテーブル
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (pre[i - 1] == post[j - 1]) dp[i - 1][j - 1] + 1
                else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }

        // バックトラック → 編集操作列 (type: 0=match, 1=delete, 2=insert)
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

        // diffブロックへのグループ化
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

            // v1.1.34: 短いパターン(2文字以下)にはコンテキスト付加（従来は1文字以下のみ）
            // 削除パターン(to空)にもコンテキスト付加
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
            return "AutoLearn: $total patterns ($promoted promoted, threshold=$PROMOTION_THRESHOLD)"
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
