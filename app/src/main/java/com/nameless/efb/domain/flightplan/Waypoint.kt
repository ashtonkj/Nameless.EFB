package com.nameless.efb.domain.flightplan

import com.nameless.efb.domain.nav.LatLon

/**
 * Discriminated union of all waypoint types that can appear in a flight plan.
 *
 * Every subtype exposes [latLon] and [identifier] uniformly so that renderers
 * and navigation calculations can operate on the sealed class without
 * exhaustive when-expressions everywhere.
 */
sealed class Waypoint {
    abstract val latLon: LatLon
    abstract val identifier: String

    data class Airport(
        val icao: String,
        override val latLon: LatLon,
    ) : Waypoint() {
        override val identifier = icao
    }

    data class Navaid(
        override val identifier: String,
        val type: String,   // "VOR","NDB","ILS","DME","FIX","VOR-DME","RNAV"
        override val latLon: LatLon,
    ) : Waypoint()

    data class Fix(
        override val identifier: String,
        override val latLon: LatLon,
    ) : Waypoint()

    data class UserPoint(
        val name: String,
        override val latLon: LatLon,
    ) : Waypoint() {
        override val identifier = name
    }

    /** A named fix that was entered via an airway designator (e.g., N871 TEBSA). */
    data class AirwayEntry(
        val fix: Fix,
        val airway: String,
    ) : Waypoint() {
        override val latLon     = fix.latLon
        override val identifier = fix.identifier
    }
}

/** X-Plane FMS v11 entry type code for this waypoint. */
fun Waypoint.fmsTypeCode(): Int = when (this) {
    is Waypoint.Airport    -> 1
    is Waypoint.Navaid     -> when (type) {
        "NDB"              -> 2
        "VOR", "VOR-DME"   -> 3
        "ILS"              -> 8
        else               -> 11
    }
    is Waypoint.Fix        -> 11
    is Waypoint.AirwayEntry -> 11
    is Waypoint.UserPoint  -> 28
}
