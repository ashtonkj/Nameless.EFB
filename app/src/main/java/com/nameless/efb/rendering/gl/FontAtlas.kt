package com.nameless.efb.rendering.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Renders ASCII 32–126 glyphs to a single 512×512 GL texture and exposes UV
 * coordinates for each character.
 *
 * Must be created on the GL thread (uploads texture to GPU).
 *
 * @param typeface    Typeface used to render glyphs.
 * @param glyphSizePx Height (and approximate width) of each glyph cell in pixels.
 */
class FontAtlas(typeface: Typeface, glyphSizePx: Int) {

    /** GL texture ID for the atlas bitmap. */
    val textureId: Int

    /** Character → normalised UV rectangle within the atlas texture. */
    val glyphUvMap: Map<Char, RectF>

    init {
        val chars = (FIRST_CHAR..LAST_CHAR).map { it.toChar() }

        // Lay out glyphs in a square-ish grid.
        val cols = ceil(sqrt(chars.size.toDouble())).toInt()
        val uvMap = mutableMapOf<Char, RectF>()

        val bitmap = Bitmap.createBitmap(ATLAS_SIZE, ATLAS_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize     = glyphSizePx.toFloat()
            color        = Color.WHITE
        }

        chars.forEachIndexed { idx, ch ->
            val col = idx % cols
            val row = idx / cols
            val x = (col * glyphSizePx).toFloat()
            val y = (row * glyphSizePx).toFloat()
            // drawText baseline is at y + ascent; shift by glyphSizePx for simple layout.
            canvas.drawText(ch.toString(), x, y + glyphSizePx, paint)
            uvMap[ch] = RectF(
                x                         / ATLAS_SIZE_F,
                y                         / ATLAS_SIZE_F,
                (x + glyphSizePx.toFloat()) / ATLAS_SIZE_F,
                (y + glyphSizePx.toFloat()) / ATLAS_SIZE_F,
            )
        }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        bitmap.recycle()

        glyphUvMap = uvMap
    }

    /** Delete the GL texture. Call from the GL thread on teardown. */
    fun release() {
        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private companion object {
        const val ATLAS_SIZE  = 512    // pixels, Int for Bitmap.createBitmap
        const val ATLAS_SIZE_F = 512f  // Float for UV normalisation
        const val FIRST_CHAR  = 32   // space
        const val LAST_CHAR   = 126  // tilde
    }
}
