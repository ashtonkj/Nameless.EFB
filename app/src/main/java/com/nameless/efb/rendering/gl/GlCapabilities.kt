package com.nameless.efb.rendering.gl

import android.opengl.GLES20
import android.opengl.GLES30
import javax.microedition.khronos.opengles.GL10

/**
 * Detected OpenGL ES capabilities. Populated on the GL thread during
 * [BaseRenderer.onSurfaceCreated]; safe to read from any thread after that.
 */
object GlCapabilities {
    /** `true` if the device supports OpenGL ES 3.0 or higher. */
    var isEs3: Boolean = false
        private set

    /** Maximum 1D/2D texture dimension supported by the GPU. */
    var maxTextureSize: Int = 2048
        private set

    /** Populate capability flags. Must be called from the GL thread. */
    fun detect(gl: GL10) {
        val versionStr = GLES30.glGetString(GLES30.GL_VERSION) ?: ""
        isEs3 = versionStr.contains("OpenGL ES 3")
        val buf = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, buf, 0)
        maxTextureSize = buf[0].coerceAtLeast(1)
    }

    /**
     * Called by [SharedContextFactory] when ES 3.0 context creation fails and
     * the renderer falls back to ES 2.0.
     */
    internal fun markEs2Only() {
        isEs3 = false
    }
}
