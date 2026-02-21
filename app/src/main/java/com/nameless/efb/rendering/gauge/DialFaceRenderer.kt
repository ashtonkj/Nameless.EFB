package com.nameless.efb.rendering.gauge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.nameless.efb.domain.gauge.AircraftProfile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pre-renders static dial face textures for all 14 steam gauges using Canvas.
 *
 * Each method returns a 512x512 [Bitmap] with tick marks, numbers, color arcs,
 * labels, and bezel ring.  The bitmap is uploaded to GL once at startup and
 * cached until a theme change.
 *
 * Gauges use a 300-degree sweep (from -150 to +150 degrees, 0 = 12 o'clock)
 * matching the [com.nameless.efb.domain.gauge.needleAngle] convention.
 */
object DialFaceRenderer {

    private const val SIZE = 512
    private const val HALF = SIZE / 2f
    private const val BEZEL_STROKE = 8f
    private const val TICK_MAJOR_LEN = 35f
    private const val TICK_MINOR_LEN = 20f
    private const val NUMBER_RADIUS = HALF - 75f  // radius for number placement
    private const val ARC_INNER = HALF - 55f
    private const val ARC_OUTER = HALF - 38f
    private const val LABEL_Y = HALF + 90f  // below centre for gauge name

    // 0° = straight up (12 o'clock), CW positive — matches needle convention.
    // Sweep: -150° → +150° = 300° total.
    private const val SWEEP_START_DEG = -150f
    private const val SWEEP_END_DEG = 150f

    private fun createDial(typeface: Typeface): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Black background.
        canvas.drawColor(Color.BLACK)
        // Bezel ring.
        val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF555555.toInt()
            style = Paint.Style.STROKE
            strokeWidth = BEZEL_STROKE
            this.typeface = typeface
        }
        canvas.drawCircle(HALF, HALF, HALF - BEZEL_STROKE / 2, bezelPaint)
        return bmp to canvas
    }

    /** Maps a value in min..max to an angle in degrees (0 = 12 o'clock, CW positive). */
    private fun valueToAngle(value: Float, min: Float, max: Float): Float {
        val t = ((value - min) / (max - min)).coerceIn(0f, 1f)
        return SWEEP_START_DEG + t * (SWEEP_END_DEG - SWEEP_START_DEG)
    }

    /**
     * Draw tick marks and numbers around the dial.
     *
     * @param canvas        target canvas
     * @param min           minimum scale value
     * @param max           maximum scale value
     * @param majorInterval interval between major ticks (with numbers)
     * @param minorInterval interval between minor ticks
     * @param typeface      font for numbers
     * @param numberFormat  format string for numbers (e.g. "%.0f" or "%d")
     * @param numberDivisor divide displayed value by this (e.g. 10 to show "1" for 10)
     */
    private fun drawTicksAndNumbers(
        canvas: Canvas,
        min: Float, max: Float,
        majorInterval: Float,
        minorInterval: Float,
        typeface: Typeface,
        numberFormat: String = "%.0f",
        numberDivisor: Float = 1f,
    ) {
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            this.typeface = typeface
        }

        var v = min
        while (v <= max + 0.01f) {
            val angleDeg = valueToAngle(v, min, max)
            val angleRad = Math.toRadians((angleDeg - 90).toDouble())  // -90 so 0° = up

            val isMajor = ((v - min) / majorInterval).let { kotlin.math.abs(it - kotlin.math.round(it)) < 0.01f }

            val tickLen = if (isMajor) TICK_MAJOR_LEN else TICK_MINOR_LEN
            val outerR = HALF - 18f
            val innerR = outerR - tickLen

            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()
            tickPaint.strokeWidth = if (isMajor) 3f else 1.5f
            canvas.drawLine(
                HALF + cosA * innerR, HALF + sinA * innerR,
                HALF + cosA * outerR, HALF + sinA * outerR,
                tickPaint,
            )

            if (isMajor) {
                val numR = NUMBER_RADIUS
                val displayVal = v / numberDivisor
                val text = if (numberDivisor > 1f) "%.0f".format(displayVal) else numberFormat.format(v)
                canvas.drawText(text, HALF + cosA * numR, HALF + sinA * numR + 10f, numberPaint)
            }

            v += minorInterval
        }
    }

    /** Draw a color arc band on the dial. */
    private fun drawColorArc(
        canvas: Canvas,
        min: Float, max: Float,
        arcStart: Float, arcEnd: Float,
        color: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = ARC_OUTER - ARC_INNER
        }
        val r = (ARC_INNER + ARC_OUTER) / 2f
        val oval = RectF(HALF - r, HALF - r, HALF + r, HALF + r)
        // Convert value-space angles to Canvas arc angles (0 = 3 o'clock, CCW negative).
        val startDeg = valueToAngle(arcStart, min, max) - 90f
        val endDeg = valueToAngle(arcEnd, min, max) - 90f
        canvas.drawArc(oval, startDeg, endDeg - startDeg, false, paint)
    }

    /** Draw label text at the bottom-centre of the dial. */
    private fun drawLabel(canvas: Canvas, label: String, unit: String, typeface: Typeface) {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            textAlign = Paint.Align.CENTER
            this.typeface = typeface
        }
        canvas.drawText(label, HALF, LABEL_Y, labelPaint)
        if (unit.isNotEmpty()) {
            labelPaint.textSize = 16f
            canvas.drawText(unit, HALF, LABEL_Y + 24f, labelPaint)
        }
    }

    // ── Individual gauge faces ──────────────────────────────────────────────────

    fun renderAsi(profile: AircraftProfile, typeface: Typeface): Bitmap {
        val (bmp, canvas) = createDial(typeface)
        val min = 0f; val max = 200f
        drawColorArc(canvas, min, max, 0f, profile.vsoKts, Color.WHITE)
        drawColorArc(canvas, min, max, profile.vs1Kts, profile.vnoKts, Color.GREEN)
        drawColorArc(canvas, min, max, profile.vnoKts, profile.vneKts, Color.YELLOW)
        drawColorArc(canvas, min, max, profile.vneKts, max, Color.RED)
        drawTicksAndNumbers(canvas, min, max, 20f, 10f, typeface)
        drawLabel(canvas, "AIRSPEED", "KNOTS", typeface)
        return bmp
    }

    fun renderAltimeter(typeface: Typeface): Bitmap {
        val (bmp, canvas) = createDial(typeface)
        drawTicksAndNumbers(canvas, 0f, 10f, 1f, 0.2f, typeface, "%.0f")
        drawLabel(canvas, "ALT", "1000 FT", typeface)
        return bmp
    }

    fun renderVsi(typeface: Typeface): Bitmap {
        val (bmp, canvas) = createDial(typeface)
        drawTicksAndNumbers(canvas, -2000f, 2000f, 500f, 100f, typeface, "%.0f", 1000f)
        drawLabel(canvas, "VERT SPEED", "1000 FPM", typeface)
        return bmp
    }

    fun renderHeading(typeface: Typeface): Bitmap {
        // DI is a special case: 360° compass, not 300° sweep.
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF555555.toInt()
            style = Paint.Style.STROKE
            strokeWidth = BEZEL_STROKE
        }
        canvas.drawCircle(HALF, HALF, HALF - BEZEL_STROKE / 2, bezelPaint)

        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            this.typeface = typeface
        }

        for (deg in 0 until 360 step 5) {
            val angleRad = Math.toRadians((deg - 90).toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()
            val isMajor = deg % 30 == 0
            val isMedium = deg % 10 == 0
            val tickLen = when {
                isMajor -> 35f
                isMedium -> 22f
                else -> 14f
            }
            tickPaint.strokeWidth = if (isMajor) 3f else 1.5f
            val outerR = HALF - 18f
            val innerR = outerR - tickLen
            canvas.drawLine(
                HALF + cosA * innerR, HALF + sinA * innerR,
                HALF + cosA * outerR, HALF + sinA * outerR,
                tickPaint,
            )
            if (isMajor) {
                val label = when (deg) {
                    0 -> "N"
                    90 -> "E"
                    180 -> "S"
                    270 -> "W"
                    else -> "${deg / 10}"
                }
                val numR = HALF - 80f
                canvas.drawText(label, HALF + cosA * numR, HALF + sinA * numR + 10f, numberPaint)
            }
        }
        val lbl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            textAlign = Paint.Align.CENTER
            this.typeface = typeface
        }
        canvas.drawText("HEADING", HALF, HALF + 90f, lbl)
        return bmp
    }

    fun renderRpm(profile: AircraftProfile, typeface: Typeface): Bitmap {
        val (bmp, canvas) = createDial(typeface)
        val min = 0f; val max = 3000f
        drawColorArc(canvas, min, max, 1500f, profile.maxRpm, Color.GREEN)
        drawColorArc(canvas, min, max, profile.maxRpm, max, Color.RED)
        drawTicksAndNumbers(canvas, min, max, 500f, 100f, typeface, "%.0f", 100f)
        drawLabel(canvas, "RPM", "x100", typeface)
        return bmp
    }

    fun renderManifoldPressure(typeface: Typeface): Bitmap {
        val (bmp, canvas) = createDial(typeface)
        val min = 10f; val max = 35f
        drawColorArc(canvas, min, max, 15f, 30f, Color.GREEN)
        drawTicksAndNumbers(canvas, min, max, 5f, 1f, typeface)
        drawLabel(canvas, "MAN PRESS", "IN HG", typeface)
        return bmp
    }

    fun renderOilTempPress(typeface: Typeface): Bitmap {
        val (bmp, canvas) = createDial(typeface)
        val min = 0f; val max = 130f
        drawColorArc(canvas, min, max, 50f, 100f, Color.GREEN)
        drawColorArc(canvas, min, max, 100f, 118f, Color.YELLOW)
        drawColorArc(canvas, min, max, 118f, max, Color.RED)
        drawTicksAndNumbers(canvas, min, max, 20f, 10f, typeface)
        drawLabel(canvas, "OIL TEMP / PRESS", "", typeface)
        return bmp
    }

    fun renderFuelFlow(typeface: Typeface): Bitmap {
        val (bmp, canvas) = createDial(typeface)
        val min = 0f; val max = 20f
        drawColorArc(canvas, min, max, 2f, 15f, Color.GREEN)
        drawTicksAndNumbers(canvas, min, max, 5f, 1f, typeface)
        drawLabel(canvas, "FUEL FLOW", "LPH", typeface)
        return bmp
    }

    fun renderFuelQty(typeface: Typeface): Bitmap {
        val (bmp, canvas) = createDial(typeface)
        val min = 0f; val max = 35f
        drawColorArc(canvas, min, max, 5f, 30f, Color.GREEN)
        drawColorArc(canvas, min, max, 0f, 5f, Color.RED)
        drawTicksAndNumbers(canvas, min, max, 5f, 1f, typeface)
        drawLabel(canvas, "FUEL QTY", "KG", typeface)
        return bmp
    }

    fun renderTurnCoordinator(typeface: Typeface): Bitmap {
        val (bmp, canvas) = createDial(typeface)
        // TC has L/R marks at standard rate (3°/s) positions.
        val min = -3f; val max = 3f
        drawTicksAndNumbers(canvas, min, max, 1f, 0.5f, typeface, "%.0f")
        drawLabel(canvas, "TURN COORD", "MIN", typeface)
        // Mark L and R at the edges.
        val lPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER; this.typeface = typeface
        }
        canvas.drawText("L", HALF - 130f, HALF + 120f, lPaint)
        canvas.drawText("R", HALF + 130f, HALF + 120f, lPaint)
        return bmp
    }

    fun renderEgt(typeface: Typeface): Bitmap {
        // EGT uses bar chart, but we render a simple background with cyl labels.
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF555555.toInt()
            style = Paint.Style.STROKE
            strokeWidth = BEZEL_STROKE
        }
        canvas.drawRect(4f, 4f, SIZE - 4f, SIZE - 4f, bezelPaint)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 20f; textAlign = Paint.Align.CENTER; this.typeface = typeface
        }
        canvas.drawText("EGT", HALF, 40f, paint)
        canvas.drawText("\u00B0C", HALF, SIZE - 20f, paint)
        paint.textSize = 16f
        for (i in 1..6) {
            val x = 50f + (i - 1) * 80f
            canvas.drawText("$i", x, SIZE - 50f, paint)
        }
        return bmp
    }

    fun renderVoltmeter(typeface: Typeface): Bitmap {
        val (bmp, canvas) = createDial(typeface)
        val min = 0f; val max = 30f
        drawColorArc(canvas, min, max, 13f, 28f, Color.GREEN)
        drawColorArc(canvas, min, max, 0f, 13f, Color.RED)
        drawTicksAndNumbers(canvas, min, max, 5f, 1f, typeface)
        drawLabel(canvas, "VOLTS", "DC", typeface)
        return bmp
    }

    fun renderSuction(typeface: Typeface): Bitmap {
        val (bmp, canvas) = createDial(typeface)
        val min = 0f; val max = 10f
        drawColorArc(canvas, min, max, 4.5f, 5.5f, Color.GREEN)
        drawTicksAndNumbers(canvas, min, max, 1f, 0.5f, typeface)
        drawLabel(canvas, "SUCTION", "IN HG", typeface)
        return bmp
    }

    fun renderAttitude(typeface: Typeface): Bitmap {
        // AI background is handled by the AI shader, so just return a minimal dial.
        val (bmp, canvas) = createDial(typeface)
        drawLabel(canvas, "", "", typeface)
        return bmp
    }
}
