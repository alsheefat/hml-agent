package com.hmlai.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider

/**
 * "Glass" layer that sits exactly behind the writing bar. It takes a small snapshot of the chat
 * (or home screen) that is currently underneath it and lets the GPU blur it, so the conversation
 * is visible through the bar, softly blurred — like Grok.
 *
 * Real blur needs Android 12+ (RenderEffect). Below that this view draws nothing and the bar
 * falls back to its (more opaque) plain background from drawable/composer_glass_bg.
 * Call [refresh] whenever the content behind the bar may have moved (scroll, new message, keyboard).
 */
class BlurBehindView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val cornerPx = resources.displayMetrics.density * 24f
    private val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val clipPath = Path()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val srcLoc = IntArray(2)
    private val dstLoc = IntArray(2)

    private var source: View? = null
    private var snapshot: Bitmap? = null

    init {
        isClickable = false
        isFocusable = false
        if (supported) {
            // Snapshot is half-resolution, so a smaller radius here still looks like a heavy blur.
            setRenderEffect(RenderEffect.createBlurEffect(14f, 14f, Shader.TileMode.CLAMP))
        }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
            }
        }
    }

    fun setSource(view: View?) {
        if (source === view) return
        source = view
        invalidate()
    }

    fun refresh() {
        if (supported) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clipPath.reset()
        clipPath.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), cornerPx, cornerPx, Path.Direction.CW)
        invalidateOutline()
        snapshot?.recycle()
        snapshot = null
    }

    override fun onDraw(canvas: Canvas) {
        if (!supported) return
        val src = source ?: return
        if (width <= 0 || height <= 0 || !src.isShown) return

        val scale = 0.5f
        val bw = (width * scale).toInt().coerceAtLeast(1)
        val bh = (height * scale).toInt().coerceAtLeast(1)
        var bmp = snapshot
        if (bmp == null || bmp.width != bw || bmp.height != bh) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            snapshot = bmp
        }
        bmp.eraseColor(0)

        src.getLocationOnScreen(srcLoc)
        getLocationOnScreen(dstLoc)
        val off = Canvas(bmp)
        off.scale(scale, scale)
        off.translate((srcLoc[0] - dstLoc[0]).toFloat(), (srcLoc[1] - dstLoc[1]).toFloat())
        src.draw(off)

        canvas.save()
        canvas.clipPath(clipPath)
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        snapshot?.recycle()
        snapshot = null
        super.onDetachedFromWindow()
    }
}
