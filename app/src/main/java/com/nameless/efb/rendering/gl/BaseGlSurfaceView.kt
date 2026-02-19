package com.nameless.efb.rendering.gl

import android.content.Context
import android.opengl.GLSurfaceView

/**
 * Base class for all EFB instrument [GLSurfaceView]s.
 *
 * Configures:
 * - ES 3.0 client version
 * - Shared EGL context (texture sharing within a panel mode)
 * - 4× MSAA (8-bit RGBA, 16-bit depth)
 * - Context preserved across `onPause` (avoids texture reload on every resume)
 *
 * Subclasses must call [setRenderer] in their constructor or `init` block.
 */
abstract class BaseGlSurfaceView(context: Context) : GLSurfaceView(context) {

    init {
        setEGLContextClientVersion(3)
        setEGLContextFactory(SharedContextFactory())
        // 8-bit RGBA colour, 16-bit depth, 4× MSAA.
        // On ES 2.0 fallback [SharedContextFactory] will still succeed; MSAA
        // silently degrades if unsupported by the driver.
        setEGLConfigChooser(8, 8, 8, 8, 16, 4)
        preserveEGLContextOnPause = true
    }

    override fun onPause() {
        super.onPause()
        queueEvent { onGlPause() }
    }

    /**
     * Called on the GL thread when the surface pauses.
     * Override to release per-frame transient resources or cancel animations.
     */
    protected open fun onGlPause() {}
}
