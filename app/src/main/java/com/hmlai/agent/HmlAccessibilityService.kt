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
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

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

    private var lastApproveAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Normal actions query the screen on demand. The only thing reacted to here is the
        // short window after the user pressed "Update" in HML: if Android's installer (or the
        // "install unknown apps" page) comes up, tap through it. Best effort only.
        if (event == null || !UpdateAutoApprover.isArmed()) return
        val pkg = event.packageName?.toString() ?: return
        if (!UpdateAutoApprover.isInstallerPackage(pkg)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastApproveAt < 700) return
        lastApproveAt = now
        mainHandler.postDelayed({ approveInstallerScreen() }, 350)
    }

    private fun gatherNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 30 || out.size > 600) return
        out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            gatherNodes(child, out, depth + 1)
        }
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String =
        (node.text?.toString() ?: "").trim().ifEmpty { (node.contentDescription?.toString() ?: "").trim() }

    private fun approveInstallerScreen() {
        if (!UpdateAutoApprover.isArmed()) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!UpdateAutoApprover.isInstallerPackage(pkg)) return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        gatherNodes(root, nodes, 0)

        if (pkg == "com.android.settings") {
            // "Install unknown apps" page for HML: switch on "Allow from this source", go back.
            val toggle = nodes.firstOrNull {
                val l = nodeLabel(it).lowercase()
                it.isCheckable && l.contains("allow") && (l.contains("source") || l.contains("install"))
            } ?: return
            if (!toggle.isChecked) {
                var target: AccessibilityNodeInfo? = toggle
                var depth = 0
                while (target != null && depth < 4) {
                    if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
                    target = target.parent
                    depth++
                }
            }
            mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800)
            return
        }

        // System installer: tap "Update" / "Install" (or "Continue" on the way).
        for (wanted in listOf("update", "install", "continue")) {
            val button = nodes.firstOrNull {
                it.isClickable && it.isEnabled && nodeLabel(it).equals(wanted, ignoreCase = true)
            }
            if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        }
    }

    /** Takes a normal system screenshot (saved to the gallery, like pressing power + volume). */
    fun takeSystemScreenshot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    override fun onInterrupt() {}

    // ============================================================
    // APP LAUNCHING
    // ============================================================

    /** Launches an installed user-facing app by its visible label. This is
     * used as the first step of a larger mission; the agent then takes over
     * inside that app with the accessibility tree. */
    fun launchAppByName(appName: String): Boolean {
        val wanted = appName.trim().lowercase()
        if (wanted.isEmpty()) return false
        val pm = packageManager
        val candidates = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            .mapNotNull { app ->
                val intent = pm.getLaunchIntentForPackage(app.packageName) ?: return@mapNotNull null
                app to (pm.getApplicationLabel(app).toString() to intent)
            }
        val match = candidates.firstOrNull { it.second.first.lowercase() == wanted }
            ?: candidates.firstOrNull { it.second.first.lowercase().startsWith(wanted) }
            ?: candidates.firstOrNull { it.second.first.lowercase().contains(wanted) }
            ?: return false
        return try {
            match.second.second.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(match.second.second)
            true
        } catch (_: Exception) { false }
    }

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

        // Privacy guard: do not transmit screenshots while a password/PIN/OTP/card
        // entry field is visible. The accessibility tree can still be used for
        // navigation, but the visual frame stays on-device.
        if (hasSensitiveInputVisible()) {
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

    private fun hasSensitiveInputVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        return hasSensitiveInput(root)
    }

    private fun hasSensitiveInput(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 25) return false
        if (node.isEditable) {
            val type = node.inputType
            val variation = type and android.text.InputType.TYPE_MASK_VARIATION
            val passwordVariation = variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            val label = listOfNotNull(node.hintText?.toString(), node.text?.toString(), node.contentDescription?.toString())
                .joinToString(" ").lowercase()
            val sensitiveLabel = listOf("password", "passcode", "pin", "otp", "verification code", "cvv", "cvc", "card number")
                .any { label.contains(it) }
            if (passwordVariation || sensitiveLabel) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = hasSensitiveInput(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
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

    /** Finds the element whose visible text or description best matches the label (exact beats
     * "starts with" beats "contains"; directly clickable beats not) and taps it. Returns true if
     * something was found and tapped. */
    fun tapByText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val wanted = label.trim().lowercase()
        if (wanted.isEmpty()) return false
        val target = findBestNode(root, wanted) ?: return false
        // Prefer the app's accessibility click action. This is more reliable for
        // Messenger/WhatsApp Send controls than guessing screen coordinates.
        if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = target.parent
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
            depth++
        }
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        return performTapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun findBestNode(root: AccessibilityNodeInfo, wanted: String): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30) return
            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                var score = 0
                if (text == wanted || desc == wanted) score = 3
                else if (text.startsWith(wanted) || desc.startsWith(wanted)) score = 2
                else if (text.contains(wanted) || desc.contains(wanted)) score = 1
                if (score > 0) {
                    if (node.isClickable) score += 1
                    if (score > bestScore) {
                        bestScore = score
                        best = node
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return best
    }

    /** Submit the current editable field using Android's IME action instead of
     * visually tapping Gboard's Search/Done/Enter key. This prevents the keyboard
     * from being dragged or accidentally entering characters such as "T5". */
    fun performImeAction(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val root = rootInActiveWindow ?: return false
        val field = focusedEditable(root) ?: findEditableNode(root) ?: return false
        return field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    // ============================================================
    // TYPING INTO THE CURRENTLY FOCUSED / A SPECIFIC FIELD
    // ============================================================

    /** Types text into the first editable field found on screen. Returns
     * true if a field was found and text was set. */
    fun typeIntoActiveField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = focusedEditable(root) ?: findEditableNode(root) ?: return false

        val arguments = android.os.Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** The field that currently has the keyboard, if any (better than "the first field on screen"). */
    private fun focusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focused != null && focused.isEditable) focused else null
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

    /** Top edge of the on-screen keyboard, or null when it isn't showing. */
    private fun keyboardTop(): Int? {
        return try {
            val keyboard = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
            val r = Rect()
            keyboard.getBoundsInScreen(r)
            if (r.height() > 0) r.top else null
        } catch (e: Exception) {
            null
        }
    }

    private fun scrollableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30) return
            if (node.isScrollable && node.isVisibleToUser) found.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return found.sortedByDescending {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.width().toLong() * r.height().toLong()
        }
    }

    /**
     * Scrolls the page. First asks the app's own scrollable list to scroll (no touch involved).
     * Only if that fails does it swipe — and then strictly ABOVE the keyboard, because a swipe
     * across the keyboard is "swipe typing" and types random letters.
     */
    private fun scrollPage(down: Boolean): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            val action = if (down) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            } else {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            }
            for (node in scrollableNodes(root).take(4)) {
                if (node.performAction(action)) return true
            }
        }
        val metrics = resources.displayMetrics
        val bottomLimit = (keyboardTop() ?: metrics.heightPixels) - 32f * metrics.density
        val topLimit = metrics.heightPixels * 0.18f
        val span = bottomLimit - topLimit
        if (span < 200f) return false
        val x = metrics.widthPixels * 0.5f
        val low = topLimit + span * 0.8f
        val high = topLimit + span * 0.2f
        return if (down) performSwipe(x, low, x, high, 350) else performSwipe(x, high, x, low, 350)
    }

    /** Scrolls down on the current screen (a common "scroll down and find X" step). */
    fun scrollDown(): Boolean = scrollPage(true)

    fun scrollUp(): Boolean = scrollPage(false)

    fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun goHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }
}
