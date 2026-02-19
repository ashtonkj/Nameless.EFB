package com.nameless.efb.domain.gauge

import kotlinx.serialization.Serializable

/**
 * Position and size of one instrument in the custom gauge layout editor.
 *
 * Serialised as JSON and stored in Room (per aircraft profile).
 * Grid origin is top-left; columns and rows are zero-based.
 */
@Serializable
data class GaugeLayoutItem(
    val gaugeType: GaugeType,
    val gridCol: Int,
    val gridRow: Int,
    val sizeClass: GaugeSizeClass = GaugeSizeClass.MEDIUM,
)
