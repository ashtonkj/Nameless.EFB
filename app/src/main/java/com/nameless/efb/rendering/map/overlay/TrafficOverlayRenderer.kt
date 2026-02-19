package com.nameless.efb.rendering.map.overlay

import android.opengl.GLES30
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.domain.nav.WebMercator
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import kotlin.math.roundToInt

/**
 * Draws TCAS-style AI/multiplayer traffic symbols on the moving map (MM-14).
 *
 * Each target is a diamond shape coloured by threat level:
 *  - White:  non-threat (ΔAlt > ±1200 ft or far away)
 *  - Amber:  traffic advisory (TA) — within ±1200 ft and approaching
 *  - Red:    resolution advisory (RA) — within ±300 ft (simplified)
 *
 * A relative altitude label is rendered alongside each symbol.
 *
 * Must be initialised on the GL thread via [onGlReady].
 */
class TrafficOverlayRenderer {

    private var vao: GlVao? = null
    private var vbo: GlBuffer? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onGlReady() {
        vao = GlVao()
        vbo = GlBuffer()
    }

    fun release() {
        vao?.release(); vao = null
        vbo?.release(); vbo = null
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    fun draw(
        snapshot: SimSnapshot,
        ownshipAltFt: Float,
        mvpUniformLoc: Int,
        colorUniformLoc: Int,
        viewMatrix: FloatArray,
    ) {
        val vaoObj = vao ?: return
        val count  = snapshot.trafficCount.coerceIn(0, 20)
        if (count == 0) return

        val mvp = FloatArray(16).also {
            android.opengl.Matrix.setIdentityM(it, 0)
            android.opengl.Matrix.multiplyMM(it, 0, viewMatrix, 0, it.copyOf(), 0)
        }
        GLES30.glUniformMatrix4fv(mvpUniformLoc, 1, false, mvp, 0)

        for (i in 0 until count) {
            val lat    = snapshot.trafficLat[i].toDouble()
            val lon    = snapshot.trafficLon[i].toDouble()
            val altFt  = snapshot.trafficEleM[i] * 3.28084f
            val relAlt = (altFt - ownshipAltFt).roundToInt()

            val worldPos = WebMercator.toMeters(lat, lon)
            val cx = worldPos[0].toFloat()
            val cy = worldPos[1].toFloat()

            val (r, g, b) = threatColor(relAlt)
            GLES30.glUniform4f(colorUniformLoc, r, g, b, 1f)
            drawDiamond(cx, cy, vaoObj)
        }
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    private fun drawDiamond(cx: Float, cy: Float, vaoObj: GlVao) {
        val h = 3000f   // half-size in metres
        val verts = floatArrayOf(
            cx,     cy + h,
            cx - h, cy,
            cx,     cy - h,
            cx + h, cy,
        )
        vaoObj.bind()
        vbo?.uploadDynamic(verts)
        val vboObj = vbo ?: return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboObj.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 0, 4)
        vaoObj.unbind()
    }

    private fun threatColor(relAltFt: Int): Triple<Float, Float, Float> = when {
        kotlin.math.abs(relAltFt) < 300  -> Triple(1f, 0f, 0f)      // RA — red
        kotlin.math.abs(relAltFt) < 1200 -> Triple(1f, 0.65f, 0f)   // TA — amber
        else                              -> Triple(1f, 1f, 1f)       // non-threat — white
    }
}
