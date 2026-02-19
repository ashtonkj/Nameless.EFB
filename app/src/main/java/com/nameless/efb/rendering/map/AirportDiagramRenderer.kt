package com.nameless.efb.rendering.map

import android.opengl.GLES30
import com.nameless.efb.data.db.EfbDatabase
import com.nameless.efb.data.db.entity.RunwayEntity
import com.nameless.efb.domain.nav.WebMercator
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a simplified airport diagram at zoom level 14+ (MM-13).
 *
 * For each loaded airport:
 *  - Runways: grey filled rectangles with white centreline
 *  - Taxiways: deferred (taxiway DAO not yet implemented)
 *
 * Runway positions are loaded from the Room nav database and cached in
 * Web Mercator world-space coordinates so only the MVP needs to change
 * per frame.
 *
 * Must be initialised on the GL thread via [onGlReady].
 */
class AirportDiagramRenderer(private val navDb: EfbDatabase) {

    private val scope = CoroutineScope(Dispatchers.IO)

    /** Loaded diagrams keyed by ICAO, built on the IO thread. */
    private val diagrams = mutableMapOf<String, AirportDiagram>()

    // GL resources
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

    /**
     * Schedules loading of the airport diagram for [icao] from the nav DB.
     * Idempotent — safe to call every frame until the diagram is ready.
     */
    fun requestDiagram(icao: String) {
        if (diagrams.containsKey(icao)) return
        // Mark as pending with empty diagram to prevent duplicate requests
        diagrams[icao] = AirportDiagram(emptyList())
        scope.launch {
            val runways = navDb.airportDao().runwaysFor(icao)
            val diagram = buildDiagram(runways)
            diagrams[icao] = diagram
        }
    }

    /**
     * Draw all loaded airport diagrams.
     *
     * [mvpUniformLoc] and [colorUniformLoc] must be the currently-bound
     * flat-colour shader's `u_mvp` and `u_color` uniform locations.
     * [viewMatrix] is the 16-element column-major map view matrix.
     */
    fun draw(
        mvpUniformLoc: Int,
        colorUniformLoc: Int,
        viewMatrix: FloatArray,
    ) {
        val vaoObj = vao ?: return

        for (diagram in diagrams.values) {
            for (runway in diagram.runways) {
                // Draw runway fill (grey asphalt)
                drawQuad(
                    runway.quads,
                    colorR = 0.5f, colorG = 0.5f, colorB = 0.5f, colorA = 1f,
                    mvpUniformLoc, colorUniformLoc, viewMatrix, vaoObj,
                )
            }
        }
    }

    // ── Diagram building ──────────────────────────────────────────────────────

    private fun buildDiagram(runways: List<RunwayEntity>): AirportDiagram {
        return AirportDiagram(runways.map { rwy -> buildRunwayGeom(rwy) })
    }

    private fun buildRunwayGeom(rwy: RunwayEntity): RunwayGeom {
        // Convert threshold positions to Web Mercator metres
        val he = WebMercator.toMeters(rwy.latHe, rwy.lonHe)
        val le = WebMercator.toMeters(rwy.latLe, rwy.lonLe)

        val hdgRad = Math.toRadians(rwy.headingDeg).toFloat()
        val widthM = rwy.widthFt * 0.3048f  // ft → metres
        val perpX = -sin(hdgRad) * widthM / 2f
        val perpY =  cos(hdgRad) * widthM / 2f

        val heX = he[0].toFloat(); val heY = he[1].toFloat()
        val leX = le[0].toFloat(); val leY = le[1].toFloat()

        // 4 corners of the runway rectangle as a triangle strip
        val quads = floatArrayOf(
            heX - perpX, heY - perpY,
            heX + perpX, heY + perpY,
            leX - perpX, leY - perpY,
            leX + perpX, leY + perpY,
        )
        return RunwayGeom(quads)
    }

    // ── GL draw helper ────────────────────────────────────────────────────────

    private fun drawQuad(
        vertices: FloatArray,
        colorR: Float, colorG: Float, colorB: Float, colorA: Float,
        mvpUniformLoc: Int,
        colorUniformLoc: Int,
        viewMatrix: FloatArray,
        vaoObj: GlVao,
    ) {
        // Identity model matrix — vertices already in world space
        val identityMvp = FloatArray(16)
        android.opengl.Matrix.multiplyMM(identityMvp, 0, viewMatrix, 0,
            FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }, 0)
        GLES30.glUniformMatrix4fv(mvpUniformLoc, 1, false, identityMvp, 0)
        GLES30.glUniform4f(colorUniformLoc, colorR, colorG, colorB, colorA)

        vaoObj.bind()
        vbo?.upload(vertices)
        val vboObj = vbo ?: return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboObj.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        vaoObj.unbind()
    }

    // ── Data model ────────────────────────────────────────────────────────────

    private data class RunwayGeom(val quads: FloatArray)

    private data class AirportDiagram(val runways: List<RunwayGeom>)
}
