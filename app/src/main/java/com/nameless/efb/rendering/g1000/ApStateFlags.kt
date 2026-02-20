package com.nameless.efb.rendering.g1000

import com.nameless.efb.data.connectivity.SimSnapshot

// ── Autopilot state flags (Rust dataref-schema `ap_state_flags` bitfield) ──────

/** Autopilot engaged (master switch). */
const val AP_ENGAGED_MASK = 0x0001

/** Heading mode active (lateral). */
const val HDG_MODE = 0x0002

/** NAV mode active (lateral). */
const val NAV_MODE = 0x0004

/** NAV mode armed (lateral, white annunciation). */
const val NAV_ARMED = 0x0008

/** Altitude hold mode active (vertical). */
const val ALT_MODE = 0x0010

/** Altitude mode armed (vertical, white annunciation). */
const val ALT_ARMED = 0x0020

/** Vertical speed mode active (vertical). */
const val VS_MODE = 0x0040

/** Flight-level change / IAS mode active (speed-based climb/descent). */
const val FLC_MODE = 0x0080

/** Approach mode active (lateral + vertical, arms GS). */
const val APR_MODE = 0x0100

/** Back-course mode active. */
const val BC_MODE = 0x0200

/** Flight director active (shows command bars, no servos). */
const val FD_ON_MASK = 0x0400

/** Yaw damper engaged. */
const val YD_ON_MASK = 0x0800

// ── AP Mode State ──────────────────────────────────────────────────────────────

/**
 * Decoded autopilot mode state for the G1000 mode annunciator strip (G-31).
 *
 * Green text = active; white text = armed.
 */
data class ApModeState(
    /** Active lateral mode label ("HDG", "NAV", "ROL", "APR", "BC"). */
    val lateralActive: String,

    /** Armed lateral mode label, or null if none armed. */
    val lateralArmed: String?,

    /** Active vertical mode label ("ALT", "VS", "FLC", "PIT", "GS"). */
    val verticalActive: String,

    /** Armed vertical mode label, or null if none armed. */
    val verticalArmed: String?,

    /** Active speed mode label, or null if not in speed mode. */
    val speedMode: String?,
)

/**
 * Decodes [SimSnapshot.apStateFlags] into a human-readable [ApModeState].
 *
 * Pure function — testable on the JVM without Android or OpenGL dependencies.
 */
fun buildApModeState(snapshot: SimSnapshot): ApModeState {
    val flags = snapshot.apStateFlags
    return ApModeState(
        lateralActive = when {
            flags and HDG_MODE != 0 -> "HDG"
            flags and NAV_MODE != 0 -> "NAV"
            flags and APR_MODE != 0 -> "APR"
            flags and BC_MODE  != 0 -> "BC"
            else                    -> "ROL"
        },
        lateralArmed  = if (flags and NAV_ARMED != 0) "NAV" else null,
        verticalActive = when {
            flags and ALT_MODE != 0 -> "ALT"
            flags and VS_MODE  != 0 -> "VS"
            flags and FLC_MODE != 0 -> "FLC"
            flags and APR_MODE != 0 -> "GS"
            else                    -> "PIT"
        },
        verticalArmed  = if (flags and ALT_ARMED != 0) "ALT" else null,
        speedMode      = null,
    )
}
