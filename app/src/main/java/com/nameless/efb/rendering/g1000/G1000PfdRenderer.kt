package com.nameless.efb.rendering.g1000

import android.content.res.AssetManager
import android.graphics.Typeface
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

// Dot spacing in px for CDI deflection (5 dots = full scale +/-2.5 dots).
private const val CDI_DOT_SPACING_PX = 40f

/**
 * OpenGL ES 3.0 renderer for the Garmin G1000 Primary Flight Display (PFD).
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

    // ── Mutable PFD state ──────────────────────────────────────────────────────

    @Volatile var hsiMode: HsiMode = HsiMode.ARC
    @Volatile var baroUnit: BaroUnit = BaroUnit.HPA
    @Volatile var windMode: WindMode = WindMode.ARROW_SOURCE
    @Volatile var insetRangeNm: Float = 10f

    // ── Surface dimensions ─────────────────────────────────────────────────────

    private var surfaceWidth  = PFD_W
    private var surfaceHeight = PFD_H
    private var layout: G1000PfdLayout? = null

    // ── Previous frame IAS (for trend vector) ──────────────────────────────────

    @Volatile private var prevIasKts = 0f
    @Volatile private var prevFrameTimeMs = 0L

    // ── Shader programs ────────────────────────────────────────────────────────

    private var attitudeProg = 0
    private var colorProg    = 0
    private var tapeProg     = 0
    private var hsiProg      = 0

    // Uniform locations
    private var pitchLoc  = 0
    private var rollLoc   = 0
    private var colorLoc  = 0
    private var tapeScrollLoc = 0
    private var tapeTexLoc = 0
    private var hsiHeadingLoc = 0
    private var hsiTexLoc = 0

    // ── Tape renderer ──────────────────────────────────────────────────────────

    private var tapeRenderer: G1000TapeRenderer? = null
    private var hsiRenderer: G1000HsiRenderer? = null

    // ── Persistent VAOs ────────────────────────────────────────────────────────

    private lateinit var attitudeVao: GlVao
    private lateinit var attitudeBuf: GlBuffer
    private lateinit var quadVao: GlVao
    private lateinit var quadBuf: GlBuffer
    private lateinit var scratchBuf: GlBuffer
    private lateinit var scratchVao: GlVao

    // ── Reusable colour arrays ─────────────────────────────────────────────────

    private val whiteColor   = floatArrayOf(1f, 1f, 1f, 1f)
    private val greenColor   = floatArrayOf(0f, 1f, 0f, 1f)
    private val cyanColor    = floatArrayOf(0f, 1f, 1f, 1f)
    private val magentaColor = floatArrayOf(1f, 0f, 1f, 1f)

    // ── BaseRenderer template methods ──────────────────────────────────────────

    override fun onGlReady() {
        attitudeProg = shaderManager.getProgram(
            "shaders/g1000/attitude.vert",
            "shaders/g1000/attitude.frag",
        )
        colorProg = shaderManager.getProgram(
            "shaders/g1000/flat_color.vert",
            "shaders/g1000/flat_color.frag",
        )
        tapeProg = shaderManager.getProgram(
            "shaders/g1000/tape.vert",
            "shaders/g1000/tape.frag",
        )
        hsiProg = shaderManager.getProgram(
            "shaders/g1000/hsi.vert",
            "shaders/g1000/hsi.frag",
        )

        pitchLoc      = GLES30.glGetUniformLocation(attitudeProg, "u_pitch_deg")
        rollLoc       = GLES30.glGetUniformLocation(attitudeProg, "u_roll_rad")
        colorLoc      = GLES30.glGetUniformLocation(colorProg,    "u_color")
        tapeScrollLoc = GLES30.glGetUniformLocation(tapeProg,     "u_scroll")
        tapeTexLoc    = GLES30.glGetUniformLocation(tapeProg,     "u_texture")
        hsiHeadingLoc = GLES30.glGetUniformLocation(hsiProg,      "u_heading_deg")
        hsiTexLoc     = GLES30.glGetUniformLocation(hsiProg,      "u_texture")

        // Build tape and HSI textures.
        val typeface = try {
            Typeface.createFromAsset(assets, "fonts/LiberationMono-Regular.ttf")
        } catch (e: Exception) {
            Typeface.MONOSPACE
        }
        tapeRenderer = G1000TapeRenderer(typeface)
        hsiRenderer = G1000HsiRenderer(typeface)

        // Clip-space fullscreen quad.
        attitudeBuf = GlBuffer()
        attitudeBuf.upload(buildClipQuadPositions())
        attitudeVao = GlVao()
        attitudeVao.bind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, attitudeBuf.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        attitudeVao.unbind()

        // Unit quad with UV.
        quadBuf = GlBuffer()
        quadBuf.upload(buildUvQuadPositions())
        quadVao = GlVao()
        quadVao.bind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        quadVao.unbind()

        // Scratch buffer.
        scratchBuf = GlBuffer()
        scratchBuf.upload(FloatArray(8), GLES30.GL_DYNAMIC_DRAW)
        scratchVao = GlVao()
        scratchVao.bind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, scratchBuf.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        scratchVao.unbind()
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

        // ── Timing for trend vector ──────────────────────────────────────────────
        val nowMs   = System.currentTimeMillis()
        val dtSec   = if (prevFrameTimeMs == 0L) 0.05f
                      else ((nowMs - prevFrameTimeMs) / 1000f).coerceIn(0.01f, 1f)
        val timeSec = nowMs / 1000f

        val currIas = snap?.iasKts ?: 0f
        val trendKt = if (dtSec > 0f) G1000PfdMath.computeTrendVector(prevIasKts, currIas, dtSec, 6f) else 0f

        prevIasKts      = currIas
        prevFrameTimeMs = nowMs

        // ── NAV/COM frequency bar ────────────────────────────────────────────────
        drawNavComBar(snap, lay.navComBar)

        // ── G-01 Attitude background (full area behind tapes + HSI) ─────────────
        drawAttitudeBackground(snap, lay.attitudeFull)

        // ── Dark flanking panels (full-height behind tapes, drawn before tapes) ─
        drawDarkPanel(lay.leftFlank)
        drawDarkPanel(lay.rightFlank)

        // ── G-02 Airspeed tape ───────────────────────────────────────────────────
        drawAirspeedTape(snap, lay.ias, currIas, trendKt)

        // ── Attitude overlays (pitch ladder, symbols) in upper centre ───────────
        drawAttitudeOverlays(snap, lay.attitude)

        // ── G-03 Altitude tape ───────────────────────────────────────────────────
        val altFt = (snap?.elevationM?.div(0.3048))?.toFloat() ?: 0f
        drawAltitudeTape(snap, lay.altitude, altFt)

        // ── G-04 VSI tape ────────────────────────────────────────────────────────
        val vsiFpm = snap?.vviFpm ?: 0f
        drawVsiIndicator(lay.vsi, vsiFpm)

        // ── G-05 HSI (drawn on top of attitude background) ──────────────────────
        drawHsi(snap, lay.hsi)

        // ── G-06 Nav status box ──────────────────────────────────────────────────
        drawNavStatus(snap, lay.hsi)

        // ── G-07 Inset map ───────────────────────────────────────────────────────
        drawInsetMap(snap, lay.insetMap)

        // ── Softkey bar ──────────────────────────────────────────────────────────
        drawSoftkeyBar(snap, lay.softkeyBar)

        // Restore full-surface viewport.
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    // ── G-01: Attitude background (sky/ground gradient, full area) ─────────────

    private fun drawAttitudeBackground(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(attitudeProg)
        GLES30.glUniform1f(pitchLoc, snap?.pitchDeg ?: 0f)
        GLES30.glUniform1f(rollLoc,  Math.toRadians((snap?.rollDeg ?: 0.0).toDouble()).toFloat())
        attitudeVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        attitudeVao.unbind()
    }

    // ── Attitude overlays (pitch ladder, symbols — upper centre viewport) ────

    private fun drawAttitudeOverlays(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)

        // Pitch ladder.
        drawPitchLadder(snap?.pitchDeg ?: 0f, snap?.rollDeg ?: 0f, vp)

        // Roll scale arc.
        drawRollScale(snap?.rollDeg ?: 0f, vp)

        // TRAFFIC annunciator (cyan on black, below roll scale, per G1000 CRG).
        if ((snap?.trafficCount ?: 0) > 0) {
            GLES30.glUseProgram(colorProg)
            GLES30.glUniform4f(colorLoc, 0f, 0f, 0f, 1f)
            drawFilledQuad(-0.12f, 0.70f, 0.24f, 0.08f)
            textRenderer.drawText("TRAFFIC", -0.10f, 0.72f, 0.06f, cyanColor)
        }

        // Fixed aircraft symbol at centre — yellow inverted-V chevron.
        GLES30.glUseProgram(colorProg)
        val yellowR = 0.90f; val yellowG = 0.80f; val yellowB = 0.0f
        GLES30.glUniform4f(colorLoc, yellowR, yellowG, yellowB, 1f)
        // Left wing: horizontal extension → diagonal down to centre.
        drawColoredBar(-0.22f, 0.0f, -0.12f, 0.0f, 0.025f)  // left wing tip
        drawColoredBar(-0.12f, 0.0f, 0.0f,  -0.06f, 0.025f)  // left diagonal
        // Right wing: diagonal from centre → horizontal extension.
        drawColoredBar(0.0f,  -0.06f, 0.12f, 0.0f, 0.025f)   // right diagonal
        drawColoredBar(0.12f,  0.0f,  0.22f, 0.0f, 0.025f)   // right wing tip
        // Small centre dot at the lowest point of the V.
        drawFilledQuad(-0.015f, -0.075f, 0.03f, 0.03f)

        // Slip/skid indicator below roll pointer.
        val slip = snap?.slipDeg ?: 0f
        val slipOffset = (slip / 15f).coerceIn(-1f, 1f) * 0.1f
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)
        drawFilledQuad(slipOffset - 0.03f, 0.85f, 0.06f, 0.04f)

        // UAR chevrons.
        val roll  = snap?.rollDeg ?: 0f
        val pitch = snap?.pitchDeg ?: 0f
        if (abs(roll) > 30f || abs(pitch) > 20f) {
            drawUarChevrons(vp, pitch, roll)
        }
    }

    private fun drawPitchLadder(pitchDeg: Float, rollDeg: Float, vp: GlViewport) {
        val ladderIntervals = floatArrayOf(-30f, -25f, -20f, -15f, -10f, -5f, -2.5f,
                                            2.5f, 5f, 10f, 15f, 20f, 25f, 30f)
        val pixPerDeg = G1000PfdMath.computePitchLadderSpacing(24f, 1f)
        val rollRad   = Math.toRadians(rollDeg.toDouble()).toFloat()
        val cosR = cos(rollRad)
        val sinR = sin(rollRad)

        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)

        for (interval in ladderIntervals) {
            val yOffset = (interval - pitchDeg) * pixPerDeg / (vp.height / 2f)
            val lineHalf = if (abs(interval) % 10f == 0f) 0.25f else 0.15f
            val cy = yOffset * cosR
            val cx = yOffset * sinR
            drawColoredBar(cx - lineHalf * cosR, cy + lineHalf * sinR,
                           cx + lineHalf * cosR, cy - lineHalf * sinR,
                           0.015f)

            // Pitch degree numbers at major intervals.
            if (abs(interval) % 10f == 0f && abs(interval) > 0f) {
                val numStr = "%.0f".format(abs(interval))
                textRenderer.drawText(numStr,
                    cx + lineHalf * cosR + 0.02f,
                    cy - lineHalf * sinR - 0.015f,
                    0.05f, whiteColor)
            }
        }
    }

    /** Draw roll scale arc at top of attitude display. */
    private fun drawRollScale(rollDeg: Float, vp: GlViewport) {
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)

        // Tick marks at standard roll angles.
        val rollAngles = floatArrayOf(-60f, -45f, -30f, -20f, -10f, 0f, 10f, 20f, 30f, 45f, 60f)
        val radius = 0.92f
        for (angle in rollAngles) {
            val rad = Math.toRadians((angle - 90).toDouble()) // -90 so 0=up
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val tickLen = if (angle == 0f || abs(angle) == 30f || abs(angle) == 60f) 0.06f else 0.04f
            val innerR = radius - tickLen
            drawColoredBar(cosA * innerR, sinA * innerR, cosA * radius, sinA * radius, 0.008f)
        }

        // Roll pointer (inverted triangle) at the current bank angle.
        val bankRad = Math.toRadians((-rollDeg - 90).toDouble())
        val cosP = cos(bankRad).toFloat()
        val sinP = sin(bankRad).toFloat()
        val ptrR = radius + 0.02f
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)
        drawFilledQuad(cosP * ptrR - 0.03f, sinP * ptrR - 0.03f, 0.06f, 0.06f)
    }

    private fun drawUarChevrons(vp: GlViewport, pitchDeg: Float, rollDeg: Float) {
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 1f, 0f, 0f, 1f)
        drawFilledQuad(-0.2f, if (pitchDeg > 20f) 0.8f else -0.8f, 0.4f, 0.05f)
    }

    // ── G-02: Airspeed tape ─────────────────────────────────────────────────────

    private fun drawAirspeedTape(snap: SimSnapshot?, vp: GlViewport, ias: Float, trendKt: Float) {
        applyViewport(vp)

        val tape = tapeRenderer
        if (tape != null) {
            // Draw scrolling tape texture — show ~55 kts window centred on current IAS.
            GLES30.glUseProgram(tapeProg)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tape.airspeedTextureId)
            GLES30.glUniform1i(tapeTexLoc, 0)
            GLES30.glUniform1f(tapeScrollLoc, 0f)  // no shader-side scroll
            // Centre the UV window on current IAS.
            // V=0 → 300 kts (canvas top/bitmap row 0), V=1 → 0 kts (canvas bottom).
            val centreV = 1f - (ias / 300f).coerceIn(0f, 1f)
            val windowV = 55f / 300f   // show ~55 kts of the 300 kt range
            // topV = lower V = higher speed (at screen top)
            val topV = (centreV - windowV / 2f).coerceIn(0f, 1f - windowV)
            val botV = topV + windowV
            drawTapeQuadUv(botV, topV)
        } else {
            // Fallback: solid background.
            GLES30.glUseProgram(colorProg)
            GLES30.glUniform4f(colorLoc, 0.1f, 0.1f, 0.1f, 1f)
            drawFilledQuad(-1f, -1f, 2f, 2f)
        }

        // Centre readout box with current IAS.
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0f, 0f, 0f, 1f)
        drawFilledQuad(-0.95f, -0.10f, 1.9f, 0.20f)
        textRenderer.drawText("%.0f".format(ias), -0.50f, -0.04f, 0.10f, whiteColor)

        // Trend vector.
        if (abs(trendKt) > 1f) {
            GLES30.glUniform4f(colorLoc, 1f, 0f, 1f, 1f)
            val yNorm = (trendKt / 40f) * 0.5f
            drawFilledQuad(0.7f, yNorm - 0.02f, 0.2f, 0.04f)
        }

        // TAS readout below tape (e.g. "TAS 0KT" per G1000 CRG).
        val tas = snap?.tasKts ?: 0f
        textRenderer.drawText("TAS %.0fKT".format(tas), -0.85f, -0.94f, 0.06f, whiteColor)
    }

    // ── G-03: Altitude tape ─────────────────────────────────────────────────────

    private fun drawAltitudeTape(snap: SimSnapshot?, vp: GlViewport, altFt: Float) {
        applyViewport(vp)

        // Dark background.
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0.10f, 0.10f, 0.10f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)

        // Procedural altitude tape — draw ticks and numbers for visible ±300 ft (G1000 CRG).
        val visibleRange = 300f  // ±300 ft from centre
        val clipPerFt = 1f / visibleRange  // clip units per foot (1 clip unit = half viewport)

        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)

        // Major ticks every 100 ft, minor every 50 ft (per reference).
        val baseAlt = (((altFt - visibleRange).toInt() / 50) * 50)
        val topAlt  = (((altFt + visibleRange).toInt() / 50 + 1) * 50)
        var alt = baseAlt
        while (alt <= topAlt) {
            val yClip = (alt - altFt) * clipPerFt
            if (yClip < -1.1f || yClip > 1.1f) { alt += 50; continue }

            if (alt % 100 == 0) {
                // Major tick + number (omit 0 label so -200, -100, 100, 200 visible).
                GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)
                drawColoredBar(-1f, yClip, -0.50f, yClip, 0.015f)
                val label = "$alt"
                textRenderer.drawText(label, -0.45f, yClip - 0.035f, 0.07f, whiteColor)
            } else {
                // Minor tick at every 50 ft.
                GLES30.glUseProgram(colorProg)
                GLES30.glUniform4f(colorLoc, 0.6f, 0.6f, 0.6f, 1f)
                drawColoredBar(-1f, yClip, -0.70f, yClip, 0.008f)
            }
            alt += 50
        }

        // Centre readout box.
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0f, 0f, 0f, 1f)
        drawFilledQuad(-0.95f, -0.10f, 1.9f, 0.20f)
        val altLabel = when {
            altFt < 0 && altFt > -100f -> "-%02.0f".format(-altFt)
            altFt >= 0f && altFt < 100f -> "%02.0f".format(altFt)
            else -> "%.0f".format(altFt)
        }
        textRenderer.drawText(altLabel, -0.65f, -0.05f, 0.10f, whiteColor)

        // Altitude bug (magenta caret pointing left at selected altitude, per G1000 CRG).
        val apAltFt = snap?.apAltitudeFt ?: altFt
        if (kotlin.math.abs(apAltFt - altFt) <= visibleRange) {
            val bugYClip = (apAltFt - altFt) * clipPerFt
            if (bugYClip >= -1.05f && bugYClip <= 1.05f) {
                GLES30.glUseProgram(colorProg)
                GLES30.glUniform4f(colorLoc, 1f, 0f, 1f, 1f)
                val cx = 0.55f
                val h = 0.04f
                drawColoredBar(cx, bugYClip, cx - 0.08f, bugYClip - h, 0.01f)
                drawColoredBar(cx - 0.08f, bugYClip - h, cx - 0.08f, bugYClip + h, 0.01f)
                drawColoredBar(cx - 0.08f, bugYClip + h, cx, bugYClip, 0.01f)
            }
        }

        // Kollsman window (baro setting).
        val baroInhg = snap?.barometerInhg ?: 29.92f
        val baroText = when (baroUnit) {
            BaroUnit.HPA -> "%.0fhPa".format(G1000PfdMath.inHgToHpa(baroInhg))
            BaroUnit.INHG -> "%.2fIN".format(baroInhg)
        }
        textRenderer.drawText(baroText, -0.85f, -0.90f, 0.08f, cyanColor)
    }

    /** Draw a full-viewport quad sampling a V sub-range of the bound tape texture. */
    private fun drawTapeQuadUv(botV: Float, topV: Float) {
        val verts = floatArrayOf(
            -1f, -1f, 0f, botV,
             1f, -1f, 1f, botV,
            -1f,  1f, 0f, topV,
             1f,  1f, 1f, topV,
        )
        quadBuf.uploadDynamic(verts)
        quadVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        quadVao.unbind()
    }

    // ── G-04: VSI indicator ─────────────────────────────────────────────────────

    private fun drawVsiIndicator(vp: GlViewport, vsiFpm: Float) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)

        // Background (semi-transparent tape per G1000 CRG).
        GLES30.glUniform4f(colorLoc, 0.07f, 0.07f, 0.07f, 0.9f)
        drawFilledQuad(-1f, -1f, 2f, 2f)

        // Scale labels: ±1, ±2 (thousands fpm), 0 at centre.
        textRenderer.drawText("2", -0.75f, 0.88f, 0.08f, whiteColor)
        textRenderer.drawText("1", -0.75f, 0.38f, 0.08f, whiteColor)
        textRenderer.drawText("0", -0.78f, -0.06f, 0.08f, whiteColor)
        textRenderer.drawText("1", -0.75f, -0.50f, 0.08f, whiteColor)
        textRenderer.drawText("2", -0.75f, -0.94f, 0.08f, whiteColor)

        // Pointer bar.
        val offset = G1000PfdMath.vsiToPixelOffset(vsiFpm, vp.height / 2f)
        val normY  = (offset / (vp.height / 2f)).coerceIn(-1f, 1f)
        GLES30.glUniform4f(colorLoc, 0f, 1f, 1f, 1f)
        drawFilledQuad(-0.8f, normY - 0.04f, 1.6f, 0.08f)

        // VS text readout.
        val sign = if (vsiFpm >= 0) "+" else ""
        textRenderer.drawText("$sign%.0f".format(vsiFpm), -0.85f, -0.04f, 0.05f, whiteColor)
    }

    // ── G-05: HSI ───────────────────────────────────────────────────────────────

    private fun drawHsi(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)

        // No opaque background — attitude gradient shows through from behind.
        // Enable blending so the compass rose texture's transparent parts work.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val heading = snap?.magHeadingDeg ?: 0f
        val hdef    = (snap?.nav1HdefDot  ?: 0f).coerceIn(-2.5f, 2.5f)
        val vdef    = (snap?.nav1VdefDot  ?: 0f).coerceIn(-2.5f, 2.5f)
        val arcMode = (hsiMode == HsiMode.ARC)

        // Compass card — use textured rose if available, otherwise procedural ticks.
        val hsi = hsiRenderer
        if (hsi != null) {
            drawTexturedCompassRose(heading, arcMode, hsi)
        } else {
            drawCompassCard(heading, arcMode)
        }

        // Viewport aspect for making circular geometry square on screen.
        val hsiAspect = vp.width.toFloat() / vp.height.toFloat()
        val discY = if (arcMode) -0.55f else -0.08f
        // Match the ring radius to the texture's outer tick marks:
        // Texture outerR = 246px from centre (half=256), scaleY=1.25.
        val scaleY = if (arcMode) 1.6f else 1.25f
        val roseR = scaleY * (246f / 256f)

        // Compass outer circle ring (white, drawn as line segments).
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 0.6f)
        val ringSegs = 72
        for (i in 0 until ringSegs) {
            val a0 = Math.toRadians(i * 360.0 / ringSegs).toFloat()
            val a1 = Math.toRadians((i + 1) * 360.0 / ringSegs).toFloat()
            drawColoredBar(
                sin(a0.toDouble()).toFloat() * roseR / hsiAspect,
                cos(a0.toDouble()).toFloat() * roseR + discY,
                sin(a1.toDouble()).toFloat() * roseR / hsiAspect,
                cos(a1.toDouble()).toFloat() * roseR + discY,
                0.006f
            )
        }

        // Course pointer (green line from CRS through centre of compass).
        val crs = snap?.nav1ObsDeg ?: 0f
        val relCrs = ((crs - heading + 360f) % 360f).let { if (it > 180f) it - 360f else it }
        val crsRad = Math.toRadians(relCrs.toDouble()).toFloat()
        val crsFromR = 0.25f  // inner radius (gap around aircraft)
        val crsToR = roseR - 0.05f  // just inside the ring
        GLES30.glUniform4f(colorLoc, 0f, 1f, 0f, 1f)
        // Forward course line (from aircraft towards CRS direction).
        drawColoredBar(
            sin(crsRad.toDouble()).toFloat() * crsFromR / hsiAspect,
            cos(crsRad.toDouble()).toFloat() * crsFromR + discY,
            sin(crsRad.toDouble()).toFloat() * crsToR / hsiAspect,
            cos(crsRad.toDouble()).toFloat() * crsToR + discY,
            0.008f
        )
        // Reciprocal course line (from aircraft away from CRS).
        val crsRecipRad = crsRad + Math.PI.toFloat()
        drawColoredBar(
            sin(crsRecipRad.toDouble()).toFloat() * crsFromR / hsiAspect,
            cos(crsRecipRad.toDouble()).toFloat() * crsFromR + discY,
            sin(crsRecipRad.toDouble()).toFloat() * crsToR / hsiAspect,
            cos(crsRecipRad.toDouble()).toFloat() * crsToR + discY,
            0.008f
        )

        // Heading readout at top-centre (HDG box).
        GLES30.glUniform4f(colorLoc, 0f, 0f, 0f, 0.7f)
        drawFilledQuad(-0.06f, 0.82f, 0.12f, 0.16f)
        textRenderer.drawText("HDG", -0.06f, 0.87f, 0.08f, greenColor)
        textRenderer.drawText("%03.0f".format(heading), -0.03f, 0.80f, 0.10f, whiteColor)

        // Aircraft symbol at centre (white, aspect-corrected).
        val wingX = 0.10f / hsiAspect
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)
        // Fuselage body
        drawFilledQuad(-0.01f / hsiAspect, discY - 0.02f, 0.02f / hsiAspect, 0.10f)
        // Wings
        drawColoredBar(-wingX, discY, wingX, discY, 0.018f)
        // Tail
        val tailX = 0.04f / hsiAspect
        drawColoredBar(-tailX, discY - 0.04f, tailX, discY - 0.04f, 0.012f)

        // Source annunciation: GPS (green), ENR (magenta).
        textRenderer.drawText("GPS", -0.08f, discY + 0.08f, 0.07f, greenColor)
        textRenderer.drawText("ENR", 0.03f, discY + 0.08f, 0.07f, magentaColor)

        // Heading bug (cyan, aspect-corrected, drawn after HDG box).
        // G1000 heading bug: a small tab on the outer edge of the compass ring.
        GLES30.glUniform4f(colorLoc, 0f, 1f, 1f, 1f)
        val bugDeg  = snap?.apHeadingBugDeg ?: heading
        var relBug  = (bugDeg - heading + 360f) % 360f
        if (relBug > 180f) relBug -= 360f
        val bugVisible = !arcMode || abs(relBug) <= 70f
        if (bugVisible) {
            val bugRad  = Math.toRadians(relBug.toDouble()).toFloat()
            val sinBug = sin(bugRad.toDouble()).toFloat()
            val cosBug = cos(bugRad.toDouble()).toFloat()
            // Inner edge sits on the ring, outer edge extends beyond.
            val innerR = roseR - 0.02f
            val outerR = roseR + 0.04f
            val bw = 0.025f  // half-width of bug tab
            // Left edge of tab.
            val lx = (sinBug * innerR - cosBug * bw) / hsiAspect
            val ly = cosBug * innerR + sinBug * bw + discY
            // Right edge of tab.
            val rx = (sinBug * innerR + cosBug * bw) / hsiAspect
            val ry = cosBug * innerR - sinBug * bw + discY
            // Tip of tab (pointed outward).
            val tx = sinBug * outerR / hsiAspect
            val ty = cosBug * outerR + discY
            drawColoredBar(lx, ly, tx, ty, 0.012f)
            drawColoredBar(tx, ty, rx, ry, 0.012f)
            // Connecting bar across ring at heading bug.
            drawColoredBar(lx, ly, rx, ry, 0.012f)
        }

        // CDI deflection dots (white, aspect-corrected).
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 0.5f)
        val dotSpacing = 0.06f / hsiAspect
        for (i in -2..2) {
            if (i == 0) continue
            val dotX = i * dotSpacing
            drawFilledQuad(dotX - 0.005f / hsiAspect, discY - 0.008f, 0.01f / hsiAspect, 0.016f)
        }

        // CDI needle (magenta, thin vertical bar through aircraft).
        val cdiPx = G1000PfdMath.computeCdiPosition(hdef, CDI_DOT_SPACING_PX)
        val cdiX = (cdiPx / (vp.width / 2f)) / hsiAspect
        GLES30.glUniform4f(colorLoc, 1f, 0f, 1f, 1f)
        drawFilledQuad(cdiX - 0.003f / hsiAspect, discY - 0.30f, 0.006f / hsiAspect, 0.60f)

        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Draw the compass rose using the pre-rendered texture rotated by heading. */
    private fun drawTexturedCompassRose(heading: Float, arcMode: Boolean, hsi: G1000HsiRenderer) {
        GLES30.glUseProgram(hsiProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hsi.textureId)
        GLES30.glUniform1i(hsiTexLoc, 0)
        GLES30.glUniform1f(hsiHeadingLoc, heading)

        // Query viewport to compute aspect ratio for square rose.
        val vpBuf = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, vpBuf, 0)
        val vpW = vpBuf[2].coerceAtLeast(1)
        val vpH = vpBuf[3].coerceAtLeast(1)
        val aspect = vpW.toFloat() / vpH.toFloat()

        // Rose must appear circular: scaleX = scaleY / aspect.
        val discY = if (arcMode) -0.55f else -0.08f
        val scaleY = if (arcMode) 1.6f else 1.25f
        val scaleX = scaleY / aspect

        val verts = floatArrayOf(
            -scaleX, -scaleY + discY, 0f, 1f,
             scaleX, -scaleY + discY, 1f, 1f,
            -scaleX,  scaleY + discY, 0f, 0f,
             scaleX,  scaleY + discY, 1f, 0f,
        )
        quadBuf.uploadDynamic(verts)
        quadVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        quadVao.unbind()

        // Switch back to flat color for subsequent draws.
        GLES30.glUseProgram(colorProg)
    }

    /** Fallback: procedural compass ticks when no texture available. */
    private fun drawCompassCard(headingDeg: Float, arcMode: Boolean) {
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)
        val discCentreY = if (arcMode) -0.55f else 0f
        for (i in 0 until 72) {
            val compassDeg = i * 5f
            var relDeg = (compassDeg - headingDeg + 360f) % 360f
            if (relDeg > 180f) relDeg -= 360f
            if (arcMode && abs(relDeg) > 70f) continue

            val screenRad = Math.toRadians(relDeg.toDouble()).toFloat()
            val tickLen   = if ((i % 6) == 0) 0.10f else if ((i % 2) == 0) 0.06f else 0.04f
            val outerR    = 0.90f
            val innerR    = outerR - tickLen
            val sx        = sin(screenRad.toDouble()).toFloat()
            val sy        = cos(screenRad.toDouble()).toFloat()
            drawColoredBar(
                sx * innerR, sy * innerR + discCentreY,
                sx * outerR, sy * outerR + discCentreY,
                0.007f,
            )

            // Cardinal/intercardinal labels at 30-degree intervals.
            if (i % 6 == 0) {
                val label = when ((i / 6) * 30) {
                    0 -> "N"
                    30 -> "3"
                    60 -> "6"
                    90 -> "E"
                    120 -> "12"
                    150 -> "15"
                    180 -> "S"
                    210 -> "21"
                    240 -> "24"
                    270 -> "W"
                    300 -> "30"
                    330 -> "33"
                    else -> ""
                }
                if (label.isNotEmpty()) {
                    val labelR = innerR - 0.08f
                    textRenderer.drawText(label,
                        sx * labelR - 0.01f * label.length,
                        sy * labelR + discCentreY - 0.06f,
                        0.14f, whiteColor)
                }
            }
        }
    }

    // ── G-06: Nav status box ────────────────────────────────────────────────────

    private fun drawNavStatus(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)

        // Small CRS readout box — top-left of HSI area.
        GLES30.glUniform4f(colorLoc, 0f, 0f, 0f, 0.6f)
        drawFilledQuad(-0.98f, 0.86f, 0.22f, 0.12f)

        val obs = snap?.nav1ObsDeg ?: 0f
        textRenderer.drawText("CRS", -0.97f, 0.88f, 0.08f, magentaColor)
        textRenderer.drawText("%03.0f".format(obs), -0.85f, 0.88f, 0.08f, magentaColor)
    }

    // ── G-07: Inset map ─────────────────────────────────────────────────────────

    private fun drawInsetMap(snap: SimSnapshot?, insetVp: GlViewport) {
        if (insetMap != null) {
            insetMap.draw(snap, insetRangeNm, insetVp)
        } else {
            applyViewport(insetVp)
            GLES30.glUseProgram(colorProg)
            // Dark terrain-like placeholder background.
            GLES30.glUniform4f(colorLoc, 0.08f, 0.12f, 0.06f, 1f)
            drawFilledQuad(-1f, -1f, 2f, 2f)
            // "NORTH UP" label.
            textRenderer.drawText("NORTH UP", -0.90f, 0.70f, 0.14f, whiteColor)
            // Aircraft position dot.
            GLES30.glUseProgram(colorProg)
            GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)
            drawFilledQuad(-0.04f, -0.04f, 0.08f, 0.08f)
        }
    }

    // ── G-08: Wind display ──────────────────────────────────────────────────────

    private fun drawWindIndicator(snap: SimSnapshot?, bottomVp: GlViewport) {
        val windDir = snap?.windDirDeg ?: 0f
        val windSpd = snap?.windSpeedKt ?: 0f

        val windVp = GlViewport(bottomVp.x, bottomVp.y, bottomVp.width / 3, bottomVp.height)
        applyViewport(windVp)
        GLES30.glUseProgram(colorProg)

        when (windMode) {
            WindMode.ARROW_SOURCE, WindMode.ARROW_WIND -> {
                val arrowAngle = if (windMode == WindMode.ARROW_SOURCE) windDir
                                 else (windDir + 180f) % 360f
                val angleRad = Math.toRadians(arrowAngle.toDouble()).toFloat()
                GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)
                val arrowX = sin(angleRad.toDouble()).toFloat() * 0.4f
                val arrowY = cos(angleRad.toDouble()).toFloat() * 0.4f
                drawColoredBar(-arrowX * 0.5f, -arrowY * 0.5f, arrowX * 0.5f, arrowY * 0.5f, 0.08f)
            }
            WindMode.HEADWIND_CROSSWIND -> {
                GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 0.5f)
                drawFilledQuad(-0.9f, -0.3f, 1.8f, 0.6f)
            }
        }

        // Wind digits.
        textRenderer.drawText("%03.0f/%02.0f".format(windDir, windSpd), -0.85f, -0.45f, 0.70f, whiteColor)
    }

    // ── G-09: Marker beacons ────────────────────────────────────────────────────

    private fun drawMarkerBeacons(snap: SimSnapshot?, bottomVp: GlViewport, timeSec: Float) {
        val markerW = bottomVp.width / 3
        val markerVp = GlViewport(bottomVp.x + bottomVp.width - markerW, bottomVp.y,
                                  markerW, bottomVp.height)
        applyViewport(markerVp)
        GLES30.glUseProgram(colorProg)

        if (snap?.outerMarker == true) {
            val visible = (timeSec % 0.5f) < 0.25f
            if (visible) {
                GLES30.glUniform4f(colorLoc, 0f, 1f, 1f, 1f)
                textRenderer.drawText("OM", -0.7f, -0.35f, 0.70f, cyanColor)
            }
        }
        if (snap?.middleMarker == true) {
            val visible = (timeSec % 0.63f) < 0.315f
            if (visible) {
                val amberColor = floatArrayOf(1f, 0.749f, 0f, 1f)
                textRenderer.drawText("MM", -0.2f, -0.35f, 0.70f, amberColor)
            }
        }
        if (snap?.innerMarker == true) {
            val visible = (timeSec % 0.167f) < 0.083f
            if (visible) {
                textRenderer.drawText("IM", 0.3f, -0.35f, 0.70f, whiteColor)
            }
        }
    }

    // ── G-10: OAT/TAS ──────────────────────────────────────────────────────────

    private fun drawOatTas(snap: SimSnapshot?, bottomVp: GlViewport) {
        val midW = bottomVp.width / 3
        val midVp = GlViewport(bottomVp.x + midW, bottomVp.y, midW, bottomVp.height)
        applyViewport(midVp)

        val oat = snap?.oatDegc ?: 0f
        val tas = snap?.tasKts ?: 0f
        textRenderer.drawText("OAT %.0f\u00B0C  TAS %.0fKT".format(oat, tas), -0.85f, -0.40f, 0.60f, whiteColor)
    }

    // ── NAV/COM frequency bar ────────────────────────────────────────────────

    private fun drawNavComBar(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)

        // Dark background.
        GLES30.glUniform4f(colorLoc, 0.05f, 0.05f, 0.05f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)

        val sz = 0.38f

        // NAV1 (left side, top row).
        textRenderer.drawText("NAV1", -0.98f, 0.15f, sz, whiteColor)
        val nav1Act = formatNavFreq(snap?.nav1ActiveHz ?: 0)
        val nav1Sby = formatNavFreq(snap?.nav1StandbyHz ?: 0)
        textRenderer.drawText(nav1Act, -0.70f, 0.15f, sz, greenColor)
        textRenderer.drawText(nav1Sby, -0.38f, 0.15f, sz, whiteColor)

        // NAV2 (left side, bottom row).
        textRenderer.drawText("NAV2", -0.98f, -0.55f, sz, whiteColor)
        textRenderer.drawText("108.00", -0.70f, -0.55f, sz, greenColor)
        textRenderer.drawText("117.95", -0.38f, -0.55f, sz, whiteColor)

        // DIS / BRG labels (centre).
        textRenderer.drawText("DIS", -0.06f, 0.15f, sz, whiteColor)
        textRenderer.drawText("BRG", 0.10f, 0.15f, sz, whiteColor)

        // COM1 (right side, top row).
        val com1Act = formatComFreq(snap?.com1ActiveHz ?: 0)
        val com1Sby = formatComFreq(snap?.com1StandbyHz ?: 0)
        textRenderer.drawText(com1Act, 0.36f, 0.15f, sz, greenColor)
        textRenderer.drawText(com1Sby, 0.60f, 0.15f, sz, whiteColor)
        textRenderer.drawText("COM1", 0.86f, 0.15f, sz, whiteColor)

        // COM2 (right side, bottom row).
        val com2Act = formatComFreq(snap?.com2ActiveHz ?: 0)
        textRenderer.drawText(com2Act, 0.36f, -0.55f, sz, greenColor)
        textRenderer.drawText("118.000", 0.60f, -0.55f, sz, whiteColor)
        textRenderer.drawText("COM2", 0.86f, -0.55f, sz, whiteColor)
    }

    private fun formatNavFreq(hz: Int): String {
        if (hz <= 0) return "---.-"
        val mhz = hz / 1_000_000
        val khz = (hz % 1_000_000) / 1000
        return "%d.%02d".format(mhz, khz / 10)
    }

    private fun formatComFreq(hz: Int): String {
        if (hz <= 0) return "---.---"
        val mhz = hz / 1_000_000
        val khz = (hz % 1_000_000) / 1000
        return "%d.%03d".format(mhz, khz)
    }

    // ── Softkey bar ──────────────────────────────────────────────────────────

    private fun drawSoftkeyBar(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)

        // Dark background.
        GLES30.glUniform4f(colorLoc, 0.05f, 0.05f, 0.05f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)

        // Thin top border line.
        GLES30.glUniform4f(colorLoc, 0.3f, 0.3f, 0.3f, 1f)
        drawFilledQuad(-1f, 0.85f, 2f, 0.15f)

        val sz = 0.90f
        val y = -0.50f

        // OAT on far left with value.
        val oat = snap?.oatDegc ?: 0f
        textRenderer.drawText("OAT %.0fC".format(oat), -0.98f, y, sz, whiteColor)

        // Softkey labels across the bar.
        textRenderer.drawText("INSET", -0.72f, y, sz, whiteColor)
        textRenderer.drawText("PFD", -0.53f, y, sz, whiteColor)
        textRenderer.drawText("CDI", -0.40f, y, sz, whiteColor)
        textRenderer.drawText("DME", -0.28f, y, sz, whiteColor)

        // XPDR with code.
        val xpdr = snap?.transponderCode ?: 0
        val xpdrMode = snap?.transponderMode ?: 0
        val xpdrModeStr = when (xpdrMode) {
            0 -> "OFF"
            1 -> "STBY"
            4 -> "ON"
            5 -> "ALT"
            else -> "ON"
        }
        textRenderer.drawText("XPDR", -0.12f, y, sz, whiteColor)
        textRenderer.drawText("%04d".format(xpdr), 0.02f, y, sz, greenColor)
        textRenderer.drawText(xpdrModeStr, 0.17f, y, sz, whiteColor)
        textRenderer.drawText("LCL", 0.28f, y, sz, whiteColor)

        textRenderer.drawText("IDENT", 0.38f, y, sz, whiteColor)
        textRenderer.drawText("TMR/REF", 0.52f, y, sz, whiteColor)
        textRenderer.drawText("NRST", 0.72f, y, sz, whiteColor)
        textRenderer.drawText("ALERTS", 0.85f, y, sz, whiteColor)
    }

    private fun drawDarkPanel(vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0.05f, 0.05f, 0.05f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)
    }

    // ── Low-level draw helpers ──────────────────────────────────────────────────

    private fun drawFilledQuad(x: Float, y: Float, w: Float, h: Float) {
        val verts = floatArrayOf(
            x,     y,
            x + w, y,
            x,     y + h,
            x + w, y + h,
        )
        scratchBuf.uploadDynamic(verts)
        scratchVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        scratchVao.unbind()
    }

    private fun drawColoredBar(x0: Float, y0: Float, x1: Float, y1: Float, thickness: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(0.001f)
        val nx  = -dy / len * thickness * 0.5f
        val ny  =  dx / len * thickness * 0.5f
        val verts = floatArrayOf(
            x0 + nx, y0 + ny,
            x0 - nx, y0 - ny,
            x1 + nx, y1 + ny,
            x1 - nx, y1 - ny,
        )
        scratchBuf.uploadDynamic(verts)
        scratchVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        scratchVao.unbind()
    }

    // ── Geometry builders ──────────────────────────────────────────────────────

    private fun buildClipQuadPositions(): FloatArray = floatArrayOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f,
    )

    private fun buildUvQuadPositions(): FloatArray = floatArrayOf(
        -0.5f, -0.5f, 0f, 0f,
         0.5f, -0.5f, 1f, 0f,
        -0.5f,  0.5f, 0f, 1f,
         0.5f,  0.5f, 1f, 1f,
    )
}
