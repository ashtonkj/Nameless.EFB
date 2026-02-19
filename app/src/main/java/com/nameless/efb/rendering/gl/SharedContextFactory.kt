package com.nameless.efb.rendering.gl

import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

private const val TAG = "EFB.SharedCtxFactory"

// EGL_CONTEXT_CLIENT_VERSION attribute key
private const val EGL_CTX_VERSION = 0x3098

/**
 * [GLSurfaceView.EGLContextFactory] that creates ES 3.0 contexts sharing a
 * single master context (via [EglContextManager]) for texture and VBO sharing.
 *
 * Falls back to a standalone ES 2.0 context when ES 3.0 is unavailable and
 * clears [GlCapabilities.isEs3] so renderers can adapt.
 */
class SharedContextFactory(
    private val manager: EglContextManager = EglContextManager,
) : GLSurfaceView.EGLContextFactory {

    override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
        return try {
            // Obtain (or create) the master shared context.
            val shared = manager.getOrCreateSharedContext(egl, display, config)

            // Each view gets its own child context that shares resources with master.
            val attribs = intArrayOf(EGL_CTX_VERSION, 3, EGL10.EGL_NONE)
            val ctx = egl.eglCreateContext(display, config, shared, attribs)
            if (ctx == null || ctx == EGL10.EGL_NO_CONTEXT) {
                throw EglException(
                    "Child context creation failed (0x${egl.eglGetError().toString(16)})"
                )
            }
            ctx
        } catch (e: EglException) {
            Log.w(TAG, "ES 3.0 unavailable — falling back to ES 2.0: ${e.message}")
            GlCapabilities.markEs2Only()
            createEs2Context(egl, display, config)
        }
    }

    override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
        egl.eglDestroyContext(display, context)
    }

    private fun createEs2Context(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
        val attribs = intArrayOf(EGL_CTX_VERSION, 2, EGL10.EGL_NONE)
        return egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attribs)
    }
}
