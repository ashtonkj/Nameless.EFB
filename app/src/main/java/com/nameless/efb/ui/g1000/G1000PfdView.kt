package com.nameless.efb.ui.g1000

import android.content.Context
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.rendering.g1000.BaroUnit
import com.nameless.efb.rendering.g1000.G1000PfdRenderer
import com.nameless.efb.rendering.g1000.HsiMode
import com.nameless.efb.rendering.g1000.PfdInsetMap
import com.nameless.efb.rendering.g1000.WindMode
import com.nameless.efb.rendering.gl.BaseGlSurfaceView
import com.nameless.efb.rendering.gl.Theme
import com.nameless.efb.rendering.map.MapRenderer
import kotlinx.coroutines.flow.StateFlow

/**
 * [android.opengl.GLSurfaceView] host for the G1000 Primary Flight Display.
 *
 * Wires up:
 *  - [G1000PfdRenderer] (OpenGL ES 3.0 renderer)
 *  - [PfdInsetMap] helper (uses shared [MapRenderer] when available)
 *  - Softkey state: HSI mode, baro unit, wind mode, inset range
 *
 * Thread safety: all softkey setters are @Volatile on the renderer.
 *
 * @param context     Android context.
 * @param simData     Live sim state flow consumed by the renderer.
 * @param mapRenderer Shared map renderer from the MFD (or null if MFD not active).
 * @param theme       Initial rendering theme.
 */
class G1000PfdView(
    context: Context,
    simData: StateFlow<SimSnapshot?>,
    mapRenderer: MapRenderer? = null,
    theme: Theme = Theme.DAY,
) : BaseGlSurfaceView(context) {

    private val renderer: G1000PfdRenderer

    init {
        val insetMap = mapRenderer?.let { PfdInsetMap(it) }
        renderer = G1000PfdRenderer(
            assets    = context.assets,
            simData   = simData,
            insetMap  = insetMap,
            theme     = theme,
        )
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    // ── Softkey API (called from UI thread) ──────────────────────────────────

    /** Toggle between 360° compass rose and 140° arc HSI mode. */
    fun setHsiMode(mode: HsiMode) {
        renderer.hsiMode = mode
    }

    /** Change the barometric pressure unit for the Kollsman window. */
    fun setBaroUnit(unit: BaroUnit) {
        renderer.baroUnit = unit
    }

    /** Change the wind data display mode. */
    fun setWindMode(mode: WindMode) {
        renderer.windMode = mode
    }

    /**
     * Set the inset map display range.
     *
     * [rangeNm] is snapped to the nearest selectable value:
     * 1, 2, 3, 5, 7, 10, 15, or 20 nm.
     */
    fun setInsetRange(rangeNm: Float) {
        renderer.insetRangeNm = PfdInsetMap.snapRange(rangeNm)
    }

    /** Apply a theme change on the GL thread. */
    fun applyTheme(newTheme: Theme) {
        queueEvent { renderer.applyTheme(newTheme) }
    }
}
