@file:Suppress("unused")
package android.graphics

/** Stub Typeface — font loading is skipped; FontAtlas produces a blank texture atlas. */
class Typeface private constructor() {
    companion object {
        val DEFAULT: Typeface = Typeface()
        val DEFAULT_BOLD: Typeface = Typeface()
        val MONOSPACE: Typeface = Typeface()

        @JvmStatic fun defaultFromStyle(style: Int): Typeface = DEFAULT
    }
}
