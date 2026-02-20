package com.nameless.efb.rendering.g1000

import com.nameless.efb.data.connectivity.CommandSink

/**
 * G1000 Direct-To button handler (G-24).
 *
 * Workflow:
 *  1. User presses D→  → opens identifier entry overlay on PFD ([onDirectToPress])
 *  2. User rotates FMS inner disc  → cycles A–Z, 0–9 characters ([onCharEntry])
 *  3. User presses ENT  → resolves identifier in nav DB → activates direct-to ([onEntPress])
 *  4. User presses CLR  → cancels without changing route ([onClrPress])
 *
 * @param commandSink  Command channel to the X-Plane plugin (mockable).
 */
class DirectToHandler(private val commandSink: CommandSink) {

    /** True while the identifier entry overlay is showing. */
    var isActive: Boolean = false
        private set

    /** Characters typed so far during identifier entry. */
    val enteredIdentifier: StringBuilder = StringBuilder()

    // ── Button / key handlers ─────────────────────────────────────────────────

    /** Activates the D→ overlay and clears any previous entry. */
    fun onDirectToPress() {
        isActive = true
        enteredIdentifier.clear()
    }

    /**
     * Appends [char] to the identifier being typed.
     *
     * Only effective when [isActive] is true.
     */
    fun onCharEntry(char: Char) {
        if (!isActive) return
        enteredIdentifier.append(char)
    }

    /**
     * Cancels identifier entry and hides the overlay without activating direct-to.
     */
    fun onClrPress() {
        isActive = false
        enteredIdentifier.clear()
    }

    /**
     * Confirms identifier entry and issues a direct-to command to X-Plane.
     *
     * If the identifier is blank, does nothing. The plugin resolves the identifier
     * against its nav database and activates the direct-to route segment.
     *
     * @return The identifier string that was submitted, or null if entry was empty.
     */
    fun onEntPress(): String? {
        if (!isActive) return null
        val identifier = enteredIdentifier.toString().trim()
        isActive = false
        enteredIdentifier.clear()
        if (identifier.isEmpty()) return null
        commandSink.sendCommand("""{"cmd":"direct_to","identifier":"$identifier"}""")
        return identifier
    }
}
