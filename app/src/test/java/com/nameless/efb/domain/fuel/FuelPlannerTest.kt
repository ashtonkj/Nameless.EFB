package com.nameless.efb.domain.fuel

import com.nameless.efb.domain.flightplan.FlightPlan
import com.nameless.efb.domain.flightplan.Waypoint
import com.nameless.efb.domain.gauge.FuelType
import com.nameless.efb.domain.nav.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelPlannerTest {

    @Test
    fun fuelFlow_avgas_kgSecToLph() {
        // 0.02 kg/s × 3600 s/hr = 72 kg/hr; 72 / 0.72 kg/L = 100 LPH
        val lph = kgSecToLph(0.02f, FuelType.AVGAS)
        assertEquals(100f, lph, 1f)
    }

    @Test
    fun fuelFlow_jeta1_kgSecToLph() {
        // 0.04 kg/s × 3600 = 144 kg/hr; 144 / 0.80 = 180 LPH
        val lph = kgSecToLph(0.04f, FuelType.JET_A1)
        assertEquals(180f, lph, 1f)
    }

    @Test
    fun sufficientFuel_shortFlight() {
        val result = FuelPlanner.compute(
            plan    = directPlan(),
            profile = FuelProfile(
                fuelType            = FuelType.AVGAS,
                usableFuelKg        = 100f,
                cruiseFuelFlowKgHr  = 30f,
                reserveFuelKg       = 10f,
            ),
            wind    = WindData.CALM,
        )
        assertTrue("Should have sufficient fuel for short flight", result.sufficientFuel)
    }

    @Test
    fun insufficientFuel_longFlight() {
        // 1000nm at 110kt = ~9.1hr at 30 kg/hr = ~273 kg needed; only 50 kg on board
        val result = FuelPlanner.compute(
            plan    = longRoutePlan(),
            profile = FuelProfile(
                fuelType            = FuelType.AVGAS,
                usableFuelKg        = 50f,
                cruiseFuelFlowKgHr  = 30f,
                reserveFuelKg       = 10f,
            ),
            wind    = WindData.CALM,
        )
        assertFalse("Should be insufficient fuel for 1000nm flight", result.sufficientFuel)
    }

    @Test
    fun legCount_matchesPlanLegs() {
        val plan   = directPlan()
        val result = FuelPlanner.compute(plan, defaultProfile(), WindData.CALM)
        assertEquals(plan.legs().size, result.legs.size)
    }

    @Test
    fun totalBurn_nonNegative() {
        val result = FuelPlanner.compute(directPlan(), defaultProfile(), WindData.CALM)
        assertTrue("Total burn should be >= 0", result.totalBurnKg >= 0f)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun directPlan() = FlightPlan(
        departure   = Waypoint.Airport("FAOR", LatLon(-26.1392, 28.2462)),
        destination = Waypoint.Airport("FAWK", LatLon(-25.8953, 27.2261)),
        waypoints   = listOf(
            Waypoint.Airport("FAOR", LatLon(-26.1392, 28.2462)),
            Waypoint.Airport("FAWK", LatLon(-25.8953, 27.2261)),
        ),
    )

    private fun longRoutePlan() = FlightPlan(
        departure   = Waypoint.Airport("FAOR", LatLon(-26.1392,  28.2462)),
        destination = Waypoint.Airport("HAAB", LatLon(  8.9779,  38.7989)),
        waypoints   = listOf(
            Waypoint.Airport("FAOR", LatLon(-26.1392,  28.2462)),
            Waypoint.Airport("HAAB", LatLon(  8.9779,  38.7989)),
        ),
    )

    private fun defaultProfile() = FuelProfile(
        fuelType           = FuelType.AVGAS,
        usableFuelKg       = 100f,
        cruiseFuelFlowKgHr = 30f,
        reserveFuelKg      = 10f,
    )
}
