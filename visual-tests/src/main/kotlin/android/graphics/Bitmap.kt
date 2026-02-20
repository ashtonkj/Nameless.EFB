@file:Suppress("unused")
package android.graphics

/** No-op Bitmap stub — FontAtlas creates bitmaps but we skip actual rasterisation. */
class Bitmap private constructor(val width: Int, val height: Int) {
    enum class Config { ARGB_8888 }

    companion object {
        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap = Bitmap(width, height)
    }

    fun recycle() = Unit
}
