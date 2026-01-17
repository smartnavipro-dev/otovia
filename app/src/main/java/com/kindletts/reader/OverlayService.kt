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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.kindletts.reader.ocr.TextCorrector
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class OverlayService : Service(), TextToSpeech.OnInitListener {

    companion object {
        var isRunning = false
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "KindleTTSService"
        private const val TAG = "KindleTTS_Service"
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
    private var ocrExecutor: ScheduledExecutorService? = null
    private var isCapturing = false
    // v1.0.17: テキスト補正機能, v1.0.39: contextパラメータ追加
    private val textCorrector by lazy { TextCorrector(this) }

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
        appState.isReading = false
        appState.isPaused = false

        stopAutoOCR()
        textToSpeech?.stop()

        updateNotification("読み上げ停止")
        updateOverlayUI()
        updatePlayPauseButton()
    }

    private fun pauseReading() {
        debugLog("Pausing reading")

        isPaused = true
        appState.isPaused = true

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
                Log.e(TAG, "[v1.1.15] OpenCV initialization failed in applyBilateralFilter")
                return bitmap
            }

            // BitmapをMatに変換
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            // バイラテラルフィルター適用
            val filtered = Mat()
            Imgproc.bilateralFilter(mat, filtered, 9, 75.0, 75.0)

            // MatをBitmapに変換
            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
            Utils.matToBitmap(filtered, resultBitmap)

            // メモリ解放
            mat.release()
            filtered.release()

            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "[v1.1.15] applyBilateralFilter failed: ${e.message}", e)
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

                // 縦書き対応: テキストブロックを位置でソート
                val extractStart = System.currentTimeMillis()
                val extractedText = extractTextWithVerticalSupport(visionText)
                val extractTime = System.currentTimeMillis() - extractStart
                debugLog("[OCR Processing] Text extraction", "time: ${extractTime}ms, length: ${extractedText.length}")

                // ✨ v1.0.33: Phase 3対応 - OCR結果オブジェクトを渡して信頼度ベース補正を有効化
                val correctionStart = System.currentTimeMillis()
                val correctedText = textCorrector.correctText(extractedText, visionText)
                val correctionTime = System.currentTimeMillis() - correctionStart
                val stats = textCorrector.getCorrectionStats(extractedText, correctedText)

                debugLog("[OCR Processing] Text correction", """
                    time: ${correctionTime}ms,
                    original_length: ${extractedText.length},
                    corrected_length: ${correctedText.length},
                    corrections: ${stats.totalCorrections},
                    economic_terms: ${stats.economicTermsFixed},
                    katakana: ${stats.katakanaFixed}
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
                if (correctedText.isNotEmpty() && correctedText != lastRecognizedText && isReading && !isPaused) {
                    lastRecognizedText = correctedText
                    val sentences = splitIntoSentences(correctedText)
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
        return text.split(Regex("[。！？\\.\\!\\?]"))
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
            // 次の文を読み上げ (既存のmainHandlerを再利用)
            debugLog("[TTS] Next sentence", "Scheduling next sentence after 500ms delay")
            mainHandler.postDelayed({
                speakCurrentSentence()
            }, 500)
        } else {
            // ページ完了
            debugLog("[TTS] Page complete", """
                autoPageTurnEnabled: $autoPageTurnEnabled,
                isReading: $isReading,
                scheduling_next_page: ${autoPageTurnEnabled && isReading}
            """.trimIndent())

            if (autoPageTurnEnabled && isReading) {
                debugLog("[Auto Page Turn]", "Scheduling next page after 2000ms delay")
                mainHandler.postDelayed({
                    nextPage()
                }, 2000)
            }
        }
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

        appState.currentPage++

        // ✅ FIX: ページ変更時に状態をリセット（TTS継続の問題を修正）
        lastRecognizedText = ""
        currentSentences = emptyList()  // 古い文のリストをクリア
        currentSentenceIndex = 0         // インデックスをリセット
        textToSpeech?.stop()             // 前のページのTTSを停止

        debugLog("[State Reset]", "text/sentences/index cleared, TTS stopped, new page: ${appState.currentPage}")

        // ページめくり方向に応じてジェスチャーを選択
        val gestureAction = if (pageDirection == "right_to_next") "NEXT_PAGE" else "PREVIOUS_PAGE"
        debugLog("[Page Turn Gesture]", "action: $gestureAction, direction: $pageDirection")

        val intent = Intent(this, AutoPageTurnService::class.java)
        intent.action = gestureAction
        intent.putExtra("page_direction", pageDirection)
        startService(intent)

        // OCRを再実行（リトライ付き）
        // ページ遷移アニメーション完了を確実に待つため2.5秒に延長
        debugLog("[OCR Retry]", "Scheduling OCR retry: maxRetries=3, initialDelay=2500ms")
        performOCRWithRetry(maxRetries = 3, initialDelay = 2500)
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

        appState.currentPage--

        // ✅ FIX: ページ変更時に状態をリセット（TTS継続の問題を修正）
        lastRecognizedText = ""
        currentSentences = emptyList()  // 古い文のリストをクリア
        currentSentenceIndex = 0         // インデックスをリセット
        textToSpeech?.stop()             // 前のページのTTSを停止

        debugLog("[State Reset]", "text/sentences/index cleared, TTS stopped, new page: ${appState.currentPage}")

        // ページめくり方向に応じてジェスチャーを選択
        val gestureAction = if (pageDirection == "right_to_next") "PREVIOUS_PAGE" else "NEXT_PAGE"
        debugLog("[Page Turn Gesture]", "action: $gestureAction, direction: $pageDirection")

        val intent = Intent(this, AutoPageTurnService::class.java)
        intent.action = gestureAction
        intent.putExtra("page_direction", pageDirection)
        startService(intent)

        // OCRを再実行（リトライ付き）
        // ページ遷移アニメーション完了を確実に待つため2.5秒に延長
        debugLog("[OCR Retry]", "Scheduling OCR retry: maxRetries=3, initialDelay=2500ms")
        performOCRWithRetry(maxRetries = 3, initialDelay = 2500)
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
            }, if (retryCount == 0) initialDelay else 1500)  // ← リトライ時も1.5秒待つ
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
            statusText.text = "$status (${appState.currentPage}ページ)"
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