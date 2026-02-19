package com.nameless.efb.rendering.g1000

/** Wind data display mode on the G1000 PFD (selectable via softkey). */
enum class WindMode {
    /** Arrow pointing toward the wind source (into-wind), plus "270° / 15 kt". */
    ARROW_SOURCE,
    /** Arrow in the wind direction (downwind), plus speed. */
    ARROW_WIND,
    /** Resolved headwind / crosswind components: "HW 10kt XW 5kt R". */
    HEADWIND_CROSSWIND,
}
