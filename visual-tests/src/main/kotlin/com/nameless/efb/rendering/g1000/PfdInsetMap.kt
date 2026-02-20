package com.nameless.efb.rendering.g1000

import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.rendering.gauge.GlViewport
import com.nameless.efb.rendering.gauge.applyViewport
import android.opengl.GLES30

/**
 * Stub PfdInsetMap — replaces the real implementation to avoid pulling in
 * MapRenderer (which depends on the full nav/tile stack).
 *
 * In visual tests we always pass null for PfdInsetMap, so this class is only
 * needed for compilation. The draw() method is never actually called.
 */
class PfdInsetMap(mapRenderer: Any? = null) {

    fun draw(snapshot: SimSnapshot?, rangeNm: Float, viewport: GlViewport) {
        applyViewport(viewport)
        GLES30.glClearColor(0.05f, 0.08f, 0.12f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
    }

    companion object {
        val SELECTABLE_RANGES_NM = floatArrayOf(1f, 2f, 3f, 5f, 7f, 10f, 15f, 20f)
        fun snapRange(requestedNm: Float): Float =
            SELECTABLE_RANGES_NM.minByOrNull { kotlin.math.abs(it - requestedNm) } ?: 10f
    }
}
