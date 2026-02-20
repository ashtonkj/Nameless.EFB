package com.nameless.efb.rendering.g1000

import com.nameless.efb.data.connectivity.CommandSink
import com.nameless.efb.rendering.g1000.BaroUnit

/**
 * G1000 PFD knob handler for HDG, CRS, BARO, ALT, and VS knobs (G-22, G-23).
 *
 * All knob actions send dataref-write commands to the X-Plane plugin via [commandSink].
 * Push actions (SYNC, STD, ALT CAP, VS engage) are handled here as well.
 *
 * @param commandSink  Command channel to the X-Plane plugin (mockable).
 */
class PfdKnobHandler(private val commandSink: CommandSink) {

    // ── G-22: HDG knob ────────────────────────────────────────────────────────

    /**
     * Rotates the heading bug by [delta] degrees (1 degree per detent).
     *
     * Wraps 0–360. Provides haptic detent feedback.
     *
     * @param currentHdgBugDeg  Current heading bug value from sim.
     * @return New heading bug in degrees.
     */
    fun onHdgDelta(currentHdgBugDeg: Float, delta: Float): Float {
        val newHdg = (currentHdgBugDeg + delta + 360f) % 360f
        write("sim/cockpit/autopilot/heading_mag", newHdg)
        return newHdg
    }

    /**
     * HDG SYNC (push): sets the heading bug to the current magnetic heading.
     *
     * @param magHeadingDeg  Current aircraft magnetic heading.
     */
    fun onHdgSync(magHeadingDeg: Float) {
        write("sim/cockpit/autopilot/heading_mag", magHeadingDeg)
    }

    // ── G-22: CRS knob ────────────────────────────────────────────────────────

    /**
     * Rotates the NAV1 OBS / course needle by [delta] degrees.
     *
     * @param currentObsDeg  Current NAV1 OBS from sim.
     * @return New OBS in degrees.
     */
    fun onCrsDelta(currentObsDeg: Float, delta: Float): Float {
        val newCrs = (currentObsDeg + delta + 360f) % 360f
        write("sim/cockpit/radios/nav1_obs_degm", newCrs)
        return newCrs
    }

    // ── G-22: BARO knob ───────────────────────────────────────────────────────

    /**
     * Adjusts the barometric altimeter setting by [delta] steps.
     *
     * Step size:
     *  - 1 hPa per detent when [unit] is [BaroUnit.HPA]
     *  - 0.01 inHg per detent when [unit] is [BaroUnit.INHG]
     *
     * @param currentBaroInhg  Current barometer value in inHg from sim.
     * @param unit             Current display unit (hPa default for SA).
     * @return New barometer value in inHg.
     */
    fun onBaroDelta(currentBaroInhg: Float, delta: Float, unit: BaroUnit): Float {
        val stepInhg = when (unit) {
            BaroUnit.HPA  -> 1f / 33.8639f  // 1 hPa → inHg
            BaroUnit.INHG -> 0.01f
        }
        val newBaro = (currentBaroInhg + delta * stepInhg).coerceIn(27.0f, 31.5f)
        write("sim/cockpit/misc/barometer_setting", newBaro)
        return newBaro
    }

    /**
     * BARO STD (push): sets altimeter to standard atmosphere (29.92 inHg / 1013.25 hPa).
     */
    fun onBaroStd() {
        write("sim/cockpit/misc/barometer_setting", 29.92f)
    }

    // ── G-23: ALT knob ────────────────────────────────────────────────────────

    /**
     * Adjusts the autopilot target altitude.
     *
     * Step size:
     *  - Inner ring (small knob): 1 000 ft per detent
     *  - Outer ring (large knob): 100 ft per detent
     *
     * @param currentAltFt   Current AP altitude from sim.
     * @param delta          Number of detents (positive = up).
     * @param isInnerRing    True when the inner (1000 ft) ring is turned.
     * @return New target altitude in feet.
     */
    fun onAltDelta(currentAltFt: Float, delta: Int, isInnerRing: Boolean): Float {
        val step = if (isInnerRing) 1000 else 100
        val newAlt = (currentAltFt + delta * step).coerceIn(0f, 50_000f)
        write("sim/cockpit/autopilot/altitude", newAlt)
        return newAlt
    }

    /**
     * ALT PUSH: activates altitude alerting (triggers ALT CAP annunciation sequence).
     */
    fun onAltPush() {
        write("sim/cockpit2/autopilot/altitude_mode", 1f)
    }

    // ── G-23: VS knob ─────────────────────────────────────────────────────────

    /**
     * Adjusts the autopilot target vertical speed by [delta] × 100 fpm.
     *
     * @param currentVsFpm  Current AP VS from sim.
     * @param delta         Number of detents (positive = climb).
     * @return New target VS in fpm.
     */
    fun onVsDelta(currentVsFpm: Float, delta: Int): Float {
        val newVs = currentVsFpm + delta * 100f
        write("sim/cockpit/autopilot/vertical_velocity", newVs)
        return newVs
    }

    /**
     * VS PUSH: engages VS mode on the autopilot with the current VS.
     */
    fun onVsPush() {
        write("sim/cockpit2/autopilot/vvi_status", 1f)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun write(path: String, value: Float) {
        commandSink.sendCommand("""{"cmd":"set_dataref","path":"$path","value":$value}""")
    }
}
