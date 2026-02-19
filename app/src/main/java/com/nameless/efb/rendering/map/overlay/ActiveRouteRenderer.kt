package com.nameless.efb.rendering.map.overlay

import android.opengl.GLES30
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.domain.flightplan.FlightPlan
import com.nameless.efb.domain.nav.GreatCircle
import com.nameless.efb.domain.nav.LatLon
import com.nameless.efb.domain.nav.WebMercator
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao

/**
 * Renders the active flight plan route line on the moving map (MM-10).
 *
 * Colours:
 *  - Magenta:        future legs (not yet flown)
 *  - Dim magenta:    past legs (already flown)
 *  - White diamond:  each waypoint symbol
 *
 * ETE per leg is recomputed each frame from [SimSnapshot.groundspeedMs].
 *
 * Must be initialised on the GL thread via [onGlReady].
 */
class ActiveRouteRenderer {

    private var vao: GlVao?    = null
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
        plan: FlightPlan,
        activeLegIndex: Int,
        snapshot: SimSnapshot?,
        mvpUniformLoc: Int,
        colorUniformLoc: Int,
        viewMatrix: FloatArray,
    ) {
        val vaoObj = vao ?: return
        val waypoints = plan.waypoints
        if (waypoints.size < 2) return

        val mvp = FloatArray(16)
        android.opengl.Matrix.setIdentityM(mvp, 0)
        android.opengl.Matrix.multiplyMM(mvp, 0, viewMatrix, 0, mvp.copyOf(), 0)
        GLES30.glUniformMatrix4fv(mvpUniformLoc, 1, false, mvp, 0)

        for (i in 1 until waypoints.size) {
            val isPast = i <= activeLegIndex
            if (isPast) {
                GLES30.glUniform4f(colorUniformLoc, 0.5f, 0f, 0.5f, 0.6f)  // dim magenta
            } else {
                GLES30.glUniform4f(colorUniformLoc, 1f, 0f, 1f, 1f)         // bright magenta
            }
            drawLeg(waypoints[i - 1].latLon, waypoints[i].latLon, vaoObj)
        }

        // Waypoint diamonds
        GLES30.glUniform4f(colorUniformLoc, 1f, 1f, 1f, 1f)
        for (wp in waypoints) {
            drawDiamond(wp.latLon, vaoObj)
        }
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    private fun drawLeg(from: LatLon, to: LatLon, vaoObj: GlVao) {
        val p1 = WebMercator.toMeters(from.latitude, from.longitude)
        val p2 = WebMercator.toMeters(to.latitude, to.longitude)
        val verts = floatArrayOf(
            p1[0].toFloat(), p1[1].toFloat(),
            p2[0].toFloat(), p2[1].toFloat(),
        )
        uploadAndDraw(verts, GLES30.GL_LINES, 2, vaoObj)
    }

    private fun drawDiamond(pos: LatLon, vaoObj: GlVao) {
        val p = WebMercator.toMeters(pos.latitude, pos.longitude)
        val cx = p[0].toFloat(); val cy = p[1].toFloat()
        val h = 2_000f  // half-size in metres
        val verts = floatArrayOf(
            cx, cy + h, cx - h, cy, cx, cy - h, cx + h, cy
        )
        uploadAndDraw(verts, GLES30.GL_LINE_LOOP, 4, vaoObj)
    }

    private fun uploadAndDraw(verts: FloatArray, mode: Int, count: Int, vaoObj: GlVao) {
        vaoObj.bind()
        vbo?.uploadDynamic(verts)
        val vboObj = vbo ?: return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboObj.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(mode, 0, count)
        vaoObj.unbind()
    }
}
