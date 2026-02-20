@file:Suppress("unused")
package android.graphics

/** No-op Paint stub. */
class Paint(flags: Int = 0) {
    companion object {
        const val ANTI_ALIAS_FLAG = 1
    }

    var typeface: Typeface? = null
    var color: Int = 0
    var textSize: Float = 12f
    var textAlign: Align = Align.LEFT

    enum class Align { LEFT, CENTER, RIGHT }

    fun measureText(text: String): Float = text.length * textSize * 0.6f
    fun setTypeface(typeface: Typeface?): Typeface? { this.typeface = typeface; return typeface }
}
