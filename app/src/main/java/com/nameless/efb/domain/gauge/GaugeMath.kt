package com.nameless.efb.domain.gauge

import kotlin.math.exp

/**
 * Pure math functions for the steam gauge panel.
 *
 * All functions are stateless and return SI or engineering units that can
 * be passed directly to GLSL `u_needle_angle` uniforms.
 */

// ── Needle angle ──────────────────────────────────────────────────────────────

/**
 * Maps [value] in [[min], [max]] to a needle rotation in **radians**.
 *
 * [startDeg] and [endDeg] are the angular positions (in degrees) corresponding
 * to the minimum and maximum values respectively. The result is clamped so
 * the needle never goes beyond the physical stops.
 *
 * Example (ASI, 0–200 kts, −150° to +150°):
 * ```
 * needleAngle(100f, 0f, 200f, -150f, 150f) → 0.0 rad  (mid-scale)
 * needleAngle(  0f, 0f, 200f, -150f, 150f) → −2.618 rad
 * ```
 */
fun needleAngle(
    value: Float,
    min: Float,
    max: Float,
    startDeg: Float,
    endDeg: Float,
): Float {
    val t = ((value - min) / (max - min)).coerceIn(0f, 1f)
    return Math.toRadians((startDeg + t * (endDeg - startDeg)).toDouble()).toFloat()
}

// ── Altimeter helper needles ──────────────────────────────────────────────────

/**
 * 100s needle: sweeps full scale for every 1000 ft.
 * Input value = (altFt % 1000) / 10  maps 0–1000 ft to 0–100 units.
 */
fun altimeter100sNeedle(altFt: Float): Float =
    needleAngle((altFt % 1000f) / 10f, 0f, 100f, -150f, 210f)

/**
 * 1000s needle: sweeps full scale for every 10 000 ft.
 * Input value = (altFt % 10 000) / 100.
 */
fun altimeter1000sNeedle(altFt: Float): Float =
    needleAngle((altFt % 10_000f) / 100f, 0f, 100f, -150f, 210f)

/**
 * 10 000s needle: sweeps 0–50 kft range.
 * Input value = altFt / 1000.
 */
fun altimeter10kNeedle(altFt: Float): Float =
    needleAngle(altFt / 1000f, 0f, 50f, -150f, 210f)

// ── Fuel conversions ──────────────────────────────────────────────────────────

/**
 * Convert fuel mass-flow from kg/s to litres per hour.
 *
 * SA default display unit is LPH (SACAA convention).
 *
 * @param kgSec fuel flow in kg/s
 * @param fuelType determines density: AVGAS 0.72 kg/L, JetA-1 0.80 kg/L
 */
fun kgSecToLph(kgSec: Float, fuelType: FuelType): Float = when (fuelType) {
    FuelType.AVGAS  -> kgSec * 3600f / 0.72f
    FuelType.JET_A1 -> kgSec * 3600f / 0.80f
}

/**
 * Estimated range in nautical miles at current fuel flow and TAS.
 *
 * Returns 0 when fuel flow is zero or TAS is zero (on the ground).
 */
fun fuelRangeNm(fuelQtyKgTotal: Float, fuelFlowKgSec: Float, tasKts: Float): Float {
    if (fuelFlowKgSec <= 0f || tasKts <= 0f) return 0f
    val enduranceHours = fuelQtyKgTotal / (fuelFlowKgSec * 3600f)
    return enduranceHours * tasKts
}

// ── IIR lag filter ────────────────────────────────────────────────────────────

/**
 * One step of a first-order IIR low-pass filter.
 *
 * Simulates the pneumatic lag of the VSI pointer (default tau = 6 s).
 * Call once per frame: `displayed = iirStep(displayed, target, dtSec)`.
 *
 * @param current current filter output (displayed value)
 * @param target  instantaneous sim value (input)
 * @param dtSec   frame delta in seconds
 * @param tauSec  time constant in seconds (default 6 s for VSI pneumatics)
 */
fun iirStep(current: Float, target: Float, dtSec: Float, tauSec: Float = 6f): Float =
    current + (1f - exp(-dtSec / tauSec)) * (target - current)
