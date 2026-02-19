package com.nameless.efb.rendering.g1000

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for G1000 PFD math functions (G-01 through G-31).
 *
 * All tests operate on pure Kotlin functions in [G1000PfdMath] —
 * no Android or OpenGL dependencies, runs on JVM.
 */
class G1000PfdTest {

    // ── G-01: Pitch ladder spacing ────────────────────────────────────────────

    @Test
    fun pitchLadder_spacingCorrect() {
        // At 24 px/deg, adjacent 10° lines must be 240 px apart.
        val spacing = G1000PfdMath.computePitchLadderSpacing(pixPerDeg = 24f, intervalDeg = 10f)
        assertEquals(240f, spacing, 1f)
    }

    // ── G-05: CDI deflection ──────────────────────────────────────────────────

    @Test
    fun cdiDeflection_fullScale() {
        // 2.5 dots × 40 px/dot = 100 px full-scale deflection.
        val deflectionPx = G1000PfdMath.computeCdiPosition(hdefDot = 2.5f, dotSpacingPx = 40f)
        assertEquals(100f, deflectionPx, 1f)
    }

    @Test
    fun cdiDeflection_centred() {
        val deflectionPx = G1000PfdMath.computeCdiPosition(hdefDot = 0f, dotSpacingPx = 40f)
        assertEquals(0f, deflectionPx, 0.01f)
    }

    @Test
    fun cdiDeflection_leftFullScale() {
        val deflectionPx = G1000PfdMath.computeCdiPosition(hdefDot = -2.5f, dotSpacingPx = 40f)
        assertEquals(-100f, deflectionPx, 1f)
    }

    // ── G-03: Kollsman window conversion ──────────────────────────────────────

    @Test
    fun altimeterKollsman_hpaConversion() {
        // Standard pressure 29.92 inHg ≈ 1013 hPa.
        val hpa = G1000PfdMath.inHgToHpa(29.92f)
        assertEquals(1013f, hpa, 1f)
    }

    @Test
    fun altimeterKollsman_lowPressure() {
        // 29.00 inHg → 981.85 hPa.
        val hpa = G1000PfdMath.inHgToHpa(29.00f)
        assertEquals(982f, hpa, 1f)
    }

    // ── G-02: Trend vector ────────────────────────────────────────────────────

    @Test
    fun trendVector_cappedAt40kt() {
        // Rate of 60 kt/s × 6 s projection = 360 kt, capped to 40 kt.
        val trend = G1000PfdMath.computeTrendVector(
            prevIas = 100f, currIas = 160f, dtSec = 1f, projectionSec = 6f
        )
        assertEquals(40f, trend, 0.1f)
    }

    @Test
    fun trendVector_withinCap() {
        // Rate of 1 kt/s × 6 s = 6 kt (not capped).
        val trend = G1000PfdMath.computeTrendVector(
            prevIas = 120f, currIas = 121f, dtSec = 1f, projectionSec = 6f
        )
        assertEquals(6f, trend, 0.01f)
    }

    @Test
    fun trendVector_negativeDecelerationCapped() {
        // Deceleration: −60 kt/s × 6 s → capped at −40 kt.
        val trend = G1000PfdMath.computeTrendVector(
            prevIas = 160f, currIas = 100f, dtSec = 1f, projectionSec = 6f
        )
        assertEquals(-40f, trend, 0.1f)
    }

    // ── G-03: Transition altitude annunciation ────────────────────────────────

    @Test
    fun transitionAltitudeAnnunciation_fl180() {
        // At exactly FL180 (18 000 ft), annunciation must trigger.
        val annunciated = G1000PfdMath.shouldAnnounceTransition(
            altFt = 18000f, transitionAlt = 18000f
        )
        assertTrue(annunciated)
    }

    @Test
    fun transitionAltitudeAnnunciation_belowTransition() {
        // Below FL180 — no annunciation.
        val annunciated = G1000PfdMath.shouldAnnounceTransition(
            altFt = 17999f, transitionAlt = 18000f
        )
        assertFalse(annunciated)
    }

    @Test
    fun transitionAltitudeAnnunciation_aboveTransition() {
        // Above FL180 — still annunciated.
        val annunciated = G1000PfdMath.shouldAnnounceTransition(
            altFt = 25000f, transitionAlt = 18000f
        )
        assertTrue(annunciated)
    }

    // ── G-04: VSI pixel offset ────────────────────────────────────────────────

    @Test
    fun vsiOffset_linearZero() {
        val offset = G1000PfdMath.vsiToPixelOffset(vsi = 0f, tapeHalfHeight = 100f)
        assertEquals(0f, offset, 0.01f)
    }

    @Test
    fun vsiOffset_linearFullScale() {
        val offset = G1000PfdMath.vsiToPixelOffset(vsi = 2000f, tapeHalfHeight = 100f)
        assertEquals(100f, offset, 0.01f)
    }

    @Test
    fun vsiOffset_compressedAbove2000() {
        // Above 2 000 fpm the scale compresses; offset must exceed tapeHalfHeight slightly.
        val offset = G1000PfdMath.vsiToPixelOffset(vsi = 4000f, tapeHalfHeight = 100f)
        assertTrue(offset > 100f)
    }

    @Test
    fun vsiOffset_negativeClimb() {
        val offset = G1000PfdMath.vsiToPixelOffset(vsi = -1000f, tapeHalfHeight = 100f)
        assertEquals(-50f, offset, 0.1f)
    }
}
