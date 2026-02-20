@file:Suppress("unused")
package android.graphics

/** No-op Canvas stub — FontAtlas draws glyphs to canvas, but stub skips actual drawing. */
class Canvas(val bitmap: Bitmap) {
    fun drawText(text: String, x: Float, y: Float, paint: Paint) = Unit
}
