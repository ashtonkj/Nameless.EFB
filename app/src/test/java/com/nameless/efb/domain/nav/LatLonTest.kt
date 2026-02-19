package com.nameless.efb.domain.nav

import com.nameless.efb.rendering.terrain.TileKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LatLonTest {

    @Test
    fun `boundingBox center is within box`() {
        val pt = LatLon(-26.1392, 28.2460)  // FAOR
        val box = pt.boundingBox(50.0)
        assertTrue(pt.latitude  in box.latMin..box.latMax)
        assertTrue(pt.longitude in box.lonMin..box.lonMax)
    }

    @Test
    fun `boundingBox latDelta equals radiusNm over 60`() {
        val pt = LatLon(0.0, 0.0)
        val radiusNm = 60.0
        val box = pt.boundingBox(radiusNm)
        // At equator latDelta = 60/60 = 1.0 degree
        assertEquals(1.0, box.latMax - pt.latitude, 1e-9)
        assertEquals(1.0, pt.latitude - box.latMin, 1e-9)
    }

    @Test
    fun `boundingBox lonDelta shrinks at high latitudes`() {
        val equator = LatLon(0.0, 0.0).boundingBox(60.0)
        val highLat = LatLon(60.0, 0.0).boundingBox(60.0)
        // Lon span at lat 60 should be roughly double that at equator
        val lonSpanEq = equator.lonMax - equator.lonMin
        val lonSpanHi = highLat.lonMax - highLat.lonMin
        assertTrue(lonSpanHi > lonSpanEq * 1.5,
            "lon span at lat 60 ($lonSpanHi) should be wider than at equator ($lonSpanEq)")
    }

    @Test
    fun `boundingBox is symmetric around center`() {
        val pt = LatLon(-33.9649, 18.6017)  // FACT
        val box = pt.boundingBox(100.0)
        val latHalf = box.latMax - pt.latitude
        val latHalfLo = pt.latitude - box.latMin
        assertEquals(latHalf, latHalfLo, 1e-9)
    }

    @Test
    fun `TileKey filename formats correctly for southern hemisphere`() {
        assertEquals("S34_E018.f16", TileKey(-34, 18).toFileName())
        assertEquals("N26_E028.f16", TileKey(26, 28).toFileName())
        assertEquals("S01_W001.f16", TileKey(-1, -1).toFileName())
    }
}
