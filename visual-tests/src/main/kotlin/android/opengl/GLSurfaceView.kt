@file:Suppress("unused")
package android.opengl

/** Stub that provides only the Renderer interface used by BaseRenderer. */
object GLSurfaceView {
    interface Renderer {
        fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10,
                             config: javax.microedition.khronos.egl.EGLConfig)
        fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10, width: Int, height: Int)
        fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10)
    }
}
