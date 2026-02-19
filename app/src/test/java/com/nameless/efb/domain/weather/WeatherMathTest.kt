package com.nameless.efb.domain.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherMathTest {

    // ── crosswindComponent ────────────────────────────────────────────────────

    @Test
    fun crosswind_perpendicular_fromRight() {
        // Wind FROM 360 (north), leg bearing 090 (east) → full crosswind from right
        val xw = crosswindComponent(windDirDeg = 360f, windSpeedKt = 20f, legBearingDeg = 90f)
        assertEquals(-20f, xw, 0.5f)
    }

    @Test
    fun crosswind_aligned_noComponent() {
        // Wind FROM 090, leg bearing 090 → headwind, no crosswind
        val xw = crosswindComponent(windDirDeg = 90f, windSpeedKt = 20f, legBearingDeg = 90f)
        assertEquals(0f, xw, 0.5f)
    }

    @Test
    fun crosswind_fromLeft_positive() {
        // Wind FROM 270, leg bearing 090 → crosswind from left (+)
        val xw = crosswindComponent(windDirDeg = 270f, windSpeedKt = 20f, legBearingDeg = 90f)
        assertEquals(20f, xw, 0.5f)
    }

    // ── headwindComponent ─────────────────────────────────────────────────────

    @Test
    fun headwind_directHeadwind() {
        // Wind FROM 090, leg 090 → full headwind
        val hw = headwindComponent(windDirDeg = 90f, windSpeedKt = 15f, legBearingDeg = 90f)
        assertEquals(15f, hw, 0.5f)
    }

    @Test
    fun headwind_tailwind_isNegative() {
        // Wind FROM 270, leg 090 → tailwind
        val hw = headwindComponent(windDirDeg = 270f, windSpeedKt = 15f, legBearingDeg = 90f)
        assertEquals(-15f, hw, 0.5f)
    }

    // ── isInGlideRange ────────────────────────────────────────────────────────

    @Test
    fun glideRange_withinRange() {
        // 3000ft → 50nm glide range; 5nm should be reachable
        assertTrue(isInGlideRange(distanceNm = 5.0, altitudeFt = 3000f))
    }

    @Test
    fun glideRange_outsideRange() {
        // 3000ft → 50nm; 55nm is too far
        assertFalse(isInGlideRange(distanceNm = 55.0, altitudeFt = 3000f))
    }

    @Test
    fun glideRange_exactBoundary() {
        // 3000ft → 50nm exactly; should be within range (<=)
        assertTrue(isInGlideRange(distanceNm = 50.0, altitudeFt = 3000f))
    }

    @Test
    fun glideRange_zeroAltitude() {
        assertFalse(isInGlideRange(distanceNm = 0.1, altitudeFt = 0f))
    }

    // ── TawsLut / buildTawsLut ────────────────────────────────────────────────

    @Test
    fun tawsLut_redBelowWarningThreshold() {
        val lut = buildTawsLut()
        // < 492ft (≈ 150m) → RED
        val color = lut.sample(clearanceFt = 50f)
        assertEquals(TawsLut.ARGB_RED, color)
    }

    @Test
    fun tawsLut_yellowBelowCautionThreshold() {
        val lut = buildTawsLut()
        // 492–984ft (≈ 150–300m) → YELLOW
        val color = lut.sample(clearanceFt = 700f)
        assertEquals(TawsLut.ARGB_YELLOW, color)
    }

    @Test
    fun tawsLut_greenAboveCautionThreshold() {
        val lut = buildTawsLut()
        // > 984ft → GREEN
        val color = lut.sample(clearanceFt = 2000f)
        assertEquals(TawsLut.ARGB_GREEN, color)
    }

    @Test
    fun tawsLut_boundaryAtWarning() {
        val lut = buildTawsLut()
        // Exactly 492ft → still RED (strictly less-than check)
        assertEquals(TawsLut.ARGB_RED, lut.sample(491f))
        assertEquals(TawsLut.ARGB_YELLOW, lut.sample(492f))
    }
}
