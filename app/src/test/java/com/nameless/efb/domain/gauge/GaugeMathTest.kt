package com.nameless.efb.domain.gauge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp

class GaugeMathTest {

    // ── needleAngle ───────────────────────────────────────────────────────────

    @Test
    fun `needleAngle at mid-range returns 0 radians`() {
        // 100 kts of a 0–200 kt range with ±150° sweep → exactly mid = 0°
        val angle = needleAngle(100f, 0f, 200f, -150f, 150f)
        assertEquals(0f, angle, 0.001f)
    }

    @Test
    fun `needleAngle at minimum maps to startDeg in radians`() {
        val angle = needleAngle(0f, 0f, 200f, -150f, 150f)
        val expected = Math.toRadians(-150.0).toFloat()
        assertEquals(expected, angle, 0.001f)
    }

    @Test
    fun `needleAngle at maximum maps to endDeg in radians`() {
        val angle = needleAngle(200f, 0f, 200f, -150f, 150f)
        val expected = Math.toRadians(150.0).toFloat()
        assertEquals(expected, angle, 0.001f)
    }

    @Test
    fun `needleAngle clamps below minimum`() {
        val atMin  = needleAngle(0f,   0f, 200f, -150f, 150f)
        val below  = needleAngle(-50f, 0f, 200f, -150f, 150f)
        assertEquals(atMin, below, 0.0001f)
    }

    @Test
    fun `needleAngle clamps above maximum`() {
        val atMax  = needleAngle(200f, 0f, 200f, -150f, 150f)
        val above  = needleAngle(300f, 0f, 200f, -150f, 150f)
        assertEquals(atMax, above, 0.0001f)
    }

    // ── Altimeter helpers ─────────────────────────────────────────────────────

    @Test
    fun `altimeter100sNeedle at 500ft returns half-sweep angle`() {
        // alt = 500 ft → (500 % 1000) / 10 = 50 units → half of 0–100
        // half sweep: (-150 + 210) / 2 + (-150) = -150 + 180 = 30°
        val angle = altimeter100sNeedle(500f)
        val expected = Math.toRadians(30.0).toFloat()
        assertEquals(expected, angle, 0.002f)
    }

    @Test
    fun `altimeter100sNeedle wraps at 1000ft to zero position`() {
        // (1000 % 1000) = 0 → same as altimeter100sNeedle(0f)
        assertEquals(altimeter100sNeedle(0f), altimeter100sNeedle(1000f), 0.0001f)
    }

    @Test
    fun `altimeter1000sNeedle at 5000ft returns half-sweep angle`() {
        // (5000 % 10000) / 100 = 50 units
        val angle = altimeter1000sNeedle(5000f)
        val expected = Math.toRadians(30.0).toFloat()
        assertEquals(expected, angle, 0.002f)
    }

    @Test
    fun `altimeter10kNeedle at 25000ft returns half-sweep angle`() {
        // 25000 / 1000 = 25 kft → half of 0–50 kft range
        val angle = altimeter10kNeedle(25_000f)
        val expected = Math.toRadians(30.0).toFloat()
        assertEquals(expected, angle, 0.002f)
    }

    // ── Fuel conversions ──────────────────────────────────────────────────────

    @Test
    fun `kgSecToLph AVGAS 0_02 kgSec equals 100 LPH`() {
        // 0.02 kg/s × 3600 s/h / 0.72 kg/L = 100 L/h
        assertEquals(100f, kgSecToLph(0.02f, FuelType.AVGAS), 0.1f)
    }

    @Test
    fun `kgSecToLph JetA1 uses 0_80 density`() {
        // 0.04 kg/s × 3600 / 0.80 = 180 L/h
        assertEquals(180f, kgSecToLph(0.04f, FuelType.JET_A1), 0.1f)
    }

    @Test
    fun `kgSecToLph zero flow returns zero`() {
        assertEquals(0f, kgSecToLph(0f, FuelType.AVGAS), 0.0001f)
    }

    // ── fuelRangeNm ───────────────────────────────────────────────────────────

    @Test
    fun `fuelRangeNm returns zero when flow is zero`() {
        assertEquals(0f, fuelRangeNm(100f, 0f, 120f), 0.0001f)
    }

    @Test
    fun `fuelRangeNm returns zero when tas is zero`() {
        assertEquals(0f, fuelRangeNm(100f, 0.02f, 0f), 0.0001f)
    }

    @Test
    fun `fuelRangeNm computes correctly`() {
        // 36 kg fuel / (0.01 kg/s × 3600 s/h) = 1 hour endurance; TAS 120 kts → 120 nm
        assertEquals(120f, fuelRangeNm(36f, 0.01f, 120f), 0.1f)
    }

    // ── iirStep ───────────────────────────────────────────────────────────────

    @Test
    fun `iirStep output moves toward target`() {
        val next = iirStep(current = 0f, target = 1000f, dtSec = 0.1f)
        assertTrue(next > 0f && next < 1000f)
    }

    @Test
    fun `iirStep approaches target after many steps`() {
        var displayed = 0f
        // 60 steps of 16 ms ≈ 1 second; tau = 6 s → ~15 % of 2000 fpm
        repeat(60) { displayed = iirStep(displayed, 2000f, 0.016f) }
        // After ~1 s with tau=6, filter has reached ~(1-exp(-1/6))×2000 ≈ 289 fpm
        assertTrue(displayed > 250f) { "Expected >250 after 1s; got $displayed" }
        assertTrue(displayed < 500f) { "Expected <500 (not fully settled); got $displayed" }
    }

    @Test
    fun `iirStep stays at target when already there`() {
        val result = iirStep(current = 500f, target = 500f, dtSec = 1f)
        assertEquals(500f, result, 0.001f)
    }
}
