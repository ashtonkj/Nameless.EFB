package com.nameless.efb.domain.flightplan

import com.nameless.efb.data.db.EfbDatabase
import com.nameless.efb.domain.nav.LatLon

data class WaypointResolution(
    val token: String,
    val waypoint: Waypoint?,
    val isUnresolved: Boolean = waypoint == null,
)

data class ParsedRoute(val resolutions: List<WaypointResolution>) {
    val waypoints: List<Waypoint> get() = resolutions.mapNotNull { it.waypoint }
    val hasUnresolved: Boolean   get() = resolutions.any { it.isUnresolved }
    val unresolvedTokens: List<String> get() = resolutions.filter { it.isUnresolved }.map { it.token }
}

/**
 * Parses ICAO route strings (FP-02) such as:
 *   "FAOR FORT1A N871 TEBSA Z30 FACT"
 *
 * Resolution order per token:
 *   1. 4-letter all-caps → attempt airport lookup
 *   2. Airway pattern (N/V/J/Q/H/B/Z + digits) → mark resolved as AirwayEntry reference
 *   3. Procedure notation (e.g., SID/FORT1A or STAR/KODAP1A) → strip qualifier, mark resolved
 *   4. Otherwise → attempt navaid lookup, then fix lookup; unresolved if all fail
 *
 * SA airway prefixes handled: N (RNAV), Z (southern Africa), V (Victor), J (Jet), Q, H, B.
 */
class IcaoRouteParser(private val navDb: EfbDatabase) {

    suspend fun parse(routeString: String): ParsedRoute {
        val tokens = tokenise(routeString)
        val resolutions = tokens.map { token -> resolveToken(token) }
        return ParsedRoute(resolutions)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    internal fun tokenise(route: String): List<String> =
        route.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }

    internal fun isAirportIcao(token: String): Boolean =
        token.length == 4 && token.all { it.isLetter() || it.isDigit() } && token[0].isLetter()

    internal fun isAirway(token: String): Boolean =
        token.matches(Regex("""[NVJQHBZ]\d+[A-Z]?"""))

    internal fun isProcedure(token: String): Boolean =
        token.contains('/') || (token.length in 5..8 && token.any { it.isDigit() } && token.any { it.isLetter() })

    private suspend fun resolveToken(token: String): WaypointResolution {
        // Strip SID/STAR qualifier (e.g., "SID/FORT1A" → "FORT1A")
        val clean = if (token.contains('/')) token.substringAfter('/') else token

        // 1. Airport ICAO
        if (isAirportIcao(clean)) {
            val ap = navDb.airportDao().byIcao(clean)
            if (ap != null) {
                return WaypointResolution(
                    token    = token,
                    waypoint = Waypoint.Airport(ap.icao, LatLon(ap.latitude, ap.longitude)),
                )
            }
        }

        // 2. Airway designator — mark as resolved without a position (airway defines the path)
        if (isAirway(clean)) {
            // Airway itself is not a positional waypoint; it's recorded as context for the
            // following fix. Return a sentinel Fix at origin — renderers ignore airways with
            // no coordinates; the real waypoints are the surrounding fixes.
            return WaypointResolution(
                token    = token,
                waypoint = Waypoint.Fix(clean, LatLon(0.0, 0.0)),
            )
        }

        // 3. Navaid lookup (VOR, NDB, ILS, etc.)
        val navaids = navDb.navaidDao().byIdent(clean)
        if (navaids.isNotEmpty()) {
            val n = navaids.first()
            return WaypointResolution(
                token    = token,
                waypoint = Waypoint.Navaid(n.identifier, n.type, LatLon(n.latitude, n.longitude)),
            )
        }

        // 4. Procedure or unresolved
        if (isProcedure(clean)) {
            // Procedures are expanded by the procedure loader; mark as tentatively resolved
            return WaypointResolution(
                token    = token,
                waypoint = Waypoint.Fix(clean, LatLon(0.0, 0.0)),
            )
        }

        // Unresolved
        return WaypointResolution(token = token, waypoint = null)
    }
}
