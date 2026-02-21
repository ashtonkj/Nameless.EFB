@file:Suppress("unused")
package android.opengl

import android.graphics.Bitmap
import org.lwjgl.BufferUtils
import org.lwjgl.opengles.GLES30 as LGL

/**
 * GLUtils stub that uploads [Bitmap] pixel data to an OpenGL texture.
 *
 * Reads ARGB pixels from the backing [java.awt.image.BufferedImage], converts
 * to RGBA byte order, and calls `glTexImage2D`.
 */
object GLUtils {

    fun texImage2D(target: Int, level: Int, bitmap: Bitmap, border: Int) {
        val img = bitmap.bufferedImage
        val w = img.width
        val h = img.height

        // Extract ARGB pixel data from BufferedImage.
        val argb = IntArray(w * h)
        img.getRGB(0, 0, w, h, argb, 0, w)

        // Convert ARGB to RGBA byte order and pack into a direct ByteBuffer.
        val buf = BufferUtils.createByteBuffer(w * h * 4)
        for (pixel in argb) {
            val a = ((pixel ushr 24) and 0xFF).toByte()
            val r = ((pixel ushr 16) and 0xFF).toByte()
            val g = ((pixel ushr  8) and 0xFF).toByte()
            val b = (pixel and 0xFF).toByte()
            buf.put(r)
            buf.put(g)
            buf.put(b)
            buf.put(a)
        }
        buf.flip()

        LGL.glTexImage2D(
            target, level, LGL.GL_RGBA,
            w, h, border,
            LGL.GL_RGBA, LGL.GL_UNSIGNED_BYTE, buf,
        )
    }
}
