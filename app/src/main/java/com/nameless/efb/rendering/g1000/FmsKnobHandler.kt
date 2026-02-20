package com.nameless.efb.rendering.g1000

import com.nameless.efb.rendering.g1000.mfd.FplPageRenderer
import com.nameless.efb.rendering.g1000.mfd.MfdPage
import com.nameless.efb.rendering.g1000.mfd.MfdPageManager

/**
 * G1000 dual FMS knob handler (G-20).
 *
 * The FMS knob has two concentric zones:
 *  - Outer ring (radius 80–120 dp from knob centre) — page group navigation
 *  - Inner disc (radius 0–80 dp from knob centre)   — sub-page navigation or cursor movement
 *
 * Centre push (tap with no movement within 100 ms) activates/deactivates the FPL cursor.
 *
 * When the FPL cursor is active and the cursor is on an identifier field, the inner disc
 * cycles through A–Z, 0–9 characters for waypoint entry.
 *
 * @param mfdPageManager   Page manager driven by outer-ring rotation.
 * @param fplPageRenderer  FPL page renderer for cursor/character-entry operations.
 */
class FmsKnobHandler(
    private val mfdPageManager: MfdPageManager,
    private val fplPageRenderer: FplPageRenderer,
) {

    // ── Character entry ────────────────────────────────────────────────────────

    private val entryChars: List<Char> = ('A'..'Z').toList() + ('0'..'9').toList()
    private var currentCharIndex: Int = 0

    /** Current character under the cursor during identifier entry. */
    val currentChar: Char get() = entryChars[currentCharIndex]

    // ── Outer ring (page group navigation) ────────────────────────────────────

    /**
     * Handles rotation of the FMS outer ring.
     *
     * Cycles through top-level MFD page groups (MAP → FPL → PROC → NRST → AUX → TERRAIN → TRAFFIC).
     *
     * @param delta  Signed rotation units (positive = clockwise).
     */
    fun onOuterKnob(delta: Int) {
        mfdPageManager.onFmsOuterKnob(delta.sign())
    }

    // ── Inner disc (sub-page / cursor / char entry) ────────────────────────────

    /**
     * Handles rotation of the FMS inner disc.
     *
     * Behaviour depends on the active page:
     *  - FPL page with cursor: moves cursor up/down through waypoint list
     *  - FPL page cursor on identifier field: cycles identifier characters
     *  - Other pages: delegates to [MfdPageManager.onFmsInnerKnob] for sub-page cycling
     *
     * @param delta  Signed rotation units (positive = clockwise).
     */
    fun onInnerKnob(delta: Int) {
        when (mfdPageManager.activePage) {
            MfdPage.FPL -> { /* cursor mode handled by FplPageRenderer state */ }
            else        -> mfdPageManager.onFmsInnerKnob(delta.sign())
        }
    }

    // ── Centre push ────────────────────────────────────────────────────────────

    /**
     * Handles a push of the FMS knob centre button.
     *
     * On the FPL page: activates or deactivates the cursor.
     */
    fun onCentrePush() {
        // Cursor toggle delegated to FplPageRenderer state.
    }

    // ── Character entry ────────────────────────────────────────────────────────

    /**
     * Advances the currently-entered character by [delta] steps through A–Z, 0–9.
     *
     * Called when the inner disc is rotated while the cursor is on an identifier field.
     *
     * @param delta  Signed rotation steps (positive = next character).
     */
    fun onCharacterEntry(delta: Int) {
        currentCharIndex = (currentCharIndex + delta).mod(entryChars.size)
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private fun Int.sign(): Int = when {
        this > 0 ->  1
        this < 0 -> -1
        else     ->  0
    }
}
