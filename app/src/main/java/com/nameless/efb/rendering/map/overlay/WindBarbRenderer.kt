package com.nameless.efb.rendering.map.overlay

import android.opengl.GLES30
import com.nameless.efb.data.db.entity.MetarCacheEntity
import com.nameless.efb.domain.nav.WebMercator
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders wind barb symbols at METAR station positions (MM-09).
 *
 * Wind barb conventions (standard WMO):
 *  - Shaft: points in the wind direction (FROM direction)
 *  - Full barb (long tick): 10 kt
 *  - Half barb (short tick): 5 kt
 *  - Pennant (filled triangle): 50 kt
 *
 * All geometry is generated on the CPU per frame (barb count is small, ≤20).
 *
 * Must be initialised on the GL thread via [onGlReady].
 */
class WindBarbRenderer {

    private var vao: GlVao? = null
    private var vbo: GlBuffer? = null

    /** Airport lat/lon for each METAR station — populated by the caller. */
    private val stationPositions = mutableMapOf<String, Pair<Double, Double>>()

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

    /** Supply station lat/lon (keyed by ICAO) before calling [draw]. */
    fun setStationPosition(icao: String, lat: Double, lon: Double) {
        stationPositions[icao] = Pair(lat, lon)
    }

    fun draw(
        stations: List<MetarCacheEntity>,
        mvpUniformLoc: Int,
        colorUniformLoc: Int,
        viewMatrix: FloatArray,
        timeSec: Float = 0f,
    ) {
        val vaoObj = vao ?: return
        if (stations.isEmpty()) return

        // Identity MVP (vertices in world space)
        val mvp = FloatArray(16).also {
            android.opengl.Matrix.setIdentityM(it, 0)
            android.opengl.Matrix.multiplyMM(it, 0, viewMatrix, 0, it.copyOf(), 0)
        }
        GLES30.glUniformMatrix4fv(mvpUniformLoc, 1, false, mvp, 0)
        GLES30.glUniform4f(colorUniformLoc, 0.2f, 0.6f, 1f, 1f)  // wind barb blue

        for (station in stations) {
            val pos = stationPositions[station.icao] ?: continue
            val world = WebMercator.toMeters(pos.first, pos.second)
            // Gentle animation: ±2° sinusoidal sway
            val animDeg = station.windDirDeg + 2f * sin(timeSec.toDouble()).toFloat()
            drawBarb(
                cx = world[0].toFloat(),
                cy = world[1].toFloat(),
                dirDeg = animDeg,
                speedKt = station.windSpeedKt.toFloat(),
                vaoObj = vaoObj,
            )
        }
    }

    // ── Barb geometry ─────────────────────────────────────────────────────────

    private fun drawBarb(
        cx: Float, cy: Float,
        dirDeg: Float, speedKt: Float,
        vaoObj: GlVao,
    ) {
        val shaftLen  = 8f * 1852f          // 8 nm shaft in metres
        val barbStep  = 1.5f * 1852f        // 1.5 nm between barbs
        val barbLen   = 4f * 1852f          // full barb length
        val halfBarb  = barbLen / 2f

        val dirRad = Math.toRadians(dirDeg.toDouble()).toFloat()
        val dx = sin(dirRad)               // shaft direction (wind FROM)
        val dy = cos(dirRad)

        // Perpendicular direction (barbs lean to the left of the shaft)
        val px = -cos(dirRad)
        val py =  sin(dirRad)

        val vertices = mutableListOf<Float>()

        // Shaft line: from station to tip
        vertices += cx; vertices += cy
        vertices += cx + dx * shaftLen; vertices += cy + dy * shaftLen

        // Barbs
        var remaining = speedKt
        var offset    = shaftLen

        // Pennants (50 kt triangles)
        while (remaining >= 50f) {
            val bx = cx + dx * offset
            val by = cy + dy * offset
            vertices += bx;                vertices += by
            vertices += bx + px * barbLen; vertices += by + py * barbLen
            vertices += bx - dx * barbStep; vertices += by - dy * barbStep
            offset    -= barbStep
            remaining -= 50f
        }
        // Full barbs (10 kt)
        while (remaining >= 10f) {
            val bx = cx + dx * offset; val by = cy + dy * offset
            vertices += bx; vertices += by
            vertices += bx + px * barbLen; vertices += by + py * barbLen
            offset    -= barbStep
            remaining -= 10f
        }
        // Half barb (5 kt)
        if (remaining >= 5f) {
            val bx = cx + dx * offset; val by = cy + dy * offset
            vertices += bx; vertices += by
            vertices += bx + px * halfBarb; vertices += by + py * halfBarb
        }

        val arr = vertices.toFloatArray()
        vaoObj.bind()
        vbo?.uploadDynamic(arr)
        val vboObj = vbo ?: return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboObj.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, arr.size / 2)
        vaoObj.unbind()
    }

    private operator fun MutableList<Float>.plusAssign(v: Float) { add(v) }
}
