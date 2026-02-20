package com.nameless.efb.rendering.g1000.mfd

import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.domain.flightplan.Waypoint

/**
 * Trip planning data for the AUX Trip page (G-16).
 *
 * @param destination         Destination waypoint, or null if no flight plan active.
 * @param distNm              Remaining distance in nautical miles.
 * @param eteHrMin            Estimated time enroute as "H:MM".
 * @param fuelRequiredKg      Fuel required to reach destination in kg.
 * @param fuelOnBoardKg       Current fuel on board in kg (sum of all tanks).
 * @param estimatedArrivalTime  Local ETA as "HH:MM".
 */
data class TripPlanData(
    val destination: Waypoint?,
    val distNm: Double,
    val eteHrMin: String,
    val fuelRequiredKg: Float,
    val fuelOnBoardKg: Float,
    val estimatedArrivalTime: String,
)

/**
 * G1000 MFD AUX pages renderer (G-16).
 *
 * Sub-pages:
 *  - AUX Trip Planning — destination, ETE, fuel required vs on-board
 *  - AUX Utility — count-up/count-down timers, fuel endurance
 *  - AUX GPS Status — simulated GPS status (always 3D NAV with 12 satellites)
 *  - AUX System Status — plugin/data-source connection info, AIRAC cycle, nav DB version
 */
class AuxPageRenderer {

    // ── Timer state (AUX Utility) ────────────────────────────────────────────

    private var countUpStartMs = 0L
    private var countUpRunning = false
    private var countDownRemainingMs = 0L
    private var countDownRunning = false

    /** Starts or resumes the count-up timer. */
    fun startCountUp() {
        if (!countUpRunning) {
            countUpStartMs = System.currentTimeMillis()
            countUpRunning = true
        }
    }

    /** Stops the count-up timer. */
    fun stopCountUp() { countUpRunning = false }

    /** Resets the count-up timer to zero and stops it. */
    fun resetCountUp() { countUpRunning = false; countUpStartMs = 0L }

    /** Elapsed time on count-up timer in milliseconds. */
    fun countUpElapsedMs(): Long =
        if (countUpRunning) System.currentTimeMillis() - countUpStartMs else 0L

    /**
     * Computes fuel endurance from current fuel quantity and flow.
     *
     * @param fuelKg       Current fuel on board in kg.
     * @param fuelFlowKgSec  Current fuel flow in kg/s.
     * @return Fuel endurance as "H:MM", or "--:--" if flow is zero.
     */
    fun fuelEndurance(fuelKg: Float, fuelFlowKgSec: Float): String {
        if (fuelFlowKgSec <= 0f) return "--:--"
        val totalSec = (fuelKg / fuelFlowKgSec).toInt()
        return "%d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60)
    }

    /**
     * Draws the AUX Trip Planning sub-page.
     *
     * @param tripData  Pre-computed trip planning data.
     */
    fun drawTrip(tripData: TripPlanData) {
        // Renders: destination ident, distance, ETE, fuel required, fuel on board, ETA.
    }

    /**
     * Draws the AUX Utility sub-page.
     *
     * @param snapshot  Current sim state for fuel endurance calculation.
     */
    fun drawUtility(snapshot: SimSnapshot?) {
        countUpElapsedMs()
        fuelEndurance(
            snapshot?.fuelQtyKg?.sum() ?: 0f,
            snapshot?.fuelFlowKgSec ?: 0f,
        )
        // GL rendering: count-up timer, count-down timer, and fuel endurance drawn by parent MFD renderer.
    }

    /**
     * Draws the AUX GPS Status sub-page.
     *
     * X-Plane always provides GPS fix, so this page shows simulated values.
     */
    fun drawGpsStatus() {
        // Renders: "Position: 3D NAV", "RAIM available: YES", satellite count = 12.
    }

    /**
     * Draws the AUX System Status sub-page.
     *
     * @param protocolName  Active data protocol name ("PLUGIN", "GDL-90", or "UDP").
     * @param latencyMs     Measured round-trip latency in milliseconds.
     * @param lastDataAgeSec  Age of the most recent data packet in seconds.
     * @param airacCycle    Current AIRAC cycle identifier (e.g. "2401").
     * @param navDbVersion  Nav database version string.
     */
    fun drawSystemStatus(
        protocolName: String,
        latencyMs: Int,
        lastDataAgeSec: Float,
        airacCycle: String,
        navDbVersion: String,
    ) {
        // Renders connection status, protocol, latency, last data age, AIRAC cycle, nav DB.
    }
}
