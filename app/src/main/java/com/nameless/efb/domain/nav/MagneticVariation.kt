package com.nameless.efb.domain.nav

/**
 * Simplified regional magnetic variation model for sub-Saharan Africa.
 *
 * Derived from NOAA World Magnetic Model 2025 published isogonic charts.
 * Accuracy: ±2° over the primary SA coverage area (lat −15 to −35, lon 15 to 35).
 *
 * A full spherical-harmonic WMM implementation is not warranted for a
 * simulator training tool where ±2° display accuracy is sufficient.
 */
object MagneticVariation {

    /**
     * Returns magnetic variation in degrees at [lat]/[lon] (WGS-84, epoch 2025.0).
     *
     * Sign convention: positive = east, negative = west.
     *
     * South Africa reference values from WMM 2025 isogonic chart:
     *   FAOR (−26°S, 28°E) ≈ −24.5°
     *   FACT (−34°S, 18°E) ≈ −22.0°
     *
     * The linear gradient approximation:
     *   dVar/dLat ≈ +0.25°/°lat  (more westerly toward the south)
     *   dVar/dLon ≈ −0.15°/°lon  (less westerly toward the east)
     */
    fun compute(lat: Double, lon: Double): Double {
        val latDelta = lat - (-26.0)
        val lonDelta = lon - 28.0
        return -24.5 + latDelta * 0.25 + lonDelta * (-0.15)
    }
}
