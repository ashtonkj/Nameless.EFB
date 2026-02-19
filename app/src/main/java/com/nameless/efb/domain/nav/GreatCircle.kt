package com.nameless.efb.domain.nav

import kotlin.math.*

/**
 * Great-circle calculations using the Vincenty inverse formula.
 *
 * Accurate to 0.5 mm on the WGS-84 ellipsoid.
 *
 * Reference: Vincenty, T. (1975). "Direct and Inverse Solutions of Geodesics
 * on the Ellipsoid with application of nested equations." Survey Review 23(176).
 */
object GreatCircle {

    // ── WGS-84 ellipsoid constants ────────────────────────────────────────────
    private const val A = 6_378_137.0           // semi-major axis (m)
    private const val F = 1.0 / 298.257223563   // flattening
    private const val B = A * (1.0 - F)         // semi-minor axis (m)

    private const val METERS_PER_NM = 1852.0

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the geodesic distance from [from] to [to] in nautical miles.
     * Returns 0.0 for coincident points or if Vincenty fails to converge
     * (nearly antipodal points).
     */
    fun distanceNm(from: LatLon, to: LatLon): Double =
        vincenty(from, to)?.first?.div(METERS_PER_NM) ?: 0.0

    /**
     * Returns the initial true bearing from [from] to [to], in degrees [0, 360).
     */
    fun initialBearingDeg(from: LatLon, to: LatLon): Double =
        vincenty(from, to)?.second ?: 0.0

    /**
     * Returns the final true bearing at [to], in degrees [0, 360).
     */
    fun finalBearingDeg(from: LatLon, to: LatLon): Double =
        vincenty(from, to)?.third ?: 0.0

    /**
     * Converts a [trueBearing] (degrees) to magnetic bearing using the
     * WMM 2025 regional approximation at [lat]/[lon].
     *
     * A westerly variation (negative) increases the magnetic bearing above true.
     */
    fun magneticBearing(trueBearing: Double, lat: Double, lon: Double): Double {
        val variation = MagneticVariation.compute(lat, lon)
        return (trueBearing - variation + 360.0) % 360.0
    }

    // ── Vincenty inverse ──────────────────────────────────────────────────────

    /**
     * Solves the Vincenty inverse problem.
     *
     * @return Triple(distanceMeters, initialBearingDeg, finalBearingDeg),
     *         or null for coincident/antipodal points.
     */
    private fun vincenty(from: LatLon, to: LatLon): Triple<Double, Double, Double>? {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lon2 = Math.toRadians(to.longitude)

        val u1 = atan((1.0 - F) * tan(lat1))
        val u2 = atan((1.0 - F) * tan(lat2))
        val l  = lon2 - lon1

        val sinU1 = sin(u1); val cosU1 = cos(u1)
        val sinU2 = sin(u2); val cosU2 = cos(u2)

        var lambda = l
        var lastLambda: Double
        var iterLimit = 100

        var sinSigma  = 0.0; var cosSigma  = 0.0; var sigma = 0.0
        var sinAlpha  = 0.0; var cosSqAlpha = 0.0
        var cos2SigmaM = 0.0; var c = 0.0

        do {
            val sinLambda = sin(lambda)
            val cosLambda = cos(lambda)

            val t1 = cosU2 * sinLambda
            val t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
            sinSigma = sqrt(t1 * t1 + t2 * t2)

            if (sinSigma == 0.0) return null  // coincident points

            cosSigma   = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
            sigma      = atan2(sinSigma, cosSigma)
            sinAlpha   = cosU1 * cosU2 * sinLambda / sinSigma
            cosSqAlpha = 1.0 - sinAlpha * sinAlpha
            cos2SigmaM = if (cosSqAlpha != 0.0)
                cosSigma - 2.0 * sinU1 * sinU2 / cosSqAlpha
            else
                0.0

            c = F / 16.0 * cosSqAlpha * (4.0 + F * (4.0 - 3.0 * cosSqAlpha))

            lastLambda = lambda
            lambda = l + (1.0 - c) * F * sinAlpha * (
                sigma + c * sinSigma * (
                    cos2SigmaM + c * cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM)
                )
            )
        } while (abs(lambda - lastLambda) > 1e-12 && --iterLimit > 0)

        if (iterLimit == 0) return null  // failed to converge (antipodal)

        val uSq = cosSqAlpha * (A * A - B * B) / (B * B)
        val kA  = 1.0 + uSq / 16384.0 * (4096.0 + uSq * (-768.0 + uSq * (320.0 - 175.0 * uSq)))
        val kB  = uSq / 1024.0 * (256.0 + uSq * (-128.0 + uSq * (74.0 - 47.0 * uSq)))

        val deltaSigma = kB * sinSigma * (
            cos2SigmaM + kB / 4.0 * (
                cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM) -
                kB / 6.0 * cos2SigmaM *
                    (-3.0 + 4.0 * sinSigma * sinSigma) *
                    (-3.0 + 4.0 * cos2SigmaM * cos2SigmaM)
            )
        )

        val distance = B * kA * (sigma - deltaSigma)

        val sinLambda = sin(lambda)
        val cosLambda = cos(lambda)
        val fwdAz = atan2(
            cosU2 * sinLambda,
            cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
        )
        val revAz = atan2(
            cosU1 * sinLambda,
            -sinU1 * cosU2 + cosU1 * sinU2 * cosLambda
        )

        return Triple(
            distance,
            (Math.toDegrees(fwdAz) + 360.0) % 360.0,
            (Math.toDegrees(revAz) + 360.0) % 360.0,
        )
    }
}
