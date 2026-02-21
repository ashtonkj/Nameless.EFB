@file:Suppress("unused")
package android.graphics

import android.content.res.AssetManager
import java.awt.Font

/**
 * Typeface stub backed by AWT [Font] for visual-test rasterisation.
 *
 * On Android, Typeface wraps a Skia font.  Here we delegate to AWT so that
 * [Canvas.drawText] produces real glyphs in the headless test environment.
 */
class Typeface private constructor(val awtFont: Font) {
    companion object {
        private val defaultAwtFont = Font(Font.MONOSPACED, Font.PLAIN, 12)

        val DEFAULT: Typeface = Typeface(defaultAwtFont)
        val DEFAULT_BOLD: Typeface = Typeface(defaultAwtFont.deriveFont(Font.BOLD))
        val MONOSPACE: Typeface = Typeface(defaultAwtFont)

        @JvmStatic fun defaultFromStyle(style: Int): Typeface = DEFAULT

        /** Load a TTF font from the [AssetManager] asset tree. */
        @JvmStatic
        fun createFromAsset(assets: AssetManager, path: String): Typeface {
            return try {
                val stream = assets.open(path)
                val awtFont = Font.createFont(Font.TRUETYPE_FONT, stream)
                stream.close()
                Typeface(awtFont)
            } catch (e: Exception) {
                // Fallback to system monospace if font file not found.
                MONOSPACE
            }
        }
    }
}
