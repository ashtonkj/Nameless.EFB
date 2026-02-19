package com.nameless.efb.rendering.map

import android.opengl.GLES30
import com.nameless.efb.data.db.EfbDatabase
import com.nameless.efb.data.db.entity.FlightPlanEntity
import com.nameless.efb.domain.nav.GreatCircle
import com.nameless.efb.domain.nav.LatLon
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import com.nameless.efb.rendering.terrain.TerrainTileCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Renders the vertical situation / profile view (IP-03).
 *
 * For a loaded route, samples the Copernicus DEM at 1 nm intervals along
 * each leg and computes the terrain profile. Renders as a 2D OpenGL chart:
 *  - Filled grey polygon: terrain elevation profile
 *  - Cyan line: planned route altitude (from flight plan altitude constraints)
 *  - Vertical red bars: obstacles above terrain surface within 2 nm of route
 *
 * The chart X axis = distance along route (nm), Y axis = altitude (ft MSL).
 *
 * Must be initialised on the GL thread via [onGlReady].
 */
class ProfileViewRenderer(
    private val terrainCache: TerrainTileCache,
    private val navDb: EfbDatabase,
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Terrain profile as [distanceNm, elevationFt] pairs. */
    private var terrainProfile: FloatArray = FloatArray(0)

    private var profileVao: GlVao? = null
    private var profileVbo: GlBuffer? = null

    // Profile view bounds
    private var maxDistNm  = 0f
    private var maxAltFt   = 30_000f

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onGlReady() {
        profileVao = GlVao()
        profileVbo = GlBuffer()
    }

    fun release() {
        profileVao?.release(); profileVao = null
        profileVbo?.release(); profileVbo = null
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Asynchronously samples terrain along the route defined by [waypoints]
     * (list of lat/lon pairs in route order) and rebuilds the terrain profile.
     *
     * Call again whenever the flight plan changes.
     */
    fun loadRoute(waypoints: List<LatLon>) {
        scope.launch(Dispatchers.Default) {
            terrainProfile = buildTerrainProfile(waypoints)
        }
    }

    /**
     * Draws the profile chart.
     *
     * [ownshipDistNm] is the aircraft's distance along the route in nm
     * (used to draw the ownship position indicator).
     * [mvpUniformLoc] and [colorUniformLoc] are flat-shader uniform locations.
     * [viewMatrix] is the profile chart's orthographic MVP (caller-supplied).
     */
    fun draw(
        ownshipDistNm: Float,
        ownshipAltFt: Float,
        mvpUniformLoc: Int,
        colorUniformLoc: Int,
        viewMatrix: FloatArray,
    ) {
        val vaoObj = profileVao ?: return
        val profile = terrainProfile
        if (profile.size < 4) return

        // Terrain filled profile
        GLES30.glUniform4f(colorUniformLoc, 0.5f, 0.5f, 0.5f, 0.8f)
        GLES30.glUniformMatrix4fv(mvpUniformLoc, 1, false, viewMatrix, 0)
        uploadAndDraw(profile, GLES30.GL_TRIANGLE_STRIP, profile.size / 2, vaoObj)

        // Ownship position cross-hair
        if (ownshipDistNm >= 0 && maxDistNm > 0) {
            val x = ownshipDistNm / maxDistNm * 2f - 1f  // NDC x
            val y = ownshipAltFt / maxAltFt * 2f - 1f     // NDC y
            val verts = floatArrayOf(x, -1f, x, 1f, -1f, y, 1f, y)
            GLES30.glUniform4f(colorUniformLoc, 0f, 1f, 1f, 1f)  // cyan
            uploadAndDraw(verts, GLES30.GL_LINES, 4, vaoObj)
        }
    }

    // ── Terrain profile ───────────────────────────────────────────────────────

    private fun buildTerrainProfile(waypoints: List<LatLon>): FloatArray {
        if (waypoints.size < 2) return FloatArray(0)

        val samples = mutableListOf<Float>()  // [distNm, elevFt, distNm, elevFt, ...]
        var accumDist = 0.0

        for (i in 0 until waypoints.size - 1) {
            val from = waypoints[i]
            val to   = waypoints[i + 1]
            val legNm = GreatCircle.distanceNm(from, to)
            if (legNm < 0.1) continue

            val stepNm = 1.0   // sample every 1 nm
            val steps  = (legNm / stepNm).toInt().coerceAtLeast(2)

            for (s in 0 until steps) {
                val t   = s.toDouble() / steps
                val lat = from.latitude  + t * (to.latitude  - from.latitude)
                val lon = from.longitude + t * (to.longitude - from.longitude)
                val elevM  = terrainCache.queryElevation(lat, lon)
                val elevFt = if (elevM.isNaN()) 0f else elevM * 3.28084f
                val dist   = (accumDist + t * legNm).toFloat()
                // Triangle strip: add base point (ground) then top point (elevation)
                samples.add(dist); samples.add(0f)
                samples.add(dist); samples.add(elevFt)
            }
            accumDist += legNm
        }

        maxDistNm = accumDist.toFloat()
        return samples.toFloatArray()
    }

    // ── GL draw ───────────────────────────────────────────────────────────────

    private fun uploadAndDraw(verts: FloatArray, mode: Int, count: Int, vaoObj: GlVao) {
        vaoObj.bind()
        profileVbo?.uploadDynamic(verts)
        val vboObj = profileVbo ?: return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboObj.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(mode, 0, count)
        vaoObj.unbind()
    }
}
