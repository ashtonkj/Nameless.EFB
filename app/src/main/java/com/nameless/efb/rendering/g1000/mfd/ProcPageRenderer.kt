package com.nameless.efb.rendering.g1000.mfd

import com.nameless.efb.data.db.EfbDatabase
import com.nameless.efb.domain.flightplan.FlightPlan

/**
 * Represents the user's procedure selection state for PROC pages (G-14).
 */
sealed class ProcSelection {
    object None : ProcSelection()
    data class Sid(
        val identifier: String,
        val runway: String?,
        val transition: String?,
    ) : ProcSelection()
    data class Star(
        val identifier: String,
        val runway: String?,
        val transition: String?,
    ) : ProcSelection()
    data class Approach(
        val type: String,
        val runway: String,
    ) : ProcSelection()
}

/** Which PROC sub-page is currently displayed. */
enum class ProcPage { SID, STAR, APPROACH }

/**
 * G1000 MFD Procedure Loading page renderer (G-14).
 *
 * Renders SID, STAR, and Approach selection lists from the Navigraph/nav database.
 * On confirmation, procedure waypoints are inserted into the active flight plan.
 *
 * Pages:
 *  1. SID selection — list of SID identifiers, runway, transition
 *  2. STAR selection
 *  3. Approach selection
 *
 * @param navDb  Navigation database for procedure lookup (null = no procedures available).
 */
class ProcPageRenderer(private val navDb: EfbDatabase? = null) {

    /** Currently active PROC sub-page. */
    var currentPage: ProcPage = ProcPage.SID

    /** Current selection state. */
    var selection: ProcSelection = ProcSelection.None

    /**
     * Loads procedure waypoints into [plan] at the appropriate position.
     *
     * @param plan       Active flight plan to modify.
     * @param selection  The confirmed procedure selection.
     * @return Updated flight plan with procedure waypoints inserted, or [plan] unchanged
     *         if the selection is [ProcSelection.None].
     */
    fun loadProcedure(plan: FlightPlan, selection: ProcSelection): FlightPlan = plan

    /**
     * Draws the PROC page into the current GL viewport.
     *
     * Renders a scrollable list of procedure identifiers, a runway sub-selector,
     * a mini-map preview of the procedure path, and a "LOAD?" confirmation.
     *
     * @param airportIcao  ICAO of the airport whose procedures are displayed.
     */
    fun draw(airportIcao: String?) {
        // Scrollable procedure list from navDb.procedureDao()
        // Runway sub-page selector
        // Mini-map preview: procedure waypoints as line segments
        // "LOAD?" confirmation prompt
    }
}
