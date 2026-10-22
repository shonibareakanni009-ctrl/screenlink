package com.screenlink.share

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Injects taps/swipes so the Chromebook can control this phone (Browserling-style).
 * User must enable this service in system Accessibility settings.
 */
class TapAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: TapAccessibilityService? = null
        private const val TAG = "ScreenLinkTap"

        fun tapNormalized(nx: Float, ny: Float): Boolean {
            val svc = instance ?: return false
            val dm = svc.resources.displayMetrics
            val x = (nx.coerceIn(0f, 1f) * dm.widthPixels)
            val y = (ny.coerceIn(0f, 1f) * dm.heightPixels)
            return svc.dispatchTap(x, y)
        }

        fun swipeNormalized(nx1: Float, ny1: Float, nx2: Float, ny2: Float, ms: Long = 200): Boolean {
            val svc = instance ?: return false
            val dm = svc.resources.displayMetrics
            val x1 = nx1.coerceIn(0f, 1f) * dm.widthPixels
            val y1 = ny1.coerceIn(0f, 1f) * dm.heightPixels
            val x2 = nx2.coerceIn(0f, 1f) * dm.widthPixels
            val y2 = ny2.coerceIn(0f, 1f) * dm.heightPixels
            return svc.dispatchSwipe(x1, y1, x2, y2, ms)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun dispatchTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms.coerceIn(50, 800))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}
