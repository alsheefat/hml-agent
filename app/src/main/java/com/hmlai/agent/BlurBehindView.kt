package com.hmlai.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View

/**
 * Lightweight Android-12+ glass backdrop. It snapshots the visible content immediately
 * behind the composer and lets the platform blur the snapshot. On older Android versions
 * it gracefully falls back to the translucent glass background.
 */
class BlurBehindView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var source: View? = null
    private var snapshot: Bitmap? = null
    private var lastW = 0
    private var lastH = 0

    init {
        setBackgroundColor(Color.TRANSPARENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP))
        }
        isClickable = false
        isFocusable = false
    }

    fun setSource(view: View?) {
        source = view
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = source ?: return
        if (width <= 0 || height <= 0 || !src.isShown) return

        val w = width
        val h = height
        if (snapshot == null || lastW != w || lastH != h) {
            snapshot?.recycle()
            snapshot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            lastW = w
            lastH = h
        }

        val bitmap = snapshot ?: return
        bitmap.eraseColor(Color.TRANSPARENT)
        val offscreen = Canvas(bitmap)
        val srcLoc = IntArray(2)
        val dstLoc = IntArray(2)
        src.getLocationOnScreen(srcLoc)
        getLocationOnScreen(dstLoc)
        offscreen.save()
        offscreen.translate((srcLoc[0] - dstLoc[0]).toFloat(), (srcLoc[1] - dstLoc[1]).toFloat())
        src.draw(offscreen)
        offscreen.restore()
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun onDetachedFromWindow() {
        snapshot?.recycle()
        snapshot = null
        super.onDetachedFromWindow()
    }
}
