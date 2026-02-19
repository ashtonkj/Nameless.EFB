package com.nameless.efb.rendering.map

import android.opengl.GLES30
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.domain.nav.WebMercator
import com.nameless.efb.domain.nav.lerpAngle
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao

/**
 * Draws the ownship (aircraft) symbol on the moving map.
 *
 * The symbol is a stylised 5-vertex arrow rendered as a filled triangle fan.
 * It is positioned at the interpolated aircraft position between 20 Hz dataref
 * updates (MM-02: smooth 60 fps rendering from 20 Hz source data).
 *
 * The VAO is created once on the GL thread via [onGlReady] and updated only
 * when the symbol size needs to change.
 */
class OwnshipRenderer {

    private var vao: GlVao? = null
    private var vbo: GlBuffer? = null

    // Interpolation state
    private var prevSnapshot: SimSnapshot? = null
    private var currSnapshot: SimSnapshot? = null
    private var lastUpdateNs: Long = 0L
    private val datarefIntervalNs = 50_000_000L  // 20 Hz = 50 ms

    // Symbol size in Web Mercator metres
    private var symbolSizeMeters = 1500f

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Initialise GL resources. Must be called from the GL thread.
     */
    fun onGlReady() {
        vao = GlVao()
        vbo = GlBuffer()
        buildSymbol()
    }

    /**
     * Release GL resources. Must be called from the GL thread.
     */
    fun release() {
        vao?.release(); vao = null
        vbo?.release(); vbo = null
    }

    // ── Data update ───────────────────────────────────────────────────────────

    /**
     * Called from any thread when a new [SimSnapshot] arrives (~20 Hz).
     */
    fun onSnapshot(snapshot: SimSnapshot) {
        prevSnapshot = currSnapshot ?: snapshot
        currSnapshot = snapshot
        lastUpdateNs = System.nanoTime()
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    /**
     * Draw the ownship symbol at the interpolated position.
     *
     * [mvpUniformLoc] and [colourUniformLoc] are the uniform locations of the
     * currently bound shader's `u_mvp` (mat4) and `u_colour` (vec4).
     * [viewMatrix] is the 4×4 map view matrix as a column-major float array.
     */
    fun draw(
        mvpUniformLoc: Int,
        colourUniformLoc: Int,
        viewMatrix: FloatArray,
    ) {
        val cur = currSnapshot ?: return
        val vaoObj = vao ?: return

        val now = System.nanoTime()
        val t = ((now - lastUpdateNs).toFloat() / datarefIntervalNs).coerceIn(0f, 1f)

        // Interpolate position
        val prev = prevSnapshot ?: cur
        val interpLat = prev.latitude  + (cur.latitude  - prev.latitude)  * t
        val interpLon = prev.longitude + (cur.longitude - prev.longitude) * t
        val interpHdg = lerpAngle(prev.magHeadingDeg, cur.magHeadingDeg, t)

        val worldPos = WebMercator.toMeters(interpLat, interpLon)
        val hdgRad   = Math.toRadians(interpHdg.toDouble()).toFloat()

        // Build model matrix: translate → rotate → scale
        val modelMatrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(modelMatrix, 0)
        android.opengl.Matrix.translateM(modelMatrix, 0,
            worldPos[0].toFloat(), worldPos[1].toFloat(), 0f)
        android.opengl.Matrix.rotateM(modelMatrix, 0,
            Math.toDegrees(hdgRad.toDouble()).toFloat(), 0f, 0f, 1f)
        android.opengl.Matrix.scaleM(modelMatrix, 0,
            symbolSizeMeters, symbolSizeMeters, 1f)

        val mvp = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvp, 0, viewMatrix, 0, modelMatrix, 0)
        GLES30.glUniformMatrix4fv(mvpUniformLoc, 1, false, mvp, 0)
        GLES30.glUniform4f(colourUniformLoc, 0.9f, 0.9f, 0.2f, 1f)  // bright yellow

        vaoObj.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, SYMBOL_VERTEX_COUNT)
        vaoObj.unbind()
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    /**
     * Builds a stylised aircraft arrow in local coordinates (−1..+1 extent).
     * The nose points toward +Y (north); the shader's rotate transform aligns
     * it to the aircraft heading.
     */
    private fun buildSymbol() {
        // 5 vertices: tip, left-wing, tail-centre, right-wing, close
        val vertices = floatArrayOf(
             0.00f,  1.00f,   // nose (tip)
            -0.60f, -0.30f,   // left wing
            -0.20f, -0.50f,   // tail left
             0.20f, -0.50f,   // tail right
             0.60f, -0.30f,   // right wing
        )
        val v = vao ?: return
        v.bind()
        vbo?.upload(vertices)
        val vboObj = vbo ?: return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboObj.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        v.unbind()
    }

    companion object {
        private const val SYMBOL_VERTEX_COUNT = 5
    }
}
