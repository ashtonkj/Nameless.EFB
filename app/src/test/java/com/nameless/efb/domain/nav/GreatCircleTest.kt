package com.nameless.efb.domain.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GreatCircleTest {

    // ── distanceNm ────────────────────────────────────────────────────────────

    @Test
    fun `distanceNm FAOR to FACT`() {
        // FAOR (Johannesburg) to FACT (Cape Town)
        // Great-circle Vincenty distance ≈ 686 nm
        // (Plan document value of 740 nm is the published airways route distance,
        //  not the direct great-circle distance)
        val d = GreatCircle.distanceNm(
            LatLon(-26.1392, 28.2462),  // FAOR
            LatLon(-33.9648, 18.6017),  // FACT
        )
        assertEquals(686.0, d, 5.0)
    }

    @Test
    fun `distanceNm coincident points returns zero`() {
        val p = LatLon(-26.0, 28.0)
        assertEquals(0.0, GreatCircle.distanceNm(p, p), 0.001)
    }

    @Test
    fun `distanceNm equator quarter circle is roughly 5400nm`() {
        // Vincenty: equatorial arc from (0°, 0°) to (0°, 90°) ≈ 5410 nm
        // (equatorial circumference ≈ 21,638 nm; quarter ≈ 5409.5 nm)
        val d = GreatCircle.distanceNm(LatLon(0.0, 0.0), LatLon(0.0, 90.0))
        assertEquals(5410.0, d, 15.0)
    }

    // ── initialBearingDeg ─────────────────────────────────────────────────────

    @Test
    fun `initialBearing due east is 90`() {
        val b = GreatCircle.initialBearingDeg(LatLon(0.0, 0.0), LatLon(0.0, 10.0))
        assertEquals(90.0, b, 0.5)
    }

    @Test
    fun `initialBearing due north is 0`() {
        val b = GreatCircle.initialBearingDeg(LatLon(0.0, 0.0), LatLon(10.0, 0.0))
        assertEquals(0.0, b, 0.5)
    }

    @Test
    fun `FAOR to FACT bearing is south-westerly`() {
        val b = GreatCircle.initialBearingDeg(
            LatLon(-26.1392, 28.2462),  // FAOR
            LatLon(-33.9648, 18.6017),  // FACT
        )
        // FACT is south-west of FAOR → bearing ~225–240°
        assertTrue(b in 210.0..260.0) {
            "FAOR→FACT bearing should be south-westerly; got $b°"
        }
    }

    // ── magneticBearing ───────────────────────────────────────────────────────

    @Test
    fun `magneticVariation SA region is westerly`() {
        // At FAOR (lat=-26, lon=28), variation ≈ -24.5° (westerly)
        // trueBearing=45° → magBearing ≈ 45 - (-24.5) = 69.5°, which is in 60..75
        val magBearing = GreatCircle.magneticBearing(
            trueBearing = 45.0,
            lat = -26.0,
            lon = 28.0,
        )
        assertTrue(magBearing in 60.0..75.0) {
            "Magnetic bearing should be in 60..75°; got $magBearing°"
        }
    }

    @Test
    fun `magneticBearing north true is north-east magnetic in SA`() {
        // trueBearing=0° (north), variation≈-24.5° → magBearing≈24.5°
        val magBearing = GreatCircle.magneticBearing(0.0, lat = -26.0, lon = 28.0)
        assertEquals(24.5, magBearing, 1.0)
    }

    // ── MagneticVariation ─────────────────────────────────────────────────────

    @Test
    fun `MagneticVariation at FAOR is approximately minus 24_5 degrees`() {
        val v = MagneticVariation.compute(-26.0, 28.0)
        assertEquals(-24.5, v, 0.1)
    }

    @Test
    fun `MagneticVariation southward increases westerly component`() {
        val north = MagneticVariation.compute(-20.0, 28.0)  // more northerly
        val south = MagneticVariation.compute(-32.0, 28.0)  // more southerly
        // More westerly (more negative) further south
        assertTrue(south < north) {
            "Southern variation ($south) should be more westerly than northern ($north)"
        }
    }
}
