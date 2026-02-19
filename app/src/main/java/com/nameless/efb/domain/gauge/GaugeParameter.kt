package com.nameless.efb.domain.gauge

/** Which GLSL uniform a custom dataref binding targets. */
enum class GaugeParameter {
    DATAREF_VALUE,  // u_dataref_value
    RANGE_MIN,      // u_range_min
    RANGE_MAX,      // u_range_max
    NEEDLE_ANGLE,   // u_needle_angle (pre-computed, radians)
}
