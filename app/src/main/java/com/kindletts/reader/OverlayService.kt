package com.kindletts.reader

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.kindletts.reader.ocr.AutoLearnManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.kindletts.reader.ocr.QuotaManager
import com.kindletts.reader.ocr.TextCorrector
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OverlayService : Service(), TextToSpeech.OnInitListener {

    companion object {
        var isRunning = false
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "KindleTTSService"
        private const val TAG = "KindleTTS_Service"
        // v1.1.45: パターン貢献エンドポイント（Google Apps Script）
        private const val CONTRIBUTION_ENDPOINT = "https://script.google.com/macros/s/AKfycbwBVOWRelXU79f68DcGi9C1i1Fwx4irw8dojRD002GRni9Y4AdE5MJZTC7nN8aqOaGQuA/exec"
    }

    // UI関連
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 画面キャプチャ関連
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestImage: Image? = null  // v1.0.83: OnImageAvailableListenerから取得した最新画像
    private var screenWidth = 0
    private var screenHeight = 0

    // TTS関連
    private var textToSpeech: TextToSpeech? = null
    private var currentSentences: List<String> = emptyList()
    private var currentSentenceIndex = 0
    private var isReading = false
    private var isPaused = false
    private var readingSpeed = 1.0f
    private var autoPageTurnEnabled = true
    private var pageDirection = "right_to_next" // "right_to_next" or "left_to_next"

    // OCR関連
    private var lastRecognizedText = ""
    private var lastExtractedText = ""  // v1.1.28: LLM前の重複チェック用（生OCRテキスト）
    private var lastCorrectedPageText = ""  // v1.1.33: 前ページコンテキスト用（ページめくり後も保持）
    private var trailingFragment = ""  // v1.1.35: 跨ページ文章結合用（前ページ末尾の不完全文）
    private var hasSpokenForCurrentPage = false  // v1.1.35: 同一ページで既にTTS開始したか
    private var currentSpokenSentence = ""  // v1.1.37: ユーザーフィードバック用（現在読み上げ中の文）
    private var correctionDialogView: View? = null  // v1.1.37: 修正ダイアログのView
    // v1.1.41: セッション統計（LLM使用/スキップ カウント）
    private var sessionLLMUsed = 0
    private var sessionLLMSkipped = 0
    // v1.1.44: per-page補正統計（ステータスバッジ用）
    private var lastPageLLMUsed = false
    private var lastPageAutoLearnCount = 0
    private var prefetchPageLLMUsed = false      // prefetchパス用の一時保存
    private var prefetchPageAutoLearnCount = 0   // prefetchパス用の一時保存
    // v1.1.42: バックグラウンドプリフェッチ状態
    private var prefetchedSentences: List<String>? = null  // null=未完了
    private var prefetchedCorrectedText: String = ""       // lastRecognizedText更新用
    private var isPrefetching = false                       // プリフェッチ実行中
    private var prefetchGestureSent = false                 // ジェスチャー送信済み
    private var prefetchGeneration = 0                      // v1.1.43: ステールコールバック検出用
    // v1.1.46: ページ履歴（前ページ修正機能）
    private data class PageHistoryEntry(val pageNumber: Int, val sentences: List<String>)
    private val pageHistory = ArrayDeque<PageHistoryEntry>()
    private val MAX_PAGE_HISTORY = 5
    private var ocrExecutor: ScheduledExecutorService? = null
    private var isCapturing = false
    // v1.0.17: テキスト補正機能, v1.0.39: contextパラメータ追加
    private val textCorrector by lazy { TextCorrector(this) }
    private val quotaManager by lazy { QuotaManager(this) }

    // 状態管理
    private data class AppState(
        var isReading: Boolean = false,
        var isPaused: Boolean = false,
        var ttsInitialized: Boolean = false,
        var screenCaptureActive: Boolean = false,
        var currentPage: Int = 1,
        var totalSentences: Int = 0,
        var currentSentence: Int = 0
    )

    private val appState = AppState()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        debugLog("OverlayService created")

        // TTS初期化
        textToSpeech = TextToSpeech(this, this)

        // OCR実行スケジューラー初期化
        ocrExecutor = Executors.newSingleThreadScheduledExecutor()

        // 画面サイズ取得
        initializeScreenMetrics()

        // 通知チャンネル作成
        createNotificationChannel()

        // フォアグラウンドサービス開始
        startForeground(NOTIFICATION_ID, createNotification("Kindle TTS Reader 準備中..."))

        debugLog("OverlayService initialization completed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debugLog("onStartCommand called", intent?.action)

        when (intent?.action) {
            "START_SERVICE" -> {
                val data = intent.getParcelableExtra<Intent>("screen_capture_data")
                readingSpeed = intent.getFloatExtra("reading_speed", 1.0f)
                autoPageTurnEnabled = intent.getBooleanExtra("auto_page_turn", true)
                pageDirection = intent.getStringExtra("page_direction") ?: "right_to_next"

                if (data != null) {
                    startScreenCapture(data)
                    createOverlay()
                }
            }
            "START_SERVICE_AND_READING" -> {
                // 画面キャプチャとオーバーレイを開始し、その後自動的に読み上げを開始
                val data = intent.getParcelableExtra<Intent>("screen_capture_data")
                readingSpeed = intent.getFloatExtra("reading_speed", 1.0f)
                autoPageTurnEnabled = intent.getBooleanExtra("auto_page_turn", true)
                pageDirection = intent.getStringExtra("page_direction") ?: "right_to_next"

                if (data != null) {
                    // ✅ v1.0.18 FIX: 既存のMediaProjectionをクリーンアップしてから新規作成
                    cleanupMediaProjection()

                    startScreenCapture(data)

                    // オーバーレイが既に存在する場合は再作成しない
                    if (overlayView == null) {
                        createOverlay()
                    }

                    // 画面キャプチャとTTSの初期化を待ってから読み上げ開始
                    mainHandler.postDelayed({
                        if (appState.screenCaptureActive && appState.ttsInitialized) {
                            startReading()
                        } else {
                            debugLog("Auto-start reading failed", "ScreenCapture: ${appState.screenCaptureActive}, TTS: ${appState.ttsInitialized}")
                        }
                    }, 1000)
                }
            }
            "START_READING" -> {
                readingSpeed = intent.getFloatExtra("reading_speed", 1.0f)
                autoPageTurnEnabled = intent.getBooleanExtra("auto_page_turn", true)
                pageDirection = intent.getStringExtra("page_direction") ?: "right_to_next"
                startReading()
            }
            "SET_PAGE_DIRECTION" -> {
                pageDirection = intent.getStringExtra("page_direction") ?: "right_to_next"
                debugLog("Page direction changed", pageDirection)
            }
            "STOP_READING" -> stopReading()
            "PAUSE_READING" -> pauseReading()
            "RESUME_READING" -> resumeReading()
            "NEXT_PAGE" -> nextPage()
            "PREVIOUS_PAGE" -> previousPage()
        }

        return START_STICKY
    }

    private fun initializeScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        }

        debugLog("Screen metrics", "Width: $screenWidth, Height: $screenHeight")
    }

    private fun startScreenCapture(data: Intent) {
        debugLog("Starting screen capture")

        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, data)

            // ✅ FIX: MediaProjectionコールバックを登録（必須）
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    debugLog("MediaProjection stopped")
                    appState.screenCaptureActive = false
                }
            }, Handler(Looper.getMainLooper()))

            // ImageReader設定
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 5)  // v1.0.83: maxImages増加 (2→5)


            // v1.0.83: OnImageAvailableListenerで画像をキャッシュ
            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        debugLog("Image available from listener", "size: ${image.width}x${image.height}")
                        // 古い画像を解放してから新しい画像を保存
                        latestImage?.close()
                        latestImage = image
                    } else {
                        debugLog("OnImageAvailableListener: null image")
                    }
                } catch (e: Exception) {
                    debugLog("Error in OnImageAvailableListener", e.message ?: "unknown")
                }
            }, Handler(Looper.getMainLooper()))

            // VirtualDisplay作成
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "KindleTTSCapture",
                screenWidth,
                screenHeight,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            appState.screenCaptureActive = true
            updateNotification("画面キャプチャ準備完了")
            debugLog("Screen capture started successfully")

        } catch (e: Exception) {
            handleError("画面キャプチャ開始エラー", e)
        }
    }

    private fun createOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )

            layoutParams.gravity = Gravity.TOP or Gravity.END
            layoutParams.x = 50
            layoutParams.y = 200

            windowManager?.addView(overlayView, layoutParams)
            setupOverlayButtons()
            makeOverlayDraggable()

            debugLog("Overlay created successfully")

        } catch (e: Exception) {
            handleError("オーバーレイ作成エラー", e)
        }
    }

    private fun setupOverlayButtons() {
        overlayView?.let { view ->
            // ボタンの取得
            val btnPrevious = view.findViewById<ImageView>(R.id.btnPreviousPage)
            val btnPlayPause = view.findViewById<ImageView>(R.id.btnPlayPause)
            val btnNext = view.findViewById<ImageView>(R.id.btnNextPage)
            val btnClose = view.findViewById<ImageView>(R.id.btnCloseOverlay)

            // 前ページボタン
            btnPrevious?.setOnClickListener {
                debugLog("Previous page button clicked")
                previousPage()
            }

            // 再生/一時停止ボタン
            btnPlayPause?.setOnClickListener {
                debugLog("Play/Pause button clicked", "isReading: $isReading, isPaused: $isPaused")

                if (!isReading) {
                    // 読み上げ開始
                    startReading()
                } else {
                    // 一時停止/再開
                    if (isPaused) {
                        resumeReading()
                    } else {
                        pauseReading()
                    }
                }

                // ボタンアイコン更新
                updatePlayPauseButton()
            }

            // 次ページボタン
            btnNext?.setOnClickListener {
                debugLog("Next page button clicked")
                nextPage()
            }

            // 閉じるボタン
            btnClose?.setOnClickListener {
                debugLog("Close button clicked")
                // ✅ v1.0.18 FIX: サービス終了前にisRunningをfalseに設定
                // これにより、MainActivityが再起動時に画面キャプチャを再要求できる
                isRunning = false
                stopSelf()
            }

            // v1.1.38: 学習パターン管理ボタン
            val btnPatternManager = view.findViewById<TextView>(R.id.btnPatternManager)
            btnPatternManager?.setOnClickListener {
                debugLog("[PatternManager] Button tapped")
                showPatternManagerDialog()
            }
            // v1.1.46: 長押し → ページ履歴ダイアログ
            btnPatternManager?.isLongClickable = true
            btnPatternManager?.setOnLongClickListener {
                debugLog("[PageHistory] Long press on pattern manager button")
                showPageHistoryDialog()
                true
            }

            // v1.1.37: テキストのロングタップ - 読み上げ中→修正ダイアログ、待機中→パターン管理
            val overlayText = view.findViewById<TextView>(R.id.overlayText)
            overlayText?.isLongClickable = true
            overlayText?.setOnLongClickListener {
                if (currentSpokenSentence.isNotEmpty()) {
                    debugLog("[UserFeedback] Long press detected", "sentence: ${currentSpokenSentence.take(30)}")
                    showCorrectionDialog(currentSpokenSentence)
                } else {
                    debugLog("[PatternManager] Long press detected (no active sentence)")
                    showPatternManagerDialog()
                }
                true
            }

            updateOverlayUI()
            updatePlayPauseButton()
        }
    }

    private fun updatePlayPauseButton() {
        overlayView?.let { view ->
            val btnPlayPause = view.findViewById<ImageView>(R.id.btnPlayPause)
            btnPlayPause?.setImageResource(
                if (isReading && !isPaused) {
                    android.R.drawable.ic_media_pause  // 一時停止アイコン
                } else {
                    android.R.drawable.ic_media_play   // 再生アイコン
                }
            )
        }
    }

    private fun makeOverlayDraggable() {
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val params = overlayView?.layoutParams as WindowManager.LayoutParams
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val params = overlayView?.layoutParams as WindowManager.LayoutParams
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(overlayView, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun startReading() {
        val timestamp = System.currentTimeMillis()
        debugLog("=== START READING MODE ===", """
            timestamp: $timestamp,
            ttsInitialized: ${appState.ttsInitialized},
            screenCaptureActive: ${appState.screenCaptureActive},
            currentPage: ${appState.currentPage},
            isReading: $isReading,
            isPaused: $isPaused
        """.trimIndent())

        if (!appState.ttsInitialized) {
            debugLog("[State Check] TTS not initialized", "Cannot start reading")
            showToast("TTS初期化中です。しばらくお待ちください。")
            return
        }

        if (!appState.screenCaptureActive) {
            debugLog("[State Check] Screen capture not active", "Cannot start reading")
            showToast("画面キャプチャが開始されていません。")
            return
        }

        isReading = true
        isPaused = false
        appState.isReading = true
        appState.isPaused = false
        trailingFragment = ""  // v1.1.35: 新規読み上げ開始時にリセット
        hasSpokenForCurrentPage = false  // v1.1.35

        debugLog("[State Transition]", "isReading: false→true, isPaused: false")

        updateNotification("読み上げ中...")
        updateOverlayUI()
        updatePlayPauseButton()

        // 自動OCR開始
        startAutoOCR()
        debugLog("=== READING MODE STARTED ===", "Auto OCR enabled")
    }

    private fun stopReading() {
        debugLog("Stopping reading mode")

        isReading = false
        isPaused = false
        trailingFragment = ""  // v1.1.35: 跨ページ断片をリセット
        appState.isReading = false
        appState.isPaused = false
        resetPrefetchState()  // v1.1.42

        stopAutoOCR()
        textToSpeech?.stop()

        updateNotification("読み上げ停止")
        updateOverlayUI()
        updatePlayPauseButton()
    }

    private fun pauseReading() {
        debugLog("Pausing reading")

        // v1.1.44: ジェスチャー送信済み状態を保存（resume後のfinishCurrentPageで正しく処理するため）
        val gestureSentBeforePause = prefetchGestureSent
        isPaused = true
        appState.isPaused = true
        resetPrefetchState()  // v1.1.42: プリフェッチをキャンセル（prefetchGestureSentもクリアされる）

        // v1.1.44: ページジェスチャーが送信済みだった場合は復元する
        // （resume後に最終文が終わったとき、finishCurrentPage → resetPageStateForNewPage+OCR を使わせる）
        if (gestureSentBeforePause) {
            prefetchGestureSent = true
            debugLog("[v1.1.44 Pause]", "Preserved prefetchGestureSent=true — page was already turned")
        }

        textToSpeech?.stop()
        stopAutoOCR()

        updateNotification("一時停止中")
        updateOverlayUI()
        updatePlayPauseButton()
    }

    private fun resumeReading() {
        debugLog("Resuming reading")

        isPaused = false
        appState.isPaused = false

        updateNotification("読み上げ中...")
        updateOverlayUI()
        updatePlayPauseButton()

        // 現在の文から再開
        if (currentSentenceIndex < currentSentences.size) {
            speakCurrentSentence()
        } else {
            startAutoOCR()
        }
    }

    private fun startAutoOCR() {
        debugLog("Starting auto OCR")

        // 既存のExecutorが停止している場合のみ新しく作成
        if (ocrExecutor == null || ocrExecutor?.isShutdown == true) {
            ocrExecutor = Executors.newSingleThreadScheduledExecutor()
            debugLog("Created new OCR executor")
        }

        ocrExecutor?.scheduleAtFixedRate({
            try {
                debugLog("OCR schedule tick", "isReading: $isReading, isPaused: $isPaused, isCapturing: $isCapturing")
                if (isReading && !isPaused && !isCapturing) {
                    performOCR()
                } else {
                    debugLog("Skipping OCR", "Conditions not met")
                }
            } catch (e: Exception) {
                debugLog("OCR schedule error", e.message)
            }
        }, 0, 2, TimeUnit.SECONDS)

        debugLog("OCR schedule started")
    }

    private fun stopAutoOCR() {
        debugLog("Stopping auto OCR")

        ocrExecutor?.let { executor ->
            if (!executor.isShutdown) {
                executor.shutdown()
                try {
                    if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        executor.shutdownNow()
                    }
                } catch (e: InterruptedException) {
                    executor.shutdownNow()
                    Thread.currentThread().interrupt()
                }
            }
        }
        // 新しいExecutorは必要時のみ作成（startAutoOCRで作成）
    }

    private fun performOCR() {
        val ocrStartTime = System.currentTimeMillis()

        if (isCapturing || imageReader == null) {
            debugLog("performOCR skipped", "isCapturing: $isCapturing, imageReader: ${imageReader != null}")
            return
        }

        isCapturing = true
        debugLog("=== OCR START ===", "timestamp: $ocrStartTime, isReading: $isReading, isPaused: $isPaused")

        try {
            // v1.0.83: まずキャッシュされた画像を使用、なければ直接取得
            val imageAcquireStart = System.currentTimeMillis()
            var image = latestImage
            var usingCachedImage = true

            if (image == null) {
                usingCachedImage = false
                image = imageReader?.acquireLatestImage()
                val acquireTime = System.currentTimeMillis() - imageAcquireStart
                debugLog("Image acquired directly", "image: ${image != null}, time: ${acquireTime}ms")
            } else {
                val acquireTime = System.currentTimeMillis() - imageAcquireStart
                debugLog("Using cached image from listener", "image: ${image != null}, time: ${acquireTime}ms")
                latestImage = null  // キャッシュをクリア（1回のみ使用）
            }

            if (image != null) {
                val conversionStart = System.currentTimeMillis()
                debugLog("Converting image to bitmap", "cached: $usingCachedImage, size: ${image.width}x${image.height}")
                val bitmap = convertImageToBitmap(image)
                if (!usingCachedImage) {
                    image.close()  // 直接取得した画像のみここでclose
                }
                // キャッシュされた画像はconvertImageToBitmapで処理後にcloseされる

                val conversionTime = System.currentTimeMillis() - conversionStart
                debugLog("Bitmap created", "bitmap: ${bitmap != null}, size: ${bitmap?.width}x${bitmap?.height}, time: ${conversionTime}ms")

                if (bitmap != null) {
                    processOCRImage(bitmap)
                    val totalTime = System.currentTimeMillis() - ocrStartTime
                    debugLog("=== OCR COMPLETED ===", "total time: ${totalTime}ms")
                } else {
                    debugLog("Bitmap conversion failed", "elapsed: ${System.currentTimeMillis() - ocrStartTime}ms")
                    isCapturing = false
                }
            } else {
                debugLog("No image available from ImageReader", "elapsed: ${System.currentTimeMillis() - ocrStartTime}ms")
                isCapturing = false
            }
        } catch (e: Exception) {
            val errorTime = System.currentTimeMillis() - ocrStartTime
            Log.e(TAG, "[$TAG] OCR処理エラー (elapsed: ${errorTime}ms)", e)
            debugLog("OCR処理エラー", "message: ${e.message}, stackTrace: ${e.stackTraceToString().take(500)}")
            handleError("OCR処理エラー", e)
            isCapturing = false
        }
    }

    private fun manualCapture() {
        debugLog("Manual capture requested")
        performOCR()
    }

    private fun convertImageToBitmap(image: Image): Bitmap? {
        try {
            debugLog("convertImageToBitmap", "Starting conversion")
            val startTime = System.currentTimeMillis()

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            debugLog("Image plane info", "pixelStride: $pixelStride, rowStride: $rowStride, padding: $rowPadding")

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // ✅ v1.1.4 FIX: バッファコピー後すぐにImageを解放
            // maxImages制限回避のため、使用後すぐにcloseする必要がある
            image.close()
            debugLog("Image closed after buffer copy")

            debugLog("Bitmap created", "Time: ${System.currentTimeMillis() - startTime}ms")

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

            debugLog("Bitmap cropped", "Time: ${System.currentTimeMillis() - startTime}ms, Size: ${screenWidth}x${screenHeight}")

            // ✨ 画像前処理を適用してOCR精度を向上
            val processedBitmap = preprocessBitmapForOCR(croppedBitmap)

            debugLog("Bitmap conversion completed", "Total time: ${System.currentTimeMillis() - startTime}ms")

            return processedBitmap

        } catch (e: Exception) {
            // エラー時もImageを確実に解放
            try {
                image.close()
                debugLog("Image closed in catch block after error")
            } catch (closeError: Exception) {
                debugLog("Error closing image in catch block", closeError.message)
            }
            handleError("ビットマップ変換エラー", e)
            return null
        }
    }

    // v1.0.15: 画像回転フラグを保持（縦書き判定後に使用）
    private var wasImageRotated = false

    /**
     * v1.0.16: 画像回転方式改善 - 縦書きテキストを横書きに変換してOCR精度を根本的に改善
     *
     * ML Kit Japanese OCRは横書きテキストに最適化されているため、
     * 縦書きテキスト（Kindle日本語書籍）を90度回転させて横書き化することで、
     * 認識精度を大幅に向上させる。
     *
     * 処理フロー:
     * 1. 日本語書籍は常に縦書きと仮定して画像を90度回転
     * 2. 適度な前処理（4倍スケール + 中程度のコントラスト）でOCR実行
     * 3. テキスト順序を縦書き読み順（右→左、上→下）に復元
     */
    private fun preprocessBitmapForOCR(bitmap: Bitmap): Bitmap {
        try {
            val startTime = System.currentTimeMillis()
            debugLog("v1.0.29 OCR preprocessing", "Starting (NO rotation)")

            // ✅ v1.0.28: 画像回転を削除 - ML Kitは縦書きを直接認識可能
            // 回転により座標系が混乱していたため、回転を無効化
            // 4倍スケーリングを実行し、OCR座標もスケール後の値となる
            wasImageRotated = false

            // 前処理のみ実行（4倍スケール + コントラスト調整）
            val processedBitmap = applyBalancedPreprocessing(bitmap)

            debugLog("v1.0.29 completed", "No rotation, Time: ${System.currentTimeMillis() - startTime}ms")

            return processedBitmap

        } catch (e: Exception) {
            debugLog("v1.0.28 preprocessing failed", e.message)
            wasImageRotated = false
            return bitmap
        }
    }

    /**
     * クイックサンプリングによる縦書き判定（高速版）
     *
     * 全画像ではなく、中央領域の一部をサンプリングしてテキストの方向性を判定。
     * これにより処理時間を大幅に短縮。
     */
    private fun detectVerticalTextFast(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height

        // 中央領域の20%をサンプリング（Kindle本文領域）
        val sampleLeft = (width * 0.1).toInt()
        val sampleTop = (height * 0.2).toInt()
        val sampleWidth = (width * 0.8).toInt()
        val sampleHeight = (height * 0.6).toInt()

        // エッジ検出による方向性判定
        var verticalEdges = 0
        var horizontalEdges = 0

        val sampleStep = 20  // 20ピクセルごとにサンプリング（高速化）

        for (y in sampleTop until (sampleTop + sampleHeight) step sampleStep) {
            for (x in sampleLeft until (sampleLeft + sampleWidth) step sampleStep) {
                if (x + 1 >= width || y + 1 >= height) continue

                val pixel = bitmap.getPixel(x, y)
                val pixelRight = bitmap.getPixel(x + 1, y)
                val pixelDown = bitmap.getPixel(x, y + 1)

                val gray = Color.red(pixel)
                val grayRight = Color.red(pixelRight)
                val grayDown = Color.red(pixelDown)

                val horizontalDiff = Math.abs(gray - grayRight)
                val verticalDiff = Math.abs(gray - grayDown)

                if (horizontalDiff > 30) horizontalEdges++
                if (verticalDiff > 30) verticalEdges++
            }
        }

        // v1.0.15 改善版: より柔軟な縦書き判定
        // 縦方向のエッジが多い = 文字の上下境界が多い = 横書きの可能性
        // 横方向のエッジが多い = 文字の左右境界が多い = 縦書きの可能性
        // しかし実測では差が小さいため、閾値を大幅に下げる

        val vToHRatio = if (horizontalEdges > 0) verticalEdges.toDouble() / horizontalEdges else 1.0
        val hToVRatio = if (verticalEdges > 0) horizontalEdges.toDouble() / verticalEdges else 1.0

        // 縦書き判定: 縦エッジがやや多い、または拮抗している場合は縦書きと判定
        // 日本語Kindleは縦書きが多いため、判定を緩くする
        val isVertical = vToHRatio >= 1.02  // 2%以上の差があれば縦書き

        debugLog("Edge detection", "V:$verticalEdges, H:$horizontalEdges, V/H:${"%.2f".format(vToHRatio)}, IsVertical:$isVertical")

        return isVertical
    }

    /**
     * 画像を90度時計回りに回転（縦書き→横書き変換）
     */
    private fun rotateBitmap90Clockwise(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(90f)

        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )

        debugLog("Bitmap rotated", "Original: ${bitmap.width}x${bitmap.height}, Rotated: ${rotated.width}x${rotated.height}")

        return rotated
    }

    /**
     * バランス型前処理: 4倍スケール + 中程度のコントラスト
     *
     * 画像回転により精度が大幅に向上するため、過度な前処理（5倍・6倍スケール）は不要。
     * 処理速度とのバランスを取った適度な前処理を適用。
     */
    /**
     * v1.1.15: OCR信頼度向上のための高度な画像前処理
     *
     * 改善内容:
     * 1. CLAHE（適応的ヒストグラム均等化）で局所的なコントラスト改善
     * 2. バイラテラルフィルターでエッジを保持しながらノイズ除去
     * 3. より強力なシャープネス処理でテキストのエッジを強調
     * 4. 2段階スケーリング戦略で処理時間を最適化
     *
     * 処理フロー:
     * 元画像 → 2倍 → グレー化 → CLAHE → デノイズ → シャープネス → 2倍 → OCR
     *           ↓        ↓        ↓        ↓           ↓           ↓
     *        10Mピクセル  適応的   ノイズ    エッジ      解像度     41Mピクセル
     *                  コントラスト  除去     強調       向上
     *
     * 目標: OCR平均信頼度 61.6% → 70%以上
     */
    private fun applyBalancedPreprocessing(bitmap: Bitmap): Bitmap {
        val totalStart = System.currentTimeMillis()
        val width = bitmap.width
        val height = bitmap.height

        debugLog("[v1.1.15 Preprocessing] START", "input size: ${width}x${height}")

        // ステップ1: 2倍拡大（処理用の中間サイズ）
        val scale2xStart = System.currentTimeMillis()
        val intermediateWidth = (width * 2.0).toInt()
        val intermediateHeight = (height * 2.0).toInt()
        val scaled2x = Bitmap.createScaledBitmap(bitmap, intermediateWidth, intermediateHeight, true)
        debugLog("[v1.1.15] Step1: 2x scaling", "size: ${intermediateWidth}x${intermediateHeight}, time: ${System.currentTimeMillis() - scale2xStart}ms")

        // ステップ2: OpenCVでグレースケール化 + CLAHE適用
        val claheStart = System.currentTimeMillis()
        val claheBitmap = applyCLAHE(scaled2x)
        debugLog("[v1.1.15] Step2: CLAHE (adaptive contrast)", "time: ${System.currentTimeMillis() - claheStart}ms")

        // ステップ3: バイラテラルフィルター（エッジ保持ノイズ除去）
        val denoiseStart = System.currentTimeMillis()
        val denoisedBitmap = applyBilateralFilter(claheBitmap)
        debugLog("[v1.1.15] Step3: Bilateral filter (denoise)", "time: ${System.currentTimeMillis() - denoiseStart}ms")

        // ステップ4: シャープネス処理（強化版）
        val sharpenStart = System.currentTimeMillis()
        val sharpenedBitmap = applySharpenEnhanced(denoisedBitmap)
        debugLog("[v1.1.15] Step4: Enhanced sharpening", "time: ${System.currentTimeMillis() - sharpenStart}ms")

        // ステップ5: 最終的に4倍サイズに拡大
        val scale4xStart = System.currentTimeMillis()
        val finalWidth = (width * 4.0).toInt()
        val finalHeight = (height * 4.0).toInt()
        val finalBitmap = Bitmap.createScaledBitmap(sharpenedBitmap, finalWidth, finalHeight, true)
        debugLog("[v1.1.15] Step5: Final 4x scaling", "size: ${finalWidth}x${finalHeight}, time: ${System.currentTimeMillis() - scale4xStart}ms")

        // メモリ解放
        if (scaled2x != bitmap) scaled2x.recycle()
        if (claheBitmap != scaled2x) claheBitmap.recycle()
        if (denoisedBitmap != claheBitmap) denoisedBitmap.recycle()
        if (sharpenedBitmap != denoisedBitmap) sharpenedBitmap.recycle()

        val totalTime = System.currentTimeMillis() - totalStart
        debugLog("[v1.1.15 Preprocessing] COMPLETE", "total time: ${totalTime}ms, final size: ${finalWidth}x${finalHeight}")

        return finalBitmap
    }

    /**
     * v1.1.6: OpenCVを使用した高速シャープネス処理
     *
     * 3x3 Laplacianカーネルを適用してエッジを強調
     *
     * 処理時間実績:
     * - 41Mピクセル（4320×9600）: 418ms
     * - 10Mピクセル（2160×4800）: 100-120ms（予測）
     *
     * v1.1.5のKotlin実装から274倍高速化（115秒 → 418ms）
     *
     * @param bitmap シャープネスを適用する画像（2倍スケール済み、10Mピクセル推奨）
     * @return シャープネス処理後の画像
     */
    private fun applySharpenFast(bitmap: Bitmap): Bitmap {
        try {
            // OpenCV初期化（初回のみ）
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "v1.1.6 OpenCV initialization failed")
                return bitmap
            }

            val startTime = System.currentTimeMillis()

            // BitmapをMatに変換
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            // 3x3 Laplacianカーネル（エッジ強調）
            val kernel = Mat(3, 3, CvType.CV_32F)
            kernel.put(0, 0, -1.0, -1.0, -1.0)
            kernel.put(1, 0, -1.0,  9.0, -1.0)
            kernel.put(2, 0, -1.0, -1.0, -1.0)

            // 畳み込み演算
            val result = Mat()
            Imgproc.filter2D(mat, result, -1, kernel)

            // MatをBitmapに変換
            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
            Utils.matToBitmap(result, resultBitmap)

            // メモリ解放
            mat.release()
            kernel.release()
            result.release()

            val elapsedTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "v1.1.6 applySharpenFast() completed. Time: ${elapsedTime}ms")

            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "v1.1.6 applySharpenFast() failed: ${e.message}", e)
            // エラー時は元の画像を返す
            return bitmap
        }
    }

    /**
     * v1.1.15: CLAHE（適応的ヒストグラム均等化）を適用
     *
     * CLAHEは画像を小さなタイル（8x8）に分割し、各タイルごとにヒストグラム均等化を行う。
     * これにより、局所的なコントラストが改善され、暗い部分と明るい部分の両方でテキストが鮮明になる。
     *
     * @param bitmap 入力画像
     * @return CLAHE適用後の画像
     */
    private fun applyCLAHE(bitmap: Bitmap): Bitmap {
        try {
            // OpenCV初期化
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "[v1.1.15] OpenCV initialization failed in applyCLAHE")
                return bitmap
            }

            // BitmapをMatに変換
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            // グレースケール化
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)

            // CLAHE適用（clipLimit=3.0, tileGridSize=8x8）
            val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
            val claheResult = Mat()
            clahe.apply(gray, claheResult)

            // MatをBitmapに変換
            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Imgproc.cvtColor(claheResult, mat, Imgproc.COLOR_GRAY2BGRA)
            Utils.matToBitmap(mat, resultBitmap)

            // メモリ解放
            mat.release()
            gray.release()
            claheResult.release()

            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "[v1.1.15] applyCLAHE failed: ${e.message}", e)
            return bitmap
        }
    }

    /**
     * v1.1.15: バイラテラルフィルター（エッジ保持型ノイズ除去）
     *
     * バイラテラルフィルターは、エッジを保持しながらノイズを除去する。
     * OCRにとって重要なテキストのエッジは保持し、ノイズのみを除去する。
     *
     * パラメータ:
     * - d=9: フィルターサイズ
     * - sigmaColor=75: 色空間のシグマ値
     * - sigmaSpace=75: 座標空間のシグマ値
     *
     * @param bitmap 入力画像
     * @return フィルター適用後の画像
     */
    private fun applyBilateralFilter(bitmap: Bitmap): Bitmap {
        try {
            // OpenCV初期化
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "[v1.1.28] OpenCV initialization failed in applyBilateralFilter")
                return bitmap
            }

            // BitmapをMatに変換（BGRA 4チャンネル）
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            // v1.1.28: BGRA→BGR変換（bilateralFilterはCV_8UC1/CV_8UC3のみ対応）
            val bgr = Mat()
            Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_BGRA2BGR)

            // バイラテラルフィルター適用（3チャンネルBGR）
            val filtered = Mat()
            Imgproc.bilateralFilter(bgr, filtered, 9, 75.0, 75.0)

            // BGR→BGRA変換（Bitmap用に4チャンネルに戻す）
            val bgra = Mat()
            Imgproc.cvtColor(filtered, bgra, Imgproc.COLOR_BGR2BGRA)

            // MatをBitmapに変換
            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
            Utils.matToBitmap(bgra, resultBitmap)

            // メモリ解放
            mat.release()
            bgr.release()
            filtered.release()
            bgra.release()

            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "[v1.1.28] applyBilateralFilter failed: ${e.message}", e)
            return bitmap
        }
    }

    /**
     * v1.1.15: 強化版シャープネス処理
     *
     * より強力な5x5 Laplacianカーネルを使用してエッジを強調。
     * v1.1.6の3x3カーネルよりも広範囲のエッジ検出が可能。
     *
     * カーネル構造:
     * -1 -1 -1 -1 -1
     * -1 -1 -1 -1 -1
     * -1 -1 25 -1 -1
     * -1 -1 -1 -1 -1
     * -1 -1 -1 -1 -1
     *
     * @param bitmap シャープネスを適用する画像
     * @return シャープネス処理後の画像
     */
    private fun applySharpenEnhanced(bitmap: Bitmap): Bitmap {
        try {
            // OpenCV初期化
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "[v1.1.15] OpenCV initialization failed in applySharpenEnhanced")
                return bitmap
            }

            // BitmapをMatに変換
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            // 5x5 Laplacianカーネル（より強力なエッジ強調）
            val kernel = Mat(5, 5, CvType.CV_32F)
            kernel.put(0, 0, -1.0, -1.0, -1.0, -1.0, -1.0)
            kernel.put(1, 0, -1.0, -1.0, -1.0, -1.0, -1.0)
            kernel.put(2, 0, -1.0, -1.0, 25.0, -1.0, -1.0)
            kernel.put(3, 0, -1.0, -1.0, -1.0, -1.0, -1.0)
            kernel.put(4, 0, -1.0, -1.0, -1.0, -1.0, -1.0)

            // 畳み込み演算
            val result = Mat()
            Imgproc.filter2D(mat, result, -1, kernel)

            // MatをBitmapに変換
            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
            Utils.matToBitmap(result, resultBitmap)

            // メモリ解放
            mat.release()
            kernel.release()
            result.release()

            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "[v1.1.15] applySharpenEnhanced failed: ${e.message}", e)
            return bitmap
        }
    }

    /**
     * 戦略1: 5倍拡大 + 超強力コントラスト (認識量重視)
     */
    private fun applyStrategy1(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // 5倍拡大
        val targetWidth = (width * 5.0).toInt()
        val targetHeight = (height * 5.0).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

        // 超強力コントラスト + 明るさ調整
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        val colorMatrix = ColorMatrix(floatArrayOf(
            4.0f, 4.0f, 4.0f, 0f, -200f,  // より強力なコントラスト
            4.0f, 4.0f, 4.0f, 0f, -200f,
            4.0f, 4.0f, 4.0f, 0f, -200f,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

        if (scaledBitmap != bitmap) scaledBitmap.recycle()
        return result
    }

    /**
     * 戦略2: 4倍拡大 + 完全二値化 (精度重視)
     */
    private fun applyStrategy2(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // 4倍拡大
        val targetWidth = (width * 4.0).toInt()
        val targetHeight = (height * 4.0).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

        // グレースケール化
        val grayBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayBitmap)
        val paint = Paint()
        val grayMatrix = ColorMatrix(floatArrayOf(
            0.33f, 0.33f, 0.33f, 0f, 0f,
            0.33f, 0.33f, 0.33f, 0f, 0f,
            0.33f, 0.33f, 0.33f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(grayMatrix)
        canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

        // 完全二値化 (閾値128で白黒化)
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas2 = Canvas(result)
        val paint2 = Paint()
        val binaryMatrix = ColorMatrix(floatArrayOf(
            255f, 255f, 255f, 0f, -128*255f,
            255f, 255f, 255f, 0f, -128*255f,
            255f, 255f, 255f, 0f, -128*255f,
            0f, 0f, 0f, 1f, 0f
        ))
        paint2.colorFilter = ColorMatrixColorFilter(binaryMatrix)
        canvas2.drawBitmap(grayBitmap, 0f, 0f, paint2)

        if (scaledBitmap != bitmap) scaledBitmap.recycle()
        grayBitmap.recycle()
        return result
    }

    /**
     * 戦略3: 6倍拡大 + バランス型 (超高解像度)
     */
    private fun applyStrategy3(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // 6倍拡大 (最高解像度)
        val targetWidth = (width * 6.0).toInt()
        val targetHeight = (height * 6.0).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

        // 中程度のコントラスト強化
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        val colorMatrix = ColorMatrix(floatArrayOf(
            3.5f, 3.5f, 3.5f, 0f, -180f,
            3.5f, 3.5f, 3.5f, 0f, -180f,
            3.5f, 3.5f, 3.5f, 0f, -180f,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

        if (scaledBitmap != bitmap) scaledBitmap.recycle()
        return result
    }

    /**
     * メディアンフィルタ: ノイズ除去（3x3ウィンドウ）
     */
    private fun applyMedianFilter(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val values = mutableListOf<Int>()

                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixel = bitmap.getPixel(x + kx, y + ky)
                        val gray = Color.red(pixel) // Already grayscale
                        values.add(gray)
                    }
                }

                values.sort()
                val median = values[4] // 中央値（9個の中央）
                result.setPixel(x, y, Color.rgb(median, median, median))
            }
        }

        return result
    }

    /**
     * 強力なシャープネスフィルタ適用: エッジを強調してテキストを明確に
     */
    private fun applyStrongSharpenFilter(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // 強力なシャープネスカーネル
        val kernel = floatArrayOf(
            -1f, -1f, -1f,
            -1f, 9f, -1f,
            -1f, -1f, -1f
        )

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var sum = 0f

                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixel = bitmap.getPixel(x + kx, y + ky)
                        val gray = Color.red(pixel)
                        val kernelValue = kernel[(ky + 1) * 3 + (kx + 1)]
                        sum += gray * kernelValue
                    }
                }

                val newValue = sum.toInt().coerceIn(0, 255)
                result.setPixel(x, y, Color.rgb(newValue, newValue, newValue))
            }
        }

        return result
    }

    /**
     * 高速二値化: サンプリングによる閾値計算で処理時間を大幅短縮
     * 全ピクセルではなく、10%のサンプリングで閾値を計算
     */
    private fun applyFastBinarization(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // サンプリングでヒストグラム計算（10%のピクセルのみ）
        val histogram = IntArray(256)
        val sampleStep = 10
        var sampleCount = 0

        for (y in 0 until height step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val gray = Color.red(pixel)
                histogram[gray]++
                sampleCount++
            }
        }

        // 大津の方法で最適な閾値を計算（サンプルのみ使用）
        var sum = 0.0
        for (i in 0..255) {
            sum += i * histogram[i]
        }

        var sumB = 0.0
        var wB = 0
        var wF = 0
        var varMax = 0.0
        var threshold = 128  // デフォルト値

        for (t in 0..255) {
            wB += histogram[t]
            if (wB == 0) continue

            wF = sampleCount - wB
            if (wF == 0) break

            sumB += t * histogram[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF

            val varBetween = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)

            if (varBetween > varMax) {
                varMax = varBetween
                threshold = t
            }
        }

        debugLog("Fast binarization threshold", threshold)

        // 二値化適用（全ピクセル）
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val gray = Color.red(pixel)
                val binaryValue = if (gray > threshold) 255 else 0
                result.setPixel(x, y, Color.rgb(binaryValue, binaryValue, binaryValue))
            }
        }

        return result
    }

    /**
     * 大津の方法による適応的二値化: テキストと背景を明確に分離（旧バージョン・使用していない）
     */
    private fun applyOtsuBinarization(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // ヒストグラム計算
        val histogram = IntArray(256)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val gray = Color.red(pixel)
                histogram[gray]++
            }
        }

        // 大津の方法で最適な閾値を計算
        val totalPixels = width * height
        var sum = 0.0
        for (i in 0..255) {
            sum += i * histogram[i]
        }

        var sumB = 0.0
        var wB = 0
        var wF = 0
        var varMax = 0.0
        var threshold = 0

        for (t in 0..255) {
            wB += histogram[t]
            if (wB == 0) continue

            wF = totalPixels - wB
            if (wF == 0) break

            sumB += t * histogram[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF

            val varBetween = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)

            if (varBetween > varMax) {
                varMax = varBetween
                threshold = t
            }
        }

        debugLog("Otsu threshold", threshold)

        // 二値化適用
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val gray = Color.red(pixel)
                val binaryValue = if (gray > threshold) 255 else 0
                result.setPixel(x, y, Color.rgb(binaryValue, binaryValue, binaryValue))
            }
        }

        return result
    }

    /**
     * 適応的コントラスト強化: テキストと背景の差を最大化（削除予定）
     */
    private fun applyAdaptiveContrast(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        // 輝度ヒストグラムを計算
        val histogram = IntArray(256)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val brightness = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()
                histogram[brightness]++
            }
        }

        // ヒストグラムから最適なコントラストパラメータを計算
        val totalPixels = width * height
        var darkPixels = 0
        var darkThreshold = 0
        for (i in 0..127) {
            darkPixels += histogram[i]
            if (darkPixels > totalPixels * 0.1) {
                darkThreshold = i
                break
            }
        }

        var brightPixels = 0
        var brightThreshold = 255
        for (i in 255 downTo 128) {
            brightPixels += histogram[i]
            if (brightPixels > totalPixels * 0.1) {
                brightThreshold = i
                break
            }
        }

        // 適応的コントラスト調整
        val contrastFactor = 255.0f / (brightThreshold - darkThreshold).coerceAtLeast(1)
        val offset = -darkThreshold * contrastFactor

        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrastFactor, 0f, 0f, 0f, offset,
            0f, contrastFactor, 0f, 0f, offset,
            0f, 0f, contrastFactor, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))

        paint.colorFilter = ColorMatrixColorFilter(contrastMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        return result
    }

    private fun processOCRImage(bitmap: Bitmap) {
        val processStart = System.currentTimeMillis()
        debugLog("[OCR Processing] START", "bitmap size: ${bitmap.width}x${bitmap.height}")

        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

        val ocrExecuteStart = System.currentTimeMillis()
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val ocrExecuteTime = System.currentTimeMillis() - ocrExecuteStart
                debugLog("[OCR Processing] ML Kit completed", "time: ${ocrExecuteTime}ms, blocks: ${visionText.textBlocks.size}")

                // v1.1.42: プリフェッチ中はautoOCRのTTS起動を抑制（ページめくり後の誤キャプチャ防止）
                if (isPrefetching) {
                    debugLog("[v1.1.42 Prefetch]", "AutoOCR suppressed during prefetch")
                    bitmap.recycle()
                    isCapturing = false
                    return@addOnSuccessListener
                }

                // 縦書き対応: テキストブロックを位置でソート
                val extractStart = System.currentTimeMillis()
                val extractedText = extractTextWithVerticalSupport(visionText)
                val extractTime = System.currentTimeMillis() - extractStart
                debugLog("[OCR Processing] Text extraction", "time: ${extractTime}ms, length: ${extractedText.length}")

                // v1.1.28: LLM呼び出し前に生OCRテキストの重複チェック（クォータ節約）
                if (extractedText == lastExtractedText && extractedText.isNotEmpty()) {
                    debugLog("[OCR Processing] PRE-DUPLICATE", "Raw OCR text unchanged, skipping correction (saved LLM call)")
                    bitmap.recycle()
                    debugLog("[Memory] Bitmap recycled after pre-duplicate check")
                    isCapturing = false
                    return@addOnSuccessListener
                }
                lastExtractedText = extractedText

                // ✨ v1.0.33: Phase 3対応 - OCR結果オブジェクトを渡して信頼度ベース補正を有効化
                // v1.1.33: 前ページの補正済みテキストをLLMコンテキストとして渡す
                val correctionStart = System.currentTimeMillis()
                // v1.1.33: 前ページコンテキスト（現ページの途中テキスト or 前ページの補正済みテキスト）
                val prevContext = lastRecognizedText.ifEmpty { lastCorrectedPageText }.ifEmpty { null }
                val correctedText = textCorrector.correctText(
                    extractedText,
                    visionText,
                    previousContext = prevContext
                )
                val correctionTime = System.currentTimeMillis() - correctionStart
                val stats = textCorrector.getCorrectionStats(extractedText, correctedText)

                // v1.1.41: セッション統計を更新
                if (textCorrector.lastCorrectionUsedLLM) sessionLLMUsed++ else sessionLLMSkipped++
                // v1.1.44: per-page補正統計を更新（通常OCRパス）
                lastPageLLMUsed = textCorrector.lastCorrectionUsedLLM
                lastPageAutoLearnCount = textCorrector.lastAutoLearnAppliedCount
                mainHandler.post { updateOverlayUI() }

                debugLog("[OCR Processing] Text correction", """
                    time: ${correctionTime}ms,
                    original_length: ${extractedText.length},
                    corrected_length: ${correctedText.length},
                    corrections: ${stats.totalCorrections},
                    economic_terms: ${stats.economicTermsFixed},
                    katakana: ${stats.katakanaFixed},
                    llm_used: ${textCorrector.lastCorrectionUsedLLM},
                    session_llm: $sessionLLMUsed used / $sessionLLMSkipped skipped
                """.trimIndent())

                // ✨ 詳細なデバッグログ（補正情報を含む）
                debugLog("[OCR Processing] SUCCESS", """
                    Total time: ${System.currentTimeMillis() - processStart}ms
                    (ML Kit: ${ocrExecuteTime}ms, Extract: ${extractTime}ms, Correction: ${correctionTime}ms),
                    TextLength: ${correctedText.length},
                    Preview: ${correctedText.take(100).replace("\n", " | ")},
                    Changed: ${correctedText != lastRecognizedText},
                    Duplicate: ${correctedText == lastRecognizedText}
                """.trimIndent())

                // ✨ v1.0.17: 補正後のテキストを使用
                // v1.1.35: 跨ページテキスト結合
                if (correctedText.isNotEmpty() && correctedText != lastRecognizedText && isReading && !isPaused) {
                    lastRecognizedText = correctedText

                    // v1.1.35: KindleのUI要素を除去（ページ進捗表示など）
                    var cleanedText = correctedText
                        .replace(Regex("\\d+\\s*ページ中\\s*\\d+\\s*ページの[^\\n]*?\\d+%"), "")
                        .replace(Regex("に戻る"), "")  // ナビゲーションUI
                        .replace(Regex("\\bAa\\b"), "")  // フォント設定ボタン
                        .trim()

                    // v1.1.35: 前ページの末尾断片を結合（ページ初回キャプチャのみ）
                    var textToSpeak = cleanedText
                    if (trailingFragment.isNotEmpty() && !hasSpokenForCurrentPage) {
                        textToSpeak = trailingFragment + textToSpeak
                        debugLog("[CrossPage] Merged", "fragment='${trailingFragment.take(50)}' (${trailingFragment.length}chars) + new page")
                        trailingFragment = ""
                    }

                    // v1.1.35: 末尾の不完全文を検出して次ページ用に保存（初回のみ）
                    val sentenceEndChars = setOf('。', '！', '？', '.', '!', '?')
                    val trimmedText = textToSpeak.trimEnd()
                    if (!hasSpokenForCurrentPage && trimmedText.isNotEmpty() && trimmedText.last() !in sentenceEndChars) {
                        val lastEndIdx = trimmedText.lastIndexOfAny(sentenceEndChars.toCharArray())
                        if (lastEndIdx >= 0) {
                            trailingFragment = trimmedText.substring(lastEndIdx + 1).trim()
                            textToSpeak = trimmedText.substring(0, lastEndIdx + 1)
                            debugLog("[CrossPage] Saved trailing", "'${trailingFragment.take(50)}' (${trailingFragment.length}chars) for next page")
                        } else {
                            debugLog("[CrossPage] No sentence-end on page", "speaking entire text (${trimmedText.length}chars)")
                        }
                    } else if (!hasSpokenForCurrentPage) {
                        debugLog("[CrossPage] Page ends with punctuation", "no trailing fragment")
                    }

                    hasSpokenForCurrentPage = true  // v1.1.35: 同一ページのリトライではCrossPage処理をスキップ

                    val sentences = splitIntoSentences(textToSpeak)
                    if (sentences.isNotEmpty()) {
                        debugLog("[TTS] Preparing to speak", "sentences: ${sentences.size}, first: ${sentences.first().take(30)}")
                        speakSentences(sentences)
                    } else {
                        debugLog("[TTS] No sentences", "Text could not be split into sentences")
                    }
                } else if (correctedText.isEmpty()) {
                    debugLog("[OCR Processing] EMPTY", "No text extracted from image")
                } else if (correctedText == lastRecognizedText) {
                    debugLog("[OCR Processing] DUPLICATE", "Same text as previous capture")
                } else if (!isReading || isPaused) {
                    debugLog("[OCR Processing] SKIPPED", "Not reading or paused: isReading=$isReading, isPaused=$isPaused")
                }

                // ✅ v1.1.12 FIX: bitmapを解放（メモリリーク防止）
                bitmap.recycle()
                debugLog("[Memory] Bitmap recycled after OCR success")

                isCapturing = false
            }
            .addOnFailureListener { e ->
                val failureTime = System.currentTimeMillis() - processStart
                Log.e(TAG, "[$TAG] OCR failed (elapsed: ${failureTime}ms)", e)
                debugLog("[OCR Processing] FAILED", """
                    time: ${failureTime}ms,
                    error: ${e.message},
                    stackTrace: ${e.stackTraceToString().take(500)}
                """.trimIndent())
                handleError("OCRエラー", e)

                // ✅ v1.1.12 FIX: bitmapを解放（メモリリーク防止）
                bitmap.recycle()
                debugLog("[Memory] Bitmap recycled after OCR failure")

                isCapturing = false
            }
    }

    private fun extractTextWithVerticalSupport(visionText: com.google.mlkit.vision.text.Text): String {
        if (visionText.textBlocks.isEmpty()) {
            debugLog("OCR: No text blocks detected")
            return visionText.text.trim()
        }

        // ✨ 信頼度フィルタリング: 低信頼度テキストを除外
        val minConfidence = 0.5f  // 信頼度50%未満のテキストは除外
        val blocks = visionText.textBlocks

        // 信頼度の統計情報を計算
        val confidences = blocks.flatMap { block ->
            block.lines.flatMap { line ->
                line.elements.map { element ->
                    element.confidence ?: 0f
                }
            }
        }

        val avgConfidence = if (confidences.isNotEmpty()) confidences.average() else 0.0
        val highConfidenceCount = confidences.count { it >= minConfidence }
        val totalElementCount = confidences.size

        debugLog("OCR confidence stats", """
            Blocks: ${blocks.size},
            Elements: $totalElementCount,
            HighConf: $highConfidenceCount (${(highConfidenceCount.toFloat() / totalElementCount * 100).toInt()}%),
            AvgConf: ${"%.2f".format(avgConfidence)}
        """.trimIndent())

        // ✅ v1.0.22: 画面サイズを取得
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // ✅ v1.0.30: デバッグ - 全ブロックの座標と完全なテキストを出力
        blocks.forEachIndexed { index, block ->
            val box = block.boundingBox
            if (box != null) {
                debugLog("Block #$index coords", "L:${box.left}, T:${box.top}, R:${box.right}, B:${box.bottom}, W:${box.width()}, H:${box.height()}, Ratio:${"%.2f".format(box.height().toDouble() / box.width().toDouble())}")
                debugLog("Block #$index text", block.text.replace("\n", "\\n"))
            }
        }

        // ✅ v1.0.28: 4倍スケーリングを考慮して比率を計算
        val scaleFactor = 4
        val scaledScreenWidth = screenWidth * scaleFactor
        val scaledScreenHeight = screenHeight * scaleFactor

        val heightWidthRatios = blocks.mapNotNull { block ->
            val box = block.boundingBox
            if (box != null && box.width() > 0) {
                // スケール後の画面サイズの1.5倍を超える座標は異常値として除外
                val isValid = box.left >= 0 && box.top >= 0 &&
                        box.right <= scaledScreenWidth * 1.5 && box.bottom <= scaledScreenHeight * 1.5
                if (isValid) {
                    box.height().toDouble() / box.width().toDouble()
                } else {
                    debugLog("v1.0.28 Filtered", "R:${box.right}, B:${box.bottom}, Limit: ${scaledScreenWidth * 1.5}x${scaledScreenHeight * 1.5}")
                    null
                }
            } else {
                null
            }
        }

        // 中央値を使って縦書き判定（平均値より異常値に強い）
        val isVertical = if (heightWidthRatios.isNotEmpty()) {
            val sortedRatios = heightWidthRatios.sorted()
            val medianRatio = sortedRatios[sortedRatios.size / 2]
            medianRatio > 1.5  // 高さが幅の1.5倍以上なら縦書き
        } else {
            false
        }

        debugLog("Text orientation", "Vertical: $isVertical, MedianRatio: ${"%.2f".format(if (heightWidthRatios.isNotEmpty()) heightWidthRatios.sorted()[heightWidthRatios.size / 2] else 0.0)}, Blocks: ${blocks.size}")

        return if (isVertical) {
            // ✅ v1.0.27: 縦書き - カラムベースのソート
            // カラムごとにグループ化し、右から左、上から下の順で読む
            sortVerticalTextByColumns(blocks)
        } else {
            // 横書き: 上から下（top座標の昇順）、左から右（left座標の昇順）
            blocks.sortedWith(compareBy<com.google.mlkit.vision.text.Text.TextBlock> {
                it.boundingBox?.top ?: 0
            }.thenBy {
                it.boundingBox?.left ?: 0
            })
                .joinToString("\n") { it.text }
                .trim()
        }
    }

    /**
     * v1.0.27: 縦書きテキストをカラムベースでソート
     * - カラムごとにグループ化（X座標の重なりで判定）
     * - カラムを右から左にソート
     * - カラム内のブロックを上から下にソート
     * - ヘッダー/フッターを除外
     */
    private fun sortVerticalTextByColumns(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>): String {
        if (blocks.isEmpty()) return ""

        // Step 1: ヘッダー/フッター/ページ番号を除外
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels

        // ✅ v1.0.28: 4倍スケーリングを考慮したフィルタリング
        // 画像を4倍スケール（1080x2400 → 4320x9600）しているため、
        // OCR座標もスケール後の値で返ってくる
        val scaleFactor = 4
        val scaledScreenWidth = screenWidth * scaleFactor
        val scaledScreenHeight = screenHeight * scaleFactor

        val filteredBlocks = blocks.filter { block ->
            val box = block.boundingBox ?: return@filter false
            val centerY = (box.top + box.bottom) / 2.0
            val relativeY = centerY / scaledScreenHeight

            // 上下5%の領域を除外（ヘッダー/フッター）
            relativeY > 0.05 && relativeY < 0.95 &&
            // 極小ブロック（ページ番号など）を除外（スケール後の値）
            box.height() > 120 &&  // 30px * 4 = 120px
            // 座標異常のブロックを除外（スケール後の画面サイズの1.5倍以内）
            box.right <= scaledScreenWidth * 1.5 && box.bottom <= scaledScreenHeight * 1.5
        }

        if (filteredBlocks.isEmpty()) return blocks.joinToString("\n") { it.text }.trim()

        debugLog("v1.0.30 Filter", "Original: ${blocks.size}, Filtered: ${filteredBlocks.size}")

        // ✅ v1.0.30改善: 幅が広いBlock（複数列が誤って結合）を各行ごとに分割
        // これにより、各行が正しいカラムに配置される
        data class TextItem(val box: android.graphics.Rect, val text: String)

        val textItems = filteredBlocks.flatMap { block ->
            val lines = block.lines
            if (lines.size > 1 && (block.boundingBox?.width() ?: 0) > 300) {
                // 行を右端座標で降順ソート（右→左）してから各行を個別アイテム化
                val sortedLines = lines.sortedByDescending { it.boundingBox?.right ?: 0 }
                debugLog("v1.0.30 Block split", "Block width:${block.boundingBox?.width()}, Lines:${lines.size} → Split into ${sortedLines.size} items")
                sortedLines.mapNotNull { line ->
                    line.boundingBox?.let { box -> TextItem(box, line.text) }
                }
            } else {
                block.boundingBox?.let { listOf(TextItem(it, block.text)) } ?: emptyList()
            }
        }

        // Step 2: TextItemをカラムにグループ化
        val itemColumns = mutableListOf<MutableList<TextItem>>()
        textItems.forEach { item ->
            val matchingColumn = itemColumns.find { column ->
                column.any { existing ->
                    horizontalOverlap(item.box, existing.box) > 0.3  // 30%以上の重なり
                }
            }
            if (matchingColumn != null) {
                matchingColumn.add(item)
            } else {
                itemColumns.add(mutableListOf(item))
            }
        }

        debugLog("v1.0.30 Columns", "Detected ${itemColumns.size} columns")

        // Step 3: カラムを右から左にソート（各カラムの右端X座標で降順）
        val sortedColumns = itemColumns.sortedByDescending { column ->
            column.maxOfOrNull { it.box.right } ?: 0
        }

        // Step 4: 各カラム内のアイテムを上から下にソート
        val sortedItems = sortedColumns.flatMap { column ->
            column.sortedBy { it.box.top }
        }

        // デバッグ: ソート後の順番を表示
        debugLog("v1.0.30 Sorted order", sortedItems.mapIndexed { idx, item ->
            "[$idx] Right:${item.box.right}, Y:${item.box.top}, Text:${item.text.take(10)}"
        }.joinToString(" | "))

        return sortedItems.joinToString("\n") { it.text }.trim()
    }

    /**
     * v1.0.27: ブロックをカラムにクラスタリング
     * 水平方向の重なりが30%以上なら同じカラムと判定
     */
    private fun clusterIntoColumns(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>): List<List<com.google.mlkit.vision.text.Text.TextBlock>> {
        if (blocks.isEmpty()) return emptyList()

        val columns = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.TextBlock>>()

        blocks.forEach { block ->
            val box = block.boundingBox ?: return@forEach

            // 既存のカラムで水平方向の重なりがあるか探す
            val matchingColumn = columns.find { column ->
                column.any { existingBlock ->
                    val existingBox = existingBlock.boundingBox ?: return@any false
                    horizontalOverlap(box, existingBox) > 0.3  // 30%以上の重なり
                }
            }

            if (matchingColumn != null) {
                matchingColumn.add(block)
            } else {
                // 新しいカラムを作成
                columns.add(mutableListOf(block))
            }
        }

        return columns
    }

    /**
     * v1.0.27: 2つのRectの水平方向の重なり率を計算（0.0～1.0）
     */
    private fun horizontalOverlap(box1: android.graphics.Rect, box2: android.graphics.Rect): Double {
        val overlapLeft = maxOf(box1.left, box2.left)
        val overlapRight = minOf(box1.right, box2.right)
        val overlapWidth = maxOf(0, overlapRight - overlapLeft)

        val box1Width = box1.width()
        val box2Width = box2.width()
        val minWidth = minOf(box1Width, box2Width)

        return if (minWidth > 0) overlapWidth.toDouble() / minWidth else 0.0
    }

    /**
     * v1.0.27: Rectの中心X座標を取得
     */
    private fun android.graphics.Rect.centerX(): Int = (left + right) / 2

    private fun splitIntoSentences(text: String): List<String> {
        // v1.1.35: 句読点を保持する分割（TTSの自然さ向上）
        // "文A。文B！文C" → ["文A。", "文B！", "文C"]
        return Regex("(?<=[。！？.!?])").split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun speakSentences(sentences: List<String>) {
        currentSentences = sentences
        currentSentenceIndex = 0
        appState.totalSentences = sentences.size
        appState.currentSentence = 0

        debugLog("[TTS] Speak sentences START", """
            count: ${sentences.size},
            preview: ${sentences.take(3).joinToString(" | ") { it.take(20) + "..." }}
        """.trimIndent())
        speakCurrentSentence()
    }

    private fun speakCurrentSentence() {
        if (currentSentenceIndex >= currentSentences.size || !isReading || isPaused) {
            debugLog("[TTS] Speak skipped", """
                index: $currentSentenceIndex,
                total: ${currentSentences.size},
                isReading: $isReading,
                isPaused: $isPaused
            """.trimIndent())
            return
        }

        val sentence = currentSentences[currentSentenceIndex]
        currentSpokenSentence = sentence  // v1.1.37: ユーザーフィードバック用
        debugLog("[TTS] Speaking", """
            index: $currentSentenceIndex/${currentSentences.size},
            length: ${sentence.length},
            text: ${sentence.take(50)}${if (sentence.length > 50) "..." else ""}
        """.trimIndent())

        appState.currentSentence = currentSentenceIndex + 1
        updateOverlayText(sentence)

        textToSpeech?.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, currentSentenceIndex.toString())
    }

    private fun onSentenceComplete() {
        debugLog("[TTS] Sentence complete", "index: $currentSentenceIndex → ${currentSentenceIndex + 1}, total: ${currentSentences.size}")
        currentSentenceIndex++

        if (currentSentenceIndex < currentSentences.size) {
            // v1.1.42: 最終文の読み上げ開始前にプリフェッチ起動
            val isStartingLastSentence = currentSentenceIndex == currentSentences.size - 1
            if (isStartingLastSentence && currentSentences.size >= 2 &&  // v1.1.45: 2文ページにも対応（旧 >= 3）
                autoPageTurnEnabled && isReading && !isPaused && !isPrefetching && !prefetchGestureSent) {
                debugLog("[v1.1.42 Prefetch]", "Triggering prefetch before last sentence (${currentSentences.size} sentences total)")
                startPrefetch()
            }
            debugLog("[TTS] Next sentence", "Scheduling next sentence after 500ms delay")
            mainHandler.postDelayed({
                speakCurrentSentence()
            }, 500)
        } else {
            // ページ完了
            debugLog("[TTS] Page complete", """
                autoPageTurnEnabled: $autoPageTurnEnabled,
                isReading: $isReading,
                isPrefetching: $isPrefetching,
                prefetchReady: ${prefetchedSentences != null}
            """.trimIndent())

            if (autoPageTurnEnabled && isReading) {
                // v1.1.42: 800ms後にページ完了処理（プリフェッチ対応、旧2000ms）
                mainHandler.postDelayed({ finishCurrentPage() }, 800)
            }
        }
    }

    // =========================================================
    // v1.1.42: バックグラウンドプリフェッチシステム
    // =========================================================

    /**
     * 最終文の読み上げ開始時に呼ぶ。
     * ページめくりジェスチャーを即座に送信し、アニメーション中にOCR+LLMを実行。
     * 最終文が終わる頃には次ページのテキストが準備完了している。
     */
    private fun startPrefetch() {
        isPrefetching = true
        prefetchGestureSent = true
        prefetchedSentences = null
        prefetchedCorrectedText = ""
        prefetchGeneration++  // v1.1.43: 新しい世代開始（旧コールバックを無効化）
        val myGeneration = prefetchGeneration
        val capturedTrailingFragment = trailingFragment  // ClosureでCapture（CrossPage用）

        debugLog("[v1.1.42 Prefetch]", "=== PREFETCH START === sending gesture, last sentence about to play (gen=$myGeneration)")

        // ページめくりジェスチャー送信（Kindleのアニメーション開始）
        val gestureAction = if (pageDirection == "right_to_next") "NEXT_PAGE" else "PREVIOUS_PAGE"
        startService(Intent(this, AutoPageTurnService::class.java).apply {
            action = gestureAction
            putExtra("page_direction", pageDirection)
        })

        // アニメーション完了後（1.2秒）にOCR開始
        mainHandler.postDelayed({
            if (myGeneration != prefetchGeneration || !isPrefetching || !isReading || isPaused) {
                debugLog("[v1.1.42 Prefetch]", "Cancelled before OCR (gen=$myGeneration current=$prefetchGeneration, isPrefetching=$isPrefetching)")
                isPrefetching = false
                return@postDelayed
            }
            debugLog("[v1.1.42 Prefetch]", "Starting background OCR after animation wait")
            performPrefetchOCR(capturedTrailingFragment, retryCount = 0, generation = myGeneration)
        }, 1200)
    }

    /**
     * バックグラウンドOCR実行。完了後 prefetchedSentences に格納する。
     * isCapturing が true なら 500ms 待機してリトライ。
     */
    private fun performPrefetchOCR(savedTrailingFragment: String, retryCount: Int, generation: Int) {
        // v1.1.43: 世代チェック（タイムアウト後のステールコールバックを排除）
        if (generation != prefetchGeneration || !isPrefetching || !isReading || isPaused) {
            if (generation != prefetchGeneration) {
                debugLog("[v1.1.43 Prefetch]", "Stale OCR cancelled (gen=$generation vs current=$prefetchGeneration)")
            }
            isPrefetching = false
            return
        }

        // isCapturing中は待機（autoOCRと競合しない）
        if (isCapturing) {
            if (retryCount < 6) {
                mainHandler.postDelayed({ performPrefetchOCR(savedTrailingFragment, retryCount + 1, generation) }, 500)
            } else {
                debugLog("[v1.1.42 Prefetch]", "Capture busy after retries, giving up")
                isPrefetching = false
            }
            return
        }

        isCapturing = true
        val image = latestImage?.also { latestImage = null } ?: imageReader?.acquireLatestImage()

        if (image == null) {
            isCapturing = false
            if (retryCount < 4) {
                debugLog("[v1.1.42 Prefetch]", "No image, retry ${retryCount + 1}")
                mainHandler.postDelayed({ performPrefetchOCR(savedTrailingFragment, retryCount + 1, generation) }, 750)
            } else {
                debugLog("[v1.1.42 Prefetch]", "No image after retries, giving up")
                isPrefetching = false
            }
            return
        }

        val bitmap = convertImageToBitmap(image)
        if (bitmap == null) {
            isCapturing = false
            isPrefetching = false
            debugLog("[v1.1.42 Prefetch]", "Bitmap conversion failed")
            return
        }

        val startTime = System.currentTimeMillis()
        val mlImage = InputImage.fromBitmap(bitmap, 0)
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            .process(mlImage)
            .addOnSuccessListener { visionText ->
                isCapturing = false

                // v1.1.43: OCR完了時点で世代チェック（LLM開始前に早期終了）
                if (generation != prefetchGeneration) {
                    debugLog("[v1.1.43 Prefetch]", "Stale OCR result discarded (gen=$generation vs current=$prefetchGeneration)")
                    bitmap.recycle()
                    isPrefetching = false
                    return@addOnSuccessListener
                }

                val extractedText = extractTextWithVerticalSupport(visionText)
                debugLog("[v1.1.42 Prefetch]", "OCR done: ${extractedText.length} chars in ${System.currentTimeMillis() - startTime}ms")

                if (extractedText.isEmpty()) {
                    bitmap.recycle()
                    if (retryCount < 3) {
                        mainHandler.postDelayed({ performPrefetchOCR(savedTrailingFragment, retryCount + 1, generation) }, 750)
                    } else {
                        debugLog("[v1.1.42 Prefetch]", "Empty text after retries, giving up")
                        isPrefetching = false
                    }
                    return@addOnSuccessListener
                }

                // テキスト補正（前ページコンテキスト付き）
                val prevContext = lastRecognizedText.takeLast(200).ifEmpty { null }
                val correctedText = textCorrector.correctText(extractedText, visionText, previousContext = prevContext)

                // v1.1.43: LLM完了後にも世代チェック（LLMが長時間かかった場合のステール排除）
                if (generation != prefetchGeneration) {
                    debugLog("[v1.1.43 Prefetch]", "Stale LLM result discarded (gen=$generation vs current=$prefetchGeneration)")
                    bitmap.recycle()
                    isPrefetching = false
                    return@addOnSuccessListener
                }

                // Kindle UI要素除去
                var cleanedText = correctedText
                    .replace(Regex("\\d+\\s*ページ中\\s*\\d+\\s*ページの[^\\n]*?\\d+%"), "")
                    .replace(Regex("に戻る"), "")
                    .replace(Regex("\\bAa\\b"), "")
                    .trim()

                // CrossPage: 前ページ末尾断片を結合（savedTrailingFragmentはCapture時の値）
                var textToSpeak = cleanedText
                if (savedTrailingFragment.isNotEmpty()) {
                    textToSpeak = savedTrailingFragment + textToSpeak
                    trailingFragment = ""  // 消費済み
                    debugLog("[v1.1.42 Prefetch CrossPage]", "Merged trailing: '${savedTrailingFragment.take(30)}'")
                }

                // 次ページ用の末尾断片を検出・保存
                val sentenceEndChars = setOf('。', '！', '？', '.', '!', '?')
                val trimmedText = textToSpeak.trimEnd()
                if (trimmedText.isNotEmpty() && trimmedText.last() !in sentenceEndChars) {
                    val lastEndIdx = trimmedText.lastIndexOfAny(sentenceEndChars.toCharArray())
                    if (lastEndIdx >= 0) {
                        trailingFragment = trimmedText.substring(lastEndIdx + 1).trim()
                        textToSpeak = trimmedText.substring(0, lastEndIdx + 1)
                        debugLog("[v1.1.42 Prefetch CrossPage]", "Next trailing: '${trailingFragment.take(30)}'")
                    }
                } else {
                    trailingFragment = ""
                }

                val sentences = splitIntoSentences(textToSpeak)
                val totalTime = System.currentTimeMillis() - startTime
                debugLog("[v1.1.42 Prefetch]", "=== PREFETCH DONE === ${sentences.size} sentences in ${totalTime}ms (gen=$generation)")

                prefetchedCorrectedText = correctedText
                prefetchedSentences = if (sentences.isNotEmpty()) sentences else null
                // v1.1.44: prefetchパスの補正統計を保存（applyPrefetchAndSpeakで lastPage* に移す）
                prefetchPageLLMUsed = textCorrector.lastCorrectionUsedLLM
                prefetchPageAutoLearnCount = textCorrector.lastAutoLearnAppliedCount
                isPrefetching = false
                bitmap.recycle()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "[v1.1.42 Prefetch] OCR failed: ${e.message}", e)
                isCapturing = false
                isPrefetching = false
                bitmap.recycle()
            }
    }

    /**
     * ページ完了時に呼ばれる（旧nextPage()の代わり）。
     * プリフェッチ状態に応じて最適なパスを選択する。
     */
    private fun finishCurrentPage() {
        if (!isReading) return
        debugLog("[v1.1.42 Prefetch]", "finishCurrentPage: prefetchReady=${prefetchedSentences != null}, isPrefetching=$isPrefetching, gestureSent=$prefetchGestureSent")
        when {
            prefetchedSentences != null -> {
                // ✅ ベストケース: プリフェッチ完了済み → 即座開始
                debugLog("[v1.1.42 Prefetch]", "✅ ZERO WAIT! Applying prefetched sentences immediately")
                applyPrefetchAndSpeak()
            }
            isPrefetching -> {
                // プリフェッチ進行中 → 最大5秒待機 (v1.1.43: 3s→5s, LLM 6.7s実測に対応)
                debugLog("[v1.1.43 Prefetch]", "Waiting for prefetch (max 5s)...")
                waitForPrefetch(remainingMs = 5000)
            }
            prefetchGestureSent -> {
                // ジェスチャー送信済みだがOCR/LLM失敗 → 短い遅延でOCR再試行
                debugLog("[v1.1.42 Prefetch]", "Gesture sent but prefetch failed, short-delay OCR fallback")
                resetPageStateForNewPage()
                performOCRWithRetry(maxRetries = 3, initialDelay = 500)
            }
            else -> {
                // プリフェッチなし（文が少ない等） → 通常フロー（delay短縮済み）
                debugLog("[v1.1.42 Prefetch]", "No prefetch, normal nextPage()")
                nextPage()
            }
        }
    }

    /** プリフェッチ完了待機（300ms間隔ポーリング、最大5秒）*/
    private fun waitForPrefetch(remainingMs: Long) {
        when {
            !isReading || isPaused -> {
                resetPrefetchState()
            }
            prefetchedSentences != null -> {
                debugLog("[v1.1.42 Prefetch]", "✅ Prefetch ready after wait! Applying now.")
                applyPrefetchAndSpeak()
            }
            remainingMs <= 0 -> {
                // v1.1.43: タイムアウト時に generation をインクリメントしてステールコールバックを無効化
                prefetchGeneration++
                debugLog("[v1.1.43 Prefetch]", "Timeout! Invalidated gen=$prefetchGeneration. Falling back. gestureSent=$prefetchGestureSent")
                isPrefetching = false
                if (prefetchGestureSent) {
                    resetPageStateForNewPage()
                    performOCRWithRetry(maxRetries = 3, initialDelay = 500)
                } else {
                    nextPage()
                }
            }
            else -> mainHandler.postDelayed({ waitForPrefetch(remainingMs - 300) }, 300)
        }
    }

    /** プリフェッチ結果を適用して即座にTTS開始 */
    private fun applyPrefetchAndSpeak() {
        val sentences = prefetchedSentences ?: return
        debugLog("[v1.1.42 Prefetch]", "applyPrefetchAndSpeak: ${sentences.size} sentences, page ${appState.currentPage} → ${appState.currentPage + 1}")

        savePageToHistory()  // v1.1.46: 現在ページをhistoryに保存
        appState.currentPage++
        lastCorrectedPageText = lastRecognizedText
        lastRecognizedText = prefetchedCorrectedText.ifEmpty { sentences.joinToString("") }
        lastExtractedText = ""
        currentSentences = emptyList()
        currentSentenceIndex = 0
        hasSpokenForCurrentPage = true  // プリフェッチがCrossPage処理済み
        textToSpeech?.stop()

        // v1.1.44: prefetchパスの補正統計を lastPage* に転記（ステータス表示用）
        lastPageLLMUsed = prefetchPageLLMUsed
        lastPageAutoLearnCount = prefetchPageAutoLearnCount

        // v1.1.41: prefetchでLLMを使ったかに基づいてセッション統計を更新
        if (lastPageLLMUsed) sessionLLMUsed++ else sessionLLMSkipped++

        resetPrefetchState()
        updateOverlayUI()
        speakSentences(sentences)
    }

    /** ジェスチャー送信済みのフォールバック用状態リセット（appState.currentPage++ あり） */
    private fun resetPageStateForNewPage() {
        appState.currentPage++
        lastCorrectedPageText = lastRecognizedText
        lastRecognizedText = ""
        lastExtractedText = ""
        currentSentences = emptyList()
        currentSentenceIndex = 0
        hasSpokenForCurrentPage = false
        textToSpeech?.stop()
        resetPrefetchState()
        updateOverlayUI()
        debugLog("[v1.1.42 Prefetch]", "resetPageStateForNewPage: page=${appState.currentPage}")
    }

    /** プリフェッチ状態を全リセット */
    private fun resetPrefetchState() {
        prefetchedSentences = null
        prefetchedCorrectedText = ""
        isPrefetching = false
        prefetchGestureSent = false
    }

    /** v1.1.46: 現在のページをhistoryに保存（ページ遷移前に呼ぶ） */
    private fun savePageToHistory() {
        if (currentSentences.isEmpty()) return
        pageHistory.addFirst(PageHistoryEntry(appState.currentPage, currentSentences.toList()))
        if (pageHistory.size > MAX_PAGE_HISTORY) pageHistory.removeLast()
        debugLog("[v1.1.46 PageHistory]", "Saved page ${appState.currentPage} (${currentSentences.size} sentences), history=${pageHistory.size}")
    }

    private fun nextPage() {
        val timestamp = System.currentTimeMillis()
        debugLog("=== NEXT PAGE ===", """
            timestamp: $timestamp,
            direction: $pageDirection,
            currentPage: ${appState.currentPage} → ${appState.currentPage + 1},
            lastText_length: ${lastRecognizedText.length},
            currentSentences: ${currentSentences.size},
            currentSentenceIndex: $currentSentenceIndex
        """.trimIndent())

        savePageToHistory()  // v1.1.46: 現在ページをhistoryに保存
        appState.currentPage++

        // ✅ FIX: ページ変更時に状態をリセット（TTS継続の問題を修正）
        // v1.1.33: 前ページコンテキストを保存してからリセット
        lastCorrectedPageText = lastRecognizedText
        lastRecognizedText = ""
        lastExtractedText = ""  // v1.1.28: 生OCRテキストもリセット
        currentSentences = emptyList()  // 古い文のリストをクリア
        currentSentenceIndex = 0         // インデックスをリセット
        hasSpokenForCurrentPage = false  // v1.1.35: 跨ページフラグリセット
        textToSpeech?.stop()             // 前のページのTTSを停止

        debugLog("[State Reset]", "text/sentences/index cleared, TTS stopped, trailingFragment=${trailingFragment.length}chars, new page: ${appState.currentPage}")

        // ページめくり方向に応じてジェスチャーを選択
        val gestureAction = if (pageDirection == "right_to_next") "NEXT_PAGE" else "PREVIOUS_PAGE"
        debugLog("[Page Turn Gesture]", "action: $gestureAction, direction: $pageDirection")

        val intent = Intent(this, AutoPageTurnService::class.java)
        intent.action = gestureAction
        intent.putExtra("page_direction", pageDirection)
        startService(intent)

        // OCRを再実行（リトライ付き）
        // v1.1.42: 1500msに短縮（プリフェッチなし場合のフォールバック）
        debugLog("[OCR Retry]", "Scheduling OCR retry: maxRetries=3, initialDelay=1500ms")
        performOCRWithRetry(maxRetries = 3, initialDelay = 1500)
    }

    private fun previousPage() {
        val timestamp = System.currentTimeMillis()
        debugLog("=== PREVIOUS PAGE ===", """
            timestamp: $timestamp,
            direction: $pageDirection,
            currentPage: ${appState.currentPage} → ${appState.currentPage - 1},
            lastText_length: ${lastRecognizedText.length},
            currentSentences: ${currentSentences.size},
            currentSentenceIndex: $currentSentenceIndex
        """.trimIndent())

        savePageToHistory()  // v1.1.46: 現在ページをhistoryに保存
        appState.currentPage--

        // ✅ FIX: ページ変更時に状態をリセット（TTS継続の問題を修正）
        // v1.1.33: 前ページコンテキストを保存してからリセット
        lastCorrectedPageText = lastRecognizedText
        lastRecognizedText = ""
        lastExtractedText = ""  // v1.1.28: 生OCRテキストもリセット
        currentSentences = emptyList()  // 古い文のリストをクリア
        currentSentenceIndex = 0         // インデックスをリセット
        hasSpokenForCurrentPage = false  // v1.1.35: 跨ページフラグリセット
        textToSpeech?.stop()             // 前のページのTTSを停止

        debugLog("[State Reset]", "text/sentences/index cleared, TTS stopped, trailingFragment=${trailingFragment.length}chars, new page: ${appState.currentPage}")

        // ページめくり方向に応じてジェスチャーを選択
        val gestureAction = if (pageDirection == "right_to_next") "PREVIOUS_PAGE" else "NEXT_PAGE"
        debugLog("[Page Turn Gesture]", "action: $gestureAction, direction: $pageDirection")

        val intent = Intent(this, AutoPageTurnService::class.java)
        intent.action = gestureAction
        intent.putExtra("page_direction", pageDirection)
        startService(intent)

        // OCRを再実行（リトライ付き）
        // v1.1.42: 1500msに短縮
        debugLog("[OCR Retry]", "Scheduling OCR retry: maxRetries=3, initialDelay=1500ms")
        performOCRWithRetry(maxRetries = 3, initialDelay = 1500)
    }

    /**
     * リトライ付きOCR実行
     * ページめくり後、画面が安定するまで待ってからOCRを実行
     * 待機時間を延長してページ遷移アニメーションの完了を確実に待つ
     */
    private fun performOCRWithRetry(maxRetries: Int, initialDelay: Long) {
        var retryCount = 0

        fun attemptOCR() {
            if (!isReading || isPaused) {
                debugLog("OCR retry cancelled", "isReading: $isReading, isPaused: $isPaused")
                return
            }

            mainHandler.postDelayed({
                if (!isReading || isPaused) return@postDelayed

                performOCR()

                // lastRecognizedTextが更新されたかチェック
                mainHandler.postDelayed({
                    if (lastRecognizedText.isEmpty() && retryCount < maxRetries) {
                        retryCount++
                        debugLog("OCR retry", "Attempt $retryCount of $maxRetries")
                        attemptOCR()
                    } else if (lastRecognizedText.isEmpty()) {
                        debugLog("OCR retry exhausted", "No text found after $maxRetries attempts")
                    } else {
                        debugLog("OCR retry success", "Text found on attempt $retryCount")
                    }
                }, 500)
            }, if (retryCount == 0) initialDelay else 750)  // v1.1.41: リトライは0.75秒（初回2.5s後、ページ安定済みなのでより頻繁に試行）
        }

        attemptOCR()
    }

    private fun updateOverlayUI() {
        overlayView?.let { view ->
            val statusText = view.findViewById<TextView>(R.id.overlayText)
            val status = when {
                !isReading -> "待機中"
                isPaused -> "一時停止"
                else -> "読み上げ中"
            }
            // v1.1.38: 昇格済み学習パターン数を表示
            val promoted = AutoLearnManager.getInstance(this).getPromotedCount()
            val patternBadge = if (promoted > 0) " [★$promoted]" else ""
            // v1.1.47: クォータ残り少ない時だけ警告表示（残り20回以下）
            val quotaRemaining = quotaManager.getRemaining()
            val quotaBadge = if (quotaRemaining <= 20) " [クォータ残:$quotaRemaining]" else ""
            statusText.text = "$status (${appState.currentPage}ページ)$patternBadge$quotaBadge"
        }
    }

    // v1.1.46: ページ履歴ダイアログ（[学]ボタン長押し）
    private fun showPageHistoryDialog() {
        if (pageHistory.isEmpty()) {
            mainHandler.post {
                Toast.makeText(this, "まだ履歴がありません（ページを進めると記録されます）", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val wasReading = isReading && !isPaused
        if (wasReading) {
            textToSpeech?.stop()
            isPaused = true
            updatePlayPauseButton()
        }

        val context = this
        mainHandler.post {
            try {
                val scrollView = ScrollView(context)
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 16, 32, 16)
                }
                scrollView.addView(container)

                for (entry in pageHistory) {
                    // ページヘッダー
                    val pageLabel = TextView(context).apply {
                        text = "── ${entry.pageNumber}ページ目 ──"
                        setTextColor(0xFF88BBFF.toInt())
                        textSize = 12f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setPadding(0, 20, 0, 6)
                    }
                    container.addView(pageLabel)

                    for (sentence in entry.sentences) {
                        val sentenceView = TextView(context).apply {
                            text = sentence
                            textSize = 13f
                            setTextColor(0xFFDDDDDD.toInt())
                            setPadding(16, 10, 16, 10)
                            isClickable = true
                            setOnClickListener {
                                showHistoryCorrectionDialog(sentence)
                            }
                        }
                        container.addView(sentenceView)

                        val divider = View(context).apply {
                            setBackgroundColor(0xFF2A2A2A.toInt())
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, 1
                            )
                        }
                        container.addView(divider)
                    }
                }

                val dialog = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
                    .setTitle("ページ履歴  タップで修正・学習")
                    .setView(scrollView)
                    .setPositiveButton("閉じる") { dlg, _ ->
                        dlg.dismiss()
                        if (wasReading) {
                            isPaused = false
                            speakCurrentSentence()
                            updatePlayPauseButton()
                        }
                    }
                    .setOnCancelListener {
                        if (wasReading) {
                            isPaused = false
                            speakCurrentSentence()
                            updatePlayPauseButton()
                        }
                    }
                    .create()
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dialog.show()
                debugLog("[v1.1.46 PageHistory]", "Dialog shown: ${pageHistory.size} pages")
            } catch (e: Exception) {
                Log.e(TAG, "[PageHistory] Failed to show dialog: ${e.message}", e)
            }
        }
    }

    // v1.1.46: 履歴文の修正ダイアログ（TTS管理なし、currentSentences更新なし）
    private fun showHistoryCorrectionDialog(originalSentence: String) {
        val context = this
        mainHandler.post {
            try {
                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 32, 48, 16)
                }

                val label = TextView(context).apply {
                    text = "選択した文:"
                    setTextColor(0xFF888888.toInt())
                    textSize = 12f
                }
                layout.addView(label)

                val originalView = TextView(context).apply {
                    text = originalSentence
                    textSize = 14f
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(0, 8, 0, 24)
                }
                layout.addView(originalView)

                val editText = EditText(context).apply {
                    setText(originalSentence)
                    textSize = 14f
                    setSelectAllOnFocus(true)
                    hint = "正しい文を入力"
                }
                layout.addView(editText)

                val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle("テキスト修正（履歴）")
                    .setView(layout)
                    .setPositiveButton("学習") { dlg, _ ->
                        val corrected = editText.text.toString().trim()
                        if (corrected.isNotEmpty() && corrected != originalSentence) {
                            val autoLearn = AutoLearnManager.getInstance(context)
                            val count = autoLearn.learnFromUserCorrection(originalSentence, corrected)
                            val msg = if (count > 0) "★ ${count}パターン学習しました" else "差分なし"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            debugLog("[v1.1.46 PageHistory]", "Learned $count patterns: '${originalSentence.take(20)}' → '${corrected.take(20)}'")
                            textCorrector.setForceFullCorrectionOnce()
                        }
                        dlg.dismiss()
                    }
                    .setNegativeButton("キャンセル") { dlg, _ -> dlg.dismiss() }
                    .create()
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dialog.show()
            } catch (e: Exception) {
                Log.e(TAG, "[PageHistory] Correction dialog error: ${e.message}", e)
            }
        }
    }

    /**
     * v1.1.45: ユーザー手動修正パターンを開発者に送信
     * from/toペアのみ送信（書籍内容・contextは含まない）
     * バックエンド: Google Apps Script → Googleスプレッドシートに自動記録
     */
    private fun contributeUserPatterns(patterns: List<AutoLearnManager.LearnedPattern>) {
        if (patterns.isEmpty()) {
            mainHandler.post {
                Toast.makeText(this, "送信できる手動修正パターンがありません（長押しで修正すると貯まります）", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (CONTRIBUTION_ENDPOINT.isEmpty()) {
            mainHandler.post {
                Toast.makeText(this, "この機能は現在準備中です", Toast.LENGTH_SHORT).show()
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val patternsArray = JSONArray()
                patterns.forEach { p ->
                    patternsArray.put(JSONObject().apply {
                        put("from", p.from)
                        put("to", p.to)
                    })
                }
                val payload = JSONObject().apply {
                    put("patterns", patternsArray)
                    put("ts", System.currentTimeMillis())
                    put("count", patterns.size)
                    put("v", BuildConfig.VERSION_NAME)
                }.toString()

                // Apps Scriptは実行成功時に302（echoURLへのリダイレクト）を返す
                // 302 = スクリプト実行済み = 成功なので追いかけない
                val code = postToAppsScript(CONTRIBUTION_ENDPOINT, payload)
                mainHandler.post {
                    if (code in 200..299 || code == 302) {
                        Toast.makeText(
                            this@OverlayService,
                            "✓ ${patterns.size}件を送信しました。ありがとうございます！",
                            Toast.LENGTH_LONG
                        ).show()
                        Log.d(TAG, "[Contribute] Sent ${patterns.size} USER patterns (HTTP $code)")
                    } else {
                        Toast.makeText(this@OverlayService, "送信失敗 (code: $code)", Toast.LENGTH_SHORT).show()
                        Log.w(TAG, "[Contribute] Server error: $code")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Contribute] Failed: ${e.message}")
                mainHandler.post {
                    Toast.makeText(this@OverlayService, "送信失敗。ネットワークを確認してください", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * v1.1.45: Google Apps ScriptへのシングルショットPOST
     * Apps ScriptはPOSTを受け取った時点でスクリプト実行 → 302を返す
     * 302はリダイレクトではなく「実行完了」のシグナルなので追わない
     */
    private fun postToAppsScript(urlStr: String, payload: String): Int {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    // v1.1.38: 学習済みパターン管理ダイアログ
    private fun showPatternManagerDialog() {
        val autoLearn = AutoLearnManager.getInstance(this)
        val allPatterns = autoLearn.getAllPatternsSorted()
        val promoted = allPatterns.filter { it.count >= 3 }
        val context = this

        mainHandler.post {
            try {
                val scrollView = ScrollView(context)
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 16, 32, 16)
                }
                scrollView.addView(container)

                if (promoted.isEmpty()) {
                    val emptyText = TextView(context).apply {
                        text = "まだ学習済みパターンがありません\n（読み上げ中にテキストをロングタップして\n修正すると学習されます）"
                        setTextColor(0xFF888888.toInt())
                        textSize = 13f
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, 48, 0, 48)
                    }
                    container.addView(emptyText)
                } else {
                    val userCount = promoted.count { it.source == "USER" }
                    val llmCount = promoted.count { it.source != "USER" }
                    val summaryText = TextView(context).apply {
                        text = "昇格済み: ${promoted.size}個 (手動: $userCount, AI: $llmCount)"
                        setTextColor(0xFF88BBFF.toInt())
                        textSize = 12f
                        setPadding(0, 0, 0, 16)
                    }
                    container.addView(summaryText)

                    val divider = View(context).apply {
                        setBackgroundColor(0xFF444444.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).also { it.bottomMargin = 8 }
                    }
                    container.addView(divider)

                    for (pattern in promoted) {
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setPadding(0, 10, 0, 10)
                        }

                        // ソースバッジ: 👤=USER, 🤖=LLM
                        val badge = TextView(context).apply {
                            text = if (pattern.source == "USER") "👤" else "🤖"
                            textSize = 14f
                            setPadding(0, 0, 8, 0)
                        }

                        // パターンテキスト: 'from' → 'to'
                        val patternText = TextView(context).apply {
                            text = "'${pattern.from}' → '${pattern.to}'"
                            textSize = 12f
                            setTextColor(0xFFEEEEEE.toInt())
                            layoutParams = LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                            )
                        }

                        // 信頼度
                        val confPct = (pattern.confidence * 100).toInt()
                        val confColor = when {
                            pattern.confidence >= 0.8f -> 0xFF88FF88.toInt()  // 緑: 高信頼
                            pattern.confidence >= 0.5f -> 0xFFFFDD00.toInt()  // 黄: 中信頼
                            else -> 0xFFFF8888.toInt()                         // 赤: 低信頼
                        }
                        val confText = TextView(context).apply {
                            text = "$confPct%"
                            textSize = 11f
                            setTextColor(confColor)
                            setPadding(8, 0, 4, 0)
                        }

                        // 削除ボタン
                        val deleteBtn = Button(context).apply {
                            text = "×"
                            textSize = 11f
                            setPadding(8, 0, 8, 0)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            setOnClickListener {
                                autoLearn.deletePattern(pattern.from)
                                container.removeView(row)
                                updateOverlayUI()  // バッジ更新
                                Toast.makeText(context, "'${pattern.from}' を削除", Toast.LENGTH_SHORT).show()
                                debugLog("[PatternManager] Deleted pattern", "'${pattern.from}' → '${pattern.to}'")
                            }
                        }

                        row.addView(badge)
                        row.addView(patternText)
                        row.addView(confText)
                        row.addView(deleteBtn)
                        container.addView(row)

                        // 区切り線
                        val rowDivider = View(context).apply {
                            setBackgroundColor(0xFF333333.toInt())
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, 1
                            )
                        }
                        container.addView(rowDivider)
                    }
                }

                val dialog = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
                    .setTitle("学習済みパターン")
                    .setView(scrollView)
                    .setNegativeButton("貢献する ↑") { dlg, _ ->
                        dlg.dismiss()
                        contributeUserPatterns(autoLearn.getUserPatterns())
                    }
                    .setNeutralButton("全削除") { dlg, _ ->
                        autoLearn.clearAll()
                        updateOverlayUI()
                        Toast.makeText(context, "全パターンを削除しました", Toast.LENGTH_SHORT).show()
                        debugLog("[PatternManager] All patterns cleared by user")
                        dlg.dismiss()
                    }
                    .setPositiveButton("閉じる") { dlg, _ -> dlg.dismiss() }
                    .create()
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dialog.show()
                debugLog("[PatternManager] Dialog shown", "${promoted.size} promoted patterns")
            } catch (e: Exception) {
                Log.e(TAG, "[PatternManager] Failed to show dialog: ${e.message}", e)
            }
        }
    }

    // v1.1.37: ユーザーフィードバック修正ダイアログ
    private fun showCorrectionDialog(originalSentence: String) {
        // TTS一時停止
        val wasReading = isReading && !isPaused
        if (wasReading) {
            textToSpeech?.stop()
            isPaused = true
            updatePlayPauseButton()
        }

        val context = this
        mainHandler.post {
            try {
                // ダイアログレイアウト構築
                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 32, 48, 16)
                }

                val label = TextView(context).apply {
                    text = "現在の文:"
                    setTextColor(0xFF888888.toInt())
                    textSize = 12f
                }
                layout.addView(label)

                val originalView = TextView(context).apply {
                    text = originalSentence
                    textSize = 14f
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(0, 8, 0, 24)
                }
                layout.addView(originalView)

                val editText = EditText(context).apply {
                    setText(originalSentence)
                    textSize = 14f
                    setSelectAllOnFocus(true)
                    hint = "正しい文を入力"
                }
                layout.addView(editText)

                val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle("テキスト修正")
                    .setView(layout)
                    .setPositiveButton("学習") { dlg, _ ->
                        val corrected = editText.text.toString().trim()
                        if (corrected.isNotEmpty() && corrected != originalSentence) {
                            val autoLearn = AutoLearnManager.getInstance(context)
                            val count = autoLearn.learnFromUserCorrection(originalSentence, corrected)
                            val msg = if (count > 0) "★ ${count}パターン学習しました" else "差分なし"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            debugLog("[UserFeedback] Learned $count patterns", "'${originalSentence.take(20)}' → '${corrected.take(20)}'")
                            // v1.1.40: 今すぐ読み上げるテキストも修正テキストに差し替え
                            if (currentSentenceIndex < currentSentences.size) {
                                val updated = currentSentences.toMutableList()
                                updated[currentSentenceIndex] = corrected
                                currentSentences = updated
                                debugLog("[UserFeedback] Replaced sentence[$currentSentenceIndex] with corrected text")
                            }
                            // v1.1.41: 次ページのCleanPageスキップを無効化（ユーザー修正 = 品質問題の兆候）
                            textCorrector.setForceFullCorrectionOnce()
                            debugLog("[UserFeedback] setForceFullCorrectionOnce: next page will use LLM regardless of skip gates")
                        }
                        dlg.dismiss()
                        correctionDialogView = null
                        // TTS再開
                        if (wasReading) {
                            isPaused = false
                            speakCurrentSentence()
                            updatePlayPauseButton()
                        }
                    }
                    .setNegativeButton("キャンセル") { dlg, _ ->
                        dlg.dismiss()
                        correctionDialogView = null
                        if (wasReading) {
                            isPaused = false
                            speakCurrentSentence()
                            updatePlayPauseButton()
                        }
                    }
                    .create()

                // オーバーレイサービスからダイアログ表示するためにTYPE_APPLICATION_OVERLAY設定
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dialog.show()
                correctionDialogView = dialog.window?.decorView
                debugLog("[UserFeedback] Correction dialog shown", "sentence: ${originalSentence.take(30)}")
            } catch (e: Exception) {
                Log.e(TAG, "[UserFeedback] Failed to show dialog: ${e.message}", e)
                // TTS再開
                if (wasReading) {
                    isPaused = false
                    speakCurrentSentence()
                    updatePlayPauseButton()
                }
            }
        }
    }

    private fun updateOverlayText(text: String) {
        overlayView?.let { view ->
            val textView = view.findViewById<TextView>(R.id.overlayText)
            textView.text = text.take(50) + if (text.length > 50) "..." else ""

            // 5秒後に非表示 (既存のmainHandlerを再利用)
            mainHandler.postDelayed({
                updateOverlayUI()
            }, 5000)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.JAPANESE)
            when (result) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                    debugLog("Japanese TTS not supported, trying English")
                    textToSpeech?.setLanguage(Locale.ENGLISH)
                }
                else -> {
                    debugLog("TTS initialized successfully")
                }
            }

            // TTS設定
            textToSpeech?.let { tts ->
                tts.setSpeechRate(readingSpeed)
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        debugLog("TTS started", utteranceId)
                    }

                    override fun onDone(utteranceId: String?) {
                        debugLog("TTS completed", utteranceId)
                        onSentenceComplete()
                    }

                    override fun onError(utteranceId: String?) {
                        debugLog("TTS error", utteranceId)
                        handleError("TTS エラー", Exception("Utterance failed: $utteranceId"))
                    }
                })
            } ?: run {
                debugLog("TTS object is null in onInit")
                handleError("TTS初期化エラー", Exception("TextToSpeech is null after successful initialization"))
                return
            }

            appState.ttsInitialized = true
            updateNotification("準備完了")

        } else {
            debugLog("TTS initialization failed")
            handleError("TTS初期化エラー", Exception("TTS initialization failed"))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kindle TTS Reader",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kindle読み上げサービスの通知"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kindle TTS Reader")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSound(null)
            .build()
    }

    private fun updateNotification(message: String) {
        val notification = createNotification(message)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleError(title: String, error: Throwable) {
        debugLog("Error: $title", error.message)
        showToast("$title: ${error.message}")
    }

    private fun debugLog(message: String, data: Any? = null) {
        Log.d(TAG, "[$TAG] $message ${if (data != null) ": $data" else ""}")
    }

    /**
     * v1.0.18: MediaProjection関連リソースをクリーンアップ
     * 新しい画面キャプチャを開始する前に、既存のリソースを解放する
     */
    private fun cleanupMediaProjection() {
        debugLog("Cleaning up MediaProjection resources")

        // OCR停止
        stopAutoOCR()

        // VirtualDisplay解放
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            debugLog("Error releasing VirtualDisplay", e.message)
        }

        // MediaProjection停止
        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            debugLog("Error stopping MediaProjection", e.message)
        }

        // ImageReader解放
        try {
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            debugLog("Error closing ImageReader", e.message)
        }
// v1.0.83: latestImage解放        try {            latestImage?.close()            latestImage = null        } catch (e: Exception) {            debugLog("Error closing latestImage", e.message)        }

        // 状態リセット
        appState.screenCaptureActive = false
        isCapturing = false

        debugLog("MediaProjection cleanup completed")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false

        debugLog("OverlayService destroying")

        // 読み上げ停止
        if (isReading) {
            stopReading()
        }

        // Handler の保留中タスクをクリア
        mainHandler.removeCallbacksAndMessages(null)

        // MediaProjection関連リソースの解放
        cleanupMediaProjection()

        // オーバーレイビューの削除
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                debugLog("Error removing overlay view", e.message)
            }
            overlayView = null
        }

        // TTS の停止とシャットダウン
        textToSpeech?.let { tts ->
            try {
                tts.stop()
                tts.shutdown()
            } catch (e: Exception) {
                debugLog("Error shutting down TTS", e.message)
            }
            textToSpeech = null
        }

        debugLog("OverlayService destroyed")
    }
}