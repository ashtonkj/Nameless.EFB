package com.nameless.efb.rendering.gl

import android.opengl.GLES30
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ── GlBuffer ──────────────────────────────────────────────────────────────────

/**
 * Thin wrapper around a single OpenGL buffer object.
 *
 * Must be created and used on the GL thread.
 */
class GlBuffer(private val target: Int = GLES30.GL_ARRAY_BUFFER) {

    val id: Int = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]

    /** Upload [data] with [GL_STATIC_DRAW] usage hint. */
    fun upload(data: FloatArray, usage: Int = GLES30.GL_STATIC_DRAW) {
        GLES30.glBindBuffer(target, id)
        GLES30.glBufferData(target, data.size * 4, FloatBuffer.wrap(data), usage)
    }

    /** Upload [data] with [GL_DYNAMIC_DRAW] usage hint (updated frequently). */
    fun uploadDynamic(data: FloatArray) = upload(data, GLES30.GL_DYNAMIC_DRAW)

    fun release() {
        GLES30.glDeleteBuffers(1, intArrayOf(id), 0)
    }
}

// ── GlVao ─────────────────────────────────────────────────────────────────────

/**
 * Thin wrapper around a Vertex Array Object.
 *
 * Must be created and used on the GL thread (ES 3.0+).
 */
class GlVao {

    val id: Int = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]

    fun bind() = GLES30.glBindVertexArray(id)
    fun unbind() = GLES30.glBindVertexArray(0)

    fun release() {
        GLES30.glDeleteVertexArrays(1, intArrayOf(id), 0)
    }
}

// ── Geometry builders ─────────────────────────────────────────────────────────

/**
 * Returns `(x, y)` vertex pairs for a filled unit circle as a triangle fan.
 *
 * Layout: `[centerX, centerY, p0x, p0y, p1x, p1y, …, p0x, p0y]`
 * (the last perimeter vertex equals the first, closing the circle).
 *
 * Draw with `GL_TRIANGLE_FAN`, vertex count = `segments + 2`.
 * Total floats = `(segments + 2) * 2`.
 */
fun buildCircleStrip(segments: Int = 64): FloatArray {
    val out = FloatArray((segments + 2) * 2)
    var i = 0
    out[i++] = 0f; out[i++] = 0f     // centre
    val step = (2.0 * PI / segments).toFloat()
    for (s in 0..segments) {
        val a = s * step
        out[i++] = cos(a)
        out[i++] = sin(a)
    }
    return out
}

/**
 * Returns `(x, y)` vertex pairs for an arc band as a triangle strip.
 *
 * Vertices alternate: inner, outer, inner, outer … along the arc.
 *
 * Draw with `GL_TRIANGLE_STRIP`, vertex count = `(segments + 1) * 2`.
 * Total floats = `(segments + 1) * 4`.
 *
 * @param innerRadius inner edge of the band (0 = filled pie slice)
 * @param outerRadius outer edge of the band
 * @param startDeg    start angle in degrees (0 = +X axis, CCW positive)
 * @param endDeg      end angle in degrees
 * @param segments    number of subdivisions along the arc
 */
fun buildArcStrip(
    innerRadius: Float,
    outerRadius: Float,
    startDeg: Float,
    endDeg: Float,
    segments: Int = 32,
): FloatArray {
    val out = FloatArray((segments + 1) * 4)
    val startRad = Math.toRadians(startDeg.toDouble()).toFloat()
    val endRad   = Math.toRadians(endDeg.toDouble()).toFloat()
    val step = (endRad - startRad) / segments
    var idx = 0
    for (s in 0..segments) {
        val a = startRad + s * step
        val c = cos(a)
        val ss = sin(a)
        out[idx++] = c * innerRadius   // inner x
        out[idx++] = ss * innerRadius  // inner y
        out[idx++] = c * outerRadius   // outer x
        out[idx++] = ss * outerRadius  // outer y
    }
    return out
}

/**
 * Returns a unit quad as a triangle strip with UV coordinates.
 *
 * Layout per vertex: `x, y, u, v` — centred on the origin (±0.5 extent).
 *
 * Draw with `GL_TRIANGLE_STRIP`, 4 vertices.
 */
fun buildQuad(): FloatArray = floatArrayOf(
    -0.5f, -0.5f,  0f, 0f,
     0.5f, -0.5f,  1f, 0f,
    -0.5f,  0.5f,  0f, 1f,
     0.5f,  0.5f,  1f, 1f,
)
