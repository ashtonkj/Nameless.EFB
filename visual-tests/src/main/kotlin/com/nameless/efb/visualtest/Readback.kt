package com.nameless.efb.visualtest

import android.opengl.GLES30
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO

/**
 * Reads the current GL framebuffer and writes a PNG file.
 *
 * OpenGL's origin is bottom-left; PNG is top-left, so we flip the Y axis.
 */
object Readback {

    fun capture(width: Int, height: Int, outputFile: File) {
        GLES30.glFinish()

        val buf = ByteBuffer.allocateDirect(width * height * 4)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.rewind()

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            val srcY = height - 1 - y  // flip: GL y=0 is bottom, image y=0 is top
            for (x in 0 until width) {
                val idx = (srcY * width + x) * 4
                val r = buf.get(idx).toInt() and 0xFF
                val g = buf.get(idx + 1).toInt() and 0xFF
                val b = buf.get(idx + 2).toInt() and 0xFF
                val a = buf.get(idx + 3).toInt() and 0xFF
                image.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }

        outputFile.parentFile?.mkdirs()
        ImageIO.write(image, "PNG", outputFile)
        println("  → ${outputFile.absolutePath}  (${width}×${height})")
    }
}
