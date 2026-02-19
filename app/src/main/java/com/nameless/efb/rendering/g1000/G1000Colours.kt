package com.nameless.efb.rendering.g1000

import androidx.compose.ui.graphics.Color

/**
 * Colour palette from Garmin G1000 CRG Rev. R (P/N 190-00498-00).
 *
 * Used by Compose UI overlays (Compose [Color]) and OpenGL uniforms alike.
 * For GL use, extract RGBA floats via [Color.red], [Color.green], etc.
 */
object G1000Colours {
    val SKY_TOP       = Color(0xFF001F4D)   // dark blue sky gradient top
    val SKY_BOTTOM    = Color(0xFF0062AB)   // medium blue sky gradient bottom
    val GROUND_TOP    = Color(0xFF5C3D0A)   // dark brown ground top
    val GROUND_BOTTOM = Color(0xFF2A1800)   // very dark brown ground bottom
    val HORIZON_LINE  = Color(0xFFFFFFFF)   // white horizon line
    val PITCH_LADDER  = Color(0xFFFFFFFF)   // white pitch ladder lines
    val MAGENTA       = Color(0xFFFF00FF)   // active route, flight director commands
    val CYAN          = Color(0xFF00FFFF)   // selected values (bug targets)
    val GREEN_ACTIVE  = Color(0xFF00FF00)   // active AP mode annunciation
    val WHITE_ARMED   = Color(0xFFFFFFFF)   // armed AP mode annunciation
    val AMBER_CAUTION = Color(0xFFFFBF00)   // cautions, warnings
    val RED_WARNING   = Color(0xFFFF0000)   // warnings
    val TAPE_BG       = Color(0xFF1A1A1A)   // dark tape background
    val TAPE_TEXT     = Color(0xFFFFFFFF)   // tape numeric readouts
}
