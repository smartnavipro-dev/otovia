package com.otovia.app

import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import com.otovia.app.ocr.QuotaManager

/**
 * QuotaSettingsActivity - API Quota管理画面
 *
 * v1.0.84で追加
 *
 * 機能:
 * - API残量の詳細表示
 * - 使用率とパーセント表示
 * - リセットまでの時間表示
 * - 統計情報表示
 * - 手動リセット機能 (デバッグモードのみ)
 */
class QuotaSettingsActivity : AppCompatActivity() {

    private lateinit var quotaManager: QuotaManager
    private val uiUpdateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    // UI要素
    private lateinit var quotaStatusText: TextView
    private lateinit var remainingText: TextView
    private lateinit var usagePercentText: TextView
    private lateinit var resetTimeText: TextView
    private lateinit var nextResetText: TextView
    private lateinit var todayUsageText: TextView
    private lateinit var avgFrequencyText: TextView
    private lateinit var lastUsedText: TextView
    private lateinit var btnManualReset: Button
    private lateinit var btnClose: Button

    companion object {
        private const val TAG = "Otovia_QuotaSettings"
        private const val UI_UPDATE_INTERVAL_MS = 60000L // 1分毎にUI更新
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quota_settings)

        quotaManager = QuotaManager(this)

        initializeViews()
        setupListeners()
        updateUI()
        startAutoUIUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoUIUpdate()
    }

    /**
     * UI要素の初期化
     */
    private fun initializeViews() {
        quotaStatusText = findViewById(R.id.quotaStatusText)
        remainingText = findViewById(R.id.remainingText)
        usagePercentText = findViewById(R.id.usagePercentText)
        resetTimeText = findViewById(R.id.resetTimeText)
        nextResetText = findViewById(R.id.nextResetText)
        todayUsageText = findViewById(R.id.todayUsageText)
        avgFrequencyText = findViewById(R.id.avgFrequencyText)
        lastUsedText = findViewById(R.id.lastUsedText)
        btnManualReset = findViewById(R.id.btnManualReset)
        btnClose = findViewById(R.id.btnClose)

        // デバッグモード判定
        if (isDebugMode()) {
            btnManualReset.visibility = View.VISIBLE
        } else {
            btnManualReset.visibility = View.GONE
        }
    }

    /**
     * イベントリスナーの設定
     */
    private fun setupListeners() {
        btnManualReset.setOnClickListener {
            onManualResetClick()
        }

        btnClose.setOnClickListener {
            finish()
        }
    }

    /**
     * UI更新
     */
    private fun updateUI() {
        val status = quotaManager.getStatus()

        // 現在の使用状況
        val color = when {
            status.remaining >= 15 -> getColor(android.R.color.holo_green_dark)
            status.remaining >= 5 -> getColor(android.R.color.holo_orange_dark)
            else -> getColor(android.R.color.holo_red_dark)
        }
        quotaStatusText.text = "現在の使用状況: ${status.count}/${status.limit}"
        quotaStatusText.setTextColor(color)

        // 残りリクエスト数
        remainingText.text = "残り: ${status.remaining} リクエスト"

        // 使用率
        val usagePercent = ((status.count.toFloat() / status.limit) * 100).toInt()
        usagePercentText.text = "使用率: ${usagePercent}%"

        // リセットまでの時間
        val resetTimeString = getResetTimeString(status.resetTime)
        resetTimeText.text = "リセットまで: $resetTimeString"

        // 次回リセット時刻
        val resetDateString = getResetDateString(status.resetTime)
        nextResetText.text = "次回リセット: $resetDateString"

        // 統計情報
        updateStatistics(status)
    }

    /**
     * 統計情報の更新
     */
    private fun updateStatistics(status: QuotaManager.QuotaStatus) {
        // 本日の使用回数
        todayUsageText.text = "本日の使用: ${status.count}回"

        // 平均使用頻度 (過去24時間)
        val hoursSinceLastReset = TimeUnit.MILLISECONDS.toHours(
            System.currentTimeMillis() - status.firstRequestTime
        )
        val avgFrequency = if (hoursSinceLastReset > 0) {
            status.count.toFloat() / hoursSinceLastReset
        } else {
            0f
        }
        avgFrequencyText.text = "平均使用頻度: %.2f回/時間".format(avgFrequency)

        // 最終使用時刻
        val lastUsedString = getLastUsedString(status.firstRequestTime)
        lastUsedText.text = "最終使用: $lastUsedString"
    }

    /**
     * リセットまでの時間を文字列化
     */
    private fun getResetTimeString(resetTime: Long): String {
        val now = System.currentTimeMillis()
        val diffMs = resetTime - now

        if (diffMs <= 0) {
            return "まもなくリセット"
        }

        val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs) % 60

        return "${hours}時間 ${minutes}分"
    }

    /**
     * リセット日時を文字列化
     */
    private fun getResetDateString(resetTime: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN)
        return sdf.format(Date(resetTime))
    }

    /**
     * 最終使用時刻を文字列化
     */
    private fun getLastUsedString(lastUsedTime: Long): String {
        if (lastUsedTime == 0L) {
            return "未使用"
        }

        val now = System.currentTimeMillis()
        val diffMs = now - lastUsedTime

        val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs) % 60

        return when {
            hours > 0 -> "${hours}時間${minutes}分前"
            minutes > 0 -> "${minutes}分前"
            else -> "1分以内"
        }
    }

    /**
     * 自動UI更新の開始
     */
    private fun startAutoUIUpdate() {
        updateRunnable = object : Runnable {
            override fun run() {
                updateUI()
                uiUpdateHandler.postDelayed(this, UI_UPDATE_INTERVAL_MS)
            }
        }
        uiUpdateHandler.postDelayed(updateRunnable!!, UI_UPDATE_INTERVAL_MS)
    }

    /**
     * 自動UI更新の停止
     */
    private fun stopAutoUIUpdate() {
        updateRunnable?.let {
            uiUpdateHandler.removeCallbacks(it)
        }
    }

    /**
     * デバッグモード判定
     */
    private fun isDebugMode(): Boolean {
        return BuildConfig.DEBUG ||
                (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * 手動リセットボタンクリック
     */
    private fun onManualResetClick() {
        if (!isDebugMode()) {
            return
        }

        showResetConfirmDialog()
    }

    /**
     * リセット確認ダイアログ表示
     */
    private fun showResetConfirmDialog() {
        val status = quotaManager.getStatus()

        AlertDialog.Builder(this)
            .setTitle("Quota 手動リセット確認")
            .setMessage(
                "API Quotaを手動でリセットします\n\n" +
                        "現在: ${status.count}/${status.limit} → リセット後: 20/20\n\n" +
                        "この操作は開発・テスト用です。"
            )
            .setPositiveButton("リセット実行") { dialog, _ ->
                performManualReset()
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * 手動リセット実行
     */
    private fun performManualReset() {
        quotaManager.resetQuota()
        updateUI()

        // 成功メッセージ
        AlertDialog.Builder(this)
            .setTitle("リセット完了")
            .setMessage("API Quotaが20/20にリセットされました。")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
