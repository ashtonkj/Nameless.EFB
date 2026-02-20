@file:Suppress("FunctionName", "unused")
package android.opengl

import org.lwjgl.BufferUtils
import org.lwjgl.opengles.GLES20 as LGL20

/** Android GLES20 shim (only glGetIntegerv is called — from GlCapabilities). */
object GLES20 {
    val GL_MAX_TEXTURE_SIZE: Int get() = LGL20.GL_MAX_TEXTURE_SIZE

    fun glGetIntegerv(pname: Int, params: IntArray, offset: Int) {
        // LWJGL requires a *direct* NIO buffer — heap buffers (IntBuffer.wrap) return
        // a NULL native address which causes a SEGFAULT inside Mesa's gallium driver.
        val buf = BufferUtils.createIntBuffer(params.size - offset)
        LGL20.glGetIntegerv(pname, buf)
        buf.rewind()
        for (i in offset until params.size) params[i] = buf.get()
    }
}
