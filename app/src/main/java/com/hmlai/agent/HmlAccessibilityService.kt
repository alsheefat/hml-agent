package com.hmlai.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * HML Agent's Accessibility Service — this is what gives the agent real,
 * general control over the phone's screen: reading what's currently
 * displayed in ANY app, tapping on specific elements by their visible
 * text or description, typing into text fields, and scrolling.
 *
 * This is fundamentally different from DeviceCommandHandler (which only
 * launches apps via Intents). This service can act INSIDE whatever app
 * is currently open, the same way a person tapping the screen would.
 *
 * Requires the user to explicitly enable it in Android Settings ->
 * Accessibility (Android does not allow this to be silently turned on).
 */
class HmlAccessibilityService : AccessibilityService() {

    companion object {
        // Lets the rest of the app check "is the service actually running
        // right now" without needing a bound-service connection.
        var instance: HmlAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /** Checks whether the user has enabled HML Agent's Accessibility
         * Service in Android Settings, regardless of whether it's finished
         * binding yet (isRunning() can briefly be false right after the
         * user enables it, before Android actually connects the service —
         * checking this separately lets the app tell the difference
         * between "never enabled" and "enabled, just starting up"). */
        fun isEnabledInSettings(context: Context): Boolean {
            val expectedComponent = "${context.packageName}/${HmlAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                if (splitter.next().equals(expectedComponent, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No continuous event handling needed — actions below query the
        // screen on-demand via rootInActiveWindow rather than reacting
        // to every event, which keeps this efficient and predictable.
    }

    override fun onInterrupt() {}

    // ============================================================
    // SCREEN READING
    // ============================================================

    /** Returns a flattened, human-readable summary of what's currently
     * visible on screen — every clickable element's text/description —
     * so the AI can decide what to tap next. */
    fun describeCurrentScreen(): String {
        val root = rootInActiveWindow ?: return "Nothing readable is currently on screen."
        val elements = mutableListOf<String>()
        collectElements(root, elements)
        return if (elements.isEmpty()) {
            "The current screen has no readable elements."
        } else {
            elements.joinToString("\n")
        }
    }

    /** Captures an actual screenshot of the current screen as base64-
     * encoded JPEG, so the AI can see icon-only buttons, images, and
     * visual layout that the text-only element list above can't
     * capture — confirmed as a real gap by two independent reference
     * projects (PokeClaw, Mobilerun) that both combine accessibility
     * trees with screenshots rather than relying on text alone.
     * Requires API 30+ (Android 11); returns null below that or on
     * failure, and callers should fall back to text-only in that case. */
    fun captureScreenshotBase64(callback: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }

        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )
                            if (bitmap == null) {
                                callback(null)
                                return
                            }
                            val softwareBitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()

                            val outputStream = java.io.ByteArrayOutputStream()
                            softwareBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
                            val base64 = android.util.Base64.encodeToString(
                                outputStream.toByteArray(), android.util.Base64.NO_WRAP
                            )
                            callback(base64)
                        } catch (e: Exception) {
                            callback(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        callback(null)
                    }
                }
            )
        } catch (e: Exception) {
            callback(null)
        }
    }

    private fun collectElements(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int = 0) {
        if (depth > 25) return // guard against unexpectedly deep trees

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val label = when {
            !text.isNullOrEmpty() -> text
            !desc.isNullOrEmpty() -> desc
            else -> null
        }

        if (label != null && (node.isClickable || node.isEditable)) {
            val kind = if (node.isEditable) "field" else "button"
            out.add("- [$kind] \"$label\"")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectElements(child, out, depth + 1)
            child.recycle()
        }
    }

    // ============================================================
    // TAPPING BY VISIBLE TEXT
    // ============================================================

    /** Finds a clickable node whose visible text or description contains
     * (case-insensitive) the given label, and taps it. Returns true if
     * something was found and tapped. */
    fun tapByText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNodeByText(root, label.lowercase())
        if (target != null) {
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            val centerX = bounds.centerX().toFloat()
            val centerY = bounds.centerY().toFloat()
            return performTapAt(centerX, centerY)
        }
        return false
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, lowerLabel: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()

        if ((node.isClickable) && (text?.contains(lowerLabel) == true || desc?.contains(lowerLabel) == true)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, lowerLabel)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    // ============================================================
    // TYPING INTO THE CURRENTLY FOCUSED / A SPECIFIC FIELD
    // ============================================================

    /** Types text into the first editable field found on screen. Returns
     * true if a field was found and text was set. */
    fun typeIntoActiveField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = findEditableNode(root) ?: return false

        val arguments = android.os.Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    // ============================================================
    // GESTURES: TAP, SWIPE, SCROLL
    // ============================================================

    fun performTapAt(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Scrolls down on the current screen (a common "scroll down and find X" step). */
    fun scrollDown(): Boolean {
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.7f
        val endY = metrics.heightPixels * 0.3f
        return performSwipe(centerX, startY, centerX, endY)
    }

    fun scrollUp(): Boolean {
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.3f
        val endY = metrics.heightPixels * 0.7f
        return performSwipe(centerX, startY, centerX, endY)
    }

    fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun goHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }
}
