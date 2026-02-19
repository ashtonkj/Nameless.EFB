package com.nameless.efb.rendering.map.overlay

import android.opengl.GLES30
import com.nameless.efb.domain.nav.BoundingBox
import com.nameless.efb.domain.nav.LatLon
import com.nameless.efb.domain.nav.WebMercator
import com.nameless.efb.domain.nav.latLonToTile
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import com.nameless.efb.rendering.gl.ShaderManager
import com.nameless.efb.rendering.gl.buildQuad
import com.nameless.efb.rendering.terrain.TerrainTileCache
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders the TAWS terrain awareness overlay (MM-07).
 *
 * For each visible 512×512 terrain tile, uploads elevation data as a
 * GL_LUMINANCE float16 texture and renders it with `terrain_taws.frag`.
 * The GLSL shader computes the AGL clearance per pixel and colours it
 * red/yellow/transparent — no LUT texture required.
 *
 * Terrain colours update within 2 frames of an aircraft altitude change
 * because only the `u_aircraft_elev_m` uniform changes, not the texture.
 *
 * Must be initialised on the GL thread via [onGlReady].
 */
class TawsRenderer(private val terrainCache: TerrainTileCache) {

    private var program         = 0
    private var mvpLoc          = 0
    private var elevTexLoc      = 0
    private var aircraftElevLoc = 0
    private var cautionLoc      = 0
    private var warningLoc      = 0

    private var quadVao: GlVao? = null
    private var quadVbo: GlBuffer? = null

    // ── TAWS alert thresholds ─────────────────────────────────────────────────

    var cautionAglM = 300f   // yellow below this (default)
    var warningAglM = 150f   // red below this (default)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onGlReady(shaderManager: ShaderManager) {
        program = shaderManager.getProgram(
            "shaders/map/tile.vert",       // reuse tile vertex shader (a_position, a_texcoord)
            "shaders/map/terrain_taws.frag"
        )
        mvpLoc          = GLES30.glGetUniformLocation(program, "u_mvp")
        elevTexLoc      = GLES30.glGetUniformLocation(program, "u_elevation_tex")
        aircraftElevLoc = GLES30.glGetUniformLocation(program, "u_aircraft_elev_m")
        cautionLoc      = GLES30.glGetUniformLocation(program, "u_agl_caution_m")
        warningLoc      = GLES30.glGetUniformLocation(program, "u_agl_warning_m")

        quadVao = GlVao()
        quadVbo = GlBuffer()
        val vaoObj = quadVao!!
        vaoObj.bind()
        quadVbo!!.upload(buildQuad())
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo!!.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        vaoObj.unbind()
    }

    fun release() {
        quadVao?.release(); quadVao = null
        quadVbo?.release(); quadVbo = null
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    /**
     * Renders the TAWS overlay for the current [viewportBbox] and
     * [aircraftElevM] (aircraft altitude in metres MSL).
     *
     * [viewMatrix] is the 16-element map view matrix.
     */
    fun draw(
        aircraftElevM: Float,
        viewportBbox: BoundingBox,
        viewMatrix: FloatArray,
        zoomLevel: Int = 10,
    ) {
        val vaoObj = quadVao ?: return

        GLES30.glUseProgram(program)
        GLES30.glUniform1f(aircraftElevLoc, aircraftElevM)
        GLES30.glUniform1f(cautionLoc, cautionAglM)
        GLES30.glUniform1f(warningLoc, warningAglM)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(elevTexLoc, 0)

        // Iterate over degree tiles in the viewport bounding box
        val latMin = viewportBbox.latMin.toInt()
        val latMax = viewportBbox.latMax.toInt()
        val lonMin = viewportBbox.lonMin.toInt()
        val lonMax = viewportBbox.lonMax.toInt()

        for (lat in latMin..latMax) {
            for (lon in lonMin..lonMax) {
                val elevGrid = terrainCache.getTileGrid(lat, lon) ?: continue
                val texId    = uploadTerrainTexture(elevGrid)
                drawTile(lat.toDouble(), lon.toDouble(), texId, viewMatrix, vaoObj)
                GLES30.glDeleteTextures(1, intArrayOf(texId), 0)
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun uploadTerrainTexture(grid: ShortArray): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val texId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val buf = ByteBuffer.allocateDirect(grid.size * 2).order(ByteOrder.nativeOrder())
        for (s in grid) buf.putShort(s)
        buf.rewind()

        // GL_R16F (float16 red channel) — supported in ES 3.0
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F,
            512, 512, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, buf
        )
        return texId
    }

    private fun drawTile(
        lat: Double, lon: Double,
        texId: Int,
        viewMatrix: FloatArray,
        vaoObj: GlVao,
    ) {
        // 1° × 1° tile in Web Mercator metres
        val sw = WebMercator.toMeters(lat,       lon)
        val ne = WebMercator.toMeters(lat + 1.0, lon + 1.0)
        val cx = ((sw[0] + ne[0]) / 2).toFloat()
        val cy = ((sw[1] + ne[1]) / 2).toFloat()
        val wx = (ne[0] - sw[0]).toFloat()
        val wy = (ne[1] - sw[1]).toFloat()

        val model = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        android.opengl.Matrix.translateM(model, 0, cx, cy, 0f)
        android.opengl.Matrix.scaleM(model, 0, wx, wy, 1f)

        val mvp = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvp, 0, viewMatrix, 0, model, 0)
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        vaoObj.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        vaoObj.unbind()
    }
}
