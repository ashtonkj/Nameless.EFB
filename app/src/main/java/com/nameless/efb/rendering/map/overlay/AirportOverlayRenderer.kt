package com.nameless.efb.rendering.map.overlay

import android.opengl.GLES30
import com.nameless.efb.data.db.EfbDatabase
import com.nameless.efb.data.db.entity.AirportEntity
import com.nameless.efb.domain.nav.LatLon
import com.nameless.efb.domain.nav.SpatialQuery
import com.nameless.efb.domain.nav.WebMercator
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Draws airport symbols as point sprites on the moving map (MM-04).
 *
 * Symbol colours by airport type:
 *   - large_airport   → blue  (towered, IFR-capable)
 *   - medium_airport  → green (non-towered)
 *   - small_airport   → green
 *   - heliport        → yellow
 *   - military        → grey
 *   - seaplane/other  → cyan
 *
 * When a METAR is available, the colour is overridden by flight category.
 *
 * Must be initialised on the GL thread via [onGlReady].
 */
class AirportOverlayRenderer(private val navDb: EfbDatabase) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private var airports: List<AirportEntity> = emptyList()
    private var vao: GlVao? = null
    private var vbo: GlBuffer? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onGlReady() {
        vao = GlVao()
        vbo = GlBuffer(GLES30.GL_ARRAY_BUFFER)
    }

    fun release() {
        vao?.release(); vao = null
        vbo?.release(); vbo = null
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Schedules a DB load for airports near [center] within [radiusNm]. */
    fun loadForViewport(center: LatLon, radiusNm: Double = 100.0) {
        scope.launch {
            airports = navDb.airportDao().nearbyRaw(SpatialQuery.nearbyAirports(center, radiusNm))
        }
    }

    /**
     * Draws all loaded airports.
     *
     * [mvpUniformLoc] and [colorUniformLoc] are the currently-bound flat
     * shader's `u_mvp` and `u_color` uniform locations.
     * [viewMatrix] is the 16-element column-major map view matrix.
     */
    fun draw(
        mvpUniformLoc: Int,
        colorUniformLoc: Int,
        viewMatrix: FloatArray,
    ) {
        val vaoObj = vao ?: return
        val list   = airports
        if (list.isEmpty()) return

        // Build vertex array: one point per airport (x, y in world metres)
        val verts = FloatArray(list.size * 2)
        list.forEachIndexed { i, ap ->
            val m = WebMercator.toMeters(ap.latitude, ap.longitude)
            verts[i * 2]     = m[0].toFloat()
            verts[i * 2 + 1] = m[1].toFloat()
        }

        // Draw all airports with a fixed identity model (positions in world space)
        val mvp = FloatArray(16)
        android.opengl.Matrix.setIdentityM(mvp, 0)
        android.opengl.Matrix.multiplyMM(mvp, 0, viewMatrix, 0, mvp.copyOf(), 0)
        GLES30.glUniformMatrix4fv(mvpUniformLoc, 1, false, mvp, 0)

        vaoObj.bind()
        vbo?.uploadDynamic(verts)
        val vboObj = vbo ?: return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboObj.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)

        // Draw each airport with its type colour
        list.forEachIndexed { i, ap ->
            val (r, g, b) = typeColor(ap.airportType, ap.isMilitary)
            GLES30.glUniform4f(colorUniformLoc, r, g, b, 1f)
            GLES30.glDrawArrays(GLES30.GL_POINTS, i, 1)
        }

        vaoObj.unbind()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun typeColor(type: String, isMilitary: Boolean): Triple<Float, Float, Float> = when {
        isMilitary           -> Triple(0.5f, 0.5f, 0.5f)   // grey
        type == "large_airport"  -> Triple(0f, 0.5f, 1f)   // blue
        type == "heliport"       -> Triple(1f, 0.8f, 0f)   // yellow
        type == "seaplane_base"  -> Triple(0f, 0.8f, 0.8f) // cyan
        else                     -> Triple(0f, 0.67f, 0f)  // green (small/medium)
    }
}
