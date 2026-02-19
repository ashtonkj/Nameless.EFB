package com.nameless.efb.rendering.map.overlay

import android.opengl.GLES30
import com.nameless.efb.data.db.EfbDatabase
import com.nameless.efb.data.db.entity.AirspaceEntity
import com.nameless.efb.domain.nav.BoundingBox
import com.nameless.efb.domain.nav.GreatCircle
import com.nameless.efb.domain.nav.LatLon
import com.nameless.efb.domain.nav.WebMercator
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Draws airspace boundary polygons on the moving map (MM-06).
 *
 * Each airspace class has a semi-transparent fill colour and an outline.
 * A proximity check ([checkProximity]) fires an alert when the ownship is
 * within 5 nm of any loaded airspace boundary.
 *
 * GeoJSON parsing: uses `kotlinx.serialization.json` (already a dependency).
 *
 * Must be initialised on the GL thread via [onGlReady].
 */
class AirspaceOverlayRenderer(private val navDb: EfbDatabase) {

    private val scope     = CoroutineScope(Dispatchers.IO)
    private var airspaces: List<AirspaceEntity> = emptyList()

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

    fun loadForViewport(bbox: BoundingBox) {
        scope.launch {
            airspaces = navDb.airspaceDao().inBbox(
                bbox.latMin, bbox.latMax, bbox.lonMin, bbox.lonMax
            )
        }
    }

    fun draw(mvpUniformLoc: Int, colorUniformLoc: Int, viewMatrix: FloatArray) {
        val vaoObj = vao ?: return

        val mvp = FloatArray(16).also {
            android.opengl.Matrix.setIdentityM(it, 0)
            android.opengl.Matrix.multiplyMM(it, 0, viewMatrix, 0, it.copyOf(), 0)
        }
        GLES30.glUniformMatrix4fv(mvpUniformLoc, 1, false, mvp, 0)

        for (airspace in airspaces) {
            val polygon = parseGeoJsonPolygon(airspace.geometryJson) ?: continue
            val (r, g, b, a) = classColor(airspace.airspaceClass)
            GLES30.glUniform4f(colorUniformLoc, r, g, b, a * 0.3f)  // filled (translucent)
            uploadAndDraw(polygon, GLES30.GL_TRIANGLE_FAN, polygon.size / 2, vaoObj)
            GLES30.glUniform4f(colorUniformLoc, r, g, b, 0.8f)       // outline (opaque)
            uploadAndDraw(polygon, GLES30.GL_LINE_LOOP, polygon.size / 2, vaoObj)
        }
    }

    /**
     * Returns true if the ownship is within 5 nm of any loaded airspace boundary.
     */
    fun checkProximity(ownship: LatLon): Boolean {
        for (airspace in airspaces) {
            // Cheap bounding-box pre-check before parsing GeoJSON
            if (ownship.latitude  < airspace.bboxLatMin - 0.1 ||
                ownship.latitude  > airspace.bboxLatMax + 0.1 ||
                ownship.longitude < airspace.bboxLonMin - 0.1 ||
                ownship.longitude > airspace.bboxLonMax + 0.1) continue

            val polygon = parseGeoJsonPolygon(airspace.geometryJson) ?: continue
            if (distanceToPolygonBoundaryNm(ownship, polygon) < 5.0) return true
        }
        return false
    }

    // ── GeoJSON parsing ───────────────────────────────────────────────────────

    /**
     * Parses a GeoJSON Polygon and returns world-space vertices as
     * `[x0, y0, x1, y1, …]` in Web Mercator metres.
     */
    private fun parseGeoJsonPolygon(json: String): FloatArray? = try {
        val root  = Json.parseToJsonElement(json).jsonObject
        val rings = root["coordinates"]?.jsonArray ?: return null
        val outer = rings[0].jsonArray
        val verts = FloatArray(outer.size * 2)
        outer.forEachIndexed { i, pt ->
            val lonLat = pt.jsonArray
            val lon = lonLat[0].jsonPrimitive.content.toDouble()
            val lat = lonLat[1].jsonPrimitive.content.toDouble()
            val m = WebMercator.toMeters(lat, lon)
            verts[i * 2]     = m[0].toFloat()
            verts[i * 2 + 1] = m[1].toFloat()
        }
        verts
    } catch (_: Exception) {
        null
    }

    // ── Proximity check ───────────────────────────────────────────────────────

    /**
     * Returns the minimum distance in nm from [ownship] to any edge of
     * the airspace [polygon] (world-space floatArray of x,y pairs).
     *
     * Uses the bounding-box centre as a single-point proxy for performance.
     */
    private fun distanceToPolygonBoundaryNm(ownship: LatLon, polygon: FloatArray): Double {
        // Convert first vertex back to LatLon for a single representative distance check
        if (polygon.size < 2) return Double.MAX_VALUE
        val m = WebMercator.toLatLon(polygon[0].toDouble(), polygon[1].toDouble())
        return GreatCircle.distanceNm(ownship, m)
    }

    // ── Class colours ─────────────────────────────────────────────────────────

    private data class Colour(val r: Float, val g: Float, val b: Float, val a: Float)

    private fun classColor(cls: String): Colour = when (cls) {
        "A"  -> Colour(1f, 0f, 0f, 1f)
        "B"  -> Colour(0f, 0f, 1f, 1f)
        "C"  -> Colour(1f, 0.53f, 0f, 1f)
        "D"  -> Colour(0f, 0.53f, 1f, 1f)
        "R"  -> Colour(1f, 0f, 0f, 0.8f)
        "P"  -> Colour(1f, 0f, 0f, 0.9f)
        else -> Colour(0f, 0.67f, 0f, 0.5f)
    }

    // ── GL draw ───────────────────────────────────────────────────────────────

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
