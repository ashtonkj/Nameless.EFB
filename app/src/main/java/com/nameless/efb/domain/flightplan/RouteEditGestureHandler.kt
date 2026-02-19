package com.nameless.efb.domain.flightplan

import com.nameless.efb.data.db.EfbDatabase
import com.nameless.efb.domain.nav.GreatCircle
import com.nameless.efb.domain.nav.LatLon
import com.nameless.efb.domain.nav.SpatialQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles touch gestures on the active route line for drag-and-drop editing (FP-07).
 *
 * - Drag leg midpoint: insert nearest named fix within 5nm at snap radius
 * - Drag waypoint: move to nearest fix within 5nm
 * - Supports 20-operation undo stack
 *
 * All DB queries are launched on [Dispatchers.IO]; [onRouteChanged] is called
 * on the calling thread after each modification.
 */
class RouteEditGestureHandler(
    private val navDb: EfbDatabase,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val onRouteChanged: (FlightPlan) -> Unit,
) {
    private val undoStack = ArrayDeque<FlightPlan>(20)
    private var currentPlan: FlightPlan = FlightPlan()

    fun setCurrentPlan(plan: FlightPlan) {
        currentPlan = plan
    }

    /** Insert a fix at [legIndex]+1, snapping to the nearest fix within 5nm of [touchLatLon]. */
    fun onLegMidpointDrag(legIndex: Int, touchLatLon: LatLon) {
        scope.launch {
            val nearest = findNearestFix(touchLatLon, radiusNm = 5.0) ?: return@launch
            pushUndo()
            val updated = currentPlan.insertWaypoint(legIndex + 1, nearest)
            currentPlan = updated
            onRouteChanged(updated)
        }
    }

    /** Move the waypoint at [waypointIndex] to the nearest fix within 5nm of [touchLatLon]. */
    fun onWaypointDrag(waypointIndex: Int, touchLatLon: LatLon) {
        scope.launch {
            val nearest = findNearestFix(touchLatLon, radiusNm = 5.0)
                ?: Waypoint.UserPoint("USR", touchLatLon)
            pushUndo()
            val updated = currentPlan
                .removeWaypoint(waypointIndex)
                .insertWaypoint(waypointIndex, nearest)
            currentPlan = updated
            onRouteChanged(updated)
        }
    }

    /** Reverts to the previous route state (up to 20 operations). */
    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        currentPlan = previous
        onRouteChanged(previous)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun pushUndo() {
        undoStack.addLast(currentPlan)
        if (undoStack.size > 20) undoStack.removeFirst()
    }

    private suspend fun findNearestFix(center: LatLon, radiusNm: Double): Waypoint? {
        val navaids = navDb.navaidDao().nearbyRaw(SpatialQuery.nearbyNavaids(center, radiusNm))
        return navaids
            .minByOrNull { GreatCircle.distanceNm(center, LatLon(it.latitude, it.longitude)) }
            ?.let { Waypoint.Navaid(it.identifier, it.type, LatLon(it.latitude, it.longitude)) }
    }
}
