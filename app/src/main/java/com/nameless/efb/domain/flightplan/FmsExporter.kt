package com.nameless.efb.domain.flightplan

/**
 * Exports a [FlightPlan] to X-Plane FMS v11 format (FP-08).
 *
 * X-Plane FMS v11 row format: `TYPE IDENT REGION ALT_FT LAT LON`
 *
 * Type codes:
 *   1  = Airport
 *   2  = NDB
 *   3  = VOR / VOR-DME
 *   8  = ILS
 *   11 = Fix / intersection
 *   28 = User-defined waypoint
 */
object FmsExporter {

    fun toFmsV11(plan: FlightPlan): String = buildString {
        appendLine("I")
        appendLine("1100 Version")
        appendLine("CYCLE 2401")
        appendLine("ADEP ${plan.departure?.icao ?: "ZZZZ"}")
        appendLine("ADES ${plan.destination?.icao ?: "ZZZZ"}")
        appendLine("NUMENR ${plan.waypoints.size}")
        for (wp in plan.waypoints) {
            val lat = "%+011.6f".format(wp.latLon.latitude)
            val lon = "%+012.6f".format(wp.latLon.longitude)
            appendLine("${wp.fmsTypeCode()} ${wp.identifier} ZA ${plan.cruiseAltitudeFt} $lat $lon")
        }
    }
}
