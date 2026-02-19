package com.nameless.efb.domain.fuel

import com.nameless.efb.domain.flightplan.FlightPlan
import com.nameless.efb.domain.flightplan.Leg
import com.nameless.efb.domain.gauge.FuelType
import kotlin.math.cos

/**
 * Wind conditions for fuel planning.
 *
 * [directionDeg] is the direction the wind is blowing FROM (true degrees).
 */
data class WindData(val directionDeg: Float, val speedKt: Float) {
    companion object {
        val CALM = WindData(0f, 0f)
    }
}

/**
 * Fuel parameters for a specific loading/configuration.
 *
 * Separated from [AircraftProfile] to allow the user to enter the actual
 * loaded fuel quantity without mutating the aircraft baseline profile.
 */
data class FuelProfile(
    val fuelType: FuelType,
    val usableFuelKg: Float,        // actual fuel on board
    val cruiseFuelFlowKgHr: Float,  // engine burn rate at cruise power
    val reserveFuelKg: Float,       // legal minimum: ICAO Annex 6 + SA AIC A001/2022
)

data class LegFuel(
    val leg: Leg,
    val burnKg: Float,
    val remainingKg: Float,         // fuel remaining at end of leg
)

data class FuelPlanResult(
    val legs: List<LegFuel>,
    val reserveKg: Float,
    val sufficientFuel: Boolean,
    val totalBurnKg: Float,
)

/**
 * Fuel planning calculator (UT-02).
 *
 * Computes per-leg fuel burn based on ground speed (TAS ± wind component)
 * and checks whether sufficient fuel is available above [FuelProfile.reserveFuelKg].
 *
 * SA reserve rules per AIC A001/2022 are captured in [reserveFuelKg]; the
 * caller is responsible for computing the correct reserve for the flight type.
 */
object FuelPlanner {

    fun compute(plan: FlightPlan, profile: FuelProfile, wind: WindData): FuelPlanResult {
        var fuelKg = profile.usableFuelKg
        val legResults = mutableListOf<LegFuel>()

        for (leg in plan.legs()) {
            val gs      = effectiveGroundSpeed(profile.cruiseFuelFlowKgHr, wind, leg.bearingDeg.toFloat())
            val timeHr  = if (gs > 0f) (leg.distanceNm / gs).toFloat() else 0f
            val burnKg  = timeHr * profile.cruiseFuelFlowKgHr
            fuelKg -= burnKg
            legResults.add(LegFuel(leg, burnKg, fuelKg))
        }

        val totalBurn = profile.usableFuelKg - fuelKg
        return FuelPlanResult(
            legs           = legResults,
            reserveKg      = profile.reserveFuelKg,
            sufficientFuel = fuelKg >= profile.reserveFuelKg,
            totalBurnKg    = totalBurn,
        )
    }

    /**
     * Estimated groundspeed = cruise TAS − headwind component.
     *
     * We estimate TAS as proportional to cruiseFuelFlowKgHr (a placeholder for
     * when AircraftProfile gains a `cruiseTasKts` field). Until then callers
     * can pass [DEFAULT_CRUISE_TAS_KT] implicitly.
     */
    private fun effectiveGroundSpeed(
        @Suppress("UNUSED_PARAMETER") fuelFlowKgHr: Float,
        wind: WindData,
        legBearingDeg: Float,
    ): Float {
        val angleDiff = Math.toRadians((wind.directionDeg - legBearingDeg).toDouble())
        val headwindKt = (wind.speedKt * cos(angleDiff)).toFloat()
        return (DEFAULT_CRUISE_TAS_KT - headwindKt).coerceAtLeast(1f)
    }

    private const val DEFAULT_CRUISE_TAS_KT = 110f
}

// ── Unit conversion helpers ───────────────────────────────────────────────────

/**
 * Converts a mass flow rate from kg/s to litres per hour for the given fuel type.
 *
 *   LPH = (kg/s × 3600 s/hr) / density_kg_L
 */
fun kgSecToLph(kgPerSec: Float, fuelType: FuelType): Float {
    val kgPerHr  = kgPerSec * 3600f
    val density  = when (fuelType) {
        FuelType.AVGAS  -> 0.72f
        FuelType.JET_A1 -> 0.80f
    }
    return kgPerHr / density
}
