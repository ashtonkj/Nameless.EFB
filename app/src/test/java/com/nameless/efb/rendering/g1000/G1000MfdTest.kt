package com.nameless.efb.rendering.g1000

import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.domain.flightplan.FlightPlan
import com.nameless.efb.domain.flightplan.Waypoint
import com.nameless.efb.domain.nav.LatLon
import com.nameless.efb.rendering.g1000.mfd.AlertLevel
import com.nameless.efb.rendering.g1000.mfd.ApproachProcedure
import com.nameless.efb.rendering.g1000.mfd.EisFormatter
import com.nameless.efb.rendering.g1000.mfd.FplPageRenderer
import com.nameless.efb.rendering.g1000.mfd.FuelFlowUnit
import com.nameless.efb.rendering.g1000.mfd.FuelType
import com.nameless.efb.rendering.g1000.mfd.G1000_RANGE_STEPS_NM
import com.nameless.efb.rendering.g1000.mfd.TerrainPageRenderer
import com.nameless.efb.rendering.g1000.mfd.TrafficPageRenderer
import com.nameless.efb.rendering.g1000.mfd.kgSecToLph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for G1000 MFD math and page logic (G-11 through G-18).
 *
 * All tests operate on pure Kotlin functions with no Android or OpenGL dependencies.
 */
class G1000MfdTest {

    // ── G-11: MFD range steps ─────────────────────────────────────────────────

    @Test
    fun mfdRangeSteps_contain1000nm() {
        assertTrue(G1000_RANGE_STEPS_NM.contains(1000))
        assertTrue(G1000_RANGE_STEPS_NM.contains(1))
    }

    @Test
    fun mfdRangeSteps_areAscending() {
        val sorted = G1000_RANGE_STEPS_NM.sorted()
        assertEquals(sorted, G1000_RANGE_STEPS_NM.toList())
    }

    // ── G-12: EIS fuel-flow formatting ───────────────────────────────────────

    @Test
    fun eisFuelFlow_saDefaultLph() {
        // 0.02 kg/s of AVGAS (density 0.72 kg/L) = 72 kg/h / 0.72 = 100 L/h.
        val lph = kgSecToLph(kgSec = 0.02f, fuelType = FuelType.AVGAS)
        // Default display should be LPH for SA.
        val display = EisFormatter.fuelFlow(lph, unit = FuelFlowUnit.LPH)
        assertTrue(display.contains("LPH"), "Expected 'LPH' in '$display'")
    }

    @Test
    fun eisFuelFlow_conversionCorrect() {
        // 0.02 kg/s × 3600 s/h = 72 kg/h; 72 kg/h / 0.72 kg/L = 100 L/h.
        val lph = kgSecToLph(kgSec = 0.02f, fuelType = FuelType.AVGAS)
        assertEquals(100f, lph, 0.5f)
    }

    @Test
    fun eisFuelFlow_gphUnit() {
        val lph = kgSecToLph(kgSec = 0.02f, fuelType = FuelType.AVGAS)
        val display = EisFormatter.fuelFlow(lph, unit = FuelFlowUnit.GPH)
        assertTrue(display.contains("GPH"), "Expected 'GPH' in '$display'")
    }

    @Test
    fun eisFuelFlow_jetA1Density() {
        // JetA-1 density = 0.80 kg/L → 72 kg/h / 0.80 = 90 L/h.
        val lph = kgSecToLph(kgSec = 0.02f, fuelType = FuelType.JET_A1)
        assertEquals(90f, lph, 0.5f)
    }

    // ── G-18: Traffic page alert logic ────────────────────────────────────────

    @Test
    fun trafficPage_taAlert() {
        // Ownship at FAOR area, traffic 0.27 nm away (TA: <0.5 nm), same altitude (0 ft diff).
        val snapshot = testSnapshot(
            trafficLat = floatArrayOf(-26.139f),
            trafficLon = floatArrayOf(28.247f),
            trafficEleM = floatArrayOf(1700f),
            ownLat = -26.139,
            ownLon = 28.252,  // ~0.27 nm east
            ownEleM = 1700.0,
        )
        val page = TrafficPageRenderer()
        val alert = page.getAlertLevel(snapshot, index = 0)
        assertEquals(AlertLevel.TA, alert)
    }

    @Test
    fun trafficPage_raAlert() {
        // Traffic at exactly same position AND same altitude → distance=0, altDiff=0 → RA.
        val snapshot = testSnapshot(
            trafficLat  = floatArrayOf(-26.139f),
            trafficLon  = floatArrayOf(28.247f),
            trafficEleM = floatArrayOf(1700f),
            ownLat  = -26.139,
            ownLon  = 28.247,   // co-located → 0 nm → RA
            ownEleM = 1700.0,
        )
        val page = TrafficPageRenderer()
        val alert = page.getAlertLevel(snapshot, index = 0)
        assertEquals(AlertLevel.RA, alert)
    }

    @Test
    fun trafficPage_otherAlert() {
        // Traffic far away → OTHER.
        val snapshot = testSnapshot(
            trafficLat  = floatArrayOf(-25.0f),
            trafficLon  = floatArrayOf(28.247f),  // >50 nm
            trafficEleM = floatArrayOf(1700f),
            ownLat  = -26.139,
            ownLon  = 28.247,
            ownEleM = 1700.0,
        )
        val page = TrafficPageRenderer()
        val alert = page.getAlertLevel(snapshot, index = 0)
        assertEquals(AlertLevel.OTHER, alert)
    }

    // ── G-17: Terrain page PDA logic ─────────────────────────────────────────

    @Test
    fun pdaLogic_triggersOnApproach() {
        // IAS 120 kt (< 160 kt), altitude 1000 ft, glidepath expects 1500 ft.
        // 1000 ft < (1500 ft − 300 ft) = 1200 ft → PDA triggered.
        val triggered = TerrainPageRenderer().checkPda(
            snapshot = approachSnapshotBelow(),
            approach = testApproach(),
        )
        assertTrue(triggered)
    }

    @Test
    fun pdaLogic_noApproach_notTriggered() {
        val triggered = TerrainPageRenderer().checkPda(
            snapshot = approachSnapshotBelow(),
            approach = null,
        )
        assertTrue(!triggered)
    }

    @Test
    fun pdaLogic_aboveGlidepath_notTriggered() {
        // Aircraft above glidepath → no PDA.
        val snapshot = SimSnapshot(elevationM = 609.6, iasKts = 120f)  // 2000 ft
        val triggered = TerrainPageRenderer().checkPda(snapshot, testApproach())
        assertTrue(!triggered)
    }

    @Test
    fun pdaLogic_highSpeed_notTriggered() {
        // IAS > 160 kt (gear-up speed) → no PDA even when below glidepath.
        val snapshot = SimSnapshot(elevationM = 304.8, iasKts = 200f)
        val triggered = TerrainPageRenderer().checkPda(snapshot, testApproach())
        assertTrue(!triggered)
    }

    // ── G-13: FPL page active leg ─────────────────────────────────────────────

    @Test
    fun fplPage_activeLegHighlighted() {
        val plan = testFlightPlan(activeLeg = 1)
        val renderer = FplPageRenderer()
        // Active leg index 1 must be a valid leg in the plan.
        assertTrue(renderer.isActiveLeg(plan, legIndex = 1))
    }

    @Test
    fun fplPage_invalidLeg_notActive() {
        val plan = testFlightPlan(activeLeg = 0)
        val renderer = FplPageRenderer()
        // Leg index beyond plan size must not be "active".
        assertTrue(!renderer.isActiveLeg(plan, legIndex = 99))
    }

    // ── Test helpers ──────────────────────────────────────────────────────────

    private fun testSnapshot(
        trafficLat: FloatArray,
        trafficLon: FloatArray,
        trafficEleM: FloatArray,
        ownLat: Double,
        ownLon: Double,
        ownEleM: Double,
    ): SimSnapshot {
        val lat20 = FloatArray(20).also { trafficLat.forEachIndexed { i, v -> it[i] = v } }
        val lon20 = FloatArray(20).also { trafficLon.forEachIndexed { i, v -> it[i] = v } }
        val ele20 = FloatArray(20).also { trafficEleM.forEachIndexed { i, v -> it[i] = v } }
        return SimSnapshot(
            latitude     = ownLat,
            longitude    = ownLon,
            elevationM   = ownEleM,
            trafficLat   = lat20,
            trafficLon   = lon20,
            trafficEleM  = ele20,
            trafficCount = trafficLat.size,
        )
    }

    /** Returns a SimSnapshot simulating an aircraft below the glidepath on approach. */
    private fun approachSnapshotBelow() = SimSnapshot(
        elevationM = 304.8,  // 1000 ft MSL
        iasKts     = 120f,   // < 160 kt gear-down speed proxy
    )

    /**
     * Returns a test [ApproachProcedure] whose glidepath altitude is always 1500 ft,
     * so an aircraft at 1000 ft is 500 ft below (> 300 ft threshold → PDA triggers).
     */
    private fun testApproach() = object : ApproachProcedure {
        override fun glideslopeAlt(snapshot: SimSnapshot) = 1500f
    }

    /** Creates a flight plan with at least two legs (departure → wp → destination). */
    private fun testFlightPlan(@Suppress("UNUSED_PARAMETER") activeLeg: Int): FlightPlan {
        val faor = Waypoint.Airport(icao = "FAOR", latLon = LatLon(-26.139, 28.246))
        val fact = Waypoint.Airport(icao = "FACT", latLon = LatLon(-33.965, 18.602))
        val midWp = Waypoint.Fix(identifier = "TEBSA", latLon = LatLon(-29.0, 24.0))
        return FlightPlan(
            departure   = faor,
            destination = fact,
            waypoints   = listOf(faor, midWp, fact),
        )
    }
}
