package com.nameless.efb.rendering.g1000

/**
 * G1000 interaction state machine.
 *
 * The active state determines:
 *  - Which softkey labels are shown (softkey context)
 *  - How the FMS knob behaves (page navigation vs cursor movement vs char entry)
 *  - What ENT and CLR do
 *
 * State transitions follow the Garmin G1000 CRG Rev. R page hierarchy exactly.
 */
sealed class G1000State {

    /** PFD main page — softkeys show INSET, PFD, OBS, CDI, DME, TMRS, etc. */
    object PfdMain : G1000State()

    /** MFD moving map page. */
    object MfdMap : G1000State()

    /** MFD FPL page — FMS knob outer cycles page groups, inner navigates sub-pages. */
    object MfdFpl : G1000State()

    /**
     * MFD FPL page with cursor active on [cursorRow].
     *
     * FMS outer ring moves cursor up/down; inner disc edits the selected field.
     * ENT confirms; CLR cancels and returns to [MfdFpl].
     */
    data class MfdFplCursor(val cursorRow: Int) : G1000State()

    /** MFD PROC page — SID/STAR/approach selection. */
    object MfdProc : G1000State()

    /** MFD NRST pages. */
    object MfdNrst : G1000State()

    /** MFD AUX pages. */
    object MfdAux : G1000State()

    /** MFD Terrain page. */
    object MfdTerrain : G1000State()

    /** MFD Traffic page. */
    object MfdTraffic : G1000State()

    /**
     * Direct-To identifier entry overlay on PFD.
     *
     * [partial] is the identifier typed so far via FMS inner knob character cycling.
     * ENT resolves the identifier and activates direct-to; CLR cancels.
     */
    data class DirectToEntry(val partial: String) : G1000State()

    /**
     * Frequency standby entry mode — FMS inner disc cycles digits.
     *
     * ENT confirms the new standby frequency; CLR cancels.
     */
    data class FreqEntry(val radio: String, val partialHz: Int) : G1000State()
}
