package com.nameless.efb.domain.weather

import kotlin.math.abs
import kotlin.math.sin

// ── Crosswind component ───────────────────────────────────────────────────────

/**
 * Returns the crosswind component in knots for a given wind and leg bearing.
 *
 * Sign convention: negative = wind from the right of the aircraft heading.
 *
 * @param windDirDeg  Direction the wind is blowing FROM (degrees true)
 * @param windSpeedKt Wind speed in knots
 * @param legBearingDeg True course of the flight leg (degrees)
 */
fun crosswindComponent(windDirDeg: Float, windSpeedKt: Float, legBearingDeg: Float): Float {
    val angleDiff = Math.toRadians((windDirDeg - legBearingDeg).toDouble())
    return (windSpeedKt * sin(angleDiff)).toFloat()
}

/**
 * Returns the headwind component in knots (negative = tailwind).
 */
fun headwindComponent(windDirDeg: Float, windSpeedKt: Float, legBearingDeg: Float): Float {
    val angleDiff = Math.toRadians((windDirDeg - legBearingDeg).toDouble())
    return (windSpeedKt * kotlin.math.cos(angleDiff)).toFloat()
}

// ── Glide range ───────────────────────────────────────────────────────────────

/**
 * Returns true if an airport at [distanceNm] from the aircraft is within
 * glide range from [altitudeFt] above ground.
 *
 * Uses a conservative 1 nm per 60 ft of altitude rule (approximately 1:3.7
 * glide ratio at sea level, very conservative for training tool use).
 */
fun isInGlideRange(distanceNm: Double, altitudeFt: Float): Boolean =
    distanceNm <= altitudeFt / 60.0

// ── TAWS colour LUT ──────────────────────────────────────────────────────────

/**
 * TAWS clearance-to-colour lookup table.
 *
 * Returns ARGB colour integers matching the terrain TAWS shader thresholds
 * defined in `shaders/map/terrain_taws.frag`:
 *   < 150 m AGL clearance → RED    (warning)
 *   < 300 m AGL clearance → YELLOW (caution)
 *   otherwise              → GREEN  (safe)
 *   (shader renders transparent when clearance is very large — handled per-pixel)
 */
class TawsLut {
    /** Returns an ARGB Int colour for [clearanceFt] of terrain clearance. */
    fun sample(clearanceFt: Float): Int = when {
        clearanceFt < 492f  -> ARGB_RED     // < ~150 m (warning)
        clearanceFt < 984f  -> ARGB_YELLOW  // < ~300 m (caution)
        else                -> ARGB_GREEN
    }

    companion object {
        const val ARGB_RED    = 0xFF_FF0000.toInt()
        const val ARGB_YELLOW = 0xFF_FFFF00.toInt()
        const val ARGB_GREEN  = 0xFF_00FF00.toInt()
    }
}

/** Constructs a [TawsLut] with default TAWS thresholds. */
fun buildTawsLut(): TawsLut = TawsLut()
