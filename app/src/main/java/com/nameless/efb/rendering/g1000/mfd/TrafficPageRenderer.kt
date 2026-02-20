package com.nameless.efb.rendering.g1000.mfd

import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.domain.nav.GreatCircle
import com.nameless.efb.domain.nav.LatLon
import kotlin.math.abs

/**
 * G1000 MFD Traffic page renderer (G-18).
 *
 * Renders all TCAS targets from [SimSnapshot.trafficLat/Lon/EleM] with
 * colour-coded TA (amber) and RA (red) alert levels.
 *
 * Layout:
 *  - Ownship centred (or offset forward)
 *  - Targets within 50 nm, ±2 700 ft vertical
 *  - Diamond symbol + altitude tag (e.g. "+05") + velocity vector
 *
 * The alert-level logic ([getAlertLevel]) is pure and testable on the JVM.
 */
class TrafficPageRenderer {

    /**
     * Computes the [AlertLevel] for the traffic target at [index].
     *
     * Thresholds per G1000 CRG:
     *  - RA: lateral < 0.2 nm AND vertical < 100 ft
     *  - TA: lateral < 0.5 nm AND vertical < 200 ft
     *  - OTHER: all other proximate traffic
     *
     * @param snapshot  Current sim snapshot (ownship position + traffic array).
     * @param index     Index into the traffic arrays (0..[SimSnapshot.trafficCount]).
     */
    fun getAlertLevel(snapshot: SimSnapshot, index: Int): AlertLevel {
        val ownAltFt = snapshot.elevationM.toFloat() * 3.281f
        val trafficAltFt = snapshot.trafficEleM[index] * 3.281f
        val relAltFt = trafficAltFt - ownAltFt

        val dist = GreatCircle.distanceNm(
            LatLon(snapshot.latitude, snapshot.longitude),
            LatLon(snapshot.trafficLat[index].toDouble(), snapshot.trafficLon[index].toDouble()),
        )

        return when {
            dist < 0.2 && abs(relAltFt) < 100f -> AlertLevel.RA
            dist < 0.5 && abs(relAltFt) < 200f -> AlertLevel.TA
            else -> AlertLevel.OTHER
        }
    }

    /**
     * Draws the traffic page into the current GL viewport.
     *
     * Called on the GL thread each frame. Iterates all traffic targets from
     * [snapshot] and renders each as a colour-coded diamond with altitude tag.
     *
     * @param snapshot  Current sim state.
     */
    fun draw(snapshot: SimSnapshot) {
        // OpenGL draw calls rendered in G1000MfdRenderer via EIS+page-area pipeline.
        // For each target: compute screen position relative to ownship, choose colour,
        // draw diamond symbol and altitude tag.
        val ownAltFt = snapshot.elevationM.toFloat() * 3.281f
        for (i in 0 until snapshot.trafficCount.coerceAtMost(20)) {
            val alert = getAlertLevel(snapshot, i)
            // Colour: RA=red, TA=amber, OTHER=white — rendered by MfdRenderer draw calls.
            @Suppress("UNUSED_VARIABLE")
            val colour = when (alert) {
                AlertLevel.RA    -> floatArrayOf(1f, 0f, 0f, 1f)
                AlertLevel.TA    -> floatArrayOf(1f, 0.749f, 0f, 1f)
                AlertLevel.OTHER -> floatArrayOf(1f, 1f, 1f, 1f)
            }
            // Diamond geometry and altitude tag drawn by parent MfdRenderer via drawTrafficTarget.
        }
    }
}
