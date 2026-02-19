package com.nameless.efb.rendering.gl

/**
 * Rendering theme passed to GLSL shaders via the `u_theme` uniform.
 *
 * Distinct from the Compose Material3 theme in `ui.theme`.
 */
enum class Theme(val uniformValue: Float) {
    DAY(0.0f),
    NIGHT(1.0f),
    RED_COCKPIT(2.0f),
}
