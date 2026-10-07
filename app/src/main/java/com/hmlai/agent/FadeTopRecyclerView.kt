package com.hmlai.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView whose content fades out toward the very top edge of the screen (like ChatGPT).
 * Content is fully transparent at y = 0 (behind the clock / network icons) and becomes fully
 * visible at y = [fadeHeight]. It only masks the chat itself — no colored overlay is drawn, so
 * nothing sits behind the top controls except their own button surfaces.
 */
class FadeTopRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    // DST_OUT removes content where the mask is opaque, so mask alpha = 1 - visibility.
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var fadeHeight = 0

    /** Set to true while a glass layer snapshots this list, so the blur sees the real content. */
    @JvmField
    var suppressFade = false

    fun setFadeHeight(px: Int) {
        if (px == fadeHeight) return
        fadeHeight = px.coerceAtLeast(0)
        maskPaint.shader = if (fadeHeight == 0) null else LinearGradient(
            0f, 0f, 0f, fadeHeight.toFloat(),
            intArrayOf(0xFF000000.toInt(), 0xA6000000.toInt(), 0x33000000, 0x00000000),
            floatArrayOf(0f, 0.45f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (fadeHeight <= 0 || suppressFade) {
            super.dispatchDraw(canvas)
            return
        }
        val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), fadeHeight.toFloat(), maskPaint)
        canvas.restoreToCount(save)
    }
}
