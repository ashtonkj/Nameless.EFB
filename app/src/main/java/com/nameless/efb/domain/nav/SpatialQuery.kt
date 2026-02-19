package com.nameless.efb.domain.nav

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Builds Room [SupportSQLiteQuery] objects for R-tree spatial pre-filters.
 *
 * Each query joins the main table with its R-tree virtual table using a
 * bounding-box filter.  Callers should apply an exact great-circle distance
 * check to the returned rows to discard corner artifacts.
 */
object SpatialQuery {

    /**
     * Airports within [radiusNm] nautical miles of [center].
     * Joins `airports` with `airports_rtree` on `rowid`.
     */
    fun nearbyAirports(center: LatLon, radiusNm: Double): SupportSQLiteQuery {
        val bbox = center.boundingBox(radiusNm)
        return SimpleSQLiteQuery(
            """
            SELECT a.* FROM airports a
            JOIN airports_rtree r ON r.id = a.rowid
            WHERE r.lat_min >= ? AND r.lat_max <= ?
              AND r.lon_min >= ? AND r.lon_max <= ?
            """.trimIndent(),
            arrayOf(bbox.latMin, bbox.latMax, bbox.lonMin, bbox.lonMax),
        )
    }

    /**
     * Navaids within [radiusNm] nautical miles of [center].
     * Joins `navaids` with `navaids_rtree` on `id`.
     */
    fun nearbyNavaids(center: LatLon, radiusNm: Double): SupportSQLiteQuery {
        val bbox = center.boundingBox(radiusNm)
        return SimpleSQLiteQuery(
            """
            SELECT n.* FROM navaids n
            JOIN navaids_rtree r ON r.id = n.id
            WHERE r.lat_min >= ? AND r.lat_max <= ?
              AND r.lon_min >= ? AND r.lon_max <= ?
            """.trimIndent(),
            arrayOf(bbox.latMin, bbox.latMax, bbox.lonMin, bbox.lonMax),
        )
    }
}
