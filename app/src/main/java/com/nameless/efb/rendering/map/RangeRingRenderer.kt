package com.nameless.efb.rendering.map

import android.opengl.GLES30
import com.nameless.efb.domain.nav.LatLon
import com.nameless.efb.domain.nav.WebMercator
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws nautical-mile range rings around the ownship position (MM-11).
 *
 * Default rings: 5, 10, 25, 50, 100 nm.
 * Custom range: added at runtime via [addCustomRange] (number-pad dialog).
 *
 * Each ring is a [SEGMENTS]-segment polyline drawn in the map's Web Mercator
 * coordinate space so it stays geometrically correct at all zoom levels.
 *
 * Must be initialised on the GL thread via [onGlReady].
 */
class RangeRingRenderer {

    val defaultRanges = mutableListOf(5f, 10f, 25f, 50f, 100f)  // nm

    private var ringVao: GlVao? = null
    private var ringVbo: GlBuffer? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onGlReady() {
        ringVao = GlVao()
        ringVbo = GlBuffer()
        buildUnitCircle()
    }

    fun release() {
        ringVao?.release(); ringVao = null
        ringVbo?.release(); ringVbo = null
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun addCustomRange(nm: Float) {
        if (!defaultRanges.contains(nm)) defaultRanges.add(nm)
    }

    /**
     * Draw all range rings for [ownship] at the current map scale.
     *
     * [metersPerPixel] is derived from the current zoom level and screen DPI.
     * [mvpUniformLoc] and [colourUniformLoc] are shader uniform locations.
     * [viewMatrix] is the 16-element column-major map view matrix.
     */
    fun draw(
        ownship: LatLon,
        ranges: List<Float> = defaultRanges,
        metersPerPixel: Float,
        mvpUniformLoc: Int,
        colourUniformLoc: Int,
        viewMatrix: FloatArray,
    ) {
        val vaoObj = ringVao ?: return
        val ownshipPos = WebMercator.toMeters(ownship.latitude, ownship.longitude)

        GLES30.glUniform4f(colourUniformLoc, 0.2f, 0.8f, 0.2f, 0.7f)  // translucent green

        for (rangeNm in ranges) {
            val radiusMeters = rangeNm * 1852f
            val modelMatrix = FloatArray(16)
            android.opengl.Matrix.setIdentityM(modelMatrix, 0)
            android.opengl.Matrix.translateM(modelMatrix, 0,
                ownshipPos[0].toFloat(), ownshipPos[1].toFloat(), 0f)
            android.opengl.Matrix.scaleM(modelMatrix, 0,
                radiusMeters, radiusMeters, 1f)

            val mvp = FloatArray(16)
            android.opengl.Matrix.multiplyMM(mvp, 0, viewMatrix, 0, modelMatrix, 0)
            GLES30.glUniformMatrix4fv(mvpUniformLoc, 1, false, mvp, 0)

            vaoObj.bind()
            GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 0, SEGMENTS)
            vaoObj.unbind()
        }
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    /** Builds a unit circle (radius = 1) as [SEGMENTS] vertices. */
    private fun buildUnitCircle() {
        val verts = FloatArray(SEGMENTS * 2)
        val step = (2.0 * PI / SEGMENTS).toFloat()
        for (i in 0 until SEGMENTS) {
            verts[i * 2]     = cos(i * step)
            verts[i * 2 + 1] = sin(i * step)
        }
        val v = ringVao ?: return
        v.bind()
        ringVbo?.upload(verts)
        val vboObj = ringVbo ?: return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboObj.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        v.unbind()
    }

    companion object {
        private const val SEGMENTS = 64
    }
}
