package com.nameless.efb.domain.gauge

/**
 * Aircraft-specific gauge limits and V-speeds used to configure arc geometry
 * and alert thresholds across the steam gauge panel.
 *
 * Serialised as JSON and stored in the aircraft profile Room table.
 * V-speeds are in knots; temperatures in °C; pressures in PSI; suction in inHg.
 *
 * Default values match a typical Cessna C172S (Lycoming IO-360).
 */
data class AircraftProfile(
    val type: String = "C172",
    val fuelType: FuelType = FuelType.AVGAS,

    // ── V-speeds (kts) ────────────────────────────────────────────────────────
    val vsoKts: Float = 40f,    // stall speed, flaps + gear extended
    val vfeKts: Float = 85f,    // max flap extension speed
    val vs1Kts: Float = 48f,    // stall speed, clean configuration
    val vnoKts: Float = 127f,   // max structural cruising speed
    val vneKts: Float = 163f,   // never-exceed speed
    val vrKts: Float = 55f,     // rotation speed
    val vxKts: Float = 59f,     // best angle of climb
    val vyKts: Float = 74f,     // best rate of climb
    val vappKts: Float = 65f,   // approach speed

    // ── Engine limits ─────────────────────────────────────────────────────────
    val hasManifoldPressure: Boolean = false,
    val maxRpm: Float = 2700f,
    val oilTempRedlineDegC: Float = 118f,
    val oilPressMinPsi: Float = 25f,

    // ── Electrical / pneumatic limits ─────────────────────────────────────────
    val busVoltsMin: Float = 13.0f,
    val suctionMinInhg: Float = 4.5f,
)
