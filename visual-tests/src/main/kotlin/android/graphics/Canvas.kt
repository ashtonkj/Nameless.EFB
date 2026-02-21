@file:Suppress("unused")
package android.graphics

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D

/**
 * Canvas stub backed by AWT [Graphics2D] for visual-test rasterisation.
 *
 * Delegates draw calls to the [Bitmap]'s backing [java.awt.image.BufferedImage].
 */
class Canvas(val bitmap: Bitmap) {

    private val g2d: Graphics2D = bitmap.bufferedImage.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        g2d.font = paint.toAwtFont()
        g2d.color = paint.toAwtColor()
        val drawX = when (paint.textAlign) {
            Paint.Align.CENTER -> x - g2d.fontMetrics.stringWidth(text) / 2f
            Paint.Align.RIGHT -> x - g2d.fontMetrics.stringWidth(text)
            else -> x
        }
        g2d.drawString(text, drawX, y)
    }

    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) {
        applyPaintStyle(paint)
        g2d.draw(Line2D.Float(x0, y0, x1, y1))
    }

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        applyPaintStyle(paint)
        val shape = Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2)
        if (paint.style == Paint.Style.FILL || paint.style == Paint.Style.FILL_AND_STROKE) {
            g2d.fill(shape)
        }
        if (paint.style == Paint.Style.STROKE || paint.style == Paint.Style.FILL_AND_STROKE) {
            g2d.draw(shape)
        }
    }

    fun drawArc(oval: RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) {
        applyPaintStyle(paint)
        val type = if (useCenter) Arc2D.PIE else Arc2D.OPEN
        val shape = Arc2D.Float(
            oval.left, oval.top, oval.right - oval.left, oval.bottom - oval.top,
            startAngle.toDouble().toFloat(), sweepAngle, type,
        )
        if (paint.style == Paint.Style.FILL || paint.style == Paint.Style.FILL_AND_STROKE) {
            g2d.fill(shape)
        }
        if (paint.style == Paint.Style.STROKE || paint.style == Paint.Style.FILL_AND_STROKE) {
            g2d.draw(shape)
        }
    }

    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        applyPaintStyle(paint)
        val x = left.toInt()
        val y = top.toInt()
        val w = (right - left).toInt()
        val h = (bottom - top).toInt()
        if (paint.style == Paint.Style.FILL || paint.style == Paint.Style.FILL_AND_STROKE) {
            g2d.fillRect(x, y, w, h)
        }
        if (paint.style == Paint.Style.STROKE || paint.style == Paint.Style.FILL_AND_STROKE) {
            g2d.drawRect(x, y, w, h)
        }
    }

    fun drawColor(color: Int) {
        val a = (color ushr 24) and 0xFF
        val r = (color ushr 16) and 0xFF
        val gg = (color ushr 8) and 0xFF
        val b = color and 0xFF
        g2d.color = java.awt.Color(r, gg, b, a)
        g2d.fillRect(0, 0, bitmap.width, bitmap.height)
    }

    private fun applyPaintStyle(paint: Paint) {
        g2d.color = paint.toAwtColor()
        g2d.stroke = paint.toAwtStroke()
        g2d.font = paint.toAwtFont()
    }
}
