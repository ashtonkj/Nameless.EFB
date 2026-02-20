package com.nameless.efb.rendering.g1000.mfd

import android.opengl.GLES30
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.rendering.gauge.GlViewport
import com.nameless.efb.rendering.gauge.applyViewport
import com.nameless.efb.rendering.gl.GlBuffer
import com.nameless.efb.rendering.gl.GlVao
import kotlin.math.abs

/**
 * Lean-assist logic for EGT (Exhaust Gas Temperature) monitoring (G-12).
 *
 * Tracks the peak EGT for each cylinder during a lean-of-peak operation
 * and identifies which cylinder reached peak first.
 */
class EgtLeanAssist {

    private val peakEgt = FloatArray(6) { 0f }
    private var leanAssistActive = false

    /** Activates lean-assist mode; resets all stored peaks. */
    fun activate() {
        leanAssistActive = true
        peakEgt.fill(0f)
    }

    /** Deactivates lean-assist mode. */
    fun deactivate() { leanAssistActive = false }

    /**
     * Updates peak EGT tracking for each cylinder.
     *
     * Only updates when lean-assist is active; silently ignored otherwise.
     *
     * @param egt  Array of current EGT values in degrees Celsius (index 0–5).
     */
    fun update(egt: FloatArray) {
        if (!leanAssistActive) return
        for (i in egt.indices.take(6)) {
            if (egt[i] > peakEgt[i]) peakEgt[i] = egt[i]
        }
    }

    /**
     * Returns the index (0–5) of the cylinder that first reached its peak EGT.
     *
     * Returns -1 if no peaks have been recorded yet.
     */
    fun getPeakCylinder(): Int {
        val max = peakEgt.maxOrNull() ?: return -1
        return if (max == 0f) -1 else peakEgt.indexOfFirst { it == max }
    }

    /**
     * Returns true when cylinder [index] is within 5 °C of its recorded peak.
     *
     * Used to highlight the peak cylinder in the EGT bar graph.
     *
     * @param index    Cylinder index (0–5).
     * @param current  Current EGT array in degrees Celsius.
     */
    fun isCylinderAtPeak(index: Int, current: FloatArray): Boolean =
        peakEgt[index] > 0f && abs(current[index] - peakEgt[index]) < 5f
}

/**
 * G1000 MFD Engine Indication System strip renderer (G-12).
 *
 * Renders the fixed 200×800 px EIS column that is always visible on the left side of
 * the MFD, regardless of the active page.  Contents from top to bottom:
 *
 *   RPM (digital + bar graph)
 *   MAP (manifold pressure, digital + bar)
 *   Fuel flow (LPH default for SA)
 *   Oil temperature (°C)
 *   Oil pressure (PSI)
 *   EGT/CHT cylinders 1–6 (bar graph)
 *   Fuel L / Fuel R (kg)
 *   Bus voltage (V)
 *   Battery amps (A)
 *
 * @param fuelFlowUnit  Unit for fuel-flow display (LPH default for South Africa).
 * @param fuelType      Fuel type for kg/s → LPH conversion (AVGAS default).
 */
class EisRenderer(
    private var fuelFlowUnit: FuelFlowUnit = FuelFlowUnit.LPH,
    private var fuelType: FuelType = FuelType.AVGAS,
) {

    private val leanAssist = EgtLeanAssist()

    // ── GL resources (initialised on GL thread) ───────────────────────────────

    private var colorProg = 0
    private var colorLoc  = 0

    /** Initialises GL shader program for EIS rendering. Must be called on the GL thread. */
    fun onGlReady(colorProgram: Int, colorUniformLoc: Int) {
        colorProg = colorProgram
        colorLoc  = colorUniformLoc
    }

    /** Draws the full EIS strip into [viewport]. Must be called on the GL thread. */
    fun draw(snapshot: SimSnapshot?, viewport: GlViewport) {
        applyViewport(viewport)
        GLES30.glUseProgram(colorProg)

        // Strip background — very dark grey.
        GLES30.glUniform4f(colorLoc, 0.04f, 0.04f, 0.04f, 1f)
        drawFilledQuad(-1f, -1f, 2f, 2f)

        if (snapshot == null) return

        // Update lean-assist tracking.
        leanAssist.update(snapshot.egtDegc)

        // Fuel flow conversion.
        val lph = kgSecToLph(snapshot.fuelFlowKgSec, fuelType)

        // EGT bar graph (6 cylinders, bottom half of strip).
        drawEgtBars(snapshot.egtDegc, viewport)

        // RPM bar.
        val rpmNorm = (snapshot.rpm / 2700f).coerceIn(0f, 1f)
        drawBarGraph(-0.9f, 0.70f, 1.8f, 0.06f, rpmNorm, 0f, 1f, 0f)

        // MAP bar (0–30 inHg range).
        val mapNorm = (snapshot.mapInhg / 30f).coerceIn(0f, 1f)
        drawBarGraph(-0.9f, 0.55f, 1.8f, 0.06f, mapNorm, 0f, 1f, 0f)
    }

    private fun drawEgtBars(egtDegc: FloatArray, viewport: GlViewport) {
        val cylCount = egtDegc.size.coerceAtMost(6)
        val barW = 1.8f / (cylCount * 2f)
        val maxEgt = 900f  // typical max EGT for colour scaling
        val peakCyl = leanAssist.getPeakCylinder()

        for (i in 0 until cylCount) {
            val norm = (egtDegc[i] / maxEgt).coerceIn(0f, 1f)
            val xLeft = -0.9f + i * (barW * 2f)
            val isPeak = i == peakCyl
            val r = if (isPeak) 1f else 0f
            val g = if (isPeak) 0.5f else 1f
            val b = 0f
            drawBarGraph(xLeft, -0.9f, barW, norm * 0.5f, 1f, r, g, b)
        }
    }

    /** Draw a proportional bar graph segment in clip space. */
    private fun drawBarGraph(x: Float, y: Float, w: Float, h: Float, fill: Float, r: Float, g: Float, b: Float) {
        // Background (dark grey).
        GLES30.glUniform4f(colorLoc, 0.15f, 0.15f, 0.15f, 1f)
        drawFilledQuad(x, y, w, h)
        // Filled portion.
        GLES30.glUniform4f(colorLoc, r, g, b, 1f)
        drawFilledQuad(x, y, w, h * fill)
    }

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

    /** Changes the fuel-flow display unit. Can be called from the UI thread. */
    fun setFuelFlowUnit(unit: FuelFlowUnit) { fuelFlowUnit = unit }
}
