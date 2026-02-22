package com.otovia.app.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.otovia.app.MainActivity
import com.otovia.app.R
import com.otovia.app.ocr.QuotaManager

/**
 * QuotaResetWorker - Quota Reset通知Worker
 *
 * v1.0.84で追加
 *
 * 機能:
 * - 15分毎にQuota状態をチェック
 * - リセット検出時に通知を表示
 * - SharedPreferencesで前回のresetTimeを記録
 *
 * ⚠️ 重要な制約:
 * - Android Doze modeにより、正確な15分間隔での実行は保証されません
 * - 通知は遅延する可能性があります (ベストエフォート方式)
 */
class QuotaResetWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "Otovia_QuotaResetWorker"
        private const val NOTIFICATION_CHANNEL_ID = "quota_reset_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "quota_reset_prefs"
        private const val KEY_LAST_RESET_TIME = "last_reset_time"
    }

    override fun doWork(): Result {
        val quotaManager = QuotaManager(applicationContext)
        val status = quotaManager.getStatus()

        // 前回チェック時のresetTimeを取得
        val lastResetTime = getLastResetTime()

        // リセットが発生したかチェック (resetTimeが変わっている)
        if (status.resetTime > lastResetTime) {
            // リセットが発生した
            showResetNotification()
            saveLastResetTime(status.resetTime)
        }

        return Result.success()
    }

    /**
     * 前回のresetTimeを取得
     */
    private fun getLastResetTime(): Long {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_RESET_TIME, 0L)
    }

    /**
     * 現在のresetTimeを保存
     */
    private fun saveLastResetTime(resetTime: Long) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_RESET_TIME, resetTime).apply()
    }

    /**
     * Reset通知を表示
     */
    private fun showResetNotification() {
        createNotificationChannel()

        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🔄 API Quota リセット完了")
            .setContentText("20回のAPIコールが再度利用可能になりました。")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "20回のAPIコールが再度利用可能になりました。\n" +
                                "次回リセット: 24時間後\n\n" +
                                "※この通知は遅延する場合があります"
                    )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 通知チャネル作成 (Android 8.0以降)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "API Quota Reset通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "API Quotaがリセットされた際の通知"
            }

            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
