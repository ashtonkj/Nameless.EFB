package com.nameless.efb.rendering.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG              = "EFB.Renderer"
private const val FRAME_BUDGET_MS  = 4.0   // NFR-P01: 4 ms per frame on render thread

/**
 * Abstract base for all EFB instrument renderers.
 *
 * Responsibilities:
 * - GL state initialisation (blend, depth test)
 * - [ShaderManager] and [GaugeTextureAtlas] lifecycle
 * - [GlCapabilities] detection
 * - 4 ms frame-budget enforcement (warns on overrun)
 * - Template methods [onGlReady] and [drawFrame] for subclasses
 *
 * Subclasses receive the latest sim data via [StateFlow] access; this base
 * class stays decoupled from the data layer.
 */
abstract class BaseRenderer(
    protected val assets: AssetManager,
    protected var theme: Theme = Theme.DAY,
) : GLSurfaceView.Renderer {

    protected lateinit var shaderManager: ShaderManager
        private set

    protected lateinit var gaugeAtlas: GaugeTextureAtlas
        private set

    // ── GLSurfaceView.Renderer ────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        GlCapabilities.detect(gl)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        shaderManager = ShaderManager(assets)
        gaugeAtlas    = GaugeTextureAtlas(shaderManager)
        gaugeAtlas.buildAll(theme)

        onGlReady()
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10) {
        val startNs = System.nanoTime()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drawFrame()

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        if (elapsedMs > FRAME_BUDGET_MS) {
            Log.w(TAG, "Frame overrun: %.1f ms (budget %.0f ms)".format(elapsedMs, FRAME_BUDGET_MS))
        }
    }

    // ── Template methods ──────────────────────────────────────────────────────

    /**
     * Called on the GL thread once the surface is ready and [shaderManager] /
     * [gaugeAtlas] are initialised.  Build VAOs, load additional textures here.
     */
    protected abstract fun onGlReady()

    /**
     * Called on the GL thread each frame after the colour/depth buffers are
     * cleared.  Issue all draw calls here.
     */
    protected abstract fun drawFrame()

    // ── Theme change ──────────────────────────────────────────────────────────

    /**
     * Change the rendering theme and rebuild static dial-face textures.
     * Must be called from the GL thread (e.g. via [GLSurfaceView.queueEvent]).
     */
    fun applyTheme(newTheme: Theme) {
        theme = newTheme
        if (::gaugeAtlas.isInitialized) {
            gaugeAtlas.buildAll(newTheme)
        }
    }
}
