package com.nameless.efb.rendering.gl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton that owns the active [Theme] and auto-switches between day and
 * night based on simulated local time (SG-22).
 *
 * ## Auto-switch logic
 * Civil twilight is approximated as fixed hours 06:00–18:00 local.  When the
 * sim clock crosses these boundaries the theme transitions automatically unless
 * the pilot has explicitly overridden it via [setManual].
 *
 * ## Integration
 * The active [Theme.uniformValue] should be uploaded each frame as `u_theme`.
 * Theme changes trigger [GaugeTextureAtlas.buildAll] on the GL thread via
 * [BaseRenderer.applyTheme].
 */
object ThemeManager {

    private val _themeFlow = MutableStateFlow(Theme.DAY)

    /** Observe active theme changes; collect on the GL thread or main thread. */
    val themeFlow: StateFlow<Theme> = _themeFlow.asStateFlow()

    private var userOverride = false

    /**
     * Active theme.  Setting this value updates [themeFlow] and stores the choice.
     * External callers should prefer [setManual] / [setAuto] to track override state.
     */
    var currentTheme: Theme = Theme.DAY
        set(value) {
            field = value
            _themeFlow.value = value
        }

    /**
     * Lock theme to [theme] regardless of the sim clock.
     * Call [setAuto] to re-enable automatic switching.
     */
    fun setManual(theme: Theme) {
        userOverride = true
        currentTheme = theme
    }

    /** Re-enable automatic day/night switching based on sim local time. */
    fun setAuto() {
        userOverride = false
    }

    /**
     * Called each sim update with the local time and latitude.
     *
     * Does nothing if a manual override is active.
     *
     * @param simLocalTimeSec seconds since midnight in sim local time.
     * @param latitude        aircraft latitude (positive = north; unused in basic twilight model).
     */
    fun update(simLocalTimeSec: Float, latitude: Double) {
        if (userOverride) return
        val hourOfDay = (simLocalTimeSec / 3600f) % 24f
        currentTheme = if (hourOfDay in 6f..18f) Theme.DAY else Theme.NIGHT
    }
}
