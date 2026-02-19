package com.nameless.efb.domain.gauge

/**
 * Maps an arbitrary X-Plane dataref to a specific gauge parameter (SG-23).
 *
 * Advanced feature: allows the pilot to display custom datarefs (e.g.
 * `laminar/B738/autopilot/vs_status`) on any gauge uniform.
 *
 * [scale] and [offset] apply a linear transform: `displayedValue = raw * scale + offset`.
 *
 * Valid bindings are sent to the Rust plugin via a `reload` command packet so
 * the plugin's subscription list is updated without restarting X-Plane.
 */
data class CustomDatarefBinding(
    val gaugeType: GaugeType,
    val parameter: GaugeParameter,
    val datarefPath: String,
    val scale: Float = 1f,
    val offset: Float = 0f,
)

/** Result of a dataref validation request sent to the Rust plugin. */
sealed class DatarefValidation {
    /** Plugin confirmed the dataref exists and reported its type. */
    data class Valid(val datarefType: String) : DatarefValidation()
    /** Plugin could not find the dataref path. */
    data object NotFound : DatarefValidation()
    /** No response within the 500 ms timeout window. */
    data object Timeout : DatarefValidation()
}
