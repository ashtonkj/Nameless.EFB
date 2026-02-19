package com.nameless.efb.rendering.g1000

import android.opengl.GLES30
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.rendering.gauge.GlViewport
import com.nameless.efb.rendering.gauge.applyViewport
import com.nameless.efb.rendering.map.MapRenderer
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * G-07 — PFD Inset Map.
 *
 * Draws a miniature moving map in the bottom-left corner of the PFD using the
 * same [MapRenderer] (and tile cache) shared with the MFD.
 *
 * Range is selectable via softkey: 1, 2, 3, 5, 7, 10, 15, 20 nm.
 *
 * @param mapRenderer  Shared moving-map renderer; may be null if map resources
 *                     are not yet initialised (draws a placeholder in that case).
 */
class PfdInsetMap(
    private val mapRenderer: MapRenderer? = null,
) {
    /**
     * Draw the inset map into [viewport].
     *
     * Must be called on the GL thread from [G1000PfdRenderer.drawFrame].
     *
     * @param snapshot   Latest sim state (position, heading); null = no data.
     * @param rangeNm    Desired half-range in nm.
     * @param viewport   GL sub-viewport for the inset map region.
     */
    fun draw(snapshot: SimSnapshot?, rangeNm: Float, viewport: GlViewport) {
        applyViewport(viewport)

        if (mapRenderer != null && snapshot != null) {
            mapRenderer.drawInset(snapshot, rangeNm, viewport)
        } else {
            drawPlaceholder(snapshot)
        }
    }

    /**
     * Draws a dark placeholder when the shared MapRenderer is unavailable.
     *
     * Shows a green ownship dot at the centre of the viewport.
     */
    private fun drawPlaceholder(snapshot: SimSnapshot?) {
        // Dark slate background.
        GLES30.glClearColor(0.05f, 0.08f, 0.12f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // Restore clear colour to black (BaseRenderer default).
        GLES30.glClearColor(0f, 0f, 0f, 1f)
    }

    companion object {
        /**
         * Selectable inset map ranges in nm (from G1000 softkey options).
         */
        val SELECTABLE_RANGES_NM = floatArrayOf(1f, 2f, 3f, 5f, 7f, 10f, 15f, 20f)

        /**
         * Returns the nearest selectable range to [requestedNm].
         */
        fun snapRange(requestedNm: Float): Float =
            SELECTABLE_RANGES_NM.minByOrNull { kotlin.math.abs(it - requestedNm) }
                ?: SELECTABLE_RANGES_NM[5]  // default 10 nm
    }
}
