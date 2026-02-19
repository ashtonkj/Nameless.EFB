package com.nameless.efb.domain.weather

/**
 * Decoded METAR observation.
 *
 * All pressure in hPa (SA default — SACAA/ICAO convention).
 * Visibility in metres; ceiling in feet.
 * [ceiling] is null when CAVOK or when no BKN/OVC layer is reported.
 */
data class DecodedMetar(
    val icao: String,
    val windDirDeg: Int,         // 0 for VRB or calm
    val windSpeedKt: Int,
    val gustKt: Int = 0,         // 0 if no gust reported
    val visibilityM: Int,        // 999999 when CAVOK / unlimited
    val ceiling: Int?,           // ft AGL; null = CAVOK / no layer
    val tempC: Float,
    val dewpointC: Float,
    val qnhHpa: Float,
    val isCavok: Boolean,
    val remarks: String = "",
    val flightCategory: FlightCategory,
)
