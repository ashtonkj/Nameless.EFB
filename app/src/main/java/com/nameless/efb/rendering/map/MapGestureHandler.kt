package com.nameless.efb.rendering.map

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Translates Android touch events into map navigation commands (UI-04).
 *
 * Supported gestures:
 *  - Pinch-zoom: changes zoom level continuously
 *  - Scroll/pan: moves the map center
 *  - Double-tap: zooms in 2× centred on tap point
 *  - Fling: momentum pan with deceleration (handled by [MapRenderer.fling])
 *
 * Attach via `glSurfaceView.setOnTouchListener(mapGestureHandler)`.
 */
class MapGestureHandler(
    context: Context,
    private val mapRenderer: MapRenderer,
) : View.OnTouchListener {

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                mapRenderer.zoom(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                // distanceX/Y are in screen pixels (positive = moved left/up)
                mapRenderer.pan(distanceX, distanceY)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                mapRenderer.zoom(2f, e.x, e.y)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                mapRenderer.fling(velocityX, velocityY)
                return true
            }
        }
    )

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        v.performClick()
        return true
    }
}
