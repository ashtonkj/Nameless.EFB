@file:Suppress("unused")
package android.opengl

/** No-op stub — FontAtlas uses texImage2D via Bitmap which is itself stubbed. */
object GLUtils {
    fun texImage2D(target: Int, level: Int, bitmap: android.graphics.Bitmap, border: Int) = Unit
}
