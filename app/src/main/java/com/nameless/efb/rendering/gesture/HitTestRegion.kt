package com.nameless.efb.rendering.gesture

import android.view.MotionEvent
import android.view.View

/**
 * A circular tap-target on the gauge panel (SG-16).
 *
 * Coordinates ([centerX], [centerY]) are in view pixel space.
 * [radiusDp] is converted to pixels at hit-test time using the view's density.
 * Minimum 60 dp per spec to guarantee reachable targets on a tablet.
 */
data class HitTestRegion(
    val centerX: Float,
    val centerY: Float,
    val radiusDp: Float = 60f,
    val onTap: () -> Unit,
    val onPress: () -> Unit = {},
) {
    fun contains(x: Float, y: Float, density: Float): Boolean {
        val radiusPx = radiusDp * density
        val dx = x - centerX
        val dy = y - centerY
        return dx * dx + dy * dy <= radiusPx * radiusPx
    }
}

/**
 * Dispatches tap/press events to whichever [HitTestRegion] the finger lands in.
 *
 * Returns `true` (consumed) only when a region is active, so unhandled touches
 * fall through to the underlying view hierarchy.
 */
class GaugeTouchHandler(private val regions: List<HitTestRegion>) : View.OnTouchListener {

    private var activeRegion: HitTestRegion? = null

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val density = view.resources.displayMetrics.density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = regions.firstOrNull { it.contains(x, y, density) }
                hit?.onPress?.invoke()
                activeRegion = hit
            }
            MotionEvent.ACTION_UP -> {
                val hit = regions.firstOrNull { it.contains(x, y, density) }
                if (hit == activeRegion) hit?.onTap?.invoke()
                activeRegion = null
            }
            MotionEvent.ACTION_CANCEL -> activeRegion = null
        }
        return activeRegion != null
    }
}
