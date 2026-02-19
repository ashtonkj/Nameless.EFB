package com.nameless.efb.rendering.map

import android.content.res.AssetManager
import android.opengl.GLES30
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.domain.nav.OrientationMode
import com.nameless.efb.domain.nav.TileXYZ
import com.nameless.efb.domain.nav.WebMercator
import com.nameless.efb.domain.nav.latLonToTile
import com.nameless.efb.rendering.gl.BaseRenderer
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import com.nameless.efb.rendering.gl.Theme
import com.nameless.efb.rendering.gl.buildQuad
import com.nameless.efb.rendering.gauge.GlViewport
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * OpenGL ES 3.0 renderer for the moving map display (MM-01, MM-02, MM-03).
 *
 * Tile rendering pipeline:
 *  1. [computeVisibleTiles] determines which tiles intersect the current viewport.
 *  2. [tileCache].requestTile enqueues async MVT rasterisation for missing tiles.
 *  3. [tileCache].drainUploads uploads up to 4 rasterised bitmaps to GL per frame.
 *  4. Each available tile is drawn as a textured quad with the computed MVP.
 *  5. Ownship symbol and range rings are drawn on top.
 *
 * Coordinate system:
 *  - World space: Web Mercator metres (X: −20,037,508 to +20,037,508).
 *  - Tile Y in OSM convention (0 = north pole, increases south).
 *  - World Y = EARTH_HALF_CIRCUM − (tileY + 1) * tileMeters  (increases north).
 *
 * Thread safety: [zoom], [pan], [fling], and [onSnapshot] are called from
 * the UI/gesture thread; all GL calls happen on the render thread via
 * GLSurfaceView's internal serialisation.
 */
class MapRenderer(
    assets: AssetManager,
    private val tileCache: TileCache,
    private val simData: StateFlow<SimSnapshot?>,
    theme: Theme = Theme.DAY,
) : BaseRenderer(assets, theme) {

    // ── Map state (volatile — written from gesture thread, read on GL thread) ─

    @Volatile var centerLat = -26.14          // default: Johannesburg
    @Volatile var centerLon = 28.25
    @Volatile var zoomLevel = 10
    @Volatile var orientationMode = OrientationMode.NORTH_UP

    // Fling state — decelerates in drawFrame
    @Volatile private var flingVx = 0f
    @Volatile private var flingVy = 0f

    // Screen dimensions (set in onSurfaceChanged)
    private var screenWidth  = 1920
    private var screenHeight = 1080

    // ── GL shader state ───────────────────────────────────────────────────────

    private var tileProgram  = 0
    private var flatProgram  = 0

    // tile shader uniforms
    private var tileMvpLoc     = 0
    private var tileTextureLoc = 0

    // flat shader uniforms
    private var flatMvpLoc   = 0
    private var flatColorLoc = 0

    // Tile quad VAO (shared for all tiles, MVP changes per tile)
    private var quadVao: GlVao? = null
    private var quadVbo: GlBuffer? = null

    // Sub-renderers
    private val ownship = OwnshipRenderer()
    private val rings   = RangeRingRenderer()

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val EARTH_HALF_CIRCUM = 20_037_508.34
        private const val TILE_PIXEL_SIZE   = 512
        private const val FLING_DECEL       = 0.95f  // friction per frame
        private const val MIN_ZOOM          = 4
        private const val MAX_ZOOM          = 16
    }

    // ── BaseRenderer template methods ─────────────────────────────────────────

    override fun onGlReady() {
        tileProgram = shaderManager.getProgram(
            "shaders/map/tile.vert", "shaders/map/tile.frag"
        )
        flatProgram = shaderManager.getProgram(
            "shaders/map/route_line.vert", "shaders/map/route_line.frag"
        )

        tileMvpLoc     = GLES30.glGetUniformLocation(tileProgram, "u_mvp")
        tileTextureLoc = GLES30.glGetUniformLocation(tileProgram, "u_texture")
        flatMvpLoc     = GLES30.glGetUniformLocation(flatProgram, "u_mvp")
        flatColorLoc   = GLES30.glGetUniformLocation(flatProgram, "u_color")

        // Build a shared unit quad (±0.5 extent, with UV) for tile rendering
        quadVao = GlVao()
        quadVbo = GlBuffer()
        val vaoObj = quadVao!!
        vaoObj.bind()
        quadVbo!!.upload(buildQuad())
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo!!.id)
        // a_position: offset 0, stride 16
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        // a_texcoord: offset 8, stride 16
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        vaoObj.unbind()

        ownship.onGlReady()
        rings.onGlReady()
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10, width: Int, height: Int) {
        super.onSurfaceChanged(gl, width, height)
        screenWidth  = width
        screenHeight = height
    }

    override fun drawFrame() {
        val snapshot = simData.value
        updateCenter(snapshot)
        applyFling()

        val viewMatrix = buildViewMatrix()
        val metersPerPx = metersPerPixel()

        // ── 1. Draw tiles ──────────────────────────────────────────────────────
        GLES30.glUseProgram(tileProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(tileTextureLoc, 0)

        for (tile in computeVisibleTiles()) {
            tileCache.requestTile(tile)
            val texId = tileCache.getTextureId(tile) ?: continue
            drawTile(tile, texId, viewMatrix)
        }

        // ── 2. Upload newly rasterised tiles (max 4 this frame) ───────────────
        tileCache.drainUploads()

        // ── 3. Draw flat-colour overlays (ownship, rings) ─────────────────────
        GLES30.glUseProgram(flatProgram)

        val ownshipLatLon = snapshot?.let {
            com.nameless.efb.domain.nav.LatLon(it.latitude, it.longitude)
        } ?: com.nameless.efb.domain.nav.LatLon(centerLat, centerLon)

        rings.draw(
            ownship = ownshipLatLon,
            metersPerPixel = metersPerPx,
            mvpUniformLoc  = flatMvpLoc,
            colourUniformLoc = flatColorLoc,
            viewMatrix     = viewMatrix,
        )

        snapshot?.let {
            ownship.onSnapshot(it)
            ownship.draw(flatMvpLoc, flatColorLoc, viewMatrix)
        }
    }

    // ── Gesture API ───────────────────────────────────────────────────────────

    /**
     * Zooms by [factor] centred on screen pixel ([focusX], [focusY]).
     * Thread-safe — called from the gesture thread.
     */
    fun zoom(factor: Float, focusX: Float, focusY: Float) {
        val newZoom = (zoomLevel + if (factor > 1f) 1 else -1).coerceIn(MIN_ZOOM, MAX_ZOOM)
        zoomLevel = newZoom
    }

    /**
     * Pans the map by ([dx], [dy]) screen pixels.
     * Thread-safe — called from the gesture thread.
     */
    fun pan(dx: Float, dy: Float) {
        val mpp = metersPerPixel()
        // Screen X+ = east, Screen Y+ = south (NDC Y flipped)
        val centerPos = WebMercator.toMeters(centerLat, centerLon)
        val newX = centerPos[0] + dx * mpp
        val newY = centerPos[1] - dy * mpp   // screen Y+ = world Y−
        val newLatLon = WebMercator.toLatLon(newX, newY)
        centerLat = newLatLon.latitude
        centerLon = newLatLon.longitude
    }

    /**
     * Starts a fling with the given velocity in screen pixels/sec.
     */
    fun fling(velocityX: Float, velocityY: Float) {
        flingVx = velocityX
        flingVy = velocityY
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun updateCenter(snapshot: SimSnapshot?) {
        // In track-up / heading-up, keep the map centred on the aircraft
        if (orientationMode != OrientationMode.NORTH_UP && snapshot != null) {
            centerLat = snapshot.latitude
            centerLon = snapshot.longitude
        }
    }

    private fun applyFling() {
        if (flingVx == 0f && flingVy == 0f) return
        // dt ≈ 1/60 s; pan by velocity * dt then decelerate
        pan(flingVx / 60f, flingVy / 60f)
        flingVx *= FLING_DECEL
        flingVy *= FLING_DECEL
        if (flingVx * flingVx + flingVy * flingVy < 1f) {
            flingVx = 0f; flingVy = 0f
        }
    }

    /**
     * Returns Web Mercator metres per screen pixel at the current zoom level.
     */
    private fun metersPerPixel(): Float {
        val tileMeters = 2.0 * EARTH_HALF_CIRCUM / (1 shl zoomLevel)
        return (tileMeters / TILE_PIXEL_SIZE).toFloat()
    }

    /**
     * Returns the set of tiles that intersect the current viewport.
     */
    private fun computeVisibleTiles(): List<TileXYZ> {
        val n     = 1 shl zoomLevel
        val center = latLonToTile(centerLat, centerLon, zoomLevel)
        val hTiles = ceil(screenWidth.toDouble()  / TILE_PIXEL_SIZE).toInt() + 2
        val vTiles = ceil(screenHeight.toDouble() / TILE_PIXEL_SIZE).toInt() + 2

        val tiles = mutableListOf<TileXYZ>()
        for (dy in -vTiles / 2..vTiles / 2) {
            for (dx in -hTiles / 2..hTiles / 2) {
                val tx = (center.x + dx).coerceIn(0, n - 1)
                val ty = (center.y + dy).coerceIn(0, n - 1)
                tiles.add(TileXYZ(tx, ty, zoomLevel))
            }
        }
        return tiles
    }

    /**
     * Builds the 4×4 column-major map view-projection matrix.
     *
     * Transforms world-space (Web Mercator metres) to NDC (−1..+1).
     *
     * Steps:
     *  1. Translate by −centerWorld (pan to aircraft position)
     *  2. Scale by ndcPerMeter (zoom)
     *  3. Flip Y (web Mercator Y increases north; NDC Y also increases north — no flip needed)
     *  4. Rotate by −mapRotationDeg for heading-up / track-up (MM-03)
     */
    private fun buildViewMatrix(): FloatArray {
        val mpp = metersPerPixel()
        val ndcPerMeter = 2.0 / (screenWidth * mpp)

        val centerPos = WebMercator.toMeters(centerLat, centerLon)

        val snapshot = simData.value
        val rotDeg = when (orientationMode) {
            OrientationMode.NORTH_UP    -> 0f
            OrientationMode.TRACK_UP   -> -(snapshot?.groundTrackDeg ?: 0f)
            OrientationMode.HEADING_UP -> -(snapshot?.magHeadingDeg  ?: 0f)
        }

        val m = FloatArray(16)
        android.opengl.Matrix.setIdentityM(m, 0)
        android.opengl.Matrix.scaleM(m, 0,
            ndcPerMeter.toFloat(), ndcPerMeter.toFloat(), 1f)
        android.opengl.Matrix.translateM(m, 0,
            -centerPos[0].toFloat(), -centerPos[1].toFloat(), 0f)
        if (rotDeg != 0f) {
            android.opengl.Matrix.rotateM(m, 0, rotDeg, 0f, 0f, 1f)
        }
        return m
    }

    /**
     * Renders the map into a sub-viewport for use as the G1000 PFD inset map (G-07).
     *
     * Temporarily overrides [centerLat]/[centerLon]/[zoomLevel] and the tracked
     * screen dimensions, then restores them.  Must be called on the GL thread
     * with the inset [viewport] already active.
     *
     * @param snapshot  Latest sim state — position used as map centre.
     * @param rangeNm   Desired visible half-range in nm.
     * @param viewport  The GL sub-viewport that was set by the caller.
     */
    fun drawInset(snapshot: SimSnapshot, rangeNm: Float, viewport: GlViewport) {
        val savedLat  = centerLat
        val savedLon  = centerLon
        val savedZoom = zoomLevel
        val savedW    = screenWidth
        val savedH    = screenHeight

        centerLat    = snapshot.latitude
        centerLon    = snapshot.longitude
        zoomLevel    = rangeToZoom(rangeNm)
        screenWidth  = viewport.width
        screenHeight = viewport.height

        drawFrame()

        centerLat    = savedLat
        centerLon    = savedLon
        zoomLevel    = savedZoom
        screenWidth  = savedW
        screenHeight = savedH
    }

    /** Approximates the OSM zoom level that shows roughly [rangeNm] of coverage. */
    private fun rangeToZoom(rangeNm: Float): Int {
        val nm = rangeNm.coerceIn(1f, 20f)
        return (14.0 - log2(nm.toDouble())).roundToInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    private fun drawTile(tile: TileXYZ, texId: Int, viewMatrix: FloatArray) {
        val n = 1 shl tile.z
        val tileMeters = 2.0 * EARTH_HALF_CIRCUM / n

        // Tile world-space centre in Web Mercator metres
        val worldXCenter = (tile.x + 0.5) * tileMeters - EARTH_HALF_CIRCUM
        val worldYCenter = EARTH_HALF_CIRCUM - (tile.y + 0.5) * tileMeters

        // Model matrix: translate to tile centre, scale to tile size
        val model = FloatArray(16)
        android.opengl.Matrix.setIdentityM(model, 0)
        android.opengl.Matrix.translateM(model, 0,
            worldXCenter.toFloat(), worldYCenter.toFloat(), 0f)
        android.opengl.Matrix.scaleM(model, 0,
            tileMeters.toFloat(), tileMeters.toFloat(), 1f)

        val mvp = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvp, 0, viewMatrix, 0, model, 0)
        GLES30.glUniformMatrix4fv(tileMvpLoc, 1, false, mvp, 0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        quadVao?.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        quadVao?.unbind()
    }
}
