package com.nameless.efb.rendering.g1000.mfd

import androidx.compose.ui.graphics.Color

/**
 * TAWS terrain colour scheme specific to the G1000 MFD (G-11, G-17).
 *
 * These thresholds match Garmin G1000 CRG Rev. R Section 5 and differ from
 * the generic TAWS colours used by the moving-map overlay.
 */
object G1000TawsColours {
    /** Solid red: terrain within 100 ft AGL — immediate threat. */
    val TERRAIN_SOLID_RED = Color(0xFFFF0000)

    /** Solid yellow: terrain 100–500 ft AGL — caution. */
    val TERRAIN_SOLID_YELLOW = Color(0xFFFFFF00)

    /** Solid green: terrain 500–2 000 ft AGL — advisory. */
    val TERRAIN_SOLID_GREEN = Color(0xFF00CC00)

    /** No threat: terrain more than 2 000 ft below — rendered transparent. */
    val TERRAIN_NO_THREAT = Color(0xFF000000)
}
