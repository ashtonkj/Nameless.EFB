package com.nameless.efb.domain.wb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightAndBalanceTest {

    @Test
    fun withinEnvelope_standardLoading() {
        val result = WeightAndBalance.compute(
            stations = c172DefaultStations(),
            envelope = c172Envelope(),
        )
        assertTrue("Standard loading should be within envelope", result.isWithinEnvelope)
    }

    @Test
    fun outsideEnvelope_aftCgExceeded() {
        // Move all weight to the baggage station (95in arm) — will push CG aft of limit
        val stations = listOf(
            WbStation(arm = 95f, weightKg = 500f, name = "Baggage (way aft)"),
        )
        val result = WeightAndBalance.compute(stations, c172Envelope())
        assertFalse("Aft-loaded aircraft should be outside envelope", result.isWithinEnvelope)
    }

    @Test
    fun cgArm_computedCorrectly() {
        val stations = listOf(
            WbStation(arm = 40f, weightKg = 100f),
            WbStation(arm = 60f, weightKg = 100f),
        )
        val result = WeightAndBalance.compute(stations, c172Envelope())
        // CG = (40×100 + 60×100) / 200 = 10000/200 = 50
        assertEquals(50f, result.cgArm, 0.01f)
    }

    @Test
    fun grossWeight_sumOfAllStations() {
        val stations = listOf(
            WbStation(arm = 37f, weightKg = 80f),
            WbStation(arm = 37f, weightKg = 75f),
            WbStation(arm = 48f, weightKg = 50f),
        )
        val result = WeightAndBalance.compute(stations, c172Envelope())
        assertEquals(205f, result.grossWeightKg, 0.01f)
    }

    @Test
    fun emptyStations_zeroWeight() {
        val result = WeightAndBalance.compute(emptyList(), c172Envelope())
        assertEquals(0f, result.grossWeightKg, 0.01f)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun c172DefaultStations() = listOf(
        WbStation(arm = 37f, weightKg = 90f,  name = "Pilot"),
        WbStation(arm = 37f, weightKg = 80f,  name = "Co-pilot"),
        WbStation(arm = 48f, weightKg = 83f,  name = "Fuel (30 USG AVGAS ≈ 83 kg)"),
        WbStation(arm = 95f, weightKg = 10f,  name = "Baggage"),
    )
    // Total = 263 kg; CG = (37×90 + 37×80 + 48×83 + 95×10) / 263 ≈ 42.7 in

    /** Simple rectangular envelope: arm 35–48 in, weight 0–1200 kg. */
    private fun c172Envelope() = listOf(
        Pair(35f,    0f),
        Pair(35f, 1200f),
        Pair(48f, 1200f),
        Pair(48f,    0f),
    )
}
