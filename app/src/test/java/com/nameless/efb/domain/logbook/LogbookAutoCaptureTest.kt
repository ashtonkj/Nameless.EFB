package com.nameless.efb.domain.logbook

import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.data.db.dao.LogbookDao
import com.nameless.efb.data.db.entity.AirportEntity
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogbookAutoCaptureTest {

    // Use Dispatchers.Unconfined so launched coroutines run synchronously in tests.
    private val dao     = mockk<LogbookDao>(relaxed = true)
    private val capture = LogbookAutoCapture(
        logbookDao = dao,
        scope      = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun takeoffDetected_whenGroundspeedExceeds40kt() {
        capture.update(snapshotKt(50f), faor())
        assertTrue("Flight should be active after takeoff", capture.flightActive)
    }

    @Test
    fun noTakeoff_belowThreshold() {
        capture.update(snapshotKt(30f), faor())
        assertFalse("Flight should NOT be active below 40kt", capture.flightActive)
    }

    @Test
    fun landingDetected_afterTakeoff() {
        capture.update(snapshotKt(80f), faor())   // takeoff
        assertTrue(capture.flightActive)

        capture.update(snapshotKt(0f), fact())    // landing
        assertFalse("Flight should end after speed drops below 5kt", capture.flightActive)
    }

    @Test
    fun noLanding_ifNeverAirborne() {
        capture.update(snapshotKt(0f), faor())
        assertFalse("Should not be active — never airborne", capture.flightActive)
    }

    @Test
    fun secondFlight_canBeDetected() {
        capture.update(snapshotKt(80f), faor())   // flight 1 takeoff
        capture.update(snapshotKt(0f),  fact())   // flight 1 landing
        assertFalse(capture.flightActive)

        capture.update(snapshotKt(80f), fact())   // flight 2 takeoff
        assertTrue("Second flight should be detected", capture.flightActive)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** [groundspeedKt] knots → SimSnapshot (groundspeedMs = kt × 0.5144). */
    private fun snapshotKt(groundspeedKt: Float) =
        SimSnapshot(groundspeedMs = groundspeedKt * 0.5144f)

    private fun faor() = AirportEntity(
        icao = "FAOR", name = "OR Tambo", latitude = -26.1392, longitude = 28.2462,
        elevationFt = 5558, airportType = "large_airport", isTowered = true,
        isMilitary = false, countryCode = "ZA", municipality = "Johannesburg", source = "ourairports",
    )

    private fun fact() = AirportEntity(
        icao = "FACT", name = "Cape Town Intl", latitude = -33.9648, longitude = 18.6017,
        elevationFt = 151, airportType = "large_airport", isTowered = true,
        isMilitary = false, countryCode = "ZA", municipality = "Cape Town", source = "ourairports",
    )
}
