package com.nameless.efb.rendering.g1000

import com.nameless.efb.data.connectivity.CommandSink

/** Radio identifier for COM/NAV frequency knob actions (G-21). */
enum class Radio { COM1, COM2, NAV1, NAV2 }

/**
 * G1000 NAV/COM frequency knob handler (G-21).
 *
 * Each COM/NAV radio has two concentric knobs:
 *  - Outer (large) ring  — tunes the MHz portion (±1 MHz steps)
 *  - Inner (small) disc  — tunes the kHz portion (25 kHz steps for COM)
 *
 * Tuned frequencies are written to standby only; the swap button exchanges
 * standby ↔ active.
 *
 * COM range: 118.000–137.000 MHz in 25 kHz steps.
 * NAV range: 108.000–117.975 MHz in 50 kHz steps.
 *
 * @param commandSink  Command channel to the X-Plane plugin (mockable).
 */
class FrequencyKnobHandler(private val commandSink: CommandSink) {

    /**
     * Current standby frequencies in Hz, keyed by radio name.
     *
     * Internal so tests can seed and inspect values without going through
     * the command channel.
     */
    internal val currentStandbyFreq: MutableMap<String, Int> = mutableMapOf(
        Radio.COM1.name to 121_500_000,
        Radio.COM2.name to 121_900_000,
        Radio.NAV1.name to 110_300_000,
        Radio.NAV2.name to 108_100_000,
    )

    // ── COM tuning ────────────────────────────────────────────────────────────

    /**
     * Tunes the COM standby frequency by [delta] MHz.
     *
     * Clamps to 118–137 MHz.
     */
    fun onComMhzDelta(radio: Radio, delta: Int) {
        require(radio == Radio.COM1 || radio == Radio.COM2)
        val current = currentStandbyFreq[radio.name] ?: 121_500_000
        val newFreq = (current + delta * 1_000_000).coerceIn(118_000_000, 137_000_000)
        currentStandbyFreq[radio.name] = newFreq
        writeFrequency(radio, newFreq)
    }

    /**
     * Tunes the COM standby frequency by [delta] × 25 kHz steps.
     *
     * The current frequency is first rounded to the nearest 25 kHz boundary,
     * then the step is applied.  Clamps to 118–137 MHz.
     */
    fun onComKhzDelta(radio: Radio, delta: Int) {
        require(radio == Radio.COM1 || radio == Radio.COM2)
        val current = currentStandbyFreq[radio.name] ?: 121_500_000
        val stepHz  = 25_000
        val aligned = (current / stepHz) * stepHz
        val newFreq = (aligned + delta * stepHz).coerceIn(118_000_000, 137_000_000)
        currentStandbyFreq[radio.name] = newFreq
        writeFrequency(radio, newFreq)
    }

    // ── NAV tuning ────────────────────────────────────────────────────────────

    /**
     * Tunes the NAV standby frequency by [delta] MHz.
     *
     * Clamps to 108–117 MHz.
     */
    fun onNavMhzDelta(radio: Radio, delta: Int) {
        require(radio == Radio.NAV1 || radio == Radio.NAV2)
        val current = currentStandbyFreq[radio.name] ?: 110_300_000
        val newFreq = (current + delta * 1_000_000).coerceIn(108_000_000, 117_975_000)
        currentStandbyFreq[radio.name] = newFreq
        writeFrequency(radio, newFreq)
    }

    /**
     * Tunes the NAV standby frequency by [delta] × 50 kHz steps.
     */
    fun onNavKhzDelta(radio: Radio, delta: Int) {
        require(radio == Radio.NAV1 || radio == Radio.NAV2)
        val current = currentStandbyFreq[radio.name] ?: 110_300_000
        val stepHz  = 50_000
        val aligned = (current / stepHz) * stepHz
        val newFreq = (aligned + delta * stepHz).coerceIn(108_000_000, 117_975_000)
        currentStandbyFreq[radio.name] = newFreq
        writeFrequency(radio, newFreq)
    }

    // ── Swap ──────────────────────────────────────────────────────────────────

    /**
     * Swaps active and standby frequencies for [radio].
     *
     * Sends a "swap_freq" command to X-Plane which handles the dataref update.
     */
    fun onFrequencySwap(radio: Radio) {
        commandSink.sendCommand("""{"cmd":"swap_freq","radio":"${radio.name}"}""")
    }

    // ── NAV OBS ───────────────────────────────────────────────────────────────

    /**
     * Adjusts the NAV1 OBS (Course) by [delta] degrees.
     *
     * Wraps 0–360.
     */
    fun onNavObsDelta(currentObsDeg: Float, delta: Int): Float {
        val newObs = (currentObsDeg + delta + 360f) % 360f
        commandSink.sendCommand(
            """{"cmd":"set_dataref","path":"sim/cockpit/radios/nav1_obs_degm","value":$newObs}"""
        )
        return newObs
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun writeFrequency(radio: Radio, freqHz: Int) {
        commandSink.sendCommand(
            """{"cmd":"set_standby_freq","radio":"${radio.name}","hz":$freqHz}"""
        )
    }
}
