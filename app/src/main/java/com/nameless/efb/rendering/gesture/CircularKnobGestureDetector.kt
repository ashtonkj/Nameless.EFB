package com.nameless.efb.rendering.gesture

import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2

// ── Pure math helpers (internal so unit tests can reach them) ─────────────────

/**
 * Shortest signed angular difference from [from] to [to], in degrees.
 * Result is always in (−180, +180].
 */
internal fun angularDelta(from: Float, to: Float): Float {
    var delta = to - from
    while (delta > 180f)  delta -= 360f
    while (delta < -180f) delta += 360f
    return delta
}

/**
 * Velocity-sensitive acceleration curve for the circular knob.
 *
 * Slow gestures (< 50 °/s) use 1× speed; fast gestures (> 200 °/s) use 10×;
 * the range in between is linearly interpolated.
 */
internal fun accelerationCurve(degSec: Float): Float = when {
    degSec < 50f  -> 1f
    degSec > 200f -> 10f
    else          -> 1f + 9f * (degSec - 50f) / 150f
}

// ── Detector ──────────────────────────────────────────────────────────────────

/**
 * Converts angular finger motion around [center] to dataref-write units (SG-15).
 *
 * One full 360° circle at 1× speed = 10 units (36°/unit).  Fast swipes scale
 * up to 10× so the pilot can quickly traverse large ranges.
 *
 * Implements both [View.OnTouchListener] (for single-touch use) and explicit
 * [startGesture]/[updateGesture] entry points used by [DualKnobGestureHandler].
 *
 * @param center     Knob centre in view pixel coordinates.
 * @param onRotation Called with signed delta-units each move event.
 */
class CircularKnobGestureDetector(
    val center: PointF,
    private val onRotation: (deltaUnits: Float) -> Unit,
) : View.OnTouchListener {

    private var lastAngleDeg: Float = 0f
    private var lastEventTime: Long = 0L

    /** Begin tracking from touch-down at ([x], [y]) at [eventTime] ms. */
    fun startGesture(x: Float, y: Float, eventTime: Long) {
        lastAngleDeg = Math.toDegrees(
            atan2((y - center.y).toDouble(), (x - center.x).toDouble())
        ).toFloat()
        lastEventTime = eventTime
    }

    /** Update gesture from a move event at ([x], [y]) at [eventTime] ms. */
    fun updateGesture(x: Float, y: Float, eventTime: Long) {
        val touchAngle = Math.toDegrees(
            atan2((y - center.y).toDouble(), (x - center.x).toDouble())
        ).toFloat()

        val dtSec = (eventTime - lastEventTime) / 1000f
        val dAngle = angularDelta(lastAngleDeg, touchAngle)

        if (dtSec > 0f) {
            val angularVelocityDegSec = abs(dAngle) / dtSec
            val acceleration = accelerationCurve(angularVelocityDegSec)
            onRotation((dAngle / 36f) * acceleration)
        }

        lastAngleDeg = touchAngle
        lastEventTime = eventTime
    }

    // ── View.OnTouchListener (single-touch) ───────────────────────────────────

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startGesture(event.x, event.y, event.eventTime)
            MotionEvent.ACTION_MOVE -> updateGesture(event.x, event.y, event.eventTime)
        }
        return true
    }
}
