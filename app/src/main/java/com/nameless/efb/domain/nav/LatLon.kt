package com.nameless.efb.domain.nav

import kotlin.math.abs
import kotlin.math.cos

/** Geographic coordinate pair. */
data class LatLon(val latitude: Double, val longitude: Double)

/** Axis-aligned bounding box for spatial pre-filtering. */
data class BoundingBox(
    val latMin: Double,
    val latMax: Double,
    val lonMin: Double,
    val lonMax: Double,
)

/**
 * Returns a bounding box [radiusNm] nautical miles around this point.
 *
 * Uses a simple degree-delta approximation (1' ≈ 1 nm, valid within ±5% for
 * latitudes up to ~75°). The box is a conservative over-estimate; callers
 * should apply an exact great-circle distance filter to the results.
 */
fun LatLon.boundingBox(radiusNm: Double): BoundingBox {
    val latDelta = radiusNm / 60.0
    // Longitude degrees per nm shrinks as cos(lat)
    val cosLat = cos(Math.toRadians(latitude)).let { if (abs(it) < 1e-6) 1e-6 else it }
    val lonDelta = radiusNm / (60.0 * cosLat)
    return BoundingBox(
        latMin = latitude  - latDelta,
        latMax = latitude  + latDelta,
        lonMin = longitude - lonDelta,
        lonMax = longitude + lonDelta,
    )
}
