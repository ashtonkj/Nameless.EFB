package com.nameless.efb.domain.performance

/**
 * Density altitude and related performance calculations (UT-03).
 *
 * Density altitude determines effective aircraft performance (climb, take-off
 * run, engine output) and is critical at high-elevation South African airports
 * such as Johannesburg FAOR (5558 ft MSL).
 */
object DensityAltitude {

    /**
     * Computes density altitude in feet from [pressureAltFt] and [oatDegC].
     *
     * Formula: DA = PA + 118.8 × (OAT − ISA_OAT)
     * where ISA_OAT = 15 − (PA / 1000) × 2  (ISA lapse rate 2°C / 1000 ft)
     *
     * Example — FAOR (5558 ft), summer (30°C):
     *   ISA_OAT = 15 − 11.1 = 3.9°C
     *   DA = 5558 + 118.8 × (30 − 3.9) ≈ 8660 ft
     */
    fun compute(pressureAltFt: Float, oatDegC: Float): Float {
        val isaOat = 15f - (pressureAltFt / 1000f) * 2f
        return pressureAltFt + 118.8f * (oatDegC - isaOat)
    }

    /**
     * Converts QNH (hPa) to pressure altitude.
     * PA = (1013.25 − QNH) × 27 ft/hPa  (simplified standard atmosphere)
     */
    fun pressureAltitude(altitudeFt: Float, qnhHpa: Float): Float =
        altitudeFt + (1013.25f - qnhHpa) * 27f
}
