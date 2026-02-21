package com.nameless.efb.rendering.gauge

import android.content.res.AssetManager
import android.opengl.GLES30
import com.nameless.efb.domain.gauge.AircraftProfile
import com.nameless.efb.domain.gauge.FuelType
import com.nameless.efb.domain.gauge.kgSecToLph
import com.nameless.efb.domain.gauge.needleAngle
import com.nameless.efb.rendering.gl.BaseRenderer
import com.nameless.efb.rendering.gl.GaugeType
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import com.nameless.efb.rendering.gl.Theme
import com.nameless.efb.rendering.gl.buildQuad
import com.nameless.efb.ui.steam.GaugeState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * OpenGL ES 3.0 renderer for the 14-instrument steam gauge panel.
 *
 * Extends [BaseRenderer] which handles GL init, [ShaderManager], [GaugeTextureAtlas],
 * and the 4 ms frame-budget watchdog.
 *
 * Panel layout (4 rows x 3 columns):
 * ```
 * +-----+-----+-----+
 * | ASI |  AI | ALT |  primary top
 * +-----+-----+-----+
 * | TC  |  DI | VSI |  primary bottom
 * +-----+-----+-----+
 * | RPM | MAP | OIL |  engine top
 * +-----+-----+-----+
 * |  FF |  FQ | EGT |  engine bottom
 * +-----+-----+-----+
 * ```
 *
 * @param assets       [AssetManager] forwarded to [BaseRenderer].
 * @param profile      Aircraft profile supplying V-speeds and alert limits.
 * @param theme        Initial display theme.
 */
class SteamGaugePanelRenderer(
    assets: AssetManager,
    private val profile: AircraftProfile = AircraftProfile(),
    theme: Theme = Theme.DAY,
) : BaseRenderer(assets, theme) {

    /** Latest gauge state -- written from the ViewModel coroutine, read on GL thread. */
    @Volatile var gaugeState: GaugeState = GaugeState()

    // Panel layout (initialised once surface dimensions are known).
    private var layout: GaugePanelLayout? = null

    // Shared VAOs / buffers (created once in onGlReady).
    private lateinit var quadVao: GlVao
    private lateinit var quadBuf: GlBuffer

    // Scratch buffer for per-frame temporary geometry (DI compass ticks, TC ball, etc.)
    private lateinit var scratchBuf: GlBuffer
    private lateinit var scratchVao: GlVao

    // Shader program IDs.
    private var needleProg: Int = 0
    private var gaugeProg: Int = 0
    private var aiHorizonProg: Int = 0
    private var egtBarProg: Int = 0
    private var flatColorProg: Int = 0

    // Cached uniform locations -- needle shader.
    private var needleAngleLoc: Int = 0
    private var needlePivotLoc: Int = 0

    // Cached uniform locations -- AI horizon shader.
    private var aiPitchLoc: Int = 0
    private var aiBankLoc: Int = 0

    // Cached uniform locations -- EGT bar shader.
    private var egtIsPeakLoc: Int = 0
    private var egtFillLoc: Int = 0
    private var egtOriginLoc: Int = 0
    private var egtSizeLoc: Int = 0

    // Cached uniform location -- flat color shader.
    private var flatColorLoc: Int = 0

    // Cached uniform location -- gauge base (texture) shader.
    private var gaugeTexLoc: Int = 0

    // Reusable color arrays for text rendering.
    private val whiteColor = floatArrayOf(1f, 1f, 1f, 1f)
    private val greenColor = floatArrayOf(0f, 1f, 0f, 1f)
    private val cyanColor  = floatArrayOf(0f, 1f, 1f, 1f)

    // ── BaseRenderer template methods ───────────────────────────────────────────

    override fun onGlReady() {
        // Set the aircraft profile on the atlas so dial faces show correct V-speed arcs.
        gaugeAtlas.profile = profile

        // Compile shader programs.
        needleProg    = shaderManager.getProgram("shaders/gauges/needle.vert",     "shaders/gauges/gauge_base.frag")
        gaugeProg     = shaderManager.getProgram("shaders/gauges/gauge_base.vert", "shaders/gauges/gauge_base.frag")
        aiHorizonProg = shaderManager.getProgram("shaders/gauges/ai_horizon.vert", "shaders/gauges/ai_horizon.frag")
        egtBarProg    = shaderManager.getProgram("shaders/gauges/egt_bar.vert",    "shaders/gauges/egt_bar.frag")
        flatColorProg = shaderManager.getProgram("shaders/g1000/flat_color.vert",  "shaders/g1000/flat_color.frag")

        // Cache uniform locations (avoids glGetUniformLocation on every frame).
        needleAngleLoc = GLES30.glGetUniformLocation(needleProg,    "u_needle_angle")
        needlePivotLoc = GLES30.glGetUniformLocation(needleProg,    "u_pivot")
        aiPitchLoc     = GLES30.glGetUniformLocation(aiHorizonProg, "u_pitch_deg")
        aiBankLoc      = GLES30.glGetUniformLocation(aiHorizonProg, "u_bank_rad")
        egtIsPeakLoc   = GLES30.glGetUniformLocation(egtBarProg,    "u_is_peak")
        egtFillLoc     = GLES30.glGetUniformLocation(egtBarProg,    "u_fill_fraction")
        egtOriginLoc   = GLES30.glGetUniformLocation(egtBarProg,    "u_bar_origin")
        egtSizeLoc     = GLES30.glGetUniformLocation(egtBarProg,    "u_bar_size")
        flatColorLoc   = GLES30.glGetUniformLocation(flatColorProg, "u_color")
        gaugeTexLoc    = GLES30.glGetUniformLocation(gaugeProg,     "u_texture")

        // Unit quad shared by needle, gauge base, and EGT bar.
        quadBuf = GlBuffer()
        quadBuf.upload(buildQuad())
        quadVao = GlVao()
        quadVao.bind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf.id)
        // Layout: x, y, u, v -- stride 4 floats x 4 bytes = 16 bytes.
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        // a_texcoord at location 1
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        quadVao.unbind()

        // Scratch buffer for temporary per-frame geometry (DI ticks, TC ball indicator).
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
        layout = GaugePanelLayout(width, height)
    }

    override fun drawFrame() {
        val state  = gaugeState
        val lay    = layout ?: return

        // ── Primary flight instruments (top 2 rows) ────────────────────────────
        drawAsi(state, lay)
        drawAi(state, lay)
        drawAlt(state, lay)
        drawTc(state, lay)
        drawDi(state, lay)
        drawVsi(state, lay)

        // ── Engine instruments (bottom 2 rows) ─────────────────────────────────
        drawRpm(state, lay)
        drawMap(state, lay)
        drawOil(state, lay)
        drawFf(state, lay)
        drawFq(state, lay)
        drawEgt(state, lay)

        // Restore full viewport after per-gauge rendering.
        val w = lay.asi.width * 3
        val h = lay.asi.height + lay.tc.height + lay.rpm.height + lay.ff.height
        GLES30.glViewport(0, 0, w, h)
    }

    // ── Per-gauge draw helpers ──────────────────────────────────────────────────

    private fun drawAsi(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.asi)
        drawDialFace(GaugeType.AIRSPEED)
        val angle = needleAngle(state.airspeedKts, 0f, 200f, -150f, 150f)
        drawNeedle(angle)
        // Digital readout at bottom.
        textRenderer.drawText("%.0f".format(state.airspeedKts), -0.35f, -0.75f, 0.65f, whiteColor)
    }

    private fun drawAi(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.ai)
        GLES30.glUseProgram(aiHorizonProg)
        GLES30.glUniform1f(aiPitchLoc, state.pitchDeg)
        GLES30.glUniform1f(aiBankLoc,  Math.toRadians(state.rollDeg.toDouble()).toFloat())
        quadVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawAlt(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.alt)
        drawDialFace(GaugeType.ALTITUDE)
        val angle100  = needleAngle((state.altFt % 1000f) / 10f,   0f, 100f, -150f, 210f)
        val angle1000 = needleAngle((state.altFt % 10_000f) / 100f, 0f, 100f, -150f, 210f)
        val angle10k  = needleAngle(state.altFt / 1000f,            0f, 50f,  -150f, 210f)
        drawNeedle(angle100)
        drawNeedle(angle1000)
        drawNeedle(angle10k)
        // Altitude readout.
        textRenderer.drawText("%.0f".format(state.altFt), -0.5f, -0.75f, 0.55f, whiteColor)
    }

    private fun drawTc(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.tc)
        drawDialFace(GaugeType.TURN_COORDINATOR)
        val angle = needleAngle(state.turnRateDegSec, -3f, 3f, -150f, 150f)
        drawNeedle(angle)

        // Inclinometer ball.
        GLES30.glUseProgram(flatColorProg)
        GLES30.glUniform4f(flatColorLoc, 1f, 1f, 1f, 1f)
        val ballX = (state.slipDeg / 20f).coerceIn(-1f, 1f) * 0.6f
        drawFilledQuad(ballX - 0.06f, -0.75f, 0.12f, 0.12f)
    }

    private fun drawDi(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.di)
        drawDialFace(GaugeType.HEADING)

        GLES30.glUseProgram(flatColorProg)
        // Compass card ticks at 30-degree intervals, rotated by heading.
        GLES30.glUniform4f(flatColorLoc, 1f, 1f, 1f, 1f)
        for (i in 0 until 12) {
            val compassDeg = i * 30f
            var relDeg = (compassDeg - state.headingDeg + 360f) % 360f
            if (relDeg > 180f) relDeg -= 360f
            val screenRad = Math.toRadians(relDeg.toDouble()).toFloat()
            val tickLen   = if ((i % 3) == 0) 0.15f else 0.09f
            val outerR    = 0.85f
            val innerR    = outerR - tickLen
            val sx        = sin(screenRad.toDouble()).toFloat()
            val sy        = cos(screenRad.toDouble()).toFloat()
            drawColoredBar(sx * innerR, sy * innerR, sx * outerR, sy * outerR, 0.012f)
        }

        // Fixed reference triangle at the top.
        GLES30.glUniform4f(flatColorLoc, 1f, 1f, 0f, 1f)
        drawFilledQuad(-0.04f, 0.78f, 0.08f, 0.10f)

        // Heading digital readout.
        textRenderer.drawText("%03.0f".format(state.headingDeg), -0.3f, -0.75f, 0.65f, whiteColor)
    }

    private fun drawVsi(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.vsi)
        drawDialFace(GaugeType.VERTICAL_SPEED)
        val angle = needleAngle(state.displayedVsiFpm, -2000f, 2000f, -150f, 150f)
        drawNeedle(angle)
        // VSI readout.
        val sign = if (state.displayedVsiFpm >= 0) "+" else ""
        textRenderer.drawText("$sign%.0f".format(state.displayedVsiFpm), -0.5f, -0.75f, 0.5f, whiteColor)
    }

    private fun drawRpm(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.rpm)
        drawDialFace(GaugeType.RPM)
        val angle = needleAngle(state.rpmEng0, 0f, 3000f, -150f, 150f)
        drawNeedle(angle)
        textRenderer.drawText("%.0f".format(state.rpmEng0), -0.4f, -0.75f, 0.55f, whiteColor)
    }

    private fun drawMap(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.map)
        drawDialFace(GaugeType.MANIFOLD_PRESSURE)
        val angle = needleAngle(state.mapInhg, 10f, 35f, -150f, 150f)
        drawNeedle(angle)
        textRenderer.drawText("%.1f".format(state.mapInhg), -0.4f, -0.75f, 0.55f, whiteColor)
    }

    private fun drawOil(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.oil)
        drawDialFace(GaugeType.OIL_TEMPERATURE)
        // Temp needle (white).
        val tempAngle = needleAngle(state.oilTempDegC, 0f, 130f, -150f, 150f)
        drawNeedle(tempAngle)
        // Pressure needle (cyan) -- slightly different colour.
        setUniformColor(needleProg, 0f, 1f, 1f, 1f)
        val pressAngle = needleAngle(state.oilPressPsi, 0f, 115f, -150f, 150f)
        drawNeedle(pressAngle)
        // Reset needle color.
        setUniformColor(needleProg, 1f, 1f, 1f, 1f)
    }

    private fun drawFf(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.ff)
        drawDialFace(GaugeType.MANIFOLD_PRESSURE)  // Reuse a similar dial for fuel flow
        val fuelFlowLph = kgSecToLph(state.fuelFlowKgSec, FuelType.AVGAS)
        val angle = needleAngle(fuelFlowLph, 0f, 20f, -150f, 150f)
        drawNeedle(angle)
        textRenderer.drawText("%.1f".format(fuelFlowLph), -0.35f, -0.75f, 0.55f, whiteColor)
    }

    private fun drawFq(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.fq)
        drawDialFace(GaugeType.FUEL_QUANTITY)
        val maxTankKg = 35f
        // Left tank -- white needle.
        val leftAngle = needleAngle(state.fuelQtyKg.getOrElse(0) { 0f }, 0f, maxTankKg, -150f, 150f)
        drawNeedle(leftAngle)
        // Right tank -- cyan needle.
        setUniformColor(needleProg, 0f, 1f, 1f, 1f)
        val rightAngle = needleAngle(state.fuelQtyKg.getOrElse(1) { 0f }, 0f, maxTankKg, -150f, 150f)
        drawNeedle(rightAngle)
        setUniformColor(needleProg, 1f, 1f, 1f, 1f)
        // Tank readouts.
        val leftKg = state.fuelQtyKg.getOrElse(0) { 0f }
        val rightKg = state.fuelQtyKg.getOrElse(1) { 0f }
        textRenderer.drawText("L:%.0f R:%.0f".format(leftKg, rightKg), -0.7f, -0.8f, 0.45f, whiteColor)
    }

    private fun drawEgt(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.egt)
        drawDialFace(GaugeType.EGT)

        GLES30.glUseProgram(egtBarProg)
        val egtMin = 200f
        val egtMax = 1000f

        var peakIdx = 0
        for (i in 1 until state.egtDegC.size) {
            if (state.egtDegC[i] > state.egtDegC[peakIdx]) peakIdx = i
        }

        val barW   = 0.25f
        val barH   = 1.6f
        val stride = 0.33f

        for (i in state.egtDegC.indices) {
            val fill = ((state.egtDegC[i] - egtMin) / (egtMax - egtMin)).coerceIn(0f, 1f)
            val ox = -0.83f + i * stride
            GLES30.glUniform1f(egtFillLoc,   fill)
            GLES30.glUniform2f(egtOriginLoc, ox, -0.8f)
            GLES30.glUniform2f(egtSizeLoc,   barW, barH)
            GLES30.glUniform1f(egtIsPeakLoc, if (i == peakIdx) 1f else 0f)
            quadVao.bind()
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    // ── Low-level helpers ───────────────────────────────────────────────────────

    /** Draw the pre-rendered dial face texture as a full-viewport background quad. */
    private fun drawDialFace(type: GaugeType) {
        GLES30.glUseProgram(gaugeProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, gaugeAtlas.getTexture(type))
        GLES30.glUniform1i(gaugeTexLoc, 0)
        // Draw full-viewport quad: the buildQuad() is -0.5..0.5, scale to -1..1.
        val verts = floatArrayOf(
            -1f, -1f, 0f, 1f,   // bottom-left  (UV: 0,1 for GL coords)
             1f, -1f, 1f, 1f,   // bottom-right
            -1f,  1f, 0f, 0f,   // top-left
             1f,  1f, 1f, 0f,   // top-right
        )
        scratchBuf.uploadDynamic(verts)
        // Bind scratch VAO but we need texcoords. Let's use a temp approach.
        // Actually, let's draw with the quad VAO after uploading adjusted verts.
        quadBuf.uploadDynamic(verts)
        quadVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        quadVao.unbind()
        // Restore the quad to the unit quad for needle drawing.
        quadBuf.uploadDynamic(com.nameless.efb.rendering.gl.buildQuad())
    }

    private fun drawNeedle(angleRad: Float) {
        GLES30.glUseProgram(needleProg)
        GLES30.glUniform1f(needleAngleLoc, angleRad)
        GLES30.glUniform2f(needlePivotLoc, 0f, 0f)
        quadVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setUniformColor(prog: Int, r: Float, g: Float, b: Float, a: Float) {
        val loc = GLES30.glGetUniformLocation(prog, "u_color")
        GLES30.glUniform4f(loc, r, g, b, a)
    }

    /** Draw a filled axis-aligned quad in clip space using the scratch buffer. */
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

    /** Draw a thick line segment between two clip-space points using the scratch buffer. */
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
}
