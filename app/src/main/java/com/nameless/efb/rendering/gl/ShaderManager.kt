package com.nameless.efb.rendering.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log

private const val TAG = "EFB.ShaderManager"
private const val COMMON_UNIFORMS_PATH = "shaders/common/common_uniforms.glsl"

/**
 * Loads, compiles, and caches GLSL shader programs from the app's asset tree.
 *
 * Each program is identified by `(vertPath, fragPath)` and cached after the
 * first successful compilation.  Programs live until [release] is called.
 *
 * Must be created and used on the GL thread.
 *
 * ## Include mechanism
 * The content of `shaders/common/common_uniforms.glsl` is automatically
 * injected after the `#version` directive of every shader source, providing
 * `u_mvp`, `u_theme`, and `u_time_sec` to all shaders without repetition.
 */
class ShaderManager(private val assets: AssetManager) {

    private val programCache = mutableMapOf<String, Int>()

    private val commonUniforms: String by lazy {
        try {
            assets.open(COMMON_UNIFORMS_PATH).bufferedReader().readText() + "\n"
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $COMMON_UNIFORMS_PATH — shaders will lack common uniforms")
            ""
        }
    }

    /**
     * Return the linked GL program for the given vertex and fragment shaders,
     * compiling and caching on first use.
     *
     * @param vertPath asset path, e.g. `"shaders/gauges/needle.vert"`
     * @param fragPath asset path, e.g. `"shaders/gauges/gauge_base.frag"`
     * @throws IllegalStateException on compile or link failure (message includes GLSL error log)
     */
    fun getProgram(vertPath: String, fragPath: String): Int {
        val key = "$vertPath|$fragPath"
        return programCache.getOrPut(key) {
            val vert = compileShader(GLES30.GL_VERTEX_SHADER,   loadAndInject(vertPath))
            val frag = compileShader(GLES30.GL_FRAGMENT_SHADER, loadAndInject(fragPath))
            linkProgram(vert, frag)
        }
    }

    /**
     * Set the `u_theme` uniform on every cached program.
     * Call once after all programs are compiled (e.g. after [BaseRenderer.onGlReady])
     * and again whenever the rendering theme changes.
     */
    fun setThemeUniform(themeValue: Float) {
        for (program in programCache.values) {
            GLES30.glUseProgram(program)
            val loc = GLES30.glGetUniformLocation(program, "u_theme")
            if (loc >= 0) {
                GLES30.glUniform1f(loc, themeValue)
            }
        }
    }

    /** Delete all cached programs. Call from the GL thread on teardown. */
    fun release() {
        programCache.values.forEach { GLES30.glDeleteProgram(it) }
        programCache.clear()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun loadAndInject(path: String): String {
        val source = assets.open(path).bufferedReader().readText()
        if (commonUniforms.isEmpty()) return source
        // Insert common uniforms on the line immediately after the #version directive.
        val newlineAfterVersion = source.indexOf('\n', source.indexOf("#version"))
        if (newlineAfterVersion < 0) return source
        return source.substring(0, newlineAfterVersion + 1) +
            commonUniforms +
            source.substring(newlineAfterVersion + 1)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == GLES30.GL_FALSE) {
            val infoLog = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("Shader compile error:\n$infoLog\nSource:\n$source")
        }
        return shader
    }

    private fun linkProgram(vert: Int, frag: Int): Int {
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vert)
        GLES30.glAttachShader(program, frag)
        GLES30.glLinkProgram(program)
        // Shaders are no longer needed once linked.
        GLES30.glDeleteShader(vert)
        GLES30.glDeleteShader(frag)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == GLES30.GL_FALSE) {
            val infoLog = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            error("Program link error:\n$infoLog")
        }
        // GLES initialises all uniforms to 0 — a zero mat4 collapses all geometry.
        // Pre-set u_mvp to the identity so shaders that pass NDC coordinates
        // directly render correctly without explicit per-frame MVP uploads.
        val mvpLoc = GLES30.glGetUniformLocation(program, "u_mvp")
        if (mvpLoc >= 0) {
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(mvpLoc, 1, false, IDENTITY_4X4, 0)
        }
        return program
    }

    companion object {
        // Column-major identity matrix (OpenGL convention).
        private val IDENTITY_4X4 = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}
