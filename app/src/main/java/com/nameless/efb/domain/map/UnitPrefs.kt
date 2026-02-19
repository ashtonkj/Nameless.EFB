package com.nameless.efb.domain.map

/** Fuel quantity display unit. */
enum class FuelUnit { LITRES, KG, USG }

/**
 * User unit preferences.
 *
 * SA defaults: hPa, Celsius, litres (or kg), knots, feet.
 */
data class UnitPrefs(
    val fuel: FuelUnit = FuelUnit.LITRES,
    val pressureHpa: Boolean = true,    // true = hPa (SA), false = inHg
    val speedKts: Boolean = true,       // true = kts, false = km/h
    val altitudeFt: Boolean = true,     // true = ft, false = m
)
