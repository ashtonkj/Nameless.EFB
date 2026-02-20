package com.nameless.efb.rendering.g1000

import com.nameless.efb.data.connectivity.CommandSink
import com.nameless.efb.data.connectivity.SimSnapshot

/**
 * G1000 autopilot panel handler (G-27 through G-31).
 *
 * Handles AP/FD/YD master buttons and lateral/vertical mode selection.
 * All mode changes are issued as dataref-write commands to the X-Plane plugin.
 *
 * AP disconnect triggers the amber AP DISC alert (flashing annunciator).
 *
 * @param commandSink  Command channel to the X-Plane plugin (mockable).
 */
class AutopilotHandler(private val commandSink: CommandSink) {

    /** True while the AP disconnect amber flash is active (cleared after ~3 s). */
    var disconnectAlertActive: Boolean = false
        private set

    private var disconnectAlertStartMs: Long = 0L

    // ── G-27: AP / FD / YD master buttons ────────────────────────────────────

    /**
     * Toggles the autopilot master (engage / disengage).
     *
     * On disengage: triggers the AP disconnect alert (amber flash + audio beep).
     *
     * @param snapshot  Current sim state (reads [SimSnapshot.apStateFlags]).
     */
    fun onApPress(snapshot: SimSnapshot) {
        val flags = snapshot.apStateFlags
        val engaged = flags and AP_ENGAGED_MASK != 0
        val newFlags = if (engaged) {
            flags and AP_ENGAGED_MASK.inv()   // disengage
        } else {
            flags or AP_ENGAGED_MASK          // engage
        }
        write("sim/cockpit/autopilot/autopilot_state", newFlags.toFloat())
        if (engaged) {
            disconnectAlertActive = true
            disconnectAlertStartMs = System.currentTimeMillis()
        }
    }

    /**
     * Toggles the Flight Director (command bars, no servos).
     *
     * @param snapshot  Current sim state.
     */
    fun onFdPress(snapshot: SimSnapshot) {
        val newFlags = snapshot.apStateFlags xor FD_ON_MASK
        write("sim/cockpit/autopilot/autopilot_state", newFlags.toFloat())
    }

    /**
     * Toggles the Yaw Damper.
     *
     * @param snapshot  Current sim state.
     */
    fun onYdPress(snapshot: SimSnapshot) {
        val newFlags = snapshot.apStateFlags xor YD_ON_MASK
        write("sim/cockpit/autopilot/autopilot_state", newFlags.toFloat())
    }

    // ── G-28: Lateral modes ───────────────────────────────────────────────────

    /** Activates Heading mode. */
    fun onHdgMode() { write("sim/cockpit2/autopilot/heading_mode", 1f) }

    /** Activates NAV mode (arms if no valid signal; captures when on course). */
    fun onNavMode() { write("sim/cockpit2/autopilot/nav_status", 1f) }

    /** Activates Approach mode (arms localiser and glideslope). */
    fun onAprMode() { write("sim/cockpit2/autopilot/approach_status", 1f) }

    /** Activates Back Course mode. */
    fun onBcMode()  { write("sim/cockpit2/autopilot/back_course_status", 1f) }

    // ── G-29: Vertical modes ──────────────────────────────────────────────────

    /** Activates Altitude hold mode. */
    fun onAltMode() { write("sim/cockpit2/autopilot/altitude_mode", 1f) }

    /** Activates Vertical Speed mode. */
    fun onVsMode()  { write("sim/cockpit2/autopilot/vvi_status", 1f) }

    /** Activates Flight Level Change (IAS) mode. */
    fun onFlcMode() { write("sim/cockpit2/autopilot/speed_status", 1f) }

    // ── G-31: Disconnect alert tick ───────────────────────────────────────────

    /**
     * Called each frame to advance the AP disconnect alert timer.
     *
     * Clears [disconnectAlertActive] after 3 seconds.
     */
    fun tickDisconnectAlert() {
        if (disconnectAlertActive &&
            System.currentTimeMillis() - disconnectAlertStartMs > 3_000L) {
            disconnectAlertActive = false
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun write(path: String, value: Float) {
        commandSink.sendCommand("""{"cmd":"set_dataref","path":"$path","value":$value}""")
    }
}
