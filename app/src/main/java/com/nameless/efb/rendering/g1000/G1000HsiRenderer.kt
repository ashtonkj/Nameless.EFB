package com.nameless.efb.rendering.g1000

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pre-renders a 512x512 compass rose texture for the G1000 HSI.
 *
 * The rose has tick marks at 5-degree intervals, cardinal/intercardinal labels,
 * and white ticks on a transparent background.  The texture is rotated in the
 * `hsi.vert` shader by `u_heading_deg`.
 *
 * Must be created on the GL thread.
 */
class G1000HsiRenderer(typeface: Typeface) {

    /** GL texture ID for the compass rose. */
    val textureId: Int

    init {
        val bitmap = renderCompassRose(typeface)
        textureId = uploadBitmap(bitmap)
        bitmap.recycle()
    }

    fun release() {
        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private fun renderCompassRose(typeface: Typeface): Bitmap {
        val size = 512
        val half = size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Transparent background (the HSI shader shows this over the dark HSI bg).
        canvas.drawColor(0x00000000)

        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            this.typeface = typeface
        }

        // Draw compass ring and ticks.
        val outerR = half - 10f
        for (deg in 0 until 360 step 5) {
            // 0 degrees = North = top of image.
            val angleRad = Math.toRadians((deg - 90).toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()

            val isMajor = deg % 30 == 0
            val isMedium = deg % 10 == 0
            val tickLen = when {
                isMajor -> 25f
                isMedium -> 15f
                else -> 8f
            }
            tickPaint.strokeWidth = if (isMajor) 3f else 1.5f
            val innerR = outerR - tickLen

            canvas.drawLine(
                half + cosA * innerR, half + sinA * innerR,
                half + cosA * outerR, half + sinA * outerR,
                tickPaint,
            )

            // Labels at 30-degree intervals.
            if (isMajor) {
                val label = when (deg) {
                    0 -> "N"
                    30 -> "3"
                    60 -> "6"
                    90 -> "E"
                    120 -> "12"
                    150 -> "15"
                    180 -> "S"
                    210 -> "21"
                    240 -> "24"
                    270 -> "W"
                    300 -> "30"
                    330 -> "33"
                    else -> ""
                }
                if (label.isNotEmpty()) {
                    val labelR = outerR - 35f
                    labelPaint.textSize = if (label.length == 1) 28f else 22f
                    canvas.drawText(
                        label,
                        half + cosA * labelR,
                        half + sinA * labelR + 8f,
                        labelPaint,
                    )
                }
            }
        }

        return bmp
    }

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
