@file:Suppress("unused")
package android.graphics

import java.awt.image.BufferedImage

/**
 * Bitmap stub backed by AWT [BufferedImage] for visual-test rasterisation.
 *
 * Provides pixel data that [android.opengl.GLUtils.texImage2D] can extract
 * and upload to an OpenGL texture.
 */
class Bitmap private constructor(val width: Int, val height: Int) {
    enum class Config { ARGB_8888 }

    /** Backing AWT image — ARGB pixel format matches Android's ARGB_8888. */
    val bufferedImage: BufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    companion object {
        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap = Bitmap(width, height)
    }

    fun recycle() = Unit
}
