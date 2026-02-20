package com.nameless.efb.rendering.g1000.mfd

/**
 * TCAS/TAS alert level for a traffic target (G-18).
 */
enum class AlertLevel {
    /** Resolution Advisory — red. Distance < 0.2 nm AND vertical < 100 ft. */
    RA,

    /** Traffic Advisory — amber. Distance < 0.5 nm AND vertical < 200 ft. */
    TA,

    /** Non-threatening proximate or other traffic — white. */
    OTHER,
}
