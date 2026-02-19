package com.nameless.efb.rendering.gl

import android.opengl.GLES30

/** Identifiers for each pre-rendered gauge dial face. */
enum class GaugeType {
    AIRSPEED,
    ALTITUDE,
    ATTITUDE,
    VERTICAL_SPEED,
    HEADING,
    TURN_COORDINATOR,
    RPM,
    MANIFOLD_PRESSURE,
    FUEL_QUANTITY,
    OIL_PRESSURE,
    OIL_TEMPERATURE,
    EGT,
    VOLTMETER,
    SUCTION,
}

/**
 * Manages pre-rendered gauge dial-face textures.
 *
 * Each dial face is rendered once at startup (and again on theme change) into a
 * dedicated 512×512 RGBA FBO texture.  Moving elements (needles, arcs) are drawn
 * on top every frame.
 *
 * Must be created and used on the GL thread.
 */
class GaugeTextureAtlas(
    @Suppress("UnusedPrivateMember")
    private val shaderManager: ShaderManager,
) {
    private val textureIds = mutableMapOf<GaugeType, Int>()

    /**
     * Allocate and initialise all gauge background textures for [theme].
     *
     * Existing textures for the same theme are released first, so this can be
     * called again on theme change.  Must be called from the GL thread.
     *
     * NOTE: Actual dial artwork is rendered in Plan 06.  This call allocates
     * blank textures so the pipeline is exercised end-to-end.
     */
    fun buildAll(theme: Theme) {
        release()
        for (type in GaugeType.entries) {
            textureIds[type] = allocateBlankTexture(ATLAS_TEXTURE_SIZE)
        }
    }

    /**
     * Return the GL texture ID for [type].
     * @throws NoSuchElementException if [buildAll] has not been called yet.
     */
    fun getTexture(type: GaugeType): Int =
        textureIds[type] ?: error("Texture not built for $type — call buildAll() first")

    /** Delete all textures.  Call from the GL thread on teardown. */
    fun release() {
        if (textureIds.isEmpty()) return
        val ids = textureIds.values.toIntArray()
        GLES30.glDeleteTextures(ids.size, ids, 0)
        textureIds.clear()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun allocateBlankTexture(size: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        // Allocate storage (no pixel data — dial artwork rendered in Plan 06).
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            size, size, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return id
    }

    private companion object {
        const val ATLAS_TEXTURE_SIZE = 512
    }
}
