package com.otovia.app.privacy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * ローカル修正学習マネージャー
 *
 * v1.1.0で追加: ユーザーの修正パターンをローカルに保存し、OCR認識精度を向上させる
 *
 * 機能:
 * - 修正パターンの保存（OCRテキスト → 修正後テキスト）
 * - パターンマッチングによる自動補正（95%類似度）
 * - 使用統計の記録（使用回数、最終使用日時）
 * - EncryptedSharedPreferences による暗号化保存（AES256-GCM）
 * - スレッドセーフな操作
 * - LRUキャッシュによる高速検索
 *
 * データ構造:
 * ```json
 * {
 *   "ocrText": "認識されたテキスト",
 *   "correctedText": "修正後のテキスト",
 *   "useCount": 5,
 *   "lastUsedAt": 1703001234567,
 *   "createdAt": 1702900000000
 * }
 * ```
 *
 * 使用例:
 * ```kotlin
 * val manager = LocalCorrectionManager.getInstance(context)
 *
 * // 修正パターンを学習
 * manager.learnCorrection("経済", "経済学")
 *
 * // 自動補正を適用
 * val corrected = manager.applyCorrection("経済")
 * // → "経済学" (学習済みの場合)
 * ```
 *
 * @since v1.1.0
 */
class LocalCorrectionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "Otovia_CorrectionMgr"
        private const val PREF_NAME = "user_corrections_encrypted"
        private const val KEY_CORRECTIONS = "corrections"
        private const val KEY_LAST_UPDATED = "last_updated"

        // 類似度閾値（95%以上で同じパターンとみなす）
        private const val SIMILARITY_THRESHOLD = 0.95

        @Volatile
        private var instance: LocalCorrectionManager? = null

        /**
         * シングルトンインスタンスを取得
         */
        fun getInstance(context: Context): LocalCorrectionManager {
            return instance ?: synchronized(this) {
                instance ?: LocalCorrectionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 修正パターンのデータクラス
     */
    data class CorrectionPattern(
        val ocrText: String,           // OCRで認識されたテキスト
        val correctedText: String,     // 修正後のテキスト
        var useCount: Int = 1,         // 使用回数
        var lastUsedAt: Long = System.currentTimeMillis(),  // 最終使用日時
        val createdAt: Long = System.currentTimeMillis()    // 作成日時
    ) {
        /**
         * テキストの類似度を計算（レーベンシュタイン距離ベース）
         */
        fun calculateSimilarity(text: String): Double {
            val distance = levenshteinDistance(ocrText, text)
            val maxLength = maxOf(ocrText.length, text.length)
            return if (maxLength == 0) 1.0 else 1.0 - (distance.toDouble() / maxLength)
        }

        companion object {
            /**
             * レーベンシュタイン距離を計算
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
        }
    }

    private val gson = Gson()
    private val lock = ReentrantReadWriteLock()
    private val encryptedPrefs: SharedPreferences by lazy { createEncryptedPreferences() }

    // LRUキャッシュ（最大100パターン）
    private val cache = object : LinkedHashMap<String, CorrectionPattern>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CorrectionPattern>?): Boolean {
            return size > 100
        }
    }

    /**
     * 暗号化されたSharedPreferencesを作成
     */
    private fun createEncryptedPreferences(): SharedPreferences {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
            // フォールバック: 通常のSharedPreferences（暗号化なし）
            Log.w(TAG, "Falling back to non-encrypted SharedPreferences")
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * 修正パターンを学習
     *
     * @param ocrText OCRで認識されたテキスト
     * @param correctedText 修正後のテキスト
     * @return 学習に成功した場合true
     */
    fun learnCorrection(ocrText: String, correctedText: String): Boolean {
        // 学習機能が無効の場合は何もしない
        if (!PrivacyPreferences.isLearningEnabled(context)) {
            Log.d(TAG, "Learning is disabled, skipping")
            return false
        }

        // 空文字列や同じテキストの場合はスキップ
        if (ocrText.isBlank() || correctedText.isBlank() || ocrText == correctedText) {
            Log.d(TAG, "Invalid correction pattern, skipping: '$ocrText' -> '$correctedText'")
            return false
        }

        return lock.write {
            try {
                val patterns = loadPatterns().toMutableMap()

                // 既存のパターンがあれば使用回数を増やす
                val existing = patterns[ocrText]
                if (existing != null) {
                    existing.useCount++
                    existing.lastUsedAt = System.currentTimeMillis()
                    Log.d(TAG, "Updated existing pattern: '$ocrText' (useCount: ${existing.useCount})")
                } else {
                    // 新しいパターンを追加
                    patterns[ocrText] = CorrectionPattern(ocrText, correctedText)
                    Log.d(TAG, "Learned new correction: '$ocrText' -> '$correctedText'")
                }

                // キャッシュを更新
                cache[ocrText] = patterns[ocrText]!!

                // 保存
                savePatterns(patterns)
                true

            } catch (e: Exception) {
                Log.e(TAG, "Failed to learn correction", e)
                false
            }
        }
    }

    /**
     * 学習済みパターンを適用して補正
     *
     * @param ocrText OCRで認識されたテキスト
     * @return 補正後のテキスト（学習済みパターンがない場合は元のテキスト）
     */
    fun applyCorrection(ocrText: String): String {
        // 学習機能が無効の場合は元のテキストをそのまま返す
        if (!PrivacyPreferences.isLearningEnabled(context)) {
            return ocrText
        }

        return lock.read {
            try {
                // 完全一致をチェック
                cache[ocrText]?.let { pattern ->
                    pattern.lastUsedAt = System.currentTimeMillis()
                    pattern.useCount++
                    Log.d(TAG, "Applied exact match correction: '$ocrText' -> '${pattern.correctedText}'")
                    return@read pattern.correctedText
                }

                // 類似パターンを検索
                val patterns = loadPatterns()
                val bestMatch = patterns.values
                    .map { it to it.calculateSimilarity(ocrText) }
                    .filter { it.second >= SIMILARITY_THRESHOLD }
                    .maxByOrNull { it.second }
                    ?.first

                if (bestMatch != null) {
                    bestMatch.lastUsedAt = System.currentTimeMillis()
                    bestMatch.useCount++
                    cache[ocrText] = bestMatch
                    Log.d(TAG, "Applied similar pattern correction: '$ocrText' -> '${bestMatch.correctedText}' (similarity: ${bestMatch.calculateSimilarity(ocrText)})")
                    return@read bestMatch.correctedText
                }

                // 学習済みパターンがない場合は元のテキスト
                ocrText

            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply correction", e)
                ocrText
            }
        }
    }

    /**
     * 学習パターン数を取得
     */
    fun getPatternCount(): Int {
        return lock.read {
            try {
                loadPatterns().size
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get pattern count", e)
                0
            }
        }
    }

    /**
     * 最終更新日時を取得
     */
    fun getLastUpdated(): Long {
        return lock.read {
            encryptedPrefs.getLong(KEY_LAST_UPDATED, 0L)
        }
    }

    /**
     * すべての学習データを削除
     */
    fun clearAll(): Boolean {
        return lock.write {
            try {
                encryptedPrefs.edit().clear().apply()
                cache.clear()
                Log.d(TAG, "All learning data cleared")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear learning data", e)
                false
            }
        }
    }

    /**
     * 学習パターンをロード
     */
    private fun loadPatterns(): Map<String, CorrectionPattern> {
        return try {
            val json = encryptedPrefs.getString(KEY_CORRECTIONS, null)
            if (json.isNullOrEmpty()) {
                emptyMap()
            } else {
                val type = object : TypeToken<Map<String, CorrectionPattern>>() {}.type
                gson.fromJson(json, type)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load patterns", e)
            emptyMap()
        }
    }

    /**
     * 学習パターンを保存
     */
    private fun savePatterns(patterns: Map<String, CorrectionPattern>) {
        try {
            val json = gson.toJson(patterns)
            encryptedPrefs.edit().apply {
                putString(KEY_CORRECTIONS, json)
                putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "Saved ${patterns.size} correction patterns")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save patterns", e)
            throw e
        }
    }
}
