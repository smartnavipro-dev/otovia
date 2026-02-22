package com.otovia.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.*

class AutoPageTurnService : AccessibilityService() {

    companion object {
        private const val TAG = "Otovia_AutoPageTurn"
        private const val KINDLE_PACKAGE = "com.amazon.kindle"
        var isServiceEnabled = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceEnabled = true
        debugLog("AutoPageTurnService connected")
        showToast("自動ページめくり機能が有効になりました")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // このサービスは主にジェスチャー実行のために使用されるため、
        // アクセシビリティイベントの処理は最小限に留める
        event?.let {
            if (it.packageName == KINDLE_PACKAGE) {
                debugLog("Kindle event detected", it.eventType.toString())
            }
        }
    }

    override fun onInterrupt() {
        debugLog("AutoPageTurnService interrupted")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debugLog("onStartCommand called", "action: ${intent?.action}, hasIntent: ${intent != null}")

        when (intent?.action) {
            "NEXT_PAGE" -> {
                debugLog("Executing NEXT_PAGE action")
                performNextPageGesture()
            }
            "PREVIOUS_PAGE" -> {
                debugLog("Executing PREVIOUS_PAGE action")
                performPreviousPageGesture()
            }
            "TAP_CENTER" -> {
                debugLog("Executing TAP_CENTER action")
                performCenterTapGesture()
            }
            null -> {
                debugLog("Received null action")
            }
            else -> {
                debugLog("Unknown action received", intent.action)
            }
        }

        return START_NOT_STICKY
    }

    private fun performNextPageGesture() {
        debugLog("Performing next page gesture")

        if (!isKindleRunning()) {
            showToast("Kindleアプリが起動していません")
            return
        }

        try {
            // ✅ FIX: Kindleアプリはスワイプでページめくりするため、タップからスワイプに変更
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels.toFloat()
            val screenHeight = displayMetrics.heightPixels.toFloat()

            // 画面中央で右から左へスワイプ（次ページ）
            val startX = screenWidth * 0.8f
            val endX = screenWidth * 0.2f
            val y = screenHeight * 0.5f

            debugLog("Next page swipe", "from ($startX, $y) to ($endX, $y)")
            performSwipeGesture(startX, y, endX, y)

        } catch (e: Exception) {
            handleError("次ページジェスチャーエラー", e)
        }
    }

    private fun performPreviousPageGesture() {
        debugLog("Performing previous page gesture")

        if (!isKindleRunning()) {
            showToast("Kindleアプリが起動していません")
            return
        }

        try {
            // ✅ FIX: Kindleアプリはスワイプでページめくりするため、タップからスワイプに変更
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels.toFloat()
            val screenHeight = displayMetrics.heightPixels.toFloat()

            // 画面中央で左から右へスワイプ（前ページ）
            val startX = screenWidth * 0.2f
            val endX = screenWidth * 0.8f
            val y = screenHeight * 0.5f

            debugLog("Previous page swipe", "from ($startX, $y) to ($endX, $y)")
            performSwipeGesture(startX, y, endX, y)

        } catch (e: Exception) {
            handleError("前ページジェスチャーエラー", e)
        }
    }

    private fun performCenterTapGesture() {
        debugLog("Performing center tap gesture")

        if (!isKindleRunning()) {
            showToast("Kindleアプリが起動していません")
            return
        }

        try {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels.toFloat()
            val screenHeight = displayMetrics.heightPixels.toFloat()

            // 画面中央をタップ
            val x = screenWidth * 0.5f
            val y = screenHeight * 0.5f

            performTapGesture(x, y) { success ->
                debugLog("Center tap gesture", if (success) "succeeded" else "failed")
            }

        } catch (e: Exception) {
            handleError("中央タップジェスチャーエラー", e)
        }
    }

    private fun performTapGesture(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            debugLog("Gesture API not supported on this Android version")
            callback?.invoke(false)
            return
        }

        try {
            val path = Path()
            path.moveTo(x, y)

            val gestureBuilder = GestureDescription.Builder()
            val gesture = gestureBuilder
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        debugLog("Tap gesture completed", "x: $x, y: $y")
                        callback?.invoke(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        debugLog("Tap gesture cancelled", "x: $x, y: $y")
                        callback?.invoke(false)
                    }
                }, null)
            } else {
                // Fallback for older API levels
                debugLog("Using fallback gesture method")
                callback?.invoke(true)
            }

        } catch (e: Exception) {
            debugLog("Failed to perform tap gesture", e.message)
            callback?.invoke(false)
        }
    }

    private fun performSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            debugLog("Gesture API not supported on this Android version")
            return
        }

        try {
            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)

            val gestureBuilder = GestureDescription.Builder()
            val gesture = gestureBuilder
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        debugLog("Swipe gesture completed", "from ($startX, $startY) to ($endX, $endY)")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        debugLog("Swipe gesture cancelled", "from ($startX, $startY) to ($endX, $endY)")
                    }
                }, null)
            }

        } catch (e: Exception) {
            debugLog("Failed to perform swipe gesture", e.message)
        }
    }

    private fun isKindleRunning(): Boolean {
        try {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val packageName = rootNode.packageName?.toString()
                val isKindle = packageName == KINDLE_PACKAGE
                debugLog("Current app package", "$packageName (Kindle: $isKindle)")
                return isKindle
            }
        } catch (e: Exception) {
            debugLog("Error checking app package", e.message)
        }
        return false
    }

    private fun showToast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            debugLog("Error showing toast", e.message)
        }
    }

    private fun handleError(title: String, error: Throwable) {
        debugLog("Error: $title", error.message)
        showToast("$title: ${error.message}")
    }

    private fun debugLog(message: String, data: Any? = null) {
        Log.d(TAG, "[$TAG] $message ${if (data != null) ": $data" else ""}")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceEnabled = false
        debugLog("AutoPageTurnService destroyed")
    }
}