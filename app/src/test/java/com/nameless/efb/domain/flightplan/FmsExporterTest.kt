package com.nameless.efb.domain.flightplan

import com.nameless.efb.domain.nav.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmsExporterTest {

    private val testPlan = FlightPlan(
        name        = "FAOR-FACT",
        departure   = Waypoint.Airport("FAOR", LatLon(-26.1392, 28.2462)),
        destination = Waypoint.Airport("FACT", LatLon(-33.9648, 18.6017)),
        waypoints   = listOf(
            Waypoint.Airport("FAOR", LatLon(-26.1392, 28.2462)),
            Waypoint.Fix("TEBSA",   LatLon(-30.5000, 24.0000)),
            Waypoint.Airport("FACT", LatLon(-33.9648, 18.6017)),
        ),
        cruiseAltitudeFt = 15_000,
    )

    @Test
    fun header_startsWithI() {
        val fms = FmsExporter.toFmsV11(testPlan)
        assertTrue("FMS file must start with 'I'", fms.startsWith("I\n"))
    }

    @Test
    fun header_containsVersion() {
        val fms = FmsExporter.toFmsV11(testPlan)
        assertTrue(fms.contains("1100 Version"))
    }

    @Test
    fun departure_matchesPlan() {
        val fms = FmsExporter.toFmsV11(testPlan)
        assertTrue("ADEP line should contain FAOR", fms.contains("ADEP FAOR"))
    }

    @Test
    fun destination_matchesPlan() {
        val fms = FmsExporter.toFmsV11(testPlan)
        assertTrue("ADES line should contain FACT", fms.contains("ADES FACT"))
    }

    @Test
    fun waypointCount_correct() {
        val fms = FmsExporter.toFmsV11(testPlan)
        val numenrLine = fms.lines().first { it.startsWith("NUMENR") }
        assertEquals("3", numenrLine.split(" ")[1])
    }

    @Test
    fun airportType_code1() {
        val fms = FmsExporter.toFmsV11(testPlan)
        val lines = fms.lines().filter { it.startsWith("1 ") }
        assertTrue("Airport waypoints should have type code 1", lines.size >= 2)
    }

    @Test
    fun fixType_code11() {
        val fms = FmsExporter.toFmsV11(testPlan)
        assertTrue("Fix TEBSA should have type code 11", fms.contains("11 TEBSA"))
    }

    @Test
    fun emptyPlan_zzzz() {
        val empty = FlightPlan()
        val fms = FmsExporter.toFmsV11(empty)
        assertTrue(fms.contains("ADEP ZZZZ"))
        assertTrue(fms.contains("ADES ZZZZ"))
        assertTrue(fms.contains("NUMENR 0"))
    }
}
