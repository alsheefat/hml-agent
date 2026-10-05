package com.hmlai.agent

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.drawerlayout.widget.DrawerLayout
import kotlin.math.abs

/** DrawerLayout with a deliberately short, natural edge-swipe trigger for HML. */
class HmlDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DrawerLayout(context, attrs, defStyleAttr) {
    private val edgeStartPx = resources.displayMetrics.density * 28f
    private val openDistancePx = resources.displayMetrics.density * 42f
    private var downX = 0f
    private var downY = 0f
    private var trackingEdge = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                trackingEdge = !isDrawerOpen(android.view.Gravity.LEFT) && downX <= edgeStartPx
            }
            MotionEvent.ACTION_MOVE -> if (trackingEdge) {
                val dx = ev.x - downX
                val dy = abs(ev.y - downY)
                if (dx >= openDistancePx && dx > dy * 1.15f) {
                    trackingEdge = false
                    openDrawer(android.view.Gravity.LEFT, true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> trackingEdge = false
        }
        return super.onInterceptTouchEvent(ev)
    }
}
