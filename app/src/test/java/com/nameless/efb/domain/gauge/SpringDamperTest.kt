package com.nameless.efb.domain.gauge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.max

class SpringDamperTest {

    @Test
    fun `overshoot is less than 5 percent for default parameters`() {
        val damper = SpringDamper(omega = 15f, zeta = 0.8f)
        var maxOvershoot = 0f
        // Step input: 0 → 1.0, 200 frames at 16ms each (≈ 3.2 seconds)
        repeat(200) {
            damper.update(target = 1f, dt = 0.016f)
            if (damper.position > 1f) {
                maxOvershoot = max(maxOvershoot, damper.position - 1f)
            }
        }
        assertTrue(maxOvershoot < 0.05f) {
            "Overshoot was ${maxOvershoot * 100}% — expected < 5%"
        }
    }

    @Test
    fun `position approaches target over time`() {
        val damper = SpringDamper()
        repeat(500) { damper.update(target = 100f, dt = 0.016f) }
        assertEquals(100f, damper.position, 0.5f)
    }

    @Test
    fun `reset snaps position without physics`() {
        val damper = SpringDamper()
        repeat(20) { damper.update(target = 50f, dt = 0.016f) }
        damper.reset(0f)
        assertEquals(0f, damper.position, 0.0001f)
    }

    @Test
    fun `stays at target when already settled`() {
        val damper = SpringDamper()
        damper.reset(1f)
        // With zero velocity and zero error, no motion should occur
        damper.update(target = 1f, dt = 0.016f)
        assertEquals(1f, damper.position, 0.0001f)
    }

    @Test
    fun `higher omega settles faster`() {
        // omega=2: settling time ≈ 4/(0.8×2) = 2.5 s → at 1 s still ~0.65
        // omega=10: settling time ≈ 4/(0.8×10) = 0.5 s → at 1 s should be ~1.0
        val slow = SpringDamper(omega = 2f,  zeta = 0.8f)
        val fast = SpringDamper(omega = 10f, zeta = 0.8f)
        repeat(62) {  // ≈ 1 second at 16 ms per step
            slow.update(1f, 0.016f)
            fast.update(1f, 0.016f)
        }
        assertTrue(fast.position > slow.position) {
            "Fast (omega=10) at ${fast.position} should exceed slow (omega=2) at ${slow.position}"
        }
    }
}
