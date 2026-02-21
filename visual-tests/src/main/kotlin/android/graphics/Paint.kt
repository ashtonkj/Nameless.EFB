@file:Suppress("unused")
package android.graphics

import java.awt.BasicStroke
import java.awt.Font

/**
 * Paint stub backed by AWT properties for visual-test rasterisation.
 *
 * Maps Android paint properties to AWT equivalents so [Canvas] draw calls
 * produce visible output.
 */
class Paint(flags: Int = 0) {
    companion object {
        const val ANTI_ALIAS_FLAG = 1
    }

    var typeface: Typeface? = null
    var color: Int = Color.WHITE
    var textSize: Float = 12f
    var textAlign: Align = Align.LEFT
    var strokeWidth: Float = 1f
    var style: Style = Style.FILL

    val isAntiAlias: Boolean = (flags and ANTI_ALIAS_FLAG) != 0

    enum class Align { LEFT, CENTER, RIGHT }
    enum class Style { FILL, STROKE, FILL_AND_STROKE }

    fun measureText(text: String): Float = text.length * textSize * 0.6f
    fun setTypeface(typeface: Typeface?): Typeface? { this.typeface = typeface; return typeface }

    /** Build an AWT Font from current typeface + textSize. */
    fun toAwtFont(): Font {
        val base = typeface?.awtFont ?: Font(Font.MONOSPACED, Font.PLAIN, 12)
        return base.deriveFont(textSize)
    }

    /** Build an AWT Color from the current Android int color. */
    fun toAwtColor(): java.awt.Color {
        val a = (color ushr 24) and 0xFF
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return java.awt.Color(r, g, b, a)
    }

    /** Build an AWT Stroke from the current stroke width. */
    fun toAwtStroke(): BasicStroke = BasicStroke(strokeWidth)
}
