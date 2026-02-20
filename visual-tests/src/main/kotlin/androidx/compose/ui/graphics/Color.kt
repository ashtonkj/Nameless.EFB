@file:Suppress("unused")
package androidx.compose.ui.graphics

/**
 * Minimal Color stub for G1000Colours.kt.
 *
 * G1000Colours uses `Color(0xFF001F4DL)` style literals (Long values in ARGB format).
 * The accessors are used by rendering code to extract float components.
 */
data class Color(val value: Long) {
    /** Construct from a 32-bit ARGB Int (e.g. legacy Android Color ints). */
    constructor(argbInt: Int) : this(argbInt.toLong() and 0xFFFFFFFFL)

    val alpha: Float get() = ((value shr 24) and 0xFFL).toFloat() / 255f
    val red:   Float get() = ((value shr 16) and 0xFFL).toFloat() / 255f
    val green: Float get() = ((value shr  8) and 0xFFL).toFloat() / 255f
    val blue:  Float get() = (value          and 0xFFL).toFloat() / 255f

    companion object {
        val White    = Color(0xFFFFFFFFL)
        val Black    = Color(0xFF000000L)
        val Red      = Color(0xFFFF0000L)
        val Green    = Color(0xFF00FF00L)
        val Blue     = Color(0xFF0000FFL)
        val Gray     = Color(0xFF808080L)
        val Cyan     = Color(0xFF00FFFFL)
        val DarkGray = Color(0xFF404040L)
        val Yellow   = Color(0xFFFFFF00L)
    }
}
