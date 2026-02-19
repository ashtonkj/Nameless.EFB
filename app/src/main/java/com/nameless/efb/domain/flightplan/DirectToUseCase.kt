package com.nameless.efb.domain.flightplan

import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.domain.nav.GreatCircle
import com.nameless.efb.domain.nav.LatLon

data class DirectToResult(
    val bearingDeg: Double,
    val distNm: Double,
    val eteMin: Int,
)

/**
 * Minimal command-send abstraction so [DirectToUseCase] and [SimFmsSync]
 * are testable without a live [DataSourceManager].
 */
fun interface CommandSender {
    suspend fun send(commandJson: String): Boolean
}

/**
 * Direct-To routing (FP-01).
 *
 * Computes the bearing and distance from the current ownship position to
 * [target], sends a JSON command to the sim, and returns the routing result.
 * Completes within one network round-trip (<< 500ms requirement).
 */
class DirectToUseCase(private val commandSender: CommandSender) {

    suspend fun execute(target: Waypoint, snapshot: SimSnapshot): DirectToResult {
        val ownship = LatLon(snapshot.latitude, snapshot.longitude)
        val bearing = GreatCircle.initialBearingDeg(ownship, target.latLon)
        val distNm  = GreatCircle.distanceNm(ownship, target.latLon)
        val eteMin  = if (snapshot.tasKts > 0f) (distNm / snapshot.tasKts * 60.0).toInt() else 0

        val json = """{"cmd":"direct_to","id":"${target.identifier}","lat":${target.latLon.latitude},"lon":${target.latLon.longitude}}"""
        commandSender.send(json)

        return DirectToResult(bearing, distNm, eteMin)
    }
}
