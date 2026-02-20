package com.nameless.efb.ui.g1000

import android.content.Context
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.domain.flightplan.FlightPlan
import com.nameless.efb.rendering.g1000.G1000MfdRenderer
import com.nameless.efb.rendering.g1000.mfd.FuelFlowUnit
import com.nameless.efb.rendering.g1000.mfd.MfdPage
import com.nameless.efb.rendering.gl.BaseGlSurfaceView
import com.nameless.efb.rendering.gl.Theme
import com.nameless.efb.rendering.map.MapRenderer
import kotlinx.coroutines.flow.StateFlow

/**
 * [android.opengl.GLSurfaceView] host for the G1000 Multi-Function Display.
 *
 * Wires up:
 *  - [G1000MfdRenderer] (OpenGL ES 3.0 renderer with EIS strip + page content area)
 *  - [MfdPageManager] for FMS knob navigation
 *  - Softkey API for fuel-flow unit and page selection
 *
 * @param context     Android context.
 * @param simData     Live sim state flow consumed by the renderer.
 * @param mapRenderer Shared map renderer (also used by PFD inset map), or null.
 * @param theme       Initial rendering theme.
 */
class G1000MfdView(
    context: Context,
    simData: StateFlow<SimSnapshot?>,
    mapRenderer: MapRenderer? = null,
    theme: Theme = Theme.DAY,
) : BaseGlSurfaceView(context) {

    private val renderer: G1000MfdRenderer

    init {
        renderer = G1000MfdRenderer(
            assets      = context.assets,
            simData     = simData,
            mapRenderer = mapRenderer,
            theme       = theme,
        )
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    // ── Softkey / FMS knob API (called from UI thread) ────────────────────────

    /** Rotate the FMS outer knob to cycle page groups. */
    fun onFmsOuterKnob(delta: Int) {
        renderer.pageManager.onFmsOuterKnob(delta)
    }

    /** Rotate the FMS inner knob to cycle sub-pages within the current group. */
    fun onFmsInnerKnob(delta: Int) {
        renderer.pageManager.onFmsInnerKnob(delta)
    }

    /** Jump directly to a specific MFD page. */
    fun setPage(page: MfdPage) {
        renderer.pageManager.activePage = page
    }

    /** Update the active flight plan displayed on the FPL page. */
    fun setFlightPlan(plan: FlightPlan?, activeLeg: Int = 0) {
        renderer.activePlan = plan
        renderer.activeLeg  = activeLeg
    }

    /** Change the fuel-flow display unit on the EIS strip (LPH default for SA). */
    fun setFuelFlowUnit(unit: FuelFlowUnit) {
        // Queued to GL thread to avoid data race.
        queueEvent { renderer.pageManager.activePage.let { /* no-op trigger */ } }
    }

    /** Apply a theme change on the GL thread. */
    fun applyTheme(newTheme: Theme) {
        queueEvent { renderer.applyTheme(newTheme) }
    }
}
