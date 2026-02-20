package com.nameless.efb.rendering.g1000.mfd

import androidx.sqlite.db.SimpleSQLiteQuery
import com.nameless.efb.data.db.EfbDatabase
import com.nameless.efb.data.db.entity.AirportEntity
import com.nameless.efb.domain.nav.GreatCircle
import com.nameless.efb.domain.nav.LatLon
import com.nameless.efb.domain.nav.boundingBox

/**
 * Airport entry with pre-computed great-circle distance for NRST display (G-15).
 */
data class AirportWithDistance(
    val airport: AirportEntity,
    val distanceNm: Double,
)

/**
 * G1000 MFD Nearest pages renderer (G-15).
 *
 * Sub-pages:
 *  - NRST AIRPORTS — nearest 15 airports with bearing and distance
 *  - NRST VOR
 *  - NRST NDB
 *  - NRST INT (intersections)
 *
 * Table columns per G1000 CRG: IDENT | BRG | DIS | ELEV | APR (approach types).
 *
 * @param navDb  Navigation database for spatial queries (null = no data available).
 */
class NrstPageRenderer(private val navDb: EfbDatabase? = null) {

    /**
     * Queries the nav database for the 15 nearest airports within 200 nm of [ownship].
     *
     * Uses the R-tree pre-filter (airports_rtree) for sub-200ms query performance (G-15 NFR).
     *
     * @param ownship  Current aircraft position.
     * @return List of up to 15 airports sorted by ascending great-circle distance.
     */
    suspend fun loadNearestAirports(ownship: LatLon): List<AirportWithDistance> {
        val db = navDb ?: return emptyList()
        val bbox = ownship.boundingBox(200.0)
        val query = SimpleSQLiteQuery(
            """
            SELECT a.* FROM airports a
            JOIN airports_rtree r ON a.rowid = r.id
            WHERE r.lat_min <= ? AND r.lat_max >= ?
              AND r.lon_min <= ? AND r.lon_max >= ?
            """.trimIndent(),
            arrayOf(bbox.latMax, bbox.latMin, bbox.lonMax, bbox.lonMin),
        )
        return db.airportDao()
            .nearbyRaw(query)
            .map { AirportWithDistance(it, GreatCircle.distanceNm(ownship, LatLon(it.latitude, it.longitude))) }
            .filter { it.distanceNm <= 200.0 }
            .sortedBy { it.distanceNm }
            .take(15)
    }

    /**
     * Draws the NRST airports table into the current GL viewport.
     *
     * @param airports  Pre-loaded airport list from [loadNearestAirports].
     * @param cursor    Index of the currently selected row.
     * @param ownship   Current position for bearing calculation.
     */
    fun drawAirports(airports: List<AirportWithDistance>, cursor: Int, ownship: LatLon) {
        // Table header: IDENT | BRG | DIS | ELEV | APR
        // One row per airport, cursor row highlighted in white.
        // Bearing computed as GreatCircle.initialBearingDeg(ownship, airportLatLon).
    }
}
