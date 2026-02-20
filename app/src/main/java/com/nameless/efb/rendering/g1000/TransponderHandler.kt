package com.nameless.efb.rendering.g1000

import com.nameless.efb.data.connectivity.CommandSink

/**
 * Transponder operating modes (G-33).
 *
 * Values match X-Plane's `sim/cockpit/radios/transponder_mode` dataref.
 */
enum class TransponderMode(val value: Int) {
    OFF(0),
    STANDBY(1),
    GROUND(2),
    ON(3),
    ALT(4),     // Mode C — altitude reporting
}

/**
 * G1000 transponder control handler (G-33).
 *
 * Squawk code is 4 octal digits (each digit 0–7).
 *
 * South Africa VFR default squawk: **7000** (SACAA AIC A025/2015).
 *
 * @param commandSink  Command channel to the X-Plane plugin (mockable).
 * @param initialCode  Initial squawk code (decimal representation of 4 octal digits).
 */
class TransponderHandler(
    private val commandSink: CommandSink,
    initialCode: Int = SA_VFR_DEFAULT,
) {

    /** SA VFR default squawk code (7000). */
    val saVfrDefaultCode: Int = SA_VFR_DEFAULT

    /** Current squawk code as a 4-digit decimal-encoded octal number (e.g. 7000, 1200). */
    var currentCode: Int = initialCode
        private set

    /** Current transponder mode. */
    var currentMode: TransponderMode = TransponderMode.ALT
        private set

    // ── Squawk digit adjustment ────────────────────────────────────────────────

    /**
     * Adjusts a single octal digit of the squawk code.
     *
     * Each digit is octal (0–7); wraps around within the octal range.
     *
     * @param digitIndex  Digit position: 0 = leftmost (thousands), 3 = rightmost (ones).
     * @param delta       Direction: +1 = increment, -1 = decrement.
     */
    fun onSquawkDigitDelta(digitIndex: Int, delta: Int) {
        require(digitIndex in 0..3)
        val digits = currentCode.toOctalDigits().toMutableList()
        digits[digitIndex] = (digits[digitIndex] + delta + 8) % 8  // octal wrap
        currentCode = digits.fromOctalDigits()
        write("sim/cockpit/radios/transponder_code", currentCode.toFloat())
    }

    // ── IDENT ──────────────────────────────────────────────────────────────────

    /** Transmits the IDENT pulse (special position identification). */
    fun onIdent() {
        write("sim/cockpit/radios/transponder_mode", TransponderMode.ALT.value.toFloat())
        commandSink.sendCommand("""{"cmd":"set_dataref","path":"sim/cockpit2/radios/actuators/transponder_id","value":1}""")
    }

    // ── Mode selection ────────────────────────────────────────────────────────

    /**
     * Selects the transponder operating mode.
     *
     * @param mode  New transponder mode.
     */
    fun onModeSelect(mode: TransponderMode) {
        currentMode = mode
        write("sim/cockpit/radios/transponder_mode", mode.value.toFloat())
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun write(path: String, value: Float) {
        commandSink.sendCommand("""{"cmd":"set_dataref","path":"$path","value":$value}""")
    }

    companion object {
        /** SA VFR squawk code (7000) per SACAA AIC A025/2015. */
        const val SA_VFR_DEFAULT = 7000
    }
}

// ── Octal digit helpers ───────────────────────────────────────────────────────

/**
 * Decomposes this 4-digit decimal-encoded squawk code into a list of digits
 * [thousands, hundreds, tens, ones], each in 0–7.
 *
 * Squawk codes are decimal-encoded: 7000 → [7, 0, 0, 0], NOT octal arithmetic.
 * Each decimal digit must be in 0–7 to be a valid transponder squawk digit.
 */
internal fun Int.toOctalDigits(): List<Int> = listOf(
    this / 1000,
    (this / 100) % 10,
    (this / 10)  % 10,
    this          % 10,
)

/**
 * Reassembles a list of 4 octal digits [thousands, hundreds, tens, ones] into
 * a decimal-encoded squawk code.
 */
internal fun List<Int>.fromOctalDigits(): Int =
    this[0] * 1000 + this[1] * 100 + this[2] * 10 + this[3]
