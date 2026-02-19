package com.nameless.efb.rendering.gl

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

/** Thrown when EGL context creation fails. */
class EglException(message: String) : Exception(message)

/**
 * Manages a single shared EGL context per panel mode.
 *
 * All [BaseGlSurfaceView] instances belonging to the same display mode share
 * resources (textures, VBOs) through this master context.  Each view still has
 * its own child context; sharing is established at context-creation time.
 *
 * Thread-safe: all public methods are guarded by [lock].
 */
object EglContextManager {

    private val lock = Any()

    @Volatile private var masterContext: EGLContext? = null
    @Volatile private var masterDisplay: EGLDisplay? = null

    /**
     * Returns the master (shared) ES 3.0 context, creating it on the first call.
     *
     * Subsequent views in the same panel mode should create their own contexts
     * using this as the `shareContext` argument to [EGL10.eglCreateContext].
     *
     * @throws EglException if the ES 3.0 context cannot be created.
     */
    fun getOrCreateSharedContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
        synchronized(lock) {
            masterContext?.let { return it }
            val attribs = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE)
            val ctx = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attribs)
            if (ctx == null || ctx == EGL10.EGL_NO_CONTEXT) {
                throw EglException(
                    "Failed to create ES 3.0 shared context (EGL error 0x${egl.eglGetError().toString(16)})"
                )
            }
            masterContext = ctx
            masterDisplay = display
            return ctx
        }
    }

    /**
     * Destroy the master context.  Call when the panel mode is torn down
     * (e.g. activity `onDestroy`).
     */
    fun releaseSharedContext(egl: EGL10) {
        synchronized(lock) {
            val ctx = masterContext ?: return
            val display = masterDisplay ?: return
            egl.eglDestroyContext(display, ctx)
            masterContext = null
            masterDisplay = null
        }
    }

    // EGL_CONTEXT_CLIENT_VERSION attribute key (EGL 1.2+)
    private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
}
