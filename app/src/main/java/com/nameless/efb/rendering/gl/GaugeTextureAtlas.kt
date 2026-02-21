package com.nameless.efb.rendering.gl

import android.graphics.Bitmap
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import com.nameless.efb.domain.gauge.AircraftProfile
import com.nameless.efb.rendering.gauge.DialFaceRenderer

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
 * dedicated 512x512 RGBA texture via [DialFaceRenderer] + Canvas.
 *
 * Must be created and used on the GL thread.
 */
class GaugeTextureAtlas(
    @Suppress("UnusedPrivateMember")
    private val shaderManager: ShaderManager,
) {
    private val textureIds = mutableMapOf<GaugeType, Int>()

    /** Aircraft profile for V-speed arcs — can be updated before [buildAll]. */
    var profile: AircraftProfile = AircraftProfile()

    /** Typeface for dial face numbers — set by [BaseRenderer] before [buildAll]. */
    var typeface: Typeface = Typeface.MONOSPACE

    /**
     * Render and upload all gauge background textures for [theme].
     *
     * Existing textures are released first, so this can be called again on
     * theme change.  Must be called from the GL thread.
     */
    fun buildAll(theme: Theme) {
        release()
        for (type in GaugeType.entries) {
            val bitmap = renderDialFace(type)
            textureIds[type] = uploadBitmap(bitmap)
            bitmap.recycle()
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

    private fun renderDialFace(type: GaugeType): Bitmap = when (type) {
        GaugeType.AIRSPEED           -> DialFaceRenderer.renderAsi(profile, typeface)
        GaugeType.ALTITUDE           -> DialFaceRenderer.renderAltimeter(typeface)
        GaugeType.ATTITUDE           -> DialFaceRenderer.renderAttitude(typeface)
        GaugeType.VERTICAL_SPEED     -> DialFaceRenderer.renderVsi(typeface)
        GaugeType.HEADING            -> DialFaceRenderer.renderHeading(typeface)
        GaugeType.TURN_COORDINATOR   -> DialFaceRenderer.renderTurnCoordinator(typeface)
        GaugeType.RPM                -> DialFaceRenderer.renderRpm(profile, typeface)
        GaugeType.MANIFOLD_PRESSURE  -> DialFaceRenderer.renderManifoldPressure(typeface)
        GaugeType.FUEL_QUANTITY      -> DialFaceRenderer.renderFuelQty(typeface)
        GaugeType.OIL_PRESSURE       -> DialFaceRenderer.renderOilTempPress(typeface)
        GaugeType.OIL_TEMPERATURE    -> DialFaceRenderer.renderOilTempPress(typeface)
        GaugeType.EGT                -> DialFaceRenderer.renderEgt(typeface)
        GaugeType.VOLTMETER          -> DialFaceRenderer.renderVoltmeter(typeface)
        GaugeType.SUCTION            -> DialFaceRenderer.renderSuction(typeface)
    }

    private fun uploadBitmap(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return id
    }
}
