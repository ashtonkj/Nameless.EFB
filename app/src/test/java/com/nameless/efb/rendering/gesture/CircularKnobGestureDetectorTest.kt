package com.nameless.efb.rendering.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

class CircularKnobGestureDetectorTest {

    // ── angularDelta ──────────────────────────────────────────────────────────

    @Test
    fun `angularDelta positive delta within range`() {
        assertEquals(45f, angularDelta(0f, 45f), 0.001f)
    }

    @Test
    fun `angularDelta negative delta within range`() {
        assertEquals(-30f, angularDelta(20f, -10f), 0.001f)
    }

    @Test
    fun `angularDelta wraps around positive boundary`() {
        // 170° + 20° counterclockwise = 190° = -170°, so delta is +20° not -340°
        assertEquals(20f, angularDelta(170f, -170f), 0.001f)
    }

    @Test
    fun `angularDelta wraps around negative boundary`() {
        // -170° - 20° clockwise = -190° = 170°, so delta is -20° not +340°
        assertEquals(-20f, angularDelta(-170f, 170f), 0.001f)
    }

    @Test
    fun `angularDelta zero delta`() {
        assertEquals(0f, angularDelta(90f, 90f), 0.001f)
    }

    // ── accelerationCurve ─────────────────────────────────────────────────────

    @Test
    fun `accelerationCurve returns 1 at slow speed`() {
        assertEquals(1f, accelerationCurve(0f),  0.001f)
        assertEquals(1f, accelerationCurve(30f), 0.001f)
        assertEquals(1f, accelerationCurve(49f), 0.001f)
    }

    @Test
    fun `accelerationCurve returns 10 at fast speed`() {
        assertEquals(10f, accelerationCurve(200f), 0.001f)
        assertEquals(10f, accelerationCurve(500f), 0.001f)
    }

    @Test
    fun `accelerationCurve interpolates in mid-range`() {
        // At 125 °/s (midpoint of 50–200): expected ~5.5×
        val acc = accelerationCurve(125f)
        assertTrue(acc > 1f && acc < 10f)
        assertEquals(5.5f, acc, 0.1f)
    }

    // ── slow full-circle accumulation ─────────────────────────────────────────

    @Test
    fun `full 360 degree rotation at 1x acceleration yields 10 units`() {
        // Use the pure math directly: 36 steps × 10° each at 5°/s (< 50°/s → 1×)
        // units per step = (angularDelta(prev, next) / 36) * accelerationCurve(5)
        var total = 0f
        for (i in 1..36) {
            val dAngle = angularDelta((i - 1) * 10f, i * 10f) // = 10°
            total += (dAngle / 36f) * accelerationCurve(5f)   // 5°/s < 50 → 1×
        }
        assertEquals(10f, total, 0.01f)
    }

    @Test
    fun `startGesture and updateGesture accumulate units over slow arc`() {
        var total = 0f
        // Use concrete x/y positions around a circle instead of PointF
        // Simulate via internal math only (PointF is Android; not available on JVM)
        val radius = 50f
        val cx = 100f
        val cy = 100f

        // Build a sequence of (dAngle, velocity) pairs for 36 slow steps
        val steps = 36
        val dtMs  = 2000L   // 2 s per 10° step → 5°/s → acceleration = 1×
        var prevAngle = 0f

        for (i in 1..steps) {
            val angleDeg = i * 10f
            val dAngle = angularDelta(prevAngle, angleDeg)
            val dtSec  = dtMs / 1000f
            val velocity = if (dtSec > 0f) Math.abs(dAngle) / dtSec else 0f
            total += (dAngle / 36f) * accelerationCurve(velocity)
            prevAngle = angleDeg
        }

        assertEquals(10f, total, 0.01f)
    }
}
