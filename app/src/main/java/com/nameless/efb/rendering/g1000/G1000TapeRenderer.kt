package com.nameless.efb.rendering.g1000

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils

/**
 * Pre-renders tall tape strip textures for the G1000 PFD airspeed and altitude
 * tapes using Canvas, then uploads to GL textures.
 *
 * The tape textures are sampled with a scrolling V-coordinate in the
 * `tape.vert` / `tape.frag` shaders.
 *
 * Must be created on the GL thread.
 */
class G1000TapeRenderer(typeface: Typeface) {

    /** GL texture ID for the airspeed tape strip. */
    val airspeedTextureId: Int

    /** GL texture ID for the altitude tape strip. */
    val altitudeTextureId: Int

    /** Total height in pixels of the airspeed tape texture. */
    val airspeedTapeHeight: Int

    /** Total height in pixels of the altitude tape texture. */
    val altitudeTapeHeight: Int

    init {
        val iasPair = renderAirspeedTape(typeface)
        airspeedTextureId = iasPair.first
        airspeedTapeHeight = iasPair.second

        val altPair = renderAltitudeTape(typeface)
        altitudeTextureId = altPair.first
        altitudeTapeHeight = altPair.second
    }

    fun release() {
        GLES30.glDeleteTextures(2, intArrayOf(airspeedTextureId, altitudeTextureId), 0)
    }

    // ── Airspeed tape ──────────────────────────────────────────────────────────

    private fun renderAirspeedTape(typeface: Typeface): Pair<Int, Int> {
        // Airspeed range: 0-300 kts, 10 pixels per knot.
        val pxPerKt = 10
        val maxKts = 300
        val w = 128
        val h = maxKts * pxPerKt

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF1A1A1A.toInt())

        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            textAlign = Paint.Align.RIGHT
            this.typeface = typeface
        }

        // V-speed color bands (left edge).
        drawVspeedBands(canvas, pxPerKt, h)

        for (kts in 0..maxKts) {
            // Y position: 0 kts at bottom of texture, 300 at top.
            val y = h - kts * pxPerKt
            if (kts % 10 == 0) {
                // Major tick + number.
                canvas.drawLine((w - 25f), y.toFloat(), w.toFloat(), y.toFloat(), tickPaint)
                canvas.drawText("$kts", (w - 30f), y + 8f, numPaint)
            } else if (kts % 5 == 0) {
                // Minor tick.
                tickPaint.strokeWidth = 1f
                canvas.drawLine((w - 15f), y.toFloat(), w.toFloat(), y.toFloat(), tickPaint)
                tickPaint.strokeWidth = 2f
            }
        }

        val texId = uploadBitmap(bmp)
        bmp.recycle()
        return texId to h
    }

    private fun drawVspeedBands(canvas: Canvas, pxPerKt: Int, totalH: Int) {
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            strokeWidth = 0f
        }
        val bandWidth = 6f

        // Low-speed red band: 0 to 50 kt (stall / low-speed awareness per G1000 CRG).
        bandPaint.color = Color.RED
        drawBand(canvas, bandPaint, 0, 50, pxPerKt, totalH, 0f, bandWidth)

        // White arc: Vso (40) to Vfe (85).
        bandPaint.color = Color.WHITE
        drawBand(canvas, bandPaint, 40, 85, pxPerKt, totalH, bandWidth, bandWidth)

        // Green arc: Vs1 (48) to Vno (127).
        bandPaint.color = Color.GREEN
        drawBand(canvas, bandPaint, 48, 127, pxPerKt, totalH, bandWidth, bandWidth)

        // Yellow arc: Vno (127) to Vne (163).
        bandPaint.color = Color.YELLOW
        drawBand(canvas, bandPaint, 127, 163, pxPerKt, totalH, bandWidth, bandWidth)

        // Red line at Vne (163).
        bandPaint.color = Color.RED
        drawBand(canvas, bandPaint, 163, 165, pxPerKt, totalH, 0f, bandWidth * 2)

        // Barber pole above Vne: alternating red/white.
        for (kts in 163..300 step 4) {
            val endKts = (kts + 2).coerceAtMost(300)
            bandPaint.color = if (((kts - 163) / 4) % 2 == 0) Color.RED else Color.WHITE
            drawBand(canvas, bandPaint, kts, endKts, pxPerKt, totalH, bandWidth * 2, bandWidth)
        }
    }

    private fun drawBand(
        canvas: Canvas, paint: Paint,
        ktsStart: Int, ktsEnd: Int, pxPerKt: Int, totalH: Int,
        xOffset: Float, width: Float,
    ) {
        val y1 = totalH - ktsEnd * pxPerKt
        val y2 = totalH - ktsStart * pxPerKt
        canvas.drawRect(xOffset, y1.toFloat(), xOffset + width, y2.toFloat(), paint)
    }

    // ── Altitude tape ──────────────────────────────────────────────────────────

    private fun renderAltitudeTape(typeface: Typeface): Pair<Int, Int> {
        // Altitude range: -1000 to 50000 ft, 1 pixel per 10 ft.
        val pxPer10Ft = 1
        val minAlt = -1000
        val maxAlt = 50000
        val range = maxAlt - minAlt
        val w = 128
        val h = range / 10 * pxPer10Ft

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF1A1A1A.toInt())

        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            textAlign = Paint.Align.LEFT
            this.typeface = typeface
        }

        var alt = minAlt
        while (alt <= maxAlt) {
            val y = h - ((alt - minAlt) / 10) * pxPer10Ft
            if (alt % 100 == 0) {
                canvas.drawLine(0f, y.toFloat(), 20f, y.toFloat(), tickPaint)
                numPaint.textSize = if (alt % 500 == 0) 20f else 16f
                canvas.drawText("$alt", 25f, y + 6f, numPaint)
            } else if (alt % 20 == 0) {
                tickPaint.strokeWidth = 1f
                canvas.drawLine(0f, y.toFloat(), 10f, y.toFloat(), tickPaint)
                tickPaint.strokeWidth = 2f
            }
            alt += 20
        }

        val texId = uploadBitmap(bmp)
        bmp.recycle()
        return texId to h
    }

    // ── GL upload ──────────────────────────────────────────────────────────────

    private fun uploadBitmap(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return id
    }
}
