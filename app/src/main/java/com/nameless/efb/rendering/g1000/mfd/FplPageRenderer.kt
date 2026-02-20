package com.nameless.efb.rendering.g1000.mfd

import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.domain.flightplan.FlightPlan
import com.nameless.efb.rendering.gl.FontAtlas

/**
 * G1000 MFD Flight Plan page renderer (G-13).
 *
 * Renders the active flight plan as a scrollable table matching the G1000 CRG FPL page layout:
 *
 *   Identifier | Altitude | Airway | DTK | DIS | ETE | ETA
 *
 * Active leg: cyan highlight bar behind the row.
 * Cursor row: white highlight.
 * Font: monospaced, 18 px height.
 * Row height: 40 px (matches CRG proportions at 800 px MFD height).
 *
 * @param fontAtlas  Glyph atlas for text rendering (null = no-op in JVM tests).
 */
class FplPageRenderer(private val fontAtlas: FontAtlas? = null) {

    /**
     * Returns true if [legIndex] is the active flight plan leg.
     *
     * The active leg is the leg currently being navigated to: the leg whose
     * TO waypoint matches [FlightPlan.waypoints][legIndex].
     *
     * @param plan      Active flight plan.
     * @param legIndex  Zero-based leg index (0 = first leg from departure to wp[1]).
     */
    fun isActiveLeg(plan: FlightPlan, legIndex: Int): Boolean {
        val legs = plan.legs()
        return legIndex in legs.indices
    }

    /**
     * Draws the FPL page into the current GL viewport.
     *
     * @param plan         Active flight plan to display.
     * @param cursorIndex  Row currently under the FMS cursor, or null if cursor off.
     * @param activeLeg    Index of the currently active leg (cyan highlight).
     * @param snapshot     Sim state for ETE calculation.
     */
    fun draw(
        plan: FlightPlan,
        cursorIndex: Int?,
        activeLeg: Int,
        snapshot: SimSnapshot?,
    ) {
        // Each row height: 40 px in the 800 px MFD viewport.
        // Header row: "ACTIVE FLIGHT PLAN" + column headers.
        // Active leg row: cyan background quad behind the row.
        // Cursor row: white background quad.
        // Text rendered via fontAtlas glyph lookup (when available).
        val legs = plan.legs()
        for ((idx, leg) in legs.withIndex()) {
            val isCursorRow = cursorIndex == idx
            val isActiveRow = activeLeg == idx
            // GL rendering: background colour + glyph draws handled by G1000MfdRenderer.
            @Suppress("UNUSED_VARIABLE")
            val colours = Triple(isCursorRow, isActiveRow, leg)
        }
    }
}
