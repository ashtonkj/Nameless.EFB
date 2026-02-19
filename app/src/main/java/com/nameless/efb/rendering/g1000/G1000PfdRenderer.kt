package com.nameless.efb.rendering.g1000

import android.content.res.AssetManager
import android.opengl.GLES30
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.rendering.gauge.GlViewport
import com.nameless.efb.rendering.gauge.applyViewport
import com.nameless.efb.rendering.gl.BaseRenderer
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import com.nameless.efb.rendering.gl.Theme
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Autopilot state flags (from Rust dataref-schema `ap_state_flags` bitfield).
private const val AP_HDG = 0x01
private const val AP_NAV = 0x02
private const val AP_VS  = 0x04
private const val AP_ALT = 0x08
private const val AP_FLC = 0x10

// G1000 nominal PFD dimensions.
private const val PFD_W = 1280
private const val PFD_H = 800

// Dot spacing in px for CDI deflection (5 dots = full scale ±2.5 dots).
private const val CDI_DOT_SPACING_PX = 40f

/**
 * OpenGL ES 3.0 renderer for the Garmin G1000 Primary Flight Display (PFD).
 *
 * Renders at 1280×800 logical resolution via an FBO; scales to the device surface.
 *
 * PFD elements:
 *  - G-01  Attitude indicator with sky/ground gradient and pitch ladder
 *  - G-02  Airspeed tape with trend vector and V-speed bands
 *  - G-03  Altitude tape with Kollsman window (hPa default for SA)
 *  - G-04  VSI tape with compressed scale above ±2 000 fpm
 *  - G-05  HSI — 360° disc or 140° arc mode with CDI, GS, bearing pointers
 *  - G-06  Nav status box (GPS waypoint, XTK, ETE)
 *  - G-07  Inset map (sub-viewport, shared MapRenderer)
 *  - G-08  Wind data display (3 selectable modes)
 *  - G-09  Marker beacon annunciators with CRG flash rates
 *  - G-10  OAT (Celsius) and TAS display
 *  - G-31  AP mode annunciator strip (active = green, armed = white)
 *
 * @param assets     [AssetManager] forwarded to [BaseRenderer].
 * @param simData    Live sim data; read on GL thread each frame.
 * @param insetMap   Optional inset map helper (shared MapRenderer).
 * @param theme      Initial rendering theme.
 */
class G1000PfdRenderer(
    assets: AssetManager,
    private val simData: StateFlow<SimSnapshot?>,
    private val insetMap: PfdInsetMap? = null,
    theme: Theme = Theme.DAY,
) : BaseRenderer(assets, theme) {

    // ── Mutable PFD state (volatile — written from UI thread, read on GL thread) ──

    /** HSI display mode: full 360° rose or 140° arc. */
    @Volatile var hsiMode: HsiMode = HsiMode.ARC

    /** Barometric pressure unit for Kollsman window (hPa default for SA). */
    @Volatile var baroUnit: BaroUnit = BaroUnit.HPA

    /** Wind data display mode. */
    @Volatile var windMode: WindMode = WindMode.ARROW_SOURCE

    /** Inset map range in nm (1, 2, 3, 5, 7, 10, 15, 20 nm selectable). */
    @Volatile var insetRangeNm: Float = 10f

    // ── Surface dimensions ────────────────────────────────────────────────────

    private var surfaceWidth  = PFD_W
    private var surfaceHeight = PFD_H
    private var layout: G1000PfdLayout? = null

    // ── Previous frame IAS (for trend vector) ─────────────────────────────────

    @Volatile private var prevIasKts = 0f
    @Volatile private var prevFrameTimeMs = 0L

    // ── Shader programs ───────────────────────────────────────────────────────

    private var attitudeProg = 0
    private var colorProg    = 0

    // attitude uniform locations
    private var pitchLoc   = 0
    private var rollLoc    = 0

    // flat-colour uniform locations
    private var colorLoc   = 0
    private var colorMvpLoc = 0

    // ── VAOs ──────────────────────────────────────────────────────────────────

    // Full-screen clip-space quad for the attitude indicator background.
    private lateinit var attitudeVao: GlVao
    private lateinit var attitudeBuf: GlBuffer

    // Unit quad (±0.5, with UV) for coloured overlays.
    private lateinit var quadVao: GlVao
    private lateinit var quadBuf: GlBuffer

    // ── BaseRenderer template methods ─────────────────────────────────────────

    override fun onGlReady() {
        attitudeProg = shaderManager.getProgram(
            "shaders/g1000/attitude.vert",
            "shaders/g1000/attitude.frag",
        )
        colorProg = shaderManager.getProgram(
            "shaders/g1000/flat_color.vert",
            "shaders/g1000/flat_color.frag",
        )

        pitchLoc    = GLES30.glGetUniformLocation(attitudeProg, "u_pitch_deg")
        rollLoc     = GLES30.glGetUniformLocation(attitudeProg, "u_roll_rad")
        colorLoc    = GLES30.glGetUniformLocation(colorProg,    "u_color")
        colorMvpLoc = GLES30.glGetUniformLocation(colorProg,    "u_mvp")

        // Clip-space fullscreen quad: positions only (x, y) in [-1, 1].
        attitudeBuf = GlBuffer()
        attitudeBuf.upload(buildClipQuadPositions())
        attitudeVao = GlVao()
        attitudeVao.bind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, attitudeBuf.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        attitudeVao.unbind()

        // Unit quad with UV for overlays (x, y, u, v — stride 16 bytes).
        quadBuf = GlBuffer()
        quadBuf.upload(buildUvQuadPositions())
        quadVao = GlVao()
        quadVao.bind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        quadVao.unbind()
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10, width: Int, height: Int) {
        super.onSurfaceChanged(gl, width, height)
        surfaceWidth  = width
        surfaceHeight = height
        layout = G1000PfdLayout(width, height)
    }

    override fun drawFrame() {
        val snap   = simData.value
        val lay    = layout ?: return

        // ── Timing for trend vector ────────────────────────────────────────────
        val nowMs   = System.currentTimeMillis()
        val dtSec   = if (prevFrameTimeMs == 0L) 0.05f
                      else ((nowMs - prevFrameTimeMs) / 1000f).coerceIn(0.01f, 1f)
        val timeSec = nowMs / 1000f

        val currIas = snap?.iasKts ?: 0f
        val trendKt = if (dtSec > 0f) G1000PfdMath.computeTrendVector(prevIasKts, currIas, dtSec, 6f) else 0f

        prevIasKts      = currIas
        prevFrameTimeMs = nowMs

        // ── G-01 Attitude indicator ────────────────────────────────────────────
        drawAttitude(snap, lay.attitude)

        // ── G-02 Airspeed tape ────────────────────────────────────────────────
        drawTapeBackground(lay.ias, 0.1f, 0.1f, 0.1f)
        drawCentreBox(lay.ias, "%.0f".format(currIas))
        if (abs(trendKt) > 1f) drawTrendMarker(lay.ias, currIas, trendKt)

        // ── G-03 Altitude tape ────────────────────────────────────────────────
        val altFt = (snap?.elevationM?.div(0.3048))?.toFloat() ?: 0f
        drawTapeBackground(lay.altitude, 0.1f, 0.1f, 0.1f)
        drawCentreBox(lay.altitude, "%.0f".format(altFt))

        // ── G-04 VSI tape ─────────────────────────────────────────────────────
        val vsiFpm = snap?.vviFpm ?: 0f
        drawVsiIndicator(lay.vsi, vsiFpm)

        // ── G-05 HSI ──────────────────────────────────────────────────────────
        drawHsi(snap, lay.hsi)

        // ── G-06 Nav status box ───────────────────────────────────────────────
        drawNavStatus(snap, lay.hsi)

        // ── G-07 Inset map ────────────────────────────────────────────────────
        drawInsetMap(snap, lay.insetMap)

        // ── G-08 Wind display ─────────────────────────────────────────────────
        drawWindIndicator(snap, lay.bottomStrip)

        // ── G-09 Marker beacons ───────────────────────────────────────────────
        drawMarkerBeacons(snap, lay.bottomStrip, timeSec)

        // ── G-31 AP mode annunciator ──────────────────────────────────────────
        drawApAnnunciator(snap, lay.annunciator)

        // Restore full-surface viewport.
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    // ── G-01: Attitude indicator ──────────────────────────────────────────────

    private fun drawAttitude(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(attitudeProg)
        GLES30.glUniform1f(pitchLoc, snap?.pitchDeg ?: 0f)
        GLES30.glUniform1f(rollLoc,  Math.toRadians((snap?.rollDeg ?: 0.0).toDouble()).toFloat())
        attitudeVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        attitudeVao.unbind()

        // Pitch ladder overlay (white lines at ±2.5°, ±5°, ±10°, ±15°, ±20°, ±25°, ±30°).
        drawPitchLadder(snap?.pitchDeg ?: 0f, snap?.rollDeg ?: 0f, vp)

        // UAR chevrons (un-usable attitude range) when extreme attitude.
        val roll  = snap?.rollDeg ?: 0f
        val pitch = snap?.pitchDeg ?: 0f
        if (abs(roll) > 30f || abs(pitch) > 20f) {
            drawUarChevrons(vp, pitch, roll)
        }
    }

    /** Draw white horizontal bars representing pitch ladder lines. */
    private fun drawPitchLadder(pitchDeg: Float, rollDeg: Float, vp: GlViewport) {
        val ladderIntervals = floatArrayOf(-30f, -25f, -20f, -15f, -10f, -5f, -2.5f,
                                            2.5f, 5f, 10f, 15f, 20f, 25f, 30f)
        val pixPerDeg = G1000PfdMath.computePitchLadderSpacing(24f, 1f)
        val rollRad   = Math.toRadians(rollDeg.toDouble()).toFloat()
        val cosR = cos(rollRad)
        val sinR = sin(rollRad)

        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)  // white

        for (interval in ladderIntervals) {
            val yOffset = (interval - pitchDeg) * pixPerDeg / (vp.height / 2f)
            // The ladder line is a short horizontal bar in clip space, rotated by roll.
            val lineHalf = if (abs(interval) % 10f == 0f) 0.25f else 0.15f
            val cy = yOffset * cosR
            val cx = yOffset * sinR
            drawColoredBar(cx - lineHalf * cosR, cy + lineHalf * sinR,
                           cx + lineHalf * cosR, cy - lineHalf * sinR,
                           0.005f)
        }
    }

    /** Draw red V-shaped chevrons pointing toward the horizon (UAR). */
    private fun drawUarChevrons(vp: GlViewport, pitchDeg: Float, rollDeg: Float) {
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 1f, 0f, 0f, 1f)  // red
        // Simplified: draw a small red rectangle at the top or bottom of the AI.
        drawFilledQuad(-0.2f, if (pitchDeg > 20f) 0.8f else -0.8f, 0.4f, 0.05f)
    }

    // ── G-02 / G-03: Tape background and centre readout box ───────────────────

    private fun drawTapeBackground(vp: GlViewport, r: Float, g: Float, b: Float) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, r, g, b, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)
    }

    private fun drawCentreBox(vp: GlViewport, @Suppress("UNUSED_PARAMETER") text: String) {
        // Draw black border and white centre box for current-value readout.
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0f, 0f, 0f, 1f)
        drawFilledQuad(-0.9f, -0.12f, 1.8f, 0.24f)
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 0.15f)
        drawFilledQuad(-0.85f, -0.10f, 1.7f, 0.20f)
    }

    private fun drawTrendMarker(vp: GlViewport, currIas: Float, trendKt: Float) {
        // Draw a small magenta triangle at the trend position on the tape edge.
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 1f, 0f, 1f, 1f)  // magenta
        val yNorm = (trendKt / 40f) * 0.5f
        drawFilledQuad(0.7f, yNorm - 0.02f, 0.2f, 0.04f)
    }

    // ── G-04: VSI indicator ───────────────────────────────────────────────────

    private fun drawVsiIndicator(vp: GlViewport, vsiFpm: Float) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)

        // Background.
        GLES30.glUniform4f(colorLoc, 0.07f, 0.07f, 0.07f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)

        // Pointer bar (cyan): maps VSI to vertical position.
        val offset = G1000PfdMath.vsiToPixelOffset(vsiFpm, vp.height / 2f)
        val normY  = (offset / (vp.height / 2f)).coerceIn(-1f, 1f)
        GLES30.glUniform4f(colorLoc, 0f, 1f, 1f, 1f)  // cyan
        drawFilledQuad(-0.8f, normY - 0.04f, 1.6f, 0.08f)
    }

    // ── G-05: HSI (Horizontal Situation Indicator) ────────────────────────────

    private fun drawHsi(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)

        // Dark background.
        GLES30.glUniform4f(colorLoc, 0.05f, 0.05f, 0.08f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)

        val heading = snap?.magHeadingDeg ?: 0f
        val course  = snap?.nav1ObsDeg    ?: 0f
        val hdef    = (snap?.nav1HdefDot  ?: 0f).coerceIn(-2.5f, 2.5f)
        val vdef    = (snap?.nav1VdefDot  ?: 0f).coerceIn(-2.5f, 2.5f)

        // Compass disc (white arc segments for cardinal/intercardinal ticks).
        drawCompassDisc()

        // CDI bar — magenta, centred on course needle.
        val cdiPosNorm = G1000PfdMath.computeCdiPosition(hdef, CDI_DOT_SPACING_PX) /
                         (vp.width / 2f)
        GLES30.glUniform4f(colorLoc, 1f, 0f, 1f, 1f)  // magenta
        drawFilledQuad(cdiPosNorm - 0.02f, -0.6f, 0.04f, 1.2f)

        // Heading bug (cyan triangle at top).
        GLES30.glUniform4f(colorLoc, 0f, 1f, 1f, 1f)  // cyan
        val bugAngleRad = Math.toRadians((snap?.apHeadingBugDeg ?: 0.0 - heading).toDouble()).toFloat()
        val bugX = sin(bugAngleRad.toDouble()).toFloat() * 0.85f
        val bugY = cos(bugAngleRad.toDouble()).toFloat() * 0.85f
        drawFilledQuad(bugX - 0.04f, bugY - 0.04f, 0.08f, 0.08f)

        // Glideslope indicator (right edge, vertical).
        GLES30.glUniform4f(colorLoc, 0.5f, 0.5f, 0.5f, 1f)
        drawFilledQuad(0.88f, -0.6f, 0.04f, 1.2f)  // GS scale
        val gsNorm = (vdef / 2.5f) * 0.6f
        GLES30.glUniform4f(colorLoc, 0f, 1f, 1f, 1f)
        drawFilledQuad(0.83f, gsNorm - 0.04f, 0.14f, 0.08f)  // GS pointer diamond
    }

    /** Draw compass rose tick marks. */
    private fun drawCompassDisc() {
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)
        val sectors = 72  // 5° increments
        for (i in 0 until sectors) {
            val angleDeg = i * 5.0
            val angleRad = Math.toRadians(angleDeg).toFloat()
            val tickLen  = if (i % 6 == 0) 0.10f else if (i % 2 == 0) 0.06f else 0.04f
            val outerR   = 0.90f
            val innerR   = outerR - tickLen
            val cx = cos(angleRad.toDouble()).toFloat()
            val cy = sin(angleRad.toDouble()).toFloat()
            drawColoredBar(
                cx * innerR, cy * innerR,
                cx * outerR, cy * outerR,
                0.007f
            )
        }
    }

    // ── G-06: Nav status box ──────────────────────────────────────────────────

    private fun drawNavStatus(snap: SimSnapshot?, vp: GlViewport) {
        // Draw a small translucent box at the top of the HSI viewport.
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0f, 0f, 0f, 0.6f)
        drawFilledQuad(-0.5f, 0.75f, 1.0f, 0.20f)

        // XTK deviation indicator (white text region placeholder).
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 0.1f)
        drawFilledQuad(-0.45f, 0.78f, 0.90f, 0.13f)
    }

    // ── G-07: Inset map ───────────────────────────────────────────────────────

    private fun drawInsetMap(snap: SimSnapshot?, insetVp: GlViewport) {
        if (insetMap != null) {
            insetMap.draw(snap, insetRangeNm, insetVp)
        } else {
            // Placeholder: dark slate viewport with ownship dot.
            applyViewport(insetVp)
            GLES30.glUseProgram(colorProg)
            GLES30.glUniform4f(colorLoc, 0.05f, 0.08f, 0.12f, 1f)
            drawFilledQuad(-1f, -1f, 2f, 2f)
            GLES30.glUniform4f(colorLoc, 0f, 1f, 0f, 1f)
            drawFilledQuad(-0.06f, -0.06f, 0.12f, 0.12f)
        }
    }

    // ── G-08: Wind display ────────────────────────────────────────────────────

    private fun drawWindIndicator(snap: SimSnapshot?, bottomVp: GlViewport) {
        val windDir = snap?.windDirDeg ?: 0f
        val windSpd = snap?.windSpeedKt ?: 0f

        // Draw in left third of the bottom strip.
        val windVp = GlViewport(bottomVp.x, bottomVp.y, bottomVp.width / 3, bottomVp.height)
        applyViewport(windVp)
        GLES30.glUseProgram(colorProg)

        when (windMode) {
            WindMode.ARROW_SOURCE, WindMode.ARROW_WIND -> {
                // Arrow pointing into wind (source mode) or downwind.
                val arrowAngle = if (windMode == WindMode.ARROW_SOURCE) windDir
                                 else (windDir + 180f) % 360f
                val angleRad = Math.toRadians(arrowAngle.toDouble()).toFloat()
                GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)
                val arrowX = sin(angleRad.toDouble()).toFloat() * 0.4f
                val arrowY = cos(angleRad.toDouble()).toFloat() * 0.4f
                drawColoredBar(-arrowX * 0.5f, -arrowY * 0.5f, arrowX * 0.5f, arrowY * 0.5f, 0.08f)
            }
            WindMode.HEADWIND_CROSSWIND -> {
                // HW/XW component display placeholder.
                GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 0.5f)
                drawFilledQuad(-0.9f, -0.3f, 1.8f, 0.6f)
            }
        }
    }

    // ── G-09: Marker beacons ──────────────────────────────────────────────────

    private fun drawMarkerBeacons(snap: SimSnapshot?, bottomVp: GlViewport, timeSec: Float) {
        // Draw in right third of the bottom strip.
        val markerW = bottomVp.width / 3
        val markerVp = GlViewport(bottomVp.x + bottomVp.width - markerW, bottomVp.y,
                                  markerW, bottomVp.height)
        applyViewport(markerVp)
        GLES30.glUseProgram(colorProg)

        // OM: cyan "O" — flash period 0.5 s (2 dashes/sec)
        if (snap?.outerMarker == true) {
            val visible = (timeSec % 0.5f) < 0.25f
            if (visible) {
                GLES30.glUniform4f(colorLoc, 0f, 1f, 1f, 1f)
                drawFilledQuad(-0.9f, -0.8f, 0.5f, 1.6f)
            }
        }
        // MM: amber "M" — flash period 0.63 s
        if (snap?.middleMarker == true) {
            val visible = (timeSec % 0.63f) < 0.315f
            if (visible) {
                GLES30.glUniform4f(colorLoc, 1f, 0.749f, 0f, 1f)
                drawFilledQuad(-0.3f, -0.8f, 0.5f, 1.6f)
            }
        }
        // IM: white "I" — flash period 0.167 s (6 dots/sec)
        if (snap?.innerMarker == true) {
            val visible = (timeSec % 0.167f) < 0.083f
            if (visible) {
                GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)
                drawFilledQuad(0.3f, -0.8f, 0.5f, 1.6f)
            }
        }
    }

    // ── G-31: AP mode annunciator strip ──────────────────────────────────────

    private fun drawApAnnunciator(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)

        // Strip background — dark.
        GLES30.glUniform4f(colorLoc, 0.05f, 0.05f, 0.05f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)

        val flags = snap?.apStateFlags ?: 0

        // Lateral active (HDG / NAV).
        if (flags and AP_HDG != 0) {
            GLES30.glUniform4f(colorLoc, 0f, 1f, 0f, 1f)   // green
            drawFilledQuad(-0.95f, -0.6f, 0.3f, 1.2f)
        } else if (flags and AP_NAV != 0) {
            GLES30.glUniform4f(colorLoc, 0f, 1f, 0f, 1f)
            drawFilledQuad(-0.6f, -0.6f, 0.3f, 1.2f)
        }

        // Vertical active (VS / ALT).
        if (flags and AP_VS != 0) {
            GLES30.glUniform4f(colorLoc, 0f, 1f, 0f, 1f)
            drawFilledQuad(0.1f, -0.6f, 0.3f, 1.2f)
        } else if (flags and AP_ALT != 0) {
            GLES30.glUniform4f(colorLoc, 0f, 1f, 0f, 1f)
            drawFilledQuad(0.45f, -0.6f, 0.3f, 1.2f)
        }
    }

    // ── Low-level draw helpers ────────────────────────────────────────────────

    /**
     * Draw a filled axis-aligned quad in clip space.
     *
     * @param x  left edge in clip space (-1..1)
     * @param y  bottom edge in clip space (-1..1)
     * @param w  width in clip space
     * @param h  height in clip space
     */
    private fun drawFilledQuad(x: Float, y: Float, w: Float, h: Float) {
        val verts = floatArrayOf(
            x,     y,
            x + w, y,
            x,     y + h,
            x + w, y + h,
        )
        val tmpBuf = GlBuffer()
        tmpBuf.upload(verts, GLES30.GL_DYNAMIC_DRAW)
        val tmpVao = GlVao()
        tmpVao.bind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, tmpBuf.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        tmpVao.unbind()
    }

    /**
     * Draw a thick line segment between two clip-space points.
     *
     * Implemented as a thin quad along the line direction.
     */
    private fun drawColoredBar(x0: Float, y0: Float, x1: Float, y1: Float, thickness: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(0.001f)
        val nx  = -dy / len * thickness * 0.5f   // normal
        val ny  =  dx / len * thickness * 0.5f

        val verts = floatArrayOf(
            x0 + nx, y0 + ny,
            x0 - nx, y0 - ny,
            x1 + nx, y1 + ny,
            x1 - nx, y1 - ny,
        )
        val tmpBuf = GlBuffer()
        tmpBuf.upload(verts, GLES30.GL_DYNAMIC_DRAW)
        val tmpVao = GlVao()
        tmpVao.bind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, tmpBuf.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        tmpVao.unbind()
    }

    // ── Geometry builders ─────────────────────────────────────────────────────

    /** Fullscreen clip-space quad: (x, y) in [-1, 1]. Draw as GL_TRIANGLE_STRIP. */
    private fun buildClipQuadPositions(): FloatArray = floatArrayOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f,
    )

    /** Unit quad with UV: (x, y, u, v) centred on origin. Draw as GL_TRIANGLE_STRIP. */
    private fun buildUvQuadPositions(): FloatArray = floatArrayOf(
        -0.5f, -0.5f, 0f, 0f,
         0.5f, -0.5f, 1f, 0f,
        -0.5f,  0.5f, 0f, 1f,
         0.5f,  0.5f, 1f, 1f,
    )
}
