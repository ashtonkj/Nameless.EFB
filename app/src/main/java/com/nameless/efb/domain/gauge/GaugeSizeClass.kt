package com.nameless.efb.domain.gauge

import kotlinx.serialization.Serializable

/** Display size for a gauge in the custom layout editor. */
@Serializable
enum class GaugeSizeClass {
    SMALL,   // 1×1 grid cell
    MEDIUM,  // standard 1×1 cell (default)
    LARGE,   // 2×2 grid cells (for primary six-pack instruments)
}
