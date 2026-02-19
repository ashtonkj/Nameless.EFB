package com.nameless.efb.domain.nav

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * Web Mercator (EPSG:3857) projection utilities.
 *
 * Uses pure Kotlin / JVM math so these functions are unit-testable without
 * Android dependencies.
 */
object WebMercator {

    private const val EARTH_HALF_CIRCUMFERENCE = 20_037_508.34

    /**
     * Projects [lat]/[lon] (degrees) to Web Mercator metres.
     *
     * Returns `doubleArrayOf(x, y)` to avoid Android `PointF` dependency.
     */
    fun toMeters(lat: Double, lon: Double): DoubleArray {
        val x = lon * EARTH_HALF_CIRCUMFERENCE / 180.0
        val latRad = Math.toRadians(lat)
        val y = ln(tan(PI / 4.0 + latRad / 2.0)) * EARTH_HALF_CIRCUMFERENCE / PI
        return doubleArrayOf(x, y)
    }

    /**
     * Inverse Mercator — Web Mercator metres back to geographic coordinates.
     */
    fun toLatLon(mx: Double, my: Double): LatLon {
        val lon = mx * 180.0 / EARTH_HALF_CIRCUMFERENCE
        val lat = Math.toDegrees(
            2.0 * atan(exp(my * PI / EARTH_HALF_CIRCUMFERENCE)) - PI / 2.0
        )
        return LatLon(lat, lon)
    }
}

/**
 * Returns the OSM-style slippy-map tile for [lat]/[lon] (degrees) at [zoom].
 *
 * Formula: https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames
 *
 * - X increases eastward (tile 0 = −180°).
 * - Y increases southward (tile 0 = north pole).
 * - Result is clamped to [0, 2^zoom − 1] on both axes.
 */
fun latLonToTile(lat: Double, lon: Double, zoom: Int): TileXYZ {
    val n = 1 shl zoom  // 2^zoom
    val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    val latRad = Math.toRadians(lat)
    val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n)
        .toInt().coerceIn(0, n - 1)
    return TileXYZ(x, y, zoom)
}

/** Linear interpolation between [a] and [b] at fraction [t]. */
fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/**
 * Interpolates between two angles (degrees) via the shortest arc.
 */
fun lerpAngle(a: Float, b: Float, t: Float): Float {
    var delta = b - a
    while (delta > 180f) delta -= 360f
    while (delta < -180f) delta += 360f
    return a + delta * t
}
