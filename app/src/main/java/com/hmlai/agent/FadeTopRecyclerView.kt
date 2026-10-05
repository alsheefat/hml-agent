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
 * RecyclerView whose content fades out toward the top edge (like ChatGPT). It only masks the
 * chat content itself: fully hidden down to [solidEnd], then a smooth ramp to fully visible at
 * [fadeEnd]. No colored overlay is drawn, so the app background glow is never covered and no
 * invisible rectangle is left behind the top controls.
 */
class FadeTopRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var solidEnd = 0
    private var fadeEnd = 0

    fun setFadeZone(solidEndPx: Int, fadeEndPx: Int) {
        if (solidEnd == solidEndPx && fadeEnd == fadeEndPx) return
        solidEnd = solidEndPx.coerceAtLeast(0)
        fadeEnd = fadeEndPx.coerceAtLeast(solidEnd + 1)
        maskPaint.shader = LinearGradient(
            0f, 0f, 0f, fadeEnd.toFloat(),
            intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
            floatArrayOf(0f, solidEnd.toFloat() / fadeEnd, 1f),
            Shader.TileMode.CLAMP
        )
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (fadeEnd <= 0) {
            super.dispatchDraw(canvas)
            return
        }
        val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), fadeEnd.toFloat(), maskPaint)
        canvas.restoreToCount(save)
    }
}
