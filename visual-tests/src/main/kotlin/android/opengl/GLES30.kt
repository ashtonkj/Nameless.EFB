@file:Suppress("FunctionName", "unused")
package android.opengl

import org.lwjgl.BufferUtils
import org.lwjgl.opengles.GLES30 as LGL
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Android GLES30 shim — delegates to org.lwjgl.opengles.GLES30 so the app's
 * renderer source files can compile and run on the JVM against Mesa GLES.
 *
 * Android uses array+offset style (glGenBuffers(n, buf, offset)).
 * LWJGL uses NIO buffers or single-value convenience methods.
 * This shim bridges the two conventions.
 *
 * IMPORTANT: LWJGL requires *direct* NIO buffers (GetDirectBufferAddress).
 * Android's GLES implementation accepts heap buffers (via GetPrimitiveArrayCritical).
 * All buffer-accepting shims must ensure the buffer is direct before forwarding.
 */

// Copy a heap NIO buffer into a fresh LWJGL direct buffer of the same type.
private fun FloatBuffer.ensureDirect(): FloatBuffer =
    if (isDirect) this
    else BufferUtils.createFloatBuffer(remaining()).also { d -> d.put(this.rewind() as FloatBuffer); d.flip() }

private fun ByteBuffer.ensureDirect(): ByteBuffer =
    if (isDirect) this
    else BufferUtils.createByteBuffer(remaining()).also { d -> d.put(this.rewind() as ByteBuffer); d.flip() }

private fun IntBuffer.ensureDirect(): IntBuffer =
    if (isDirect) this
    else BufferUtils.createIntBuffer(remaining()).also { d -> d.put(this.rewind() as IntBuffer); d.flip() }

object GLES30 {

    // ── Constants (OpenGL ES 3.0 spec values — identical between Android and LWJGL) ──

    val GL_FALSE                  get() = LGL.GL_FALSE
    val GL_TRUE                   get() = LGL.GL_TRUE
    val GL_VERSION                get() = LGL.GL_VERSION
    val GL_VENDOR                 get() = LGL.GL_VENDOR
    val GL_RENDERER               get() = LGL.GL_RENDERER
    val GL_EXTENSIONS             get() = LGL.GL_EXTENSIONS

    val GL_VERTEX_SHADER          get() = LGL.GL_VERTEX_SHADER
    val GL_FRAGMENT_SHADER        get() = LGL.GL_FRAGMENT_SHADER
    val GL_COMPILE_STATUS         get() = LGL.GL_COMPILE_STATUS
    val GL_LINK_STATUS            get() = LGL.GL_LINK_STATUS
    val GL_INFO_LOG_LENGTH        get() = LGL.GL_INFO_LOG_LENGTH

    val GL_ARRAY_BUFFER           get() = LGL.GL_ARRAY_BUFFER
    val GL_ELEMENT_ARRAY_BUFFER   get() = LGL.GL_ELEMENT_ARRAY_BUFFER
    val GL_UNIFORM_BUFFER         get() = LGL.GL_UNIFORM_BUFFER
    val GL_STATIC_DRAW            get() = LGL.GL_STATIC_DRAW
    val GL_DYNAMIC_DRAW           get() = LGL.GL_DYNAMIC_DRAW
    val GL_STREAM_DRAW            get() = LGL.GL_STREAM_DRAW

    val GL_TRIANGLES              get() = LGL.GL_TRIANGLES
    val GL_TRIANGLE_FAN           get() = LGL.GL_TRIANGLE_FAN
    val GL_TRIANGLE_STRIP         get() = LGL.GL_TRIANGLE_STRIP
    val GL_LINES                  get() = LGL.GL_LINES
    val GL_LINE_STRIP             get() = LGL.GL_LINE_STRIP
    val GL_POINTS                 get() = LGL.GL_POINTS

    val GL_FLOAT                  get() = LGL.GL_FLOAT
    val GL_UNSIGNED_BYTE          get() = LGL.GL_UNSIGNED_BYTE
    val GL_UNSIGNED_SHORT         get() = LGL.GL_UNSIGNED_SHORT
    val GL_UNSIGNED_INT           get() = LGL.GL_UNSIGNED_INT
    val GL_BYTE                   get() = LGL.GL_BYTE
    val GL_INT                    get() = LGL.GL_INT

    val GL_BLEND                  get() = LGL.GL_BLEND
    val GL_SRC_ALPHA              get() = LGL.GL_SRC_ALPHA
    val GL_ONE_MINUS_SRC_ALPHA    get() = LGL.GL_ONE_MINUS_SRC_ALPHA
    val GL_ONE                    get() = LGL.GL_ONE
    val GL_ZERO                   get() = LGL.GL_ZERO

    val GL_DEPTH_TEST             get() = LGL.GL_DEPTH_TEST
    val GL_LEQUAL                 get() = LGL.GL_LEQUAL
    val GL_CULL_FACE              get() = LGL.GL_CULL_FACE
    val GL_SCISSOR_TEST           get() = LGL.GL_SCISSOR_TEST
    val GL_STENCIL_TEST           get() = LGL.GL_STENCIL_TEST

    val GL_COLOR_BUFFER_BIT       get() = LGL.GL_COLOR_BUFFER_BIT
    val GL_DEPTH_BUFFER_BIT       get() = LGL.GL_DEPTH_BUFFER_BIT
    val GL_STENCIL_BUFFER_BIT     get() = LGL.GL_STENCIL_BUFFER_BIT

    val GL_TEXTURE_2D             get() = LGL.GL_TEXTURE_2D
    val GL_RGBA                   get() = LGL.GL_RGBA
    val GL_RGB                    get() = LGL.GL_RGB
    val GL_RGBA8                  get() = LGL.GL_RGBA8
    val GL_DEPTH_COMPONENT16      get() = LGL.GL_DEPTH_COMPONENT16
    val GL_DEPTH_COMPONENT        get() = LGL.GL_DEPTH_COMPONENT
    val GL_TEXTURE_MIN_FILTER     get() = LGL.GL_TEXTURE_MIN_FILTER
    val GL_TEXTURE_MAG_FILTER     get() = LGL.GL_TEXTURE_MAG_FILTER
    val GL_TEXTURE_WRAP_S         get() = LGL.GL_TEXTURE_WRAP_S
    val GL_TEXTURE_WRAP_T         get() = LGL.GL_TEXTURE_WRAP_T
    val GL_LINEAR                 get() = LGL.GL_LINEAR
    val GL_NEAREST                get() = LGL.GL_NEAREST
    val GL_LINEAR_MIPMAP_LINEAR   get() = LGL.GL_LINEAR_MIPMAP_LINEAR
    val GL_CLAMP_TO_EDGE          get() = LGL.GL_CLAMP_TO_EDGE
    val GL_REPEAT                 get() = LGL.GL_REPEAT
    val GL_TEXTURE0               get() = LGL.GL_TEXTURE0
    val GL_TEXTURE1               get() = LGL.GL_TEXTURE1
    val GL_TEXTURE2               get() = LGL.GL_TEXTURE2

    val GL_FRAMEBUFFER            get() = LGL.GL_FRAMEBUFFER
    val GL_RENDERBUFFER           get() = LGL.GL_RENDERBUFFER
    val GL_COLOR_ATTACHMENT0      get() = LGL.GL_COLOR_ATTACHMENT0
    val GL_DEPTH_ATTACHMENT       get() = LGL.GL_DEPTH_ATTACHMENT
    val GL_FRAMEBUFFER_COMPLETE   get() = LGL.GL_FRAMEBUFFER_COMPLETE

    val GL_MAX_TEXTURE_SIZE       get() = LGL.GL_MAX_TEXTURE_SIZE

    // ── Buffer objects ────────────────────────────────────────────────────────

    fun glGenBuffers(n: Int, buffers: IntArray, offset: Int) {
        for (i in 0 until n) buffers[offset + i] = LGL.glGenBuffers()
    }

    fun glDeleteBuffers(n: Int, buffers: IntArray, offset: Int) {
        for (i in 0 until n) LGL.glDeleteBuffers(buffers[offset + i])
    }

    fun glBindBuffer(target: Int, buffer: Int) = LGL.glBindBuffer(target, buffer)

    fun glBufferData(target: Int, size: Int, data: Buffer?, usage: Int) {
        when (data) {
            is FloatBuffer -> LGL.glBufferData(target, data.ensureDirect(), usage)
            is ByteBuffer  -> LGL.glBufferData(target, data.ensureDirect(), usage)
            is IntBuffer   -> LGL.glBufferData(target, data.ensureDirect(), usage)
            null           -> LGL.glBufferData(target, size.toLong(), usage)
            else           -> error("glBufferData: unsupported buffer type ${data::class.simpleName}")
        }
    }

    // ── Vertex Array Objects ──────────────────────────────────────────────────

    fun glGenVertexArrays(n: Int, arrays: IntArray, offset: Int) {
        for (i in 0 until n) arrays[offset + i] = LGL.glGenVertexArrays()
    }

    fun glDeleteVertexArrays(n: Int, arrays: IntArray, offset: Int) {
        for (i in 0 until n) LGL.glDeleteVertexArrays(arrays[offset + i])
    }

    fun glBindVertexArray(array: Int) = LGL.glBindVertexArray(array)

    // ── Vertex attributes ─────────────────────────────────────────────────────

    fun glEnableVertexAttribArray(index: Int) = LGL.glEnableVertexAttribArray(index)
    fun glDisableVertexAttribArray(index: Int) = LGL.glDisableVertexAttribArray(index)

    /** [ptr] is a byte offset into the currently bound VBO. */
    fun glVertexAttribPointer(
        index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, ptr: Int,
    ) = LGL.glVertexAttribPointer(index, size, type, normalized, stride, ptr.toLong())

    // ── Shaders ───────────────────────────────────────────────────────────────

    fun glCreateShader(type: Int): Int = LGL.glCreateShader(type)
    fun glDeleteShader(shader: Int) = LGL.glDeleteShader(shader)
    fun glShaderSource(shader: Int, string: String) = LGL.glShaderSource(shader, string)
    fun glCompileShader(shader: Int) = LGL.glCompileShader(shader)

    fun glGetShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int) {
        params[offset] = LGL.glGetShaderi(shader, pname)
    }

    fun glGetShaderInfoLog(shader: Int): String = LGL.glGetShaderInfoLog(shader)

    // ── Programs ──────────────────────────────────────────────────────────────

    fun glCreateProgram(): Int = LGL.glCreateProgram()
    fun glDeleteProgram(program: Int) = LGL.glDeleteProgram(program)
    fun glAttachShader(program: Int, shader: Int) = LGL.glAttachShader(program, shader)
    fun glLinkProgram(program: Int) = LGL.glLinkProgram(program)
    fun glUseProgram(program: Int) = LGL.glUseProgram(program)

    fun glGetProgramiv(program: Int, pname: Int, params: IntArray, offset: Int) {
        params[offset] = LGL.glGetProgrami(program, pname)
    }

    fun glGetProgramInfoLog(program: Int): String = LGL.glGetProgramInfoLog(program)
    fun glGetAttribLocation(program: Int, name: String): Int = LGL.glGetAttribLocation(program, name)
    fun glGetUniformLocation(program: Int, name: String): Int = LGL.glGetUniformLocation(program, name)

    // ── Uniform blocks ────────────────────────────────────────────────────────

    fun glGetUniformBlockIndex(program: Int, name: String): Int =
        LGL.glGetUniformBlockIndex(program, name)

    fun glUniformBlockBinding(program: Int, blockIndex: Int, binding: Int) =
        LGL.glUniformBlockBinding(program, blockIndex, binding)

    fun glBindBufferBase(target: Int, index: Int, buffer: Int) =
        LGL.glBindBufferBase(target, index, buffer)

    // ── Uniforms ──────────────────────────────────────────────────────────────

    fun glUniform1i(location: Int, x: Int) = LGL.glUniform1i(location, x)
    fun glUniform1f(location: Int, x: Float) = LGL.glUniform1f(location, x)
    fun glUniform2f(location: Int, x: Float, y: Float) = LGL.glUniform2f(location, x, y)
    fun glUniform3f(location: Int, x: Float, y: Float, z: Float) = LGL.glUniform3f(location, x, y, z)
    fun glUniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) =
        LGL.glUniform4f(location, x, y, z, w)

    fun glUniformMatrix4fv(
        location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int,
    ) {
        if (offset == 0 && count * 16 == value.size) {
            LGL.glUniformMatrix4fv(location, transpose, value)
        } else {
            LGL.glUniformMatrix4fv(location, transpose, value.copyOfRange(offset, offset + count * 16))
        }
    }

    // ── Textures ──────────────────────────────────────────────────────────────

    fun glGenTextures(n: Int, textures: IntArray, offset: Int) {
        for (i in 0 until n) textures[offset + i] = LGL.glGenTextures()
    }

    fun glDeleteTextures(n: Int, textures: IntArray, offset: Int) {
        for (i in 0 until n) LGL.glDeleteTextures(textures[offset + i])
    }

    fun glBindTexture(target: Int, texture: Int) = LGL.glBindTexture(target, texture)
    fun glActiveTexture(texture: Int) = LGL.glActiveTexture(texture)
    fun glTexParameteri(target: Int, pname: Int, param: Int) = LGL.glTexParameteri(target, pname, param)
    fun glGenerateMipmap(target: Int) = LGL.glGenerateMipmap(target)

    /** Single overload accepts any NIO buffer type or null to avoid ambiguity on null literals. */
    fun glTexImage2D(
        target: Int, level: Int, internalformat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: Buffer?,
    ) = when (pixels) {
        is IntBuffer  -> LGL.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels.ensureDirect())
        is ByteBuffer -> LGL.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels.ensureDirect())
        null          -> LGL.glTexImage2D(target, level, internalformat, width, height, border, format, type, null as ByteBuffer?)
        else          -> error("glTexImage2D: unsupported pixel buffer type ${pixels::class.simpleName}")
    }

    // ── Framebuffers ─────────────────────────────────────────────────────────

    fun glGenFramebuffers(n: Int, framebuffers: IntArray, offset: Int) {
        for (i in 0 until n) framebuffers[offset + i] = LGL.glGenFramebuffers()
    }

    fun glDeleteFramebuffers(n: Int, framebuffers: IntArray, offset: Int) {
        for (i in 0 until n) LGL.glDeleteFramebuffers(framebuffers[offset + i])
    }

    fun glBindFramebuffer(target: Int, framebuffer: Int) = LGL.glBindFramebuffer(target, framebuffer)

    fun glFramebufferTexture2D(
        target: Int, attachment: Int, textarget: Int, texture: Int, level: Int,
    ) = LGL.glFramebufferTexture2D(target, attachment, textarget, texture, level)

    fun glCheckFramebufferStatus(target: Int): Int = LGL.glCheckFramebufferStatus(target)

    fun glGenRenderbuffers(n: Int, renderbuffers: IntArray, offset: Int) {
        for (i in 0 until n) renderbuffers[offset + i] = LGL.glGenRenderbuffers()
    }

    fun glDeleteRenderbuffers(n: Int, renderbuffers: IntArray, offset: Int) {
        for (i in 0 until n) LGL.glDeleteRenderbuffers(renderbuffers[offset + i])
    }

    fun glBindRenderbuffer(target: Int, renderbuffer: Int) = LGL.glBindRenderbuffer(target, renderbuffer)

    fun glRenderbufferStorage(target: Int, internalformat: Int, width: Int, height: Int) =
        LGL.glRenderbufferStorage(target, internalformat, width, height)

    fun glFramebufferRenderbuffer(
        target: Int, attachment: Int, renderbuffertarget: Int, renderbuffer: Int,
    ) = LGL.glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer)

    // ── State ─────────────────────────────────────────────────────────────────

    fun glEnable(cap: Int) = LGL.glEnable(cap)
    fun glDisable(cap: Int) = LGL.glDisable(cap)
    fun glBlendFunc(sfactor: Int, dfactor: Int) = LGL.glBlendFunc(sfactor, dfactor)
    fun glDepthFunc(func: Int) = LGL.glDepthFunc(func)
    fun glDepthMask(flag: Boolean) = LGL.glDepthMask(flag)
    fun glLineWidth(width: Float) = LGL.glLineWidth(width)
    fun glScissor(x: Int, y: Int, width: Int, height: Int) = LGL.glScissor(x, y, width, height)
    fun glViewport(x: Int, y: Int, width: Int, height: Int) = LGL.glViewport(x, y, width, height)
    fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float) =
        LGL.glClearColor(red, green, blue, alpha)
    fun glClear(mask: Int) = LGL.glClear(mask)
    fun glFlush() = LGL.glFlush()
    fun glFinish() = LGL.glFinish()

    fun glGetString(name: Int): String? = LGL.glGetString(name)

    fun glGetIntegerv(pname: Int, params: IntArray, offset: Int) {
        // LWJGL requires a *direct* NIO buffer — heap buffers (IntBuffer.wrap) give a
        // NULL native address which causes a SEGFAULT inside Mesa's Gallium driver.
        val buf = BufferUtils.createIntBuffer(params.size - offset)
        LGL.glGetIntegerv(pname, buf)
        buf.rewind()
        for (i in offset until params.size) params[i] = buf.get()
    }

    // ── Draw calls ────────────────────────────────────────────────────────────

    fun glDrawArrays(mode: Int, first: Int, count: Int) = LGL.glDrawArrays(mode, first, count)

    fun glDrawElements(mode: Int, count: Int, type: Int, indices: Int) =
        LGL.glDrawElements(mode, count, type, indices.toLong())

    // ── Pixel readback (used by Readback.kt) ──────────────────────────────────

    fun glReadPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: ByteBuffer) =
        LGL.glReadPixels(x, y, width, height, format, type, pixels)
}
