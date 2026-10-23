package com.screenlink.share

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Injects taps/swipes/text so the Chromebook can control this phone.
 */
class TapAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenLinkA11y"
        @Volatile var instance: TapAccessibilityService? = null

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

        fun typeText(text: String): Boolean {
            val svc = instance ?: return false
            return svc.injectText(text)
        }

        fun globalBack(): Boolean {
            val svc = instance ?: return false
            return svc.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        fun globalHome(): Boolean {
            val svc = instance ?: return false
            return svc.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        fun globalRecents(): Boolean {
            val svc = instance ?: return false
            return svc.performGlobalAction(GLOBAL_ACTION_RECENTS)
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
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
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

    private fun injectText(text: String): Boolean {
        try {
            val root = rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                val existing = focused.text?.toString() ?: ""
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    existing + text
                )
                val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                focused.recycle()
                root.recycle()
                return ok
            }
            val editable = findEditable(root)
            if (editable != null) {
                val existing = editable.text?.toString() ?: ""
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    existing + text
                )
                val ok = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                editable.recycle()
                root.recycle()
                return ok
            }
            root.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "injectText: ${e.message}")
        }
        return false
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }
}
