package com.nameless.efb.domain.gauge

import kotlinx.serialization.Serializable

/** Identifies a specific instrument in the steam gauge panel. */
@Serializable
enum class GaugeType {
    ASI,         // Airspeed Indicator
    AI,          // Attitude Indicator
    ALT,         // Altimeter
    TC,          // Turn Coordinator
    DI,          // Heading Indicator / HSI
    VSI,         // Vertical Speed Indicator
    RPM,         // Tachometer
    MAP,         // Manifold Pressure
    OIL,         // Oil Temperature & Pressure
    FUEL_FLOW,   // Fuel Flow
    FUEL_QTY,    // Fuel Quantity
    EGT,         // EGT / CHT
    ELECTRICAL,  // Volts / Amps
    SUCTION,     // Suction / Vacuum
}
