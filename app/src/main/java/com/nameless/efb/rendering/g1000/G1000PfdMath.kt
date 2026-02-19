package com.nameless.efb.rendering.g1000

import kotlin.math.abs
import kotlin.math.sign

/**
 * Pure math functions for G1000 PFD rendering.
 *
 * No Android or OpenGL dependencies — all functions are testable on the JVM.
 */
object G1000PfdMath {

    /**
     * Returns the pixel distance between adjacent pitch ladder lines.
     *
     * @param pixPerDeg  pixels per degree of pitch (24 px/deg for G1000 CRG)
     * @param intervalDeg  interval between ladder lines in degrees (e.g. 10°)
     */
    fun computePitchLadderSpacing(pixPerDeg: Float, intervalDeg: Float): Float =
        pixPerDeg * intervalDeg

    /**
     * Returns CDI deflection in pixels from the centre of the HSI.
     *
     * @param hdefDot     horizontal deflection in dots (±2.5 = full scale)
     * @param dotSpacingPx  pixel distance between adjacent dots
     */
    fun computeCdiPosition(hdefDot: Float, dotSpacingPx: Float): Float =
        hdefDot * dotSpacingPx

    /**
     * Converts altimeter setting from inHg to hPa.
     *
     * Standard conversion: 1 inHg = 33.8639 hPa.
     */
    fun inHgToHpa(inhg: Float): Float = inhg * 33.8639f

    /**
     * Computes the IAS trend vector (6-second projection of rate of change),
     * capped at ±40 kt per G1000 CRG.
     *
     * @param prevIas        IAS at previous sample (kt)
     * @param currIas        IAS at current sample (kt)
     * @param dtSec          elapsed time between samples (s)
     * @param projectionSec  projection horizon in seconds (6 s for G1000)
     */
    fun computeTrendVector(
        prevIas: Float,
        currIas: Float,
        dtSec: Float,
        projectionSec: Float,
    ): Float {
        val rate = (currIas - prevIas) / dtSec
        return (rate * projectionSec).coerceIn(-40f, 40f)
    }

    /**
     * Returns true when the aircraft is at or above the transition altitude,
     * triggering the "TL FLxxx" annunciation on the altitude tape.
     *
     * South Africa default transition altitude: FL180 (18 000 ft).
     */
    fun shouldAnnounceTransition(altFt: Float, transitionAlt: Float): Boolean =
        altFt >= transitionAlt

    /**
     * Maps a VSI value (fpm) to a pixel offset on the VSI tape.
     *
     * Scale is linear from 0–2 000 fpm and compressed logarithmically
     * above 2 000 fpm (up to 6 000 fpm).
     *
     * @param vsi             vertical speed in fpm (positive = climb)
     * @param tapeHalfHeight  half the tape height in pixels (full scale = 2 000 fpm)
     */
    fun vsiToPixelOffset(vsi: Float, tapeHalfHeight: Float): Float =
        if (abs(vsi) <= 2000f) {
            vsi / 2000f * tapeHalfHeight
        } else {
            val excess = (abs(vsi) - 2000f) / 4000f
            sign(vsi) * tapeHalfHeight * (1f + excess * 0.2f)
        }
}
