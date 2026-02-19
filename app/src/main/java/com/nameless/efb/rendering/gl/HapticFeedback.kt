package com.nameless.efb.rendering.gl

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Thin wrapper around [Vibrator] for instrument-panel haptic cues (SG-18).
 *
 * Must be initialised once via [init] before any feedback method is called.
 * All methods are no-ops if uninitialised or if the device lacks a vibrator.
 * Haptic feedback can be disabled globally via Settings (UI-06).
 */
object HapticFeedback {

    private var vibrator: Vibrator? = null

    /** Initialise with an application or activity [Context]. */
    fun init(context: Context) {
        vibrator = context.getSystemService(Vibrator::class.java)
    }

    /**
     * Short tick pulse per knob detent (one unit of rotation).
     * Requires API 29+; silently skipped on older devices.
     */
    fun detent() {
        if (Build.VERSION.SDK_INT >= 29) {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    /** Sharp click for physical button press feedback. */
    fun buttonPress() {
        if (Build.VERSION.SDK_INT >= 29) {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }
    }

    /**
     * Three-pulse burst for limit warnings (oil-temp redline, low-fuel, etc.).
     * Uses explicit amplitude waveform — requires API 26+ (minSdk = 26).
     */
    fun warningBurst() {
        val pattern    = longArrayOf(0, 80, 60, 80, 60, 80)
        val amplitudes = intArrayOf(0, 200, 0, 200, 0, 200)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
    }
}
