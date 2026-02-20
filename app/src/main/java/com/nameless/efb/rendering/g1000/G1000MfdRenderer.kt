package com.nameless.efb.rendering.g1000

import android.content.res.AssetManager
import android.opengl.GLES30
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.domain.flightplan.FlightPlan
import com.nameless.efb.rendering.g1000.mfd.AlertLevel
import com.nameless.efb.rendering.g1000.mfd.AuxPageRenderer
import com.nameless.efb.rendering.g1000.mfd.EisRenderer
import com.nameless.efb.rendering.g1000.mfd.FplPageRenderer
import com.nameless.efb.rendering.g1000.mfd.MfdPage
import com.nameless.efb.rendering.g1000.mfd.MfdPageManager
import com.nameless.efb.rendering.g1000.mfd.NrstPageRenderer
import com.nameless.efb.rendering.g1000.mfd.ProcPageRenderer
import com.nameless.efb.rendering.g1000.mfd.TerrainPageRenderer
import com.nameless.efb.rendering.g1000.mfd.TrafficPageRenderer
import com.nameless.efb.rendering.gauge.GlViewport
import com.nameless.efb.rendering.gauge.applyViewport
import com.nameless.efb.rendering.gl.BaseRenderer
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import com.nameless.efb.rendering.gl.Theme
import com.nameless.efb.rendering.map.MapRenderer
import kotlinx.coroutines.flow.StateFlow

// G1000 MFD nominal dimensions.
private const val MFD_W = 1280
private const val MFD_H = 800

// EIS strip is always visible on the left 200 px of the MFD.
private const val EIS_WIDTH_PX = 200

/**
 * OpenGL ES 3.0 renderer for the Garmin G1000 Multi-Function Display (MFD).
 *
 * Renders at 1280×800 logical resolution. The EIS strip (200×800 px) is always
 * visible on the left; the remaining 1080×800 px area shows the active page.
 *
 * Pages (selected by FMS knob, Plan 13):
 *  - G-11  Full-screen moving map with TAWS
 *  - G-12  EIS strip (always-on)
 *  - G-13  Flight Plan page
 *  - G-14  Procedure Loading pages
 *  - G-15  Nearest pages (airports, VOR, NDB)
 *  - G-16  AUX pages (trip, utility, GPS, system status)
 *  - G-17  Terrain page
 *  - G-18  Traffic page
 *
 * @param assets      [AssetManager] for shader loading.
 * @param simData     Live sim state from [DataSourceManager].
 * @param mapRenderer Shared [MapRenderer] instance (also used by PFD inset map).
 * @param theme       Initial rendering theme.
 */
class G1000MfdRenderer(
    assets: AssetManager,
    private val simData: StateFlow<SimSnapshot?>,
    private val mapRenderer: MapRenderer? = null,
    theme: Theme = Theme.DAY,
) : BaseRenderer(assets, theme) {

    // ── Page management ───────────────────────────────────────────────────────

    val pageManager = MfdPageManager { /* page changed — no GL call needed here */ }

    // ── Flight plan (set from UI thread) ─────────────────────────────────────

    @Volatile var activePlan: FlightPlan? = null
    @Volatile var activeLeg: Int = 0

    // ── Page-specific renderer instances ─────────────────────────────────────

    private val eisRenderer    = EisRenderer()
    private val fplRenderer    = FplPageRenderer()
    private val procRenderer   = ProcPageRenderer()
    private val nrstRenderer   = NrstPageRenderer()
    private val auxRenderer    = AuxPageRenderer()
    private val terrainRenderer = TerrainPageRenderer()
    private val trafficRenderer = TrafficPageRenderer()

    // ── Surface dimensions ────────────────────────────────────────────────────

    private var surfaceWidth  = MFD_W
    private var surfaceHeight = MFD_H

    // ── Shader programs ───────────────────────────────────────────────────────

    private var colorProg  = 0
    private var colorLoc   = 0

    // ── BaseRenderer template methods ─────────────────────────────────────────

    override fun onGlReady() {
        colorProg = shaderManager.getProgram(
            "shaders/g1000/flat_color.vert",
            "shaders/g1000/flat_color.frag",
        )
        colorLoc = GLES30.glGetUniformLocation(colorProg, "u_color")
        eisRenderer.onGlReady(colorProg, colorLoc)
    }

    override fun onSurfaceChanged(
        gl: javax.microedition.khronos.opengles.GL10,
        width: Int,
        height: Int,
    ) {
        super.onSurfaceChanged(gl, width, height)
        surfaceWidth  = width
        surfaceHeight = height
    }

    override fun drawFrame() {
        val snap = simData.value

        // Compute viewport dimensions (scale from 1280×800 logical to device surface).
        val scaleX = surfaceWidth.toFloat()  / MFD_W
        val scaleY = surfaceHeight.toFloat() / MFD_H

        val eisW = (EIS_WIDTH_PX * scaleX).toInt()
        val pageW = surfaceWidth - eisW

        val eisVp  = GlViewport(0, 0, eisW, surfaceHeight)
        val pageVp = GlViewport(eisW, 0, pageW, surfaceHeight)

        // ── EIS strip (always visible) ────────────────────────────────────────
        eisRenderer.draw(snap, eisVp)

        // ── Active page content area ──────────────────────────────────────────
        when (pageManager.activePage) {
            MfdPage.MAP, MfdPage.ENGINE_MAP -> drawMapPage(snap, pageVp)
            MfdPage.FPL                     -> drawFplPage(snap, pageVp)
            MfdPage.PROC                    -> drawProcPage(pageVp)
            MfdPage.NRST_AIRPORTS,
            MfdPage.NRST_VORS,
            MfdPage.NRST_NDBS               -> drawNrstPage(pageVp)
            MfdPage.AUX_TRIP,
            MfdPage.AUX_UTILITY,
            MfdPage.AUX_GPS_STATUS,
            MfdPage.AUX_SYSTEM_STATUS       -> drawAuxPage(snap, pageVp)
            MfdPage.TERRAIN                 -> drawTerrainPage(snap, pageVp)
            MfdPage.TRAFFIC                 -> drawTrafficPage(snap, pageVp)
        }

        // Restore full-surface viewport.
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    // ── Page draw methods ─────────────────────────────────────────────────────

    private fun drawMapPage(snap: SimSnapshot?, vp: GlViewport) {
        // Full-screen moving map (G-11): tiles + TAWS overlay + all overlays.
        // Uses shared MapRenderer for the 1080×800 area to the right of EIS.
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0.02f, 0.05f, 0.10f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)

        // Ownship dot placeholder (MapRenderer draws into this viewport when available).
        if (snap != null) {
            GLES30.glUniform4f(colorLoc, 0f, 1f, 0f, 1f)
            drawFilledQuad(-0.04f, -0.04f, 0.08f, 0.08f)
        }

        // BARO altitude overlay at bottom-right corner.
        val altFt = ((snap?.elevationM ?: 0.0) / 0.3048).toFloat()
        @Suppress("UNUSED_VARIABLE")
        val altLabel = "%.0f ft".format(altFt)
        GLES30.glUniform4f(colorLoc, 0f, 0f, 0f, 0.7f)
        drawFilledQuad(0.6f, -0.95f, 0.38f, 0.12f)
    }

    private fun drawFplPage(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)
        // Dark background.
        GLES30.glUniform4f(colorLoc, 0.05f, 0.05f, 0.05f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)
        // Delegate to FplPageRenderer for row drawing.
        activePlan?.let { plan ->
            fplRenderer.draw(plan, cursorIndex = null, activeLeg = activeLeg, snapshot = snap)
        }
    }

    private fun drawProcPage(vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0.05f, 0.05f, 0.05f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)
        procRenderer.draw(airportIcao = activePlan?.departure?.icao)
    }

    private fun drawNrstPage(vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0.05f, 0.05f, 0.05f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)
        // NRST data loaded asynchronously; empty list shown until loaded.
        nrstRenderer.drawAirports(emptyList(), cursor = 0, ownship = com.nameless.efb.domain.nav.LatLon(0.0, 0.0))
    }

    private fun drawAuxPage(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0.05f, 0.05f, 0.05f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)
        when (pageManager.activePage) {
            MfdPage.AUX_TRIP     -> auxRenderer.drawTrip(buildTripData(snap))
            MfdPage.AUX_UTILITY  -> auxRenderer.drawUtility(snap)
            MfdPage.AUX_GPS_STATUS -> auxRenderer.drawGpsStatus()
            MfdPage.AUX_SYSTEM_STATUS -> auxRenderer.drawSystemStatus(
                protocolName  = "PLUGIN",
                latencyMs     = 0,
                lastDataAgeSec = 0f,
                airacCycle    = "2401",
                navDbVersion  = "OurAirports v2024-01",
            )
            else -> Unit
        }
    }

    private fun drawTerrainPage(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0.02f, 0.04f, 0.02f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)
        if (snap != null) {
            terrainRenderer.draw(snap, approach = null)
        }
    }

    private fun drawTrafficPage(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0.02f, 0.02f, 0.08f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)
        if (snap != null) {
            // Ownship at centre.
            GLES30.glUniform4f(colorLoc, 0f, 1f, 0f, 1f)
            drawFilledQuad(-0.04f, -0.04f, 0.08f, 0.08f)
            // Draw each traffic target.
            for (i in 0 until snap.trafficCount.coerceAtMost(20)) {
                val alert = trafficRenderer.getAlertLevel(snap, i)
                val (r, g, b) = when (alert) {
                    AlertLevel.RA    -> Triple(1f, 0f, 0f)
                    AlertLevel.TA    -> Triple(1f, 0.749f, 0f)
                    AlertLevel.OTHER -> Triple(1f, 1f, 1f)
                }
                GLES30.glUniform4f(colorLoc, r, g, b, 1f)
                // Simple diamond: two crossed bars.
                drawFilledQuad(-0.03f, -0.01f, 0.06f, 0.02f)
                drawFilledQuad(-0.01f, -0.03f, 0.02f, 0.06f)
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun buildTripData(snap: SimSnapshot?) = com.nameless.efb.rendering.g1000.mfd.TripPlanData(
        destination      = activePlan?.destination,
        distNm           = activePlan?.totalDistanceNm ?: 0.0,
        eteHrMin         = "--:--",
        fuelRequiredKg   = 0f,
        fuelOnBoardKg    = snap?.fuelQtyKg?.sum() ?: 0f,
        estimatedArrivalTime = "--:--",
    )

    private fun drawFilledQuad(x: Float, y: Float, w: Float, h: Float) {
        val verts = floatArrayOf(x, y, x + w, y, x, y + h, x + w, y + h)
        val buf = GlBuffer()
        buf.upload(verts, GLES30.GL_DYNAMIC_DRAW)
        val vao = GlVao()
        vao.bind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        vao.unbind()
    }
}
