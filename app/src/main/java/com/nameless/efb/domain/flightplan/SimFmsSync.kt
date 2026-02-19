package com.nameless.efb.domain.flightplan

import com.nameless.efb.domain.nav.LatLon

/** Requests the current FMS plan from the X-Plane plugin. */
fun interface FmsPlanRequester {
    suspend fun requestFmsPlan(): String?
}

/**
 * Bidirectional FMS synchronisation between the EFB and X-Plane (FP-09).
 *
 * - [pushToSim]: serialises the [FlightPlan] to JSON and sends it via [CommandSender].
 * - [pullFromSim]: requests the current FMS plan from the plugin and decodes it.
 *
 * The plugin responds to `set_fms_plan` by loading the waypoints into X-Plane's
 * built-in FMS. For `get_fms_plan`, the plugin replies with a JSON list of the
 * current FMS entries on the same UDP port.
 */
class SimFmsSync(
    private val commandSender: CommandSender,
    private val fmsPlanRequester: FmsPlanRequester? = null,
) {

    suspend fun pushToSim(plan: FlightPlan): Boolean {
        val entries = plan.waypoints.joinToString(",") { wp ->
            """{"type":${wp.fmsTypeCode()},"id":"${wp.identifier}","lat":${wp.latLon.latitude},"lon":${wp.latLon.longitude},"alt":${plan.cruiseAltitudeFt}}"""
        }
        val json = """{"cmd":"set_fms_plan","waypoints":[$entries]}"""
        return commandSender.send(json)
    }

    suspend fun pullFromSim(): FlightPlan? {
        val response = fmsPlanRequester?.requestFmsPlan() ?: return null
        return decodeFmsPlan(response)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Decodes a plugin FMS plan JSON response.
     *
     * Expected format: `{"waypoints":[{"type":1,"id":"FAOR","lat":-26.14,"lon":28.25,"alt":0}, ...]}`
     *
     * Deferred: full JSON parsing implemented when plugin protocol is extended (Plan 11+).
     */
    private fun decodeFmsPlan(json: String): FlightPlan? {
        if (!json.contains("\"waypoints\"")) return null
        // Simplified stub — returns an empty plan with the payload noted in the name.
        // Real decoding to be added alongside plugin FMS command implementation.
        return FlightPlan(name = "From Sim")
    }
}
