package com.hmlai.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/** One visible element on the current screen, captured from the accessibility tree. */
class UiNode(
    val info: AccessibilityNodeInfo,
    val text: String,
    val desc: String,
    val hint: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val showingHint: Boolean
) {
    /** What the person would read: visible text, or the content description for icon buttons. */
    val label: String get() = if (text.isNotEmpty() && !showingHint) text else desc

    /** Everything searchable about the node (text + description + hint), lower-cased. */
    val searchable: String get() = "$text $desc $hint".lowercase()
}

/**
 * Small, careful helpers for driving other apps through the accessibility tree.
 * All calls are blocking, so use them from a background thread (QuickSkills does).
 */
class UiTools(
    private val service: HmlAccessibilityService,
    private val isCancelled: () -> Boolean
) {

    companion object {
        /** Lower-case, letters/digits only (Bangla and other scripts kept), single spaces. */
        fun norm(s: String): String =
            s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    val cancelled: Boolean get() = isCancelled()

    fun activePackage(): String? = try {
        service.rootInActiveWindow?.packageName?.toString()
    } catch (e: Exception) {
        null
    }

    fun snapshot(): List<UiNode> {
        val root = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val out = ArrayList<UiNode>()
        collect(root, out, 0)
        return out
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<UiNode>, depth: Int) {
        if (depth > 40 || out.size > 900) return
        if (node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = node.hintText?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() || node.isEditable || node.isClickable) {
                out.add(
                    UiNode(
                        info = node,
                        text = text,
                        desc = desc,
                        hint = hint,
                        bounds = bounds,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        showingHint = node.isShowingHintText
                    )
                )
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1)
        }
    }

    /** Retries [block] until it returns non-null, the timeout passes, or the mission is cancelled. */
    fun <T> waitFor(timeoutMs: Long, pollMs: Long = 300, block: () -> T?): T? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!isCancelled()) {
            val result = try {
                block()
            } catch (e: Exception) {
                null
            }
            if (result != null) return result
            if (SystemClock.uptimeMillis() >= end) return null
            Thread.sleep(pollMs)
        }
        return null
    }

    fun sleep(ms: Long) {
        var left = ms
        while (left > 0 && !isCancelled()) {
            val slice = if (left < 100) left else 100L
            Thread.sleep(slice)
            left -= slice
        }
    }

    /**
     * Clicks the node, or the nearest clickable parent (list rows are usually clickable on the
     * row, not on the text inside). Falls back to a real tap at its centre.
     */
    fun click(node: UiNode): Boolean {
        var current: AccessibilityNodeInfo? = node.info
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            depth++
        }
        return service.performTapAt(node.bounds.exactCenterX(), node.bounds.exactCenterY())
    }

    /** Current text of an editable node (empty if it only shows its hint). */
    fun contentOf(node: UiNode): String {
        node.info.refresh()
        return if (node.info.isShowingHintText) "" else node.info.text?.toString().orEmpty()
    }

    /**
     * Puts [value] into an editable field. Uses ACTION_SET_TEXT (no keyboard keys are pressed,
     * so nothing can be mistyped); if the app refuses, pastes through the clipboard instead.
     */
    fun setText(node: UiNode, value: String): Boolean {
        node.info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        sleep(150)

        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        var ok = node.info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            sleep(150)
            ok = contentOf(node).contains(value.trim())
        }
        if (!ok) {
            try {
                val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("hml", value))
                node.info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                ok = node.info.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (ok) {
                    sleep(200)
                    ok = contentOf(node).contains(value.trim())
                }
            } catch (e: Exception) {
                ok = false
            }
        }
        return ok
    }
}
