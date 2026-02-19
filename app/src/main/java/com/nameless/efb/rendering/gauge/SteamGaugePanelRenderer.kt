package com.nameless.efb.rendering.gauge

import android.content.res.AssetManager
import android.opengl.GLES30
import com.nameless.efb.domain.gauge.AircraftProfile
import com.nameless.efb.domain.gauge.needleAngle
import com.nameless.efb.rendering.gl.BaseRenderer
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import com.nameless.efb.rendering.gl.Theme
import com.nameless.efb.rendering.gl.buildArcStrip
import com.nameless.efb.rendering.gl.buildQuad
import com.nameless.efb.ui.steam.GaugeState

/**
 * OpenGL ES 3.0 renderer for the 14-instrument steam gauge panel.
 *
 * Extends [BaseRenderer] which handles GL init, [ShaderManager], [GaugeTextureAtlas],
 * and the 4 ms frame-budget watchdog.
 *
 * Callers update [gaugeState] from any thread; the render thread reads it atomically
 * on each frame. No locking needed — worst case is one stale frame.
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

    /** Latest gauge state — written from the ViewModel coroutine, read on GL thread. */
    @Volatile var gaugeState: GaugeState = GaugeState()

    // Panel layout (initialised once surface dimensions are known).
    private var layout: GaugePanelLayout? = null

    // Shared VAOs / buffers (created once in onGlReady).
    private lateinit var quadVao: GlVao
    private lateinit var quadBuf: GlBuffer

    // Arc VAOs for ASI coloured bands (white/green/yellow/red).
    private lateinit var asiWhiteArcVao: GlVao
    private lateinit var asiGreenArcVao: GlVao
    private lateinit var asiYellowArcVao: GlVao

    // Shader program IDs (resolved via ShaderManager on first use).
    private var needleProg: Int = 0
    private var arcProg: Int = 0
    private var gaugeProg: Int = 0
    private var aiHorizonProg: Int = 0
    private var egtBarProg: Int = 0

    // ── BaseRenderer template methods ─────────────────────────────────────────

    override fun onGlReady() {
        // Compile shader programs.
        needleProg    = shaderManager.getProgram("shaders/gauges/needle.vert",     "shaders/gauges/gauge_base.frag")
        arcProg       = shaderManager.getProgram("shaders/gauges/arc_segment.vert","shaders/gauges/arc_segment.frag")
        gaugeProg     = shaderManager.getProgram("shaders/gauges/gauge_base.vert", "shaders/gauges/gauge_base.frag")
        aiHorizonProg = shaderManager.getProgram("shaders/gauges/ai_horizon.vert", "shaders/gauges/ai_horizon.frag")
        egtBarProg    = shaderManager.getProgram("shaders/gauges/egt_bar.vert",    "shaders/gauges/egt_bar.frag")

        // Unit quad shared by needle, gauge base, and EGT bar.
        quadBuf = GlBuffer()
        quadBuf.upload(buildQuad())
        quadVao = GlVao()
        quadVao.bind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf.id)
        // Layout: x, y, u, v — stride 4 floats × 4 bytes = 16 bytes.
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        quadVao.unbind()

        // ASI arc bands (built once; geometry never changes).
        asiWhiteArcVao  = buildArcVao(0.82f, 0.92f, angFor(0f),   angFor(profile.vsoKts))
        asiGreenArcVao  = buildArcVao(0.82f, 0.92f, angFor(profile.vs1Kts), angFor(profile.vnoKts))
        asiYellowArcVao = buildArcVao(0.82f, 0.92f, angFor(profile.vnoKts), angFor(profile.vneKts))
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10, width: Int, height: Int) {
        super.onSurfaceChanged(gl, width, height)
        layout = GaugePanelLayout(width, height)
    }

    override fun drawFrame() {
        val state  = gaugeState
        val lay    = layout ?: return

        drawAsi(state, lay)
        drawAi(state, lay)
        drawAlt(state, lay)
        drawVsi(state, lay)
        drawEgt(state, lay)

        // Restore full viewport after per-gauge rendering.
        val w = lay.asi.width * 3
        val h = lay.asi.height + lay.tc.height + lay.rpm.height + lay.ff.height
        GLES30.glViewport(0, 0, w, h)
    }

    // ── Per-gauge draw helpers ─────────────────────────────────────────────────

    private fun drawAsi(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.asi)
        // Draw green arc (Vs1 → Vno).
        GLES30.glUseProgram(arcProg)
        setUniformColor(arcProg, 0f, 0.8f, 0f, 1f)
        asiGreenArcVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, ASI_ARC_VERTICES)
        // Draw yellow arc (Vno → Vne).
        setUniformColor(arcProg, 1f, 1f, 0f, 1f)
        asiYellowArcVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, ASI_ARC_VERTICES)
        // Draw white arc (0 → Vso).
        setUniformColor(arcProg, 1f, 1f, 1f, 1f)
        asiWhiteArcVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, ASI_ARC_VERTICES)
        // Needle.
        val angle = needleAngle(state.airspeedKts, 0f, 200f, -150f, 150f)
        drawNeedle(angle)
    }

    private fun drawAi(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.ai)
        GLES30.glUseProgram(aiHorizonProg)
        val pitchLoc = GLES30.glGetUniformLocation(aiHorizonProg, "u_pitch_deg")
        val bankLoc  = GLES30.glGetUniformLocation(aiHorizonProg, "u_bank_rad")
        GLES30.glUniform1f(pitchLoc, state.pitchDeg)
        GLES30.glUniform1f(bankLoc,  Math.toRadians(state.rollDeg.toDouble()).toFloat())
        quadVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawAlt(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.alt)
        val angle100  = needleAngle((state.altFt % 1000f) / 10f,   0f, 100f, -150f, 210f)
        val angle1000 = needleAngle((state.altFt % 10_000f) / 100f, 0f, 100f, -150f, 210f)
        val angle10k  = needleAngle(state.altFt / 1000f,            0f, 50f,  -150f, 210f)
        // Draw three needles (100s thinnest; 10k shortest).
        drawNeedle(angle100)
        drawNeedle(angle1000)
        drawNeedle(angle10k)
    }

    private fun drawVsi(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.vsi)
        val angle = needleAngle(state.displayedVsiFpm, -2000f, 2000f, -150f, 150f)
        drawNeedle(angle)
    }

    private fun drawEgt(state: GaugeState, lay: GaugePanelLayout) {
        applyViewport(lay.egt)
        GLES30.glUseProgram(egtBarProg)

        val egtMin = 200f
        val egtMax = 1000f

        // Find peak EGT cylinder index.
        var peakIdx = 0
        for (i in 1 until state.egtDegC.size) {
            if (state.egtDegC[i] > state.egtDegC[peakIdx]) peakIdx = i
        }

        val isPeakLoc    = GLES30.glGetUniformLocation(egtBarProg, "u_is_peak")
        val fillLoc      = GLES30.glGetUniformLocation(egtBarProg, "u_fill_fraction")
        val originLoc    = GLES30.glGetUniformLocation(egtBarProg, "u_bar_origin")
        val sizeLoc      = GLES30.glGetUniformLocation(egtBarProg, "u_bar_size")

        val barW   = 0.25f
        val barH   = 1.6f
        val stride = 0.33f

        for (i in state.egtDegC.indices) {
            val fill = ((state.egtDegC[i] - egtMin) / (egtMax - egtMin)).coerceIn(0f, 1f)
            val ox = -0.83f + i * stride
            GLES30.glUniform1f(fillLoc,   fill)
            GLES30.glUniform2f(originLoc, ox, -0.8f)
            GLES30.glUniform2f(sizeLoc,   barW, barH)
            GLES30.glUniform1f(isPeakLoc, if (i == peakIdx) 1f else 0f)
            quadVao.bind()
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    // ── Low-level helpers ─────────────────────────────────────────────────────

    private fun drawNeedle(angleRad: Float) {
        GLES30.glUseProgram(needleProg)
        val angleLoc = GLES30.glGetUniformLocation(needleProg, "u_needle_angle")
        val pivotLoc = GLES30.glGetUniformLocation(needleProg, "u_pivot")
        GLES30.glUniform1f(angleLoc, angleRad)
        GLES30.glUniform2f(pivotLoc, 0f, 0f)
        quadVao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setUniformColor(prog: Int, r: Float, g: Float, b: Float, a: Float) {
        val loc = GLES30.glGetUniformLocation(prog, "u_color")
        GLES30.glUniform4f(loc, r, g, b, a)
    }

    /** Returns a GlVao containing the arc band triangle strip. */
    private fun buildArcVao(innerR: Float, outerR: Float, startDeg: Float, endDeg: Float): GlVao {
        val data = buildArcStrip(innerR, outerR, startDeg, endDeg)
        val buf  = GlBuffer()
        buf.upload(data)
        val vao = GlVao()
        vao.bind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf.id)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        vao.unbind()
        return vao
    }

    /** Maps an ASI knot value to the arc degree position for `buildArcStrip`. */
    private fun angFor(kts: Float): Float =
        needleAngleDeg(kts, 0f, 200f, -150f, 150f)

    /** Degree version of needleAngle (for arc geometry, not needle uniforms). */
    private fun needleAngleDeg(v: Float, min: Float, max: Float, s: Float, e: Float): Float {
        val t = ((v - min) / (max - min)).coerceIn(0f, 1f)
        return s + t * (e - s)
    }

    companion object {
        // Arc VAO vertex count for 32-segment arc: (32 + 1) * 2 = 66
        private const val ASI_ARC_VERTICES = 66
    }
}
