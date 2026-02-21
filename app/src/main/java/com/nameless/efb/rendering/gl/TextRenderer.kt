package com.nameless.efb.rendering.gl

import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30

/**
 * Draws text strings using the [FontAtlas] and the `text_glyph` shader program.
 *
 * Positions are in clip space (-1..1).  Each [drawText] call builds quad geometry
 * for every character, uploads to a dynamic VBO, and issues a single draw call.
 *
 * Must be created and used on the GL thread.
 *
 * @param shaderManager  used to obtain the text_glyph shader program
 * @param typeface       typeface for glyph rasterisation
 * @param glyphSizePx    pixel height of each glyph cell in the atlas (default 48)
 */
class TextRenderer(
    shaderManager: ShaderManager,
    typeface: Typeface,
    private val glyphSizePx: Int = 48,
) {
    private val fontAtlas = FontAtlas(typeface, glyphSizePx)

    private val program = shaderManager.getProgram(
        "shaders/gauges/text_glyph.vert",
        "shaders/gauges/text_glyph.frag",
    )

    private val colorLoc    = GLES30.glGetUniformLocation(program, "u_color")
    private val fontAtlasLoc = GLES30.glGetUniformLocation(program, "u_font_atlas")

    // Dynamic VBO + VAO for per-call glyph quads.
    private val vbo = GlBuffer()
    private val vao = GlVao()

    init {
        // Pre-allocate with small buffer; resized dynamically.
        vbo.upload(FloatArray(16 * 6 * 4), GLES30.GL_DYNAMIC_DRAW)  // ~16 chars initial
        vao.bind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo.id)
        // Layout: x, y, u, v — stride 16 bytes, 2 attributes
        // a_position = location 0
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        // a_texcoord = location 1
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        vao.unbind()
    }

    /**
     * Draw [text] at clip-space position ([x], [y]) with the given [scale] and [color].
     *
     * [scale] is the clip-space height of one character cell.  The width is
     * aspect-corrected using the current GL viewport so characters appear
     * square on screen.  Advance between characters is 60 % of the cell width
     * (monospace spacing).
     *
     * @param text   ASCII string to render (chars 32–126)
     * @param x      left edge in clip space
     * @param y      bottom edge in clip space
     * @param scale  character height in clip-space units (e.g. 0.1 = 5 % of viewport height)
     * @param color  RGBA float array (length 4)
     */
    fun drawText(text: String, x: Float, y: Float, scale: Float, color: FloatArray) {
        if (text.isEmpty()) return

        // Query current GL viewport for aspect ratio correction.
        val vpBuf = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, vpBuf, 0)
        val vpW = vpBuf[2].coerceAtLeast(1)
        val vpH = vpBuf[3].coerceAtLeast(1)
        val aspect = vpW.toFloat() / vpH.toFloat()

        // scale = clip-space height of one character cell.
        // Aspect-correct width so characters appear square on screen.
        val charH = scale
        val charW = scale / aspect
        val advance = charW * 0.6f

        // Build triangle geometry: 6 vertices per character (2 triangles).
        val verts = FloatArray(text.length * 6 * 4)  // 6 verts * 4 floats each
        var idx = 0
        var cursorX = x

        for (ch in text) {
            val uv = fontAtlas.glyphUvMap[ch] ?: fontAtlas.glyphUvMap[' '] ?: continue

            val x0 = cursorX
            val x1 = cursorX + charW
            val y0 = y
            val y1 = y + charH

            // Triangle 1: bottom-left, bottom-right, top-left
            verts[idx++] = x0; verts[idx++] = y0; verts[idx++] = uv.left;  verts[idx++] = uv.bottom
            verts[idx++] = x1; verts[idx++] = y0; verts[idx++] = uv.right; verts[idx++] = uv.bottom
            verts[idx++] = x0; verts[idx++] = y1; verts[idx++] = uv.left;  verts[idx++] = uv.top

            // Triangle 2: bottom-right, top-right, top-left
            verts[idx++] = x1; verts[idx++] = y0; verts[idx++] = uv.right; verts[idx++] = uv.bottom
            verts[idx++] = x1; verts[idx++] = y1; verts[idx++] = uv.right; verts[idx++] = uv.top
            verts[idx++] = x0; verts[idx++] = y1; verts[idx++] = uv.left;  verts[idx++] = uv.top

            cursorX += advance
        }

        // Upload and draw.
        GLES30.glUseProgram(program)
        GLES30.glUniform4f(colorLoc, color[0], color[1], color[2], color[3])

        // Bind font atlas to texture unit 1.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fontAtlas.textureId)
        GLES30.glUniform1i(fontAtlasLoc, 1)

        vbo.uploadDynamic(verts)
        vao.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, text.length * 6)
        vao.unbind()

        // Restore texture unit 0 as active.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /**
     * Convenience overload: draw white text.
     */
    fun drawText(text: String, x: Float, y: Float, scale: Float) {
        drawText(text, x, y, scale, WHITE)
    }

    /** Delete GL resources. Call from the GL thread on teardown. */
    fun release() {
        fontAtlas.release()
        vbo.release()
        vao.release()
    }

    companion object {
        private val WHITE = floatArrayOf(1f, 1f, 1f, 1f)
    }
}
