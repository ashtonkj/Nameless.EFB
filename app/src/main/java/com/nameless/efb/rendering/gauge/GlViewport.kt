package com.nameless.efb.rendering.gauge

import android.opengl.GLES30

/**
 * A rectangular sub-region of the GL framebuffer assigned to one instrument gauge.
 *
 * [x] and [y] are the lower-left corner in pixels (GL convention: y=0 is bottom).
 */
data class GlViewport(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Activate [vp] as the current GL viewport.
 *
 * Call before issuing draw calls for the gauge that owns this viewport.
 */
fun applyViewport(vp: GlViewport) {
    GLES30.glViewport(vp.x, vp.y, vp.width, vp.height)
}
