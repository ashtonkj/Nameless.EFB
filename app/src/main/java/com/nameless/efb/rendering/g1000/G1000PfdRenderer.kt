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

        // ── G-01 Attitude indicator ──────────────────────────────────────────────
        drawAttitude(snap, lay.attitude)

        // ── G-02 Airspeed tape ───────────────────────────────────────────────────
        drawAirspeedTape(snap, lay.ias, currIas, trendKt)

        // ── G-03 Altitude tape ───────────────────────────────────────────────────
        val altFt = (snap?.elevationM?.div(0.3048))?.toFloat() ?: 0f
        drawAltitudeTape(snap, lay.altitude, altFt)

        // ── G-04 VSI tape ────────────────────────────────────────────────────────
        val vsiFpm = snap?.vviFpm ?: 0f
        drawVsiIndicator(lay.vsi, vsiFpm)

        // ── G-05 HSI ─────────────────────────────────────────────────────────────
        drawHsi(snap, lay.hsi)

        // ── G-06 Nav status box ──────────────────────────────────────────────────
        drawNavStatus(snap, lay.hsi)

        // ── G-07 Inset map ───────────────────────────────────────────────────────
        drawInsetMap(snap, lay.insetMap)

        // ── G-08 Wind display ────────────────────────────────────────────────────
        drawWindIndicator(snap, lay.bottomStrip)

        // ── G-09 Marker beacons ──────────────────────────────────────────────────
        drawMarkerBeacons(snap, lay.bottomStrip, timeSec)

        // ── G-10 OAT/TAS strip ──────────────────────────────────────────────────
        drawOatTas(snap, lay.bottomStrip)

        // ── G-31 AP mode annunciator ─────────────────────────────────────────────
        drawApAnnunciator(snap, lay.annunciator)

        // Restore full-surface viewport.
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    // ── G-01: Attitude indicator ────────────────────────────────────────────────

    private fun drawAttitude(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(attitudeProg)
        GLES30.glUniform1f(pitchLoc, snap?.pitchDeg ?: 0f)
        GLES30.glUniform1f(rollLoc,  Math.toRadians((snap?.rollDeg ?: 0.0).toDouble()).toFloat())
        attitudeVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        attitudeVao.unbind()

        // Pitch ladder.
        drawPitchLadder(snap?.pitchDeg ?: 0f, snap?.rollDeg ?: 0f, vp)

        // Roll scale arc.
        drawRollScale(snap?.rollDeg ?: 0f, vp)

        // Fixed aircraft symbol at centre.
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)
        drawColoredBar(-0.15f, 0f, 0.15f, 0f, 0.03f)        // wings
        drawColoredBar(0f, 0f, 0f, -0.08f, 0.02f)            // tail
        drawFilledQuad(-0.02f, -0.02f, 0.04f, 0.04f)         // centre dot

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
                    cx + lineHalf * cosR + 0.03f,
                    cy - lineHalf * sinR - 0.02f,
                    0.35f, whiteColor)
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
            // Draw scrolling tape texture.
            GLES30.glUseProgram(tapeProg)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tape.airspeedTextureId)
            GLES30.glUniform1i(tapeTexLoc, 0)
            // Scroll: IAS 0 = bottom of texture, 300 = top. Normalise to 0..1.
            val scroll = ias / 300f
            GLES30.glUniform1f(tapeScrollLoc, scroll)
            drawTapeQuad()
        } else {
            // Fallback: solid background.
            GLES30.glUseProgram(colorProg)
            GLES30.glUniform4f(colorLoc, 0.1f, 0.1f, 0.1f, 1f)
            drawFilledQuad(-1f, -1f, 2f, 2f)
        }

        // Centre readout box with current IAS.
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0f, 0f, 0f, 1f)
        drawFilledQuad(-0.95f, -0.14f, 1.9f, 0.28f)
        textRenderer.drawText("%.0f".format(ias), -0.75f, -0.08f, 0.7f, whiteColor)

        // Trend vector.
        if (abs(trendKt) > 1f) {
            GLES30.glUniform4f(colorLoc, 1f, 0f, 1f, 1f)
            val yNorm = (trendKt / 40f) * 0.5f
            drawFilledQuad(0.7f, yNorm - 0.02f, 0.2f, 0.04f)
        }

        // TAS readout below tape.
        val tas = snap?.tasKts ?: 0f
        textRenderer.drawText("TAS %.0f".format(tas), -0.9f, -0.92f, 0.4f, whiteColor)
    }

    // ── G-03: Altitude tape ─────────────────────────────────────────────────────

    private fun drawAltitudeTape(snap: SimSnapshot?, vp: GlViewport, altFt: Float) {
        applyViewport(vp)

        val tape = tapeRenderer
        if (tape != null) {
            GLES30.glUseProgram(tapeProg)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tape.altitudeTextureId)
            GLES30.glUniform1i(tapeTexLoc, 0)
            // Scroll normalised: -1000 to 50000 ft range.
            val scroll = (altFt + 1000f) / 51000f
            GLES30.glUniform1f(tapeScrollLoc, scroll)
            drawTapeQuad()
        } else {
            GLES30.glUseProgram(colorProg)
            GLES30.glUniform4f(colorLoc, 0.1f, 0.1f, 0.1f, 1f)
            drawFilledQuad(-1f, -1f, 2f, 2f)
        }

        // Centre readout box with current altitude.
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0f, 0f, 0f, 1f)
        drawFilledQuad(-0.95f, -0.14f, 1.9f, 0.28f)
        textRenderer.drawText("%.0f".format(altFt), -0.85f, -0.08f, 0.6f, whiteColor)

        // Kollsman window (baro setting).
        val baroInhg = snap?.barometerInhg ?: 29.92f
        val baroText = when (baroUnit) {
            BaroUnit.HPA -> "%.0f hPa".format(G1000PfdMath.inHgToHpa(baroInhg))
            BaroUnit.INHG -> "%.2f\"".format(baroInhg)
        }
        textRenderer.drawText(baroText, -0.9f, -0.92f, 0.38f, cyanColor)
    }

    /** Draw a full-viewport tape quad using the quad VAO with UVs. */
    private fun drawTapeQuad() {
        // Full-viewport quad with UVs.
        val verts = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
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

        // Background.
        GLES30.glUniform4f(colorLoc, 0.07f, 0.07f, 0.07f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)

        // Pointer bar.
        val offset = G1000PfdMath.vsiToPixelOffset(vsiFpm, vp.height / 2f)
        val normY  = (offset / (vp.height / 2f)).coerceIn(-1f, 1f)
        GLES30.glUniform4f(colorLoc, 0f, 1f, 1f, 1f)
        drawFilledQuad(-0.8f, normY - 0.04f, 1.6f, 0.08f)

        // VS text readout.
        val sign = if (vsiFpm >= 0) "+" else ""
        textRenderer.drawText("$sign%.0f".format(vsiFpm), -0.9f, -0.15f, 0.35f, whiteColor)
    }

    // ── G-05: HSI ───────────────────────────────────────────────────────────────

    private fun drawHsi(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)

        // Dark background.
        GLES30.glUniform4f(colorLoc, 0.05f, 0.05f, 0.08f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)

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

        // Heading readout at top.
        textRenderer.drawText("%03.0f\u00B0".format(heading), -0.2f, 0.85f, 0.5f, whiteColor)

        // CDI bar (magenta).
        val cdiNorm = G1000PfdMath.computeCdiPosition(hdef, CDI_DOT_SPACING_PX) / (vp.width / 2f)
        GLES30.glUniform4f(colorLoc, 1f, 0f, 1f, 1f)
        drawFilledQuad(cdiNorm - 0.02f, -0.6f, 0.04f, 1.2f)

        // CDI dots (5 hollow circles represented as small squares).
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 0.5f)
        for (i in -2..2) {
            if (i == 0) continue
            val dotX = (i.toFloat() / 2.5f) * (CDI_DOT_SPACING_PX * 2.5f / (vp.width / 2f))
            drawFilledQuad(dotX - 0.015f, -0.015f, 0.03f, 0.03f)
        }

        // Aircraft symbol at centre.
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 1f)
        val discY = if (arcMode) -0.55f else 0f
        drawFilledQuad(-0.02f, discY - 0.02f, 0.04f, 0.04f)  // centre
        drawColoredBar(-0.12f, discY, 0.12f, discY, 0.02f)   // wings
        drawColoredBar(0f, discY, 0f, discY + 0.1f, 0.015f)  // nose

        // Heading bug.
        GLES30.glUniform4f(colorLoc, 0f, 1f, 1f, 1f)
        val bugDeg  = snap?.apHeadingBugDeg ?: heading
        var relBug  = (bugDeg - heading + 360f) % 360f
        if (relBug > 180f) relBug -= 360f
        val bugVisible = !arcMode || abs(relBug) <= 70f
        if (bugVisible) {
            val bugRad  = Math.toRadians(relBug.toDouble()).toFloat()
            val bugR    = 0.85f
            val bugX    = sin(bugRad.toDouble()).toFloat() * bugR
            val bugY    = cos(bugRad.toDouble()).toFloat() * bugR + discY
            // Open triangle heading bug.
            drawColoredBar(bugX - 0.04f, bugY, bugX, bugY + 0.06f, 0.012f)
            drawColoredBar(bugX, bugY + 0.06f, bugX + 0.04f, bugY, 0.012f)
        }

        // Glideslope indicator (right edge).
        // GS scale dots.
        GLES30.glUniform4f(colorLoc, 1f, 1f, 1f, 0.4f)
        for (i in -2..2) {
            if (i == 0) continue
            val dotY = i * 0.15f
            drawFilledQuad(0.88f, dotY - 0.015f, 0.03f, 0.03f)
        }
        // GS pointer diamond.
        val gsNorm = (vdef / 2.5f) * 0.3f
        GLES30.glUniform4f(colorLoc, 0f, 1f, 0f, 1f)
        drawColoredBar(0.86f, gsNorm, 0.92f, gsNorm + 0.04f, 0.008f)
        drawColoredBar(0.92f, gsNorm + 0.04f, 0.98f, gsNorm, 0.008f)
        drawColoredBar(0.98f, gsNorm, 0.92f, gsNorm - 0.04f, 0.008f)
        drawColoredBar(0.92f, gsNorm - 0.04f, 0.86f, gsNorm, 0.008f)
    }

    /** Draw the compass rose using the pre-rendered texture rotated by heading. */
    private fun drawTexturedCompassRose(heading: Float, arcMode: Boolean, hsi: G1000HsiRenderer) {
        GLES30.glUseProgram(hsiProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hsi.textureId)
        GLES30.glUniform1i(hsiTexLoc, 0)
        GLES30.glUniform1f(hsiHeadingLoc, heading)

        // Draw rose quad.  In ARC mode, shift down and scale up.
        val discY = if (arcMode) -0.55f else 0f
        val scale = if (arcMode) 1.8f else 1.8f
        val verts = floatArrayOf(
            -scale, -scale + discY, 0f, 1f,
             scale, -scale + discY, 1f, 1f,
            -scale,  scale + discY, 0f, 0f,
             scale,  scale + discY, 1f, 0f,
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
                        sx * labelR - 0.03f * label.length,
                        sy * labelR + discCentreY - 0.03f,
                        0.35f, whiteColor)
                }
            }
        }
    }

    // ── G-06: Nav status box ────────────────────────────────────────────────────

    private fun drawNavStatus(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)
        GLES30.glUniform4f(colorLoc, 0f, 0f, 0f, 0.6f)
        drawFilledQuad(-0.5f, 0.75f, 1.0f, 0.20f)

        // Active waypoint / nav source.
        val obs = snap?.nav1ObsDeg ?: 0f
        textRenderer.drawText("CRS %03.0f".format(obs), -0.45f, 0.80f, 0.35f, greenColor)
    }

    // ── G-07: Inset map ─────────────────────────────────────────────────────────

    private fun drawInsetMap(snap: SimSnapshot?, insetVp: GlViewport) {
        if (insetMap != null) {
            insetMap.draw(snap, insetRangeNm, insetVp)
        } else {
            applyViewport(insetVp)
            GLES30.glUseProgram(colorProg)
            GLES30.glUniform4f(colorLoc, 0.05f, 0.08f, 0.12f, 1f)
            drawFilledQuad(-1f, -1f, 2f, 2f)
            GLES30.glUniform4f(colorLoc, 0f, 1f, 0f, 1f)
            drawFilledQuad(-0.06f, -0.06f, 0.12f, 0.12f)
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
        textRenderer.drawText("%03.0f/%02.0f".format(windDir, windSpd), -0.9f, -0.5f, 0.35f, whiteColor)
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
                textRenderer.drawText("OM", -0.7f, -0.3f, 0.6f, cyanColor)
            }
        }
        if (snap?.middleMarker == true) {
            val visible = (timeSec % 0.63f) < 0.315f
            if (visible) {
                val amberColor = floatArrayOf(1f, 0.749f, 0f, 1f)
                textRenderer.drawText("MM", -0.2f, -0.3f, 0.6f, amberColor)
            }
        }
        if (snap?.innerMarker == true) {
            val visible = (timeSec % 0.167f) < 0.083f
            if (visible) {
                textRenderer.drawText("IM", 0.3f, -0.3f, 0.6f, whiteColor)
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
        textRenderer.drawText("OAT %.0fC TAS %.0f".format(oat, tas), -0.9f, -0.3f, 0.3f, whiteColor)
    }

    // ── G-31: AP mode annunciator strip ─────────────────────────────────────────

    private fun drawApAnnunciator(snap: SimSnapshot?, vp: GlViewport) {
        applyViewport(vp)
        GLES30.glUseProgram(colorProg)

        GLES30.glUniform4f(colorLoc, 0.05f, 0.05f, 0.05f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)

        val flags = snap?.apStateFlags ?: 0

        // Lateral mode labels.
        if (flags and AP_HDG != 0) {
            textRenderer.drawText("HDG", -0.95f, -0.4f, 0.55f, greenColor)
        }
        if (flags and AP_NAV != 0) {
            textRenderer.drawText("NAV", -0.5f, -0.4f, 0.55f, greenColor)
        }

        // Vertical mode labels.
        if (flags and AP_VS != 0) {
            textRenderer.drawText("VS", 0.15f, -0.4f, 0.55f, greenColor)
        }
        if (flags and AP_ALT != 0) {
            textRenderer.drawText("ALT", 0.45f, -0.4f, 0.55f, greenColor)
        }
        if (flags and AP_FLC != 0) {
            textRenderer.drawText("FLC", 0.75f, -0.4f, 0.55f, greenColor)
        }
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
