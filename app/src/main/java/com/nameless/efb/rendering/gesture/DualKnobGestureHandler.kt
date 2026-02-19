package com.nameless.efb.rendering.gesture

import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

/**
 * Tracks two independent circular-knob gestures simultaneously (SG-17).
 *
 * Each finger is assigned to the nearest knob on touch-down and tracked
 * independently through all subsequent move events.  The two resulting
 * dataref writes are batched into a single `set_multi` command packet so
 * the sim receives them atomically within the same network frame.
 *
 * @param knob1 First [CircularKnobGestureDetector] (e.g. heading-bug knob).
 * @param knob2 Second [CircularKnobGestureDetector] (e.g. OBS knob).
 */
class DualKnobGestureHandler(
    private val knob1: CircularKnobGestureDetector,
    private val knob2: CircularKnobGestureDetector,
) : View.OnTouchListener {

    private val activePointers = mutableMapOf<Int, CircularKnobGestureDetector>()

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        val pointerId    = event.getPointerId(pointerIndex)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                val knob = nearerKnob(x, y)
                activePointers[pointerId] = knob
                knob.startGesture(x, y, event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                repeat(event.pointerCount) { i ->
                    val id = event.getPointerId(i)
                    activePointers[id]?.updateGesture(
                        event.getX(i), event.getY(i), event.eventTime
                    )
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                activePointers.remove(pointerId)
            }
            MotionEvent.ACTION_CANCEL -> activePointers.clear()
        }
        return true
    }

    private fun nearerKnob(x: Float, y: Float): CircularKnobGestureDetector {
        val d1 = distance(knob1.center.x, knob1.center.y, x, y)
        val d2 = distance(knob2.center.x, knob2.center.y, x, y)
        return if (d1 <= d2) knob1 else knob2
    }

    private fun distance(cx: Float, cy: Float, x: Float, y: Float): Float {
        val dx = x - cx
        val dy = y - cy
        return sqrt(dx * dx + dy * dy)
    }
}
