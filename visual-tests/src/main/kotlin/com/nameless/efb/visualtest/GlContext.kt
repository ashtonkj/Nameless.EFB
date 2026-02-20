package com.nameless.efb.visualtest

import org.lwjgl.BufferUtils
import org.lwjgl.egl.EGL14
import org.lwjgl.egl.EGL14.EGL_NO_CONTEXT
import org.lwjgl.egl.EGL14.EGL_NO_DISPLAY
import org.lwjgl.egl.EGL14.EGL_NO_SURFACE
import org.lwjgl.egl.EGL14.eglChooseConfig
import org.lwjgl.egl.EGL14.eglCreateContext
import org.lwjgl.egl.EGL14.eglCreatePbufferSurface
import org.lwjgl.egl.EGL14.eglDestroyContext
import org.lwjgl.egl.EGL14.eglDestroySurface
import org.lwjgl.egl.EGL14.eglGetDisplay
import org.lwjgl.egl.EGL14.eglInitialize
import org.lwjgl.egl.EGL14.eglMakeCurrent
import org.lwjgl.egl.EGL14.eglTerminate
import org.lwjgl.opengles.GLES

/**
 * Creates a headless OpenGL ES 3.0 context using Mesa EGL with a pbuffer surface.
 *
 * Requires `EGL_PLATFORM=surfaceless` in the environment so Mesa can create an
 * EGL display without a running X11 or Wayland compositor (set by the Gradle task).
 */
object GlContext {

    // EGL integer attribute constants (EGL 1.0/1.2 spec values)
    private const val EGL_SURFACE_TYPE         = 0x3033
    private const val EGL_PBUFFER_BIT          = 0x0001
    private const val EGL_RED_SIZE             = 0x3024
    private const val EGL_GREEN_SIZE           = 0x3023
    private const val EGL_BLUE_SIZE            = 0x3022
    private const val EGL_ALPHA_SIZE           = 0x3021
    private const val EGL_DEPTH_SIZE           = 0x3025
    private const val EGL_RENDERABLE_TYPE      = 0x3040
    private const val EGL_OPENGL_ES3_BIT       = 0x0040   // EGL_KHR_create_context
    private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098 // EGL 1.2 / KHR extension
    private const val EGL_WIDTH                = 0x3057
    private const val EGL_HEIGHT               = 0x3056
    private const val EGL_NONE                 = 0x3038

    private var display: Long = EGL_NO_DISPLAY
    private var context: Long = EGL_NO_CONTEXT
    private var surface: Long = EGL_NO_SURFACE

    /**
     * Initialise an ES 3.0 context backed by a [width]×[height] pbuffer surface.
     *
     * Must be called before any GL draw calls.  The environment variable
     * `EGL_PLATFORM=surfaceless` must be set by the caller (done by the Gradle
     * `runVisualTests` task) so Mesa works without a display server.
     */
    fun create(width: Int, height: Int) {
        display = eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL_NO_DISPLAY) {
            "eglGetDisplay failed — is EGL_PLATFORM=surfaceless set and libEGL.so available?"
        }

        val major = BufferUtils.createIntBuffer(1)
        val minor = BufferUtils.createIntBuffer(1)
        check(eglInitialize(display, major, minor)) { "eglInitialize failed" }
        println("EGL ${major[0]}.${minor[0]} initialised")

        val configAttribs = BufferUtils.createIntBuffer(15).apply {
            put(EGL_SURFACE_TYPE);      put(EGL_PBUFFER_BIT)
            put(EGL_RED_SIZE);          put(8)
            put(EGL_GREEN_SIZE);        put(8)
            put(EGL_BLUE_SIZE);         put(8)
            put(EGL_ALPHA_SIZE);        put(8)
            put(EGL_DEPTH_SIZE);        put(16)
            put(EGL_RENDERABLE_TYPE);   put(EGL_OPENGL_ES3_BIT)
            put(EGL_NONE)
            flip()
        }

        val configs    = BufferUtils.createPointerBuffer(1)
        val numConfigs = BufferUtils.createIntBuffer(1)
        check(eglChooseConfig(display, configAttribs, configs, numConfigs)) {
            "eglChooseConfig failed"
        }
        check(numConfigs[0] > 0) {
            "No EGL config found — Mesa may not support EGL_OPENGL_ES3_BIT on this driver"
        }
        val eglConfig = configs[0]

        val ctxAttribs = BufferUtils.createIntBuffer(3).apply {
            put(EGL_CONTEXT_CLIENT_VERSION); put(3)
            put(EGL_NONE)
            flip()
        }
        context = eglCreateContext(display, eglConfig, EGL_NO_CONTEXT, ctxAttribs)
        check(context != EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val pbufAttribs = BufferUtils.createIntBuffer(5).apply {
            put(EGL_WIDTH);  put(width)
            put(EGL_HEIGHT); put(height)
            put(EGL_NONE)
            flip()
        }
        surface = eglCreatePbufferSurface(display, eglConfig, pbufAttribs)
        check(surface != EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }

        check(eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        // Set up LWJGL's function pointer table for GLES30 calls.
        GLES.createCapabilities()
        println("OpenGL ES: ${org.lwjgl.opengles.GLES30.glGetString(org.lwjgl.opengles.GLES30.GL_VERSION)}")
    }

    /** Release all EGL resources. */
    fun destroy() {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
        eglDestroySurface(display, surface)
        eglDestroyContext(display, context)
        eglTerminate(display)
    }
}
