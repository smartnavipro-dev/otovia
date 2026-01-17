package com.kindletts.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.graphics.Color
import android.animation.ObjectAnimator
import android.animation.ArgbEvaluator
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kindletts.reader.databinding.ActivityMainBinding
import com.kindletts.reader.ocr.QuotaManager
import java.util.*
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.kindletts.reader.workers.QuotaResetWorker
import java.util.concurrent.TimeUnit
import com.kindletts.reader.privacy.PrivacyConsentDialog
import com.kindletts.reader.privacy.PrivacyPreferences
import com.kindletts.reader.privacy.LocalCorrectionManager

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private var textToSpeech: TextToSpeech? = null
    private lateinit var sharedPreferences: SharedPreferences

    // v1.0.82: QuotaManager
    private lateinit var quotaManager: QuotaManager
    private lateinit var quotaTextView: TextView

    // v1.1.0: LocalCorrectionManager (learning feature)
    private var localCorrectionManager: LocalCorrectionManager? = null

    // 状態管理
    private var isReading = false
    private var isPaused = false
    private var currentReadingSpeed = 1.0f
    private var autoPageTurnEnabled = true
    private var pageDirection = "right_to_next" // "right_to_next" or "left_to_next"

    // MediaProjection 関連
    private var mediaProjectionManager: MediaProjectionManager? = null

    // 権限リクエスト
    private val screenCapturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { data ->
                    startOverlayServiceAndReading(data)
                }
            } else {
                showToast("画面キャプチャ権限が必要です")
            }
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                debugLog("Overlay permission granted")
                updatePermissionButtonStates()
            } else {
                showToast("オーバーレイ権限が必要です")
            }
        }

    private val accessibilitySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            checkAccessibilityServiceEnabled()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        debugLog("MainActivity created")

        // SharedPreferences初期化
        sharedPreferences = getSharedPreferences("KindleTTSPrefs", Context.MODE_PRIVATE)

        // v1.0.82: QuotaManager初期化
        quotaManager = QuotaManager(this)
        quotaTextView = binding.quotaTextView
        updateQuotaDisplay()

        // TTS初期化
        textToSpeech = TextToSpeech(this, this)

        // MediaProjectionManager初期化
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // UI初期化
        setupUI()
        loadSettings()
        updatePermissionButtonStates()
        updateUIState()


        // v1.0.84: Quota Reset通知WorkManager設定
        setupQuotaResetWorker()

        // v1.1.0: プライバシー同意ダイアログ表示（初回起動時のみ）
        showPrivacyConsentDialogIfNeeded()

        // v1.1.0: LocalCorrectionManager初期化（同意済みの場合のみ）
        initializeLocalCorrectionManager()

        debugLog("MainActivity initialization completed")
    }

    private fun setupUI() {
        // 読み上げコントロール
        binding.btnStartReading.setOnClickListener { toggleReading() }
        binding.btnPauseResume.setOnClickListener { togglePauseResume() }

        // ページコントロール
        binding.btnPrevPage.setOnClickListener { previousPage() }
        binding.btnNextPage.setOnClickListener { nextPage() }

        // 権限ボタン（アクセシビリティのみ表示）
        binding.btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        // v1.1.0: 設定画面への遷移
        binding.btnSettings.setOnClickListener { openSettings() }
        // v1.0.84: Quota設定画面への遷移
        binding.quotaDisplay.setOnClickListener { openQuotaSettings() }

        // 設定コントロール
        binding.speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentReadingSpeed = (progress + 1) * 0.25f // 0.25倍から2.0倍まで
                    applyTTSSettings()
                    saveSettings()
                    debugLog("Reading speed changed to $currentReadingSpeed")
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.autoPageTurnSwitch.setOnCheckedChangeListener { _, isChecked ->
            autoPageTurnEnabled = isChecked
            saveSettings()
            debugLog("Auto page turn: $autoPageTurnEnabled")
        }

        // ページめくり方向設定
        binding.pageDirectionGroup.setOnCheckedChangeListener { _, checkedId ->
            pageDirection = when (checkedId) {
                R.id.radioLeftToNext -> "left_to_next"
                else -> "right_to_next"
            }
            saveSettings()
            sendPageDirectionToService()
            debugLog("Page direction changed", pageDirection)
        }
    }

    private fun toggleReading() {
        val timestamp = System.currentTimeMillis()
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        debugLog("=== TOGGLE READING ===", """
            timestamp: $timestamp,
            current_state: ${if (isReading) "READING" else "STOPPED"},
            accessibility_enabled: $accessibilityEnabled
        """.trimIndent())

        // アクセシビリティ権限のみチェック（画面キャプチャは自動で要求）
        if (!accessibilityEnabled) {
            debugLog("[Permission Check] FAILED", "Accessibility service not enabled")
            showPermissionDialog()
            return
        }

        if (isReading) {
            debugLog("[State Transition]", "READING → STOPPED")
            stopReading()
        } else {
            debugLog("[State Transition]", "STOPPED → READING")
            startReading()
        }
    }

    private fun startReading() {
        val timestamp = System.currentTimeMillis()
        debugLog("=== START READING (MainActivity) ===", """
            timestamp: $timestamp,
            OverlayService.isRunning: ${OverlayService.isRunning},
            currentReadingSpeed: $currentReadingSpeed,
            autoPageTurnEnabled: $autoPageTurnEnabled,
            pageDirection: $pageDirection
        """.trimIndent())

        // ✅ v1.0.18 FIX: 常に既存サービスを停止してから新規作成
        // これにより、「×」ボタン後の再起動でもクリーンな状態から開始できる
        debugLog("[Service Cleanup]", "Stopping existing OverlayService if any")
        stopService(Intent(this, OverlayService::class.java))

        // サービスの完全停止を待つ
        debugLog("[Service Cleanup]", "Waiting 300ms for service to stop completely")
        Handler(Looper.getMainLooper()).postDelayed({
            debugLog("[Permission Request]", "Requesting screen capture permission")
            requestScreenCapturePermission()
        }, 300)
    }

    private fun sendPageDirectionToService() {
        if (OverlayService.isRunning) {
            val intent = Intent(this, OverlayService::class.java)
            intent.action = "SET_PAGE_DIRECTION"
            intent.putExtra("page_direction", pageDirection)
            startService(intent)
        }
    }

    private fun stopReading() {
        debugLog("Stopping reading mode")

        isReading = false
        isPaused = false
        updateUIState()
        updateStatusText("準備完了")

        // OverlayServiceに読み上げ停止を通知
        val intent = Intent(this, OverlayService::class.java)
        intent.action = "STOP_READING"
        startService(intent)

        // TTSも停止
        textToSpeech?.stop()
    }

    private fun togglePauseResume() {
        if (!isReading) return

        if (isPaused) {
            debugLog("Resuming reading")
            isPaused = false
            updateStatusText("読み上げ中...")

            val intent = Intent(this, OverlayService::class.java)
            intent.action = "RESUME_READING"
            startService(intent)
        } else {
            debugLog("Pausing reading")
            isPaused = true
            updateStatusText("一時停止中")

            val intent = Intent(this, OverlayService::class.java)
            intent.action = "PAUSE_READING"
            startService(intent)

            textToSpeech?.stop()
        }

        updateUIState()
    }

    private fun previousPage() {
        debugLog("Previous page requested")

        val intent = Intent(this, OverlayService::class.java)
        intent.action = "PREVIOUS_PAGE"
        startService(intent)
    }

    private fun nextPage() {
        debugLog("Next page requested")

        val intent = Intent(this, OverlayService::class.java)
        intent.action = "NEXT_PAGE"
        startService(intent)
    }

    private fun requestScreenCapturePermission() {
        debugLog("Requesting screen capture permission")

        // オーバーレイ権限が無い場合は先にそれを要求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showToast("オーバーレイ権限を先に許可してください")
            requestOverlayPermission()
            return
        }

        // MediaProjection権限ダイアログを表示
        mediaProjectionManager?.let { manager ->
            val captureIntent = manager.createScreenCaptureIntent()
            screenCapturePermissionLauncher.launch(captureIntent)
        }
    }

    private fun requestOverlayPermission() {
        debugLog("Requesting overlay permission")

        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        overlayPermissionLauncher.launch(intent)
    }

    private fun openAccessibilitySettings() {
        debugLog("Opening accessibility settings")

        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        accessibilitySettingsLauncher.launch(intent)
    }
    /**
     * v1.0.84: Quota設定画面を開く
     */
    private fun openQuotaSettings() {
        debugLog("Opening quota settings")

        val intent = Intent(this, QuotaSettingsActivity::class.java)
        startActivity(intent)
    }

    /**
     * v1.1.0: 設定画面を開く
     */
    private fun openSettings() {
        debugLog("Opening settings")

        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    /**
     * v1.0.84: Quota Reset通知WorkManagerの設定
     */
    private fun setupQuotaResetWorker() {
        debugLog("Setting up quota reset worker")
        
        val quotaCheckRequest = PeriodicWorkRequestBuilder<QuotaResetWorker>(
            15, TimeUnit.MINUTES
        ).build()
        
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "quota_reset_check",
            ExistingPeriodicWorkPolicy.KEEP,
            quotaCheckRequest
        )
        
        debugLog("Quota reset worker scheduled")
    }

    private fun startOverlayServiceAndReading(data: Intent) {
        val timestamp = System.currentTimeMillis()
        debugLog("=== START OVERLAY SERVICE ===", """
            timestamp: $timestamp,
            reading_speed: $currentReadingSpeed,
            auto_page_turn: $autoPageTurnEnabled,
            page_direction: $pageDirection,
            screen_capture_data: ${data != null}
        """.trimIndent())

        val intent = Intent(this, OverlayService::class.java)
        intent.action = "START_SERVICE_AND_READING"  // 新しいアクション
        intent.putExtra("screen_capture_data", data)
        intent.putExtra("reading_speed", currentReadingSpeed)
        intent.putExtra("auto_page_turn", autoPageTurnEnabled)
        intent.putExtra("page_direction", pageDirection)
        startService(intent)

        debugLog("[Service Started]", "OverlayService started with action: START_SERVICE_AND_READING")

        // UI状態を更新
        isReading = true
        isPaused = false

        debugLog("[State Update]", "isReading: false→true, isPaused: false")

        // OverlayServiceが起動するまで少し待ってからUIを更新
        debugLog("[UI Update]", "Scheduling UI update after 500ms delay")
        binding.root.postDelayed({
            updatePermissionButtonStates()
            updateUIState()
            updateStatusText("読み上げ中...")
            debugLog("[UI Update]", "UI updated: status='読み上げ中...'")
        }, 500)
    }

    private fun checkAllPermissions(): Boolean {
        return (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) &&
               isAccessibilityServiceEnabled()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/${AutoPageTurnService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        debugLog("Checking accessibility service - Expected: $expectedComponentName, Enabled: $enabledServices")

        // サービス名は {{...}} で囲まれることがあるため、より柔軟に検索
        return enabledServices.contains(expectedComponentName) ||
               enabledServices.contains("${packageName}/.AutoPageTurnService")
    }

    private fun checkAccessibilityServiceEnabled() {
        val isEnabled = isAccessibilityServiceEnabled()
        debugLog("Accessibility service enabled: $isEnabled")
        updatePermissionButtonStates()
    }

    private fun updatePermissionButtonStates() {
        // アクセシビリティ権限状態のみ表示（画面キャプチャは自動）
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        binding.btnAccessibility.text = if (accessibilityEnabled) "アクセシビリティ権限 ✓" else "アクセシビリティ権限を許可"
        binding.btnAccessibility.isEnabled = !accessibilityEnabled

        debugLog("Permission states - Accessibility: $accessibilityEnabled")
    }

    private fun updateUIState() {
        binding.btnStartReading.text = if (isReading) "読み上げ停止" else "読み上げ開始"
        binding.btnPauseResume.text = if (isPaused) "再開" else "一時停止"
        binding.btnPauseResume.isEnabled = isReading

        binding.btnPrevPage.isEnabled = isReading
        binding.btnNextPage.isEnabled = isReading

        // アクセシビリティ権限があれば読み上げ開始可能（画面キャプチャは自動）
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        binding.btnStartReading.isEnabled = accessibilityEnabled || isReading
    }

    private fun updateStatusText(status: String) {
        binding.statusText.text = status
        debugLog("Status updated: $status")
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("権限が必要です")
            .setMessage("Kindle読み上げ機能を使用するには、画面キャプチャ権限とアクセシビリティ権限が必要です。")
            .setPositiveButton("設定") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                } else if (!isAccessibilityServiceEnabled()) {
                    openAccessibilitySettings()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun loadSettings() {
        currentReadingSpeed = sharedPreferences.getFloat("reading_speed", 1.0f)
        autoPageTurnEnabled = sharedPreferences.getBoolean("auto_page_turn", true)
        pageDirection = sharedPreferences.getString("page_direction", "right_to_next") ?: "right_to_next"

        // UIに設定を反映
        val speedProgress = ((currentReadingSpeed / 0.25f) - 1).toInt().coerceIn(0, 8)
        binding.speedSeekBar.progress = speedProgress
        binding.autoPageTurnSwitch.isChecked = autoPageTurnEnabled

        // ページめくり方向設定を反映
        when (pageDirection) {
            "left_to_next" -> binding.radioLeftToNext.isChecked = true
            else -> binding.radioRightToNext.isChecked = true
        }

        debugLog("Settings loaded - Speed: $currentReadingSpeed, AutoPageTurn: $autoPageTurnEnabled, PageDirection: $pageDirection")
    }

    private fun saveSettings() {
        sharedPreferences.edit()
            .putFloat("reading_speed", currentReadingSpeed)
            .putBoolean("auto_page_turn", autoPageTurnEnabled)
            .putString("page_direction", pageDirection)
            .apply()

        debugLog("Settings saved")
    }

    /**
     * v1.1.0: プライバシー同意ダイアログを表示（初回起動時のみ）
     */
    private fun showPrivacyConsentDialogIfNeeded() {
        PrivacyConsentDialog.showIfNeeded(this) { consented ->
            debugLog("Privacy consent result: $consented")

            if (consented) {
                // 同意した場合、LocalCorrectionManagerを初期化
                initializeLocalCorrectionManager()
                showToast("学習機能が有効になりました")
            } else {
                // 拒否した場合のメッセージ
                showToast("学習機能は無効です。設定からいつでも変更できます。")
            }
        }
    }

    /**
     * v1.1.0: LocalCorrectionManagerの初期化
     */
    private fun initializeLocalCorrectionManager() {
        if (PrivacyPreferences.isLearningEnabled(this)) {
            localCorrectionManager = LocalCorrectionManager.getInstance(this)
            val patternCount = localCorrectionManager?.getPatternCount() ?: 0
            debugLog("LocalCorrectionManager initialized with $patternCount patterns")
        } else {
            localCorrectionManager = null
            debugLog("LocalCorrectionManager not initialized (learning disabled)")
        }
    }

    private fun applyTTSSettings() {
        textToSpeech?.setSpeechRate(currentReadingSpeed)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let { tts ->
                val result = tts.setLanguage(Locale.JAPANESE)
                when (result) {
                    TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                        debugLog("Japanese TTS not supported, trying English")
                        tts.setLanguage(Locale.ENGLISH)
                    }
                    else -> {
                        debugLog("TTS initialized successfully with Japanese")
                    }
                }
                applyTTSSettings()
                updateStatusText("準備完了")
            } ?: run {
                debugLog("TTS object is null after successful init")
                updateStatusText("TTS初期化エラー")
            }
        } else {
            debugLog("TTS initialization failed")
            updateStatusText("TTS初期化に失敗しました")
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButtonStates()
        checkAccessibilityServiceEnabled()
        updateQuotaDisplay()  // v1.0.82: クォータ表示更新

        // ✅ v1.0.18 FIX: サービスが停止していたらMainActivityの状態をリセット
        // 「×」ボタンでサービスが停止された場合に対応
        if (!OverlayService.isRunning && isReading) {
            debugLog("Service stopped externally, resetting MainActivity state")
            isReading = false
            isPaused = false
            updateStatusText("準備完了")
        }

        updateUIState()  // ✅ FIX: Update UI state after checking permissions
    }

    override fun onDestroy() {
        super.onDestroy()

        if (isReading) {
            stopReading()
        }

        textToSpeech?.let { tts ->
            tts.stop()
            tts.shutdown()
        }

        debugLog("MainActivity destroyed")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun debugLog(message: String, data: Any? = null) {
        Log.d(TAG, "[$TAG] $message ${if (data != null) ": $data" else ""}")
    }
    /**
     * v1.0.82: クォータ表示を更新
     * v1.0.84: パーセント表示とアニメーション追加
     */
    private fun updateQuotaDisplay() {
        runOnUiThread {
            val status = quotaManager.getStatus()
            
            // v1.0.84: パーセント表示追加
            val usagePercent = ((status.count.toFloat() / status.limit) * 100).toInt()
            val newText = "API残量: ${status.remaining}/${status.limit} (${usagePercent}%)"
            
            // v1.0.84: フェードアウト→テキスト変更→フェードイン
            quotaTextView.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    quotaTextView.text = newText
                    quotaTextView.animate()
                        .alpha(1f)
                        .setDuration(150)
                        .start()
                }
                .start()
            
            // v1.0.84: 色分け表示（スムーズトランジション）
            val targetColor = when {
                status.remaining >= 15 -> Color.GREEN
                status.remaining >= 5 -> Color.rgb(255, 165, 0) // Orange
                else -> Color.RED
            }
            
            val colorAnimator = ObjectAnimator.ofObject(
                quotaTextView,
                "textColor",
                ArgbEvaluator(),
                quotaTextView.currentTextColor,
                targetColor
            )
            colorAnimator.duration = 300
            colorAnimator.start()
        }
    }

    companion object {
        private const val TAG = "KindleTTS_MainActivity"
    }
}