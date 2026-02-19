package com.nameless.efb.domain.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectionTest {

    // ── latLonToTile ──────────────────────────────────────────────────────────

    @Test
    fun `latLonToTile FAOR at zoom 12`() {
        // FAOR: lat=-26.1392, lon=28.2462
        // Correct OSM tile at z12: x=2369, y=2356
        // (Plan document values 2423/2037 are incorrect)
        val tile = latLonToTile(-26.1392, 28.2462, zoom = 12)
        assertEquals(2369, tile.x)
        assertEquals(2356, tile.y)
        assertEquals(12, tile.z)
    }

    @Test
    fun `latLonToTile FACT at zoom 12`() {
        // FACT: lat=-33.9648, lon=18.6017
        val tile = latLonToTile(-33.9648, 18.6017, zoom = 12)
        assertEquals(2259, tile.x)
        assertEquals(2459, tile.y)
    }

    @Test
    fun `latLonToTile null island origin`() {
        // lat=0, lon=0 → tile (2048, 2048) at zoom 12
        val tile = latLonToTile(0.0, 0.0, zoom = 12)
        assertEquals(2048, tile.x)
        assertEquals(2048, tile.y)
    }

    @Test
    fun `latLonToTile antimeridian west edge`() {
        // lon=-180 → x=0 at any zoom
        val tile = latLonToTile(0.0, -180.0, zoom = 8)
        assertEquals(0, tile.x)
    }

    // ── WebMercator round-trip ────────────────────────────────────────────────

    @Test
    fun `WebMercator toMeters and back is identity`() {
        val lat = -26.1392; val lon = 28.2462
        val meters = WebMercator.toMeters(lat, lon)
        val latLon = WebMercator.toLatLon(meters[0], meters[1])
        assertEquals(lat, latLon.latitude,  0.0001)
        assertEquals(lon, latLon.longitude, 0.0001)
    }

    @Test
    fun `WebMercator null island is origin`() {
        val m = WebMercator.toMeters(0.0, 0.0)
        assertEquals(0.0, m[0], 0.01)
        assertEquals(0.0, m[1], 0.01)
    }

    // ── lerp ─────────────────────────────────────────────────────────────────

    @Test
    fun `lerp midpoint`() {
        assertEquals(-26.005, lerp(-26.0, -26.01, 0.5), 0.0001)
    }

    @Test
    fun `lerp at t=0 returns a`() {
        assertEquals(10.0, lerp(10.0, 20.0, 0.0), 0.0001)
    }

    @Test
    fun `lerp at t=1 returns b`() {
        assertEquals(20.0, lerp(10.0, 20.0, 1.0), 0.0001)
    }

    // ── lerpAngle ────────────────────────────────────────────────────────────

    @Test
    fun `lerpAngle wraps correctly across 360 boundary`() {
        // from=350°, to=10°: short path is +20°, not -340°
        val result = lerpAngle(350f, 10f, 0.5f)
        // expected: 350 + 20*0.5 = 360 = 0°, but we don't normalise output
        assertEquals(360f, result, 0.01f)
    }
}
