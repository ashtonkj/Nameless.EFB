package com.nameless.efb.domain.flightplan

import com.nameless.efb.domain.nav.GreatCircle

/**
 * A single inter-waypoint leg with pre-computed navigation values.
 */
data class Leg(
    val from: Waypoint,
    val to: Waypoint,
    val distanceNm: Double,
    val bearingDeg: Double,
)

/**
 * In-memory flight plan.
 *
 * Persistence is handled by [FlightPlanEntity] + [FlightPlanWaypointEntity]; this
 * is the domain model used for all in-flight calculations and rendering.
 *
 * [waypoints] is an ordered list that includes the departure and destination
 * airports as the first and last elements respectively.
 */
data class FlightPlan(
    val id: Long = 0,
    val name: String = "",
    val departure: Waypoint.Airport? = null,
    val destination: Waypoint.Airport? = null,
    val waypoints: List<Waypoint> = emptyList(),
    val aircraftProfileId: Long? = null,
    val cruiseAltitudeFt: Int = 5000,
    val notes: String = "",
) {
    /** Returns one [Leg] for each consecutive waypoint pair. */
    fun legs(): List<Leg> = waypoints.zipWithNext().map { (from, to) ->
        Leg(
            from        = from,
            to          = to,
            distanceNm  = GreatCircle.distanceNm(from.latLon, to.latLon),
            bearingDeg  = GreatCircle.initialBearingDeg(from.latLon, to.latLon),
        )
    }

    /** Returns a copy with [waypoint] inserted at [index]. */
    fun insertWaypoint(index: Int, waypoint: Waypoint): FlightPlan =
        copy(waypoints = waypoints.toMutableList().apply { add(index, waypoint) })

    /** Returns a copy with the waypoint at [index] removed. */
    fun removeWaypoint(index: Int): FlightPlan =
        copy(waypoints = waypoints.filterIndexed { i, _ -> i != index })

    val totalDistanceNm: Double get() = legs().sumOf { it.distanceNm }
}
