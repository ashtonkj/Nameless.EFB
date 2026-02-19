package com.nameless.efb.domain.gauge

/**
 * Second-order spring-damper used for needle over-swing simulation (SG-19).
 *
 * Models an underdamped mechanical system: the needle overshoots and rings
 * before settling at the target value.
 *
 * Default parameters (`omega = 15`, `zeta = 0.8`) produce ≤5 % overshoot,
 * satisfying the acceptance criterion.
 *
 * Usage (once per frame on the GL thread):
 * ```kotlin
 * damper.update(target = needleAngle, dt = dtSec)
 * glUniform1f(angleLoc, damper.position)
 * ```
 *
 * @param omega  Natural frequency in rad/s (controls settling speed).
 * @param zeta   Damping ratio (< 1 = underdamped; > 1 = overdamped; 1 = critical).
 */
class SpringDamper(val omega: Float = 15f, val zeta: Float = 0.8f) {

    /** Current spring position (displayed value). */
    var position: Float = 0f
        private set

    /** Current spring velocity. */
    var velocity: Float = 0f
        private set

    /**
     * Advance the spring by one time step toward [target].
     *
     * @param target  Target position (e.g. the true needle angle in radians).
     * @param dt      Frame delta in seconds.
     */
    fun update(target: Float, dt: Float) {
        val force = -2f * zeta * omega * velocity - omega * omega * (position - target)
        velocity += force * dt
        position += velocity * dt
    }

    /**
     * Snap position and velocity to given values immediately (no physics).
     * Useful on gauge power-up or hidden-to-visible transition.
     */
    fun reset(value: Float = 0f) {
        position = value
        velocity = 0f
    }
}
