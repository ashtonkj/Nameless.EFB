package com.nameless.efb.rendering.map.overlay

import android.opengl.GLES30
import com.nameless.efb.data.db.EfbDatabase
import com.nameless.efb.data.db.entity.NavaidEntity
import com.nameless.efb.domain.nav.GreatCircle
import com.nameless.efb.domain.nav.LatLon
import com.nameless.efb.domain.nav.SpatialQuery
import com.nameless.efb.domain.nav.WebMercator
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws navaid symbols (VOR, NDB, ILS feather, fix triangles, DME) on the
 * moving map (MM-05).
 *
 * Symbol geometry is built once per data load and rendered as point-and-line
 * batches per navaid type.
 *
 * Must be initialised on the GL thread via [onGlReady].
 */
class NavaidOverlayRenderer(private val navDb: EfbDatabase) {

    private val scope    = CoroutineScope(Dispatchers.IO)
    private var navaids: List<NavaidEntity> = emptyList()

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

    // ── Public API ────────────────────────────────────────────────────────────

    fun loadForViewport(center: LatLon, radiusNm: Double = 80.0) {
        scope.launch {
            navaids = navDb.navaidDao().nearbyRaw(SpatialQuery.nearbyNavaids(center, radiusNm))
        }
    }

    fun draw(mvpUniformLoc: Int, colorUniformLoc: Int, viewMatrix: FloatArray) {
        val vaoObj = vao ?: return
        val list   = navaids
        if (list.isEmpty()) return

        // Identity MVP (positions already in world metres)
        val mvp = FloatArray(16).also {
            android.opengl.Matrix.setIdentityM(it, 0)
            android.opengl.Matrix.multiplyMM(it, 0, viewMatrix, 0, it.copyOf(), 0)
        }
        GLES30.glUniformMatrix4fv(mvpUniformLoc, 1, false, mvp, 0)

        for (navaid in list) {
            val pos = WebMercator.toMeters(navaid.latitude, navaid.longitude)
            val px = pos[0].toFloat()
            val py = pos[1].toFloat()

            when (navaid.type) {
                "VOR", "VOR-DME", "RNAV" -> drawVor(px, py, colorUniformLoc, vaoObj)
                "NDB"                     -> drawNdb(px, py, colorUniformLoc, vaoObj)
                "ILS"                     -> drawIlsFeather(navaid, colorUniformLoc, vaoObj)
                "FIX"                     -> drawFix(px, py, colorUniformLoc, vaoObj)
                "DME"                     -> drawDme(px, py, colorUniformLoc, vaoObj)
            }
        }
    }

    // ── Symbol drawers ────────────────────────────────────────────────────────

    /** VOR: compass-rose hexagon (6 vertices). */
    private fun drawVor(px: Float, py: Float, colorLoc: Int, vaoObj: GlVao) {
        val r = 5000f   // 5 km symbol radius in metres
        val verts = FloatArray(12)
        for (i in 0 until 6) {
            val a = i * PI.toFloat() / 3f
            verts[i * 2]     = px + r * cos(a)
            verts[i * 2 + 1] = py + r * sin(a)
        }
        GLES30.glUniform4f(colorLoc, 0.5f, 0f, 1f, 1f)  // violet
        uploadAndDraw(verts, GLES30.GL_LINE_LOOP, 6, vaoObj)
    }

    /** NDB: filled circle (triangle fan). */
    private fun drawNdb(px: Float, py: Float, colorLoc: Int, vaoObj: GlVao) {
        val r = 4000f
        val segs = 12
        val verts = FloatArray((segs + 2) * 2)
        verts[0] = px; verts[1] = py
        for (i in 0..segs) {
            val a = i * 2f * PI.toFloat() / segs
            verts[(i + 1) * 2]     = px + r * cos(a)
            verts[(i + 1) * 2 + 1] = py + r * sin(a)
        }
        GLES30.glUniform4f(colorLoc, 0.8f, 0.3f, 0f, 1f)  // amber
        uploadAndDraw(verts, GLES30.GL_TRIANGLE_FAN, segs + 2, vaoObj)
    }

    /** Fix/intersection: open equilateral triangle. */
    private fun drawFix(px: Float, py: Float, colorLoc: Int, vaoObj: GlVao) {
        val r = 3500f
        val verts = floatArrayOf(
            px,          py + r,
            px - r * 0.866f, py - r * 0.5f,
            px + r * 0.866f, py - r * 0.5f,
        )
        GLES30.glUniform4f(colorLoc, 0.1f, 0.8f, 0.1f, 1f)  // cyan-green
        uploadAndDraw(verts, GLES30.GL_LINE_LOOP, 3, vaoObj)
    }

    /** DME: small square. */
    private fun drawDme(px: Float, py: Float, colorLoc: Int, vaoObj: GlVao) {
        val h = 3000f
        val verts = floatArrayOf(
            px - h, py - h,
            px + h, py - h,
            px + h, py + h,
            px - h, py + h,
        )
        GLES30.glUniform4f(colorLoc, 0.6f, 0.6f, 0.9f, 1f)  // light blue
        uploadAndDraw(verts, GLES30.GL_LINE_LOOP, 4, vaoObj)
    }

    /**
     * ILS feather: two converging lines extending 8 nm in the inbound course direction
     * from the runway threshold.
     */
    private fun drawIlsFeather(navaid: NavaidEntity, colorLoc: Int, vaoObj: GlVao) {
        val startPos = WebMercator.toMeters(navaid.latitude, navaid.longitude)
        val sx = startPos[0].toFloat()
        val sy = startPos[1].toFloat()

        // Inbound course (the bearing from the ILS station toward the aircraft)
        val courseRad = Math.toRadians(navaid.magneticVariation.toDouble()).toFloat()
        val lengthM   = 8f * 1852f   // 8 nm
        val spreadM   = 2000f        // half-width at far end

        val ex = sx + lengthM * sin(courseRad)
        val ey = sy + lengthM * cos(courseRad)
        val perpX = -cos(courseRad) * spreadM
        val perpY =  sin(courseRad) * spreadM

        val verts = floatArrayOf(
            sx, sy, ex - perpX, ey - perpY,
            sx, sy, ex + perpX, ey + perpY,
        )
        GLES30.glUniform4f(colorLoc, 0.9f, 0.9f, 0f, 1f)  // yellow
        uploadAndDraw(verts, GLES30.GL_LINES, 4, vaoObj)
    }

    // ── Utility ───────────────────────────────────────────────────────────────

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
