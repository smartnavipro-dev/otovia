package com.kindletts.reader.ocr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * v1.0.82: API Quota Management
 *
 * Gemini API無料枠（20 requests/day）の使用状況を監視・管理する。
 *
 * 機能:
 * - 日次リクエストカウント
 * - 24時間自動リセット
 * - 残量チェック
 * - クォータ統計追跡
 * - SharedPreferences永続化
 *
 * アーキテクチャ:
 * - 単一責任原則: クォータ管理のみに特化
 * - 疎結合: LLMCorrectorと独立して動作
 * - 永続化: アプリ再起動後も状態を保持
 */
class QuotaManager(context: Context) {

    companion object {
        private const val TAG = "KindleTTS_QuotaManager"

        /**
         * SharedPreferences設定
         */
        private const val PREFS_NAME = "api_quota_manager"
        private const val KEY_REQUEST_COUNT = "request_count"
        private const val KEY_RESET_TIME = "reset_time_ms"
        private const val KEY_FIRST_REQUEST_TIME = "first_request_time_ms"

        /**
         * クォータ制限設定
         * v1.1.26: テスト用に一時的に100に引き上げ
         */
        const val DAILY_LIMIT = 250                  // v1.1.45: Gemini 2.5 Flash無料枠実際の上限（2025年12月以降）
        const val RESET_INTERVAL_MS = 24 * 60 * 60 * 1000L  // 24時間
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * クォータ状態データクラス
     */
    data class QuotaStatus(
        val count: Int,                 // 現在のリクエスト数
        val limit: Int,                 // 上限
        val resetTime: Long,            // 次回リセット時刻（ms）
        val firstRequestTime: Long,     // 初回リクエスト時刻（ms）
        val remainingMs: Long           // リセットまでの残り時間（ms）
    ) {
        val remaining: Int
            get() = maxOf(limit - count, 0)

        val isExceeded: Boolean
            get() = count >= limit

        val usagePercent: Int
            get() = if (limit > 0) (count * 100 / limit) else 0
    }

    init {
        Log.d(TAG, "[v1.0.82] QuotaManager initialized")
        logCurrentStatus()
    }

    /**
     * APIリクエストを記録
     *
     * 呼び出しタイミング: LLM API呼び出し成功時（200 OK）
     */
    fun recordAPICall() {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val resetTime = prefs.getLong(KEY_RESET_TIME, 0)

            // リセット時刻を過ぎた場合、カウンターをリセット
            if (now >= resetTime || resetTime == 0L) {
                Log.d(TAG, "[v1.0.82] Quota reset (new 24h window)")
                prefs.edit()
                    .putInt(KEY_REQUEST_COUNT, 1)
                    .putLong(KEY_RESET_TIME, now + RESET_INTERVAL_MS)
                    .putLong(KEY_FIRST_REQUEST_TIME, now)
                    .apply()
            } else {
                // カウント増加
                val currentCount = prefs.getInt(KEY_REQUEST_COUNT, 0)
                val newCount = currentCount + 1

                prefs.edit()
                    .putInt(KEY_REQUEST_COUNT, newCount)
                    .apply()

                Log.d(TAG, "[v1.0.82] API call recorded: $newCount/$DAILY_LIMIT")
            }
        }
    }

    /**
     * 現在のクォータ状態を取得
     */
    fun getStatus(): QuotaStatus {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val count = prefs.getInt(KEY_REQUEST_COUNT, 0)
            val resetTime = prefs.getLong(KEY_RESET_TIME, now + RESET_INTERVAL_MS)
            val firstRequestTime = prefs.getLong(KEY_FIRST_REQUEST_TIME, now)
            val remainingMs = maxOf(resetTime - now, 0)

            // リセット時刻を過ぎている場合、カウントをリセット
            if (now >= resetTime && count > 0) {
                Log.d(TAG, "[v1.0.82] Auto-reset quota (24h elapsed)")
                prefs.edit()
                    .putInt(KEY_REQUEST_COUNT, 0)
                    .putLong(KEY_RESET_TIME, now + RESET_INTERVAL_MS)
                    .apply()

                return QuotaStatus(
                    count = 0,
                    limit = DAILY_LIMIT,
                    resetTime = now + RESET_INTERVAL_MS,
                    firstRequestTime = now,
                    remainingMs = RESET_INTERVAL_MS
                )
            }

            return QuotaStatus(
                count = count,
                limit = DAILY_LIMIT,
                resetTime = resetTime,
                firstRequestTime = firstRequestTime,
                remainingMs = remainingMs
            )
        }
    }

    /**
     * リクエスト可能かチェック
     *
     * @return true: リクエスト可能, false: クォータ超過
     */
    fun canMakeRequest(): Boolean {
        val status = getStatus()
        return !status.isExceeded
    }

    /**
     * 残量を取得
     */
    fun getRemaining(): Int {
        return getStatus().remaining
    }

    /**
     * クォータをリセット（デバッグ・テスト用）
     */
    fun resetQuota() {
        synchronized(this) {
            prefs.edit()
                .putInt(KEY_REQUEST_COUNT, 0)
                .putLong(KEY_RESET_TIME, System.currentTimeMillis() + RESET_INTERVAL_MS)
                .putLong(KEY_FIRST_REQUEST_TIME, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "[v1.0.82] Quota manually reset")
        }
    }

    /**
     * 現在の状態をログ出力
     */
    fun logCurrentStatus() {
        val status = getStatus()
        val resetTimeHours = status.remainingMs / (60 * 60 * 1000)
        val resetTimeMinutes = (status.remainingMs % (60 * 60 * 1000)) / (60 * 1000)

        Log.i(TAG, "========================================")
        Log.i(TAG, "[v1.0.82] API Quota Status")
        Log.i(TAG, "========================================")
        Log.i(TAG, "Current usage: ${status.count}/${status.limit} (${status.usagePercent}%)")
        Log.i(TAG, "Remaining: ${status.remaining} requests")
        Log.i(TAG, "Reset in: ${resetTimeHours}h ${resetTimeMinutes}m")
        Log.i(TAG, "Status: ${if (status.isExceeded) "EXCEEDED" else "OK"}")
        Log.i(TAG, "========================================")
    }

    /**
     * 統計情報を取得
     */
    fun getStatistics(): Map<String, Any> {
        val status = getStatus()
        return mapOf(
            "count" to status.count,
            "limit" to status.limit,
            "remaining" to status.remaining,
            "usagePercent" to status.usagePercent,
            "isExceeded" to status.isExceeded,
            "resetTimeMs" to status.resetTime,
            "remainingMs" to status.remainingMs
        )
    }

    /**
     * リセット時刻を文字列で取得（UI表示用）
     *
     * @return "HH:mm" 形式の時刻文字列
     */
    fun getResetTimeString(): String {
        val status = getStatus()
        val hours = status.remainingMs / (60 * 60 * 1000)
        val minutes = (status.remainingMs % (60 * 60 * 1000)) / (60 * 1000)
        return String.format("%02d:%02d", hours, minutes)
    }
}
