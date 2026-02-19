package com.nameless.efb.rendering.map.overlay

import android.opengl.GLES30
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a compact HSI compass rose in a 120×120 px sub-viewport in the
 * bottom-left corner of the map (IP-02).
 *
 * Contents:
 *  - Rotating compass card (with cardinal labels via FontAtlas — deferred)
 *  - CDI needle (NAV1 hdef_dot)
 *  - Bearing pointer 1 (NAV1/GPS bearing)
 *
 * The sub-viewport shares the same EGL context as the main map renderer so
 * no separate EGL setup is needed.
 *
 * Must be initialised on the GL thread via [onGlReady].
 */
class MiniHsiRenderer {

    private var cardVao: GlVao? = null
    private var cardVbo: GlBuffer? = null
    private var needleVao: GlVao? = null
    private var needleVbo: GlBuffer? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onGlReady() {
        cardVao   = GlVao()
        cardVbo   = GlBuffer()
        needleVao = GlVao()
        needleVbo = GlBuffer()
        buildCompassCard()
    }

    fun release() {
        cardVao?.release();   cardVao   = null
        cardVbo?.release();   cardVbo   = null
        needleVao?.release(); needleVao = null
        needleVbo?.release(); needleVbo = null
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    /**
     * Draw the mini HSI in [viewport] using [snapshot] data.
     *
     * [mvpUniformLoc] and [colorUniformLoc] must be set by the caller on the
     * currently bound flat-colour shader.
     */
    /**
     * [viewportXYWH] is an IntArray of [x, y, width, height] in pixels.
     */
    fun draw(
        snapshot: SimSnapshot?,
        viewportXYWH: IntArray,
        mvpUniformLoc: Int,
        colorUniformLoc: Int,
    ) {
        GLES30.glViewport(viewportXYWH[0], viewportXYWH[1], viewportXYWH[2], viewportXYWH[3])

        val hdg = snapshot?.magHeadingDeg ?: 0f
        val hdef = snapshot?.nav1HdefDot ?: 0f

        // Build an orthographic MVP that maps [-1,1] to the viewport
        val proj = FloatArray(16)
        android.opengl.Matrix.orthoM(proj, 0, -1f, 1f, -1f, 1f, -1f, 1f)

        // Rotate compass card opposite to heading (card rotates; aircraft arrow stays fixed)
        val cardModel = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        android.opengl.Matrix.rotateM(cardModel, 0, -hdg, 0f, 0f, 1f)

        val cardMvp = FloatArray(16)
        android.opengl.Matrix.multiplyMM(cardMvp, 0, proj, 0, cardModel, 0)

        // Draw rotating compass ring
        GLES30.glUniform4f(colorUniformLoc, 0.8f, 0.8f, 0.8f, 1f)
        GLES30.glUniformMatrix4fv(mvpUniformLoc, 1, false, cardMvp, 0)
        cardVao?.bind()
        GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 0, CARD_SEGMENTS)
        cardVao?.unbind()

        // Draw fixed aircraft arrow (no rotation)
        val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        val identMvp = FloatArray(16)
        android.opengl.Matrix.multiplyMM(identMvp, 0, proj, 0, identity, 0)
        GLES30.glUniform4f(colorUniformLoc, 1f, 1f, 0f, 1f)  // yellow
        GLES30.glUniformMatrix4fv(mvpUniformLoc, 1, false, identMvp, 0)
        val arrowVerts = floatArrayOf(
            0f, 0.15f,  -0.07f, -0.1f,  0.07f, -0.1f,  0f, 0.15f
        )
        needleVao?.bind()
        needleVbo?.uploadDynamic(arrowVerts)
        val nVbo = needleVbo ?: return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, nVbo.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, 4)
        needleVao?.unbind()
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    private fun buildCompassCard() {
        val verts = FloatArray(CARD_SEGMENTS * 2)
        val step = (2.0 * PI / CARD_SEGMENTS).toFloat()
        for (i in 0 until CARD_SEGMENTS) {
            val r = 0.9f
            verts[i * 2]     = r * sin(i * step)
            verts[i * 2 + 1] = r * cos(i * step)
        }
        cardVao?.bind()
        cardVbo?.upload(verts)
        val cVbo = cardVbo ?: return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cVbo.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        cardVao?.unbind()
    }

    companion object {
        private const val CARD_SEGMENTS = 72
    }
}
