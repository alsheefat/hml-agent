package com.hmlai.agent

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A "Dynamic Island"-style floating capsule shown over whatever app is open while
 * HML Agent is acting on screen: a pill at the top-center (where a real Dynamic
 * Island sits), a pulsing status dot, and a small pop animation whenever the
 * status text changes. The concept was inspired by an overlay seen in a reference
 * APK; this is an original implementation in our own theme.
 *
 * Needs the SYSTEM_ALERT_WINDOW permission, which Android may block for sideloaded
 * APKs (Restricted Settings). It is a nice-to-have visual: every failure path is
 * silent and the agent works the same without it.
 */
object OverlayBubble {

    private var overlayContainer: LinearLayout? = null
    private var statusText: TextView? = null
    private var windowManager: WindowManager? = null
    private var pulseAnimator: ValueAnimator? = null

    fun canShowOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestOverlayPermission(context: Context) {
        val intent = android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun show(context: Context, status: String = "HML Agent") {
        if (!canShowOverlay(context)) return

        val appContext = context.applicationContext
        val wm = windowManager ?: (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .also { windowManager = it }
        val density = appContext.resources.displayMetrics.density

        if (overlayContainer == null) {
            val dot = View(appContext).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#2861FF"))
                }
            }
            val text = TextView(appContext).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                maxLines = 1
            }
            val pad = (14 * density).toInt()
            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, pad * 2 / 3, pad, pad * 2 / 3)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E6000000"))
                    cornerRadius = 999f
                    setStroke((1.5f * density).toInt(), Color.parseColor("#2861FF"))
                }
                val dotSize = (8 * density).toInt()
                addView(dot, LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = (8 * density).toInt()
                })
                addView(text, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                scaleX = 0.6f
                scaleY = 0.6f
                alpha = 0f
            }

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (12 * density).toInt()
            }

            try {
                wm.addView(container, params)
            } catch (e: Exception) {
                return
            }
            overlayContainer = container
            statusText = text
            container.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(280).setInterpolator(OvershootInterpolator(1.6f)).start()
            startPulse(dot)
        }

        val newText = "🤖 $status"
        if (statusText?.text?.toString() != newText) {
            overlayContainer?.let { view ->
                AnimatorSet().apply {
                    playSequentially(
                        ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.08f).setDuration(90),
                        ObjectAnimator.ofFloat(view, "scaleX", 1.08f, 1f).setDuration(120)
                    )
                    start()
                }
            }
        }
        statusText?.text = newText
    }

    private fun startPulse(dot: View) {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0.4f, 1f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { dot.alpha = it.animatedValue as Float }
            start()
        }
    }

    fun hide(context: Context) {
        pulseAnimator?.cancel()
        pulseAnimator = null
        val view = overlayContainer ?: return
        overlayContainer = null
        statusText = null
        fun remove() {
            try { windowManager?.removeView(view) } catch (e: Exception) { /* already gone */ }
        }
        try {
            view.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f)
                .setDuration(180).withEndAction { remove() }.start()
        } catch (e: Exception) {
            remove()
        }
    }
}
