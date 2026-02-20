package com.nameless.efb.rendering.g1000.mfd

/**
 * G1000 MFD range steps in nautical miles (G-11).
 *
 * Must contain 1 nm (minimum) and 1000 nm (maximum) per G1000 CRG.
 */
val G1000_RANGE_STEPS_NM = intArrayOf(
    1, 2, 3, 4, 5, 7, 10, 15, 20, 25, 30, 40, 50, 75, 100, 150, 200, 300, 500, 1000
)

/**
 * All G1000 MFD page identifiers.
 *
 * The FMS outer knob cycles through page groups; the inner knob cycles sub-pages
 * within the current group (e.g. NRST sub-pages).
 */
enum class MfdPage {
    MAP,
    ENGINE_MAP,
    FPL,
    PROC,
    NRST_AIRPORTS,
    NRST_VORS,
    NRST_NDBS,
    AUX_TRIP,
    AUX_UTILITY,
    AUX_GPS_STATUS,
    AUX_SYSTEM_STATUS,
    TERRAIN,
    TRAFFIC,
}

/**
 * Manages active MFD page state and FMS knob navigation between pages.
 *
 * @param onPageChanged  Called on the UI thread whenever the active page changes.
 */
class MfdPageManager(private val onPageChanged: (MfdPage) -> Unit) {

    /** Currently displayed MFD page. Setting this triggers [onPageChanged]. */
    var activePage: MfdPage = MfdPage.MAP
        set(value) {
            field = value
            onPageChanged(value)
        }

    /** FMS outer knob: cycles through top-level page groups. */
    fun onFmsOuterKnob(delta: Int) {
        val groups = listOf(
            MfdPage.MAP, MfdPage.FPL, MfdPage.PROC, MfdPage.NRST_AIRPORTS,
            MfdPage.AUX_TRIP, MfdPage.TERRAIN, MfdPage.TRAFFIC,
        )
        val current = groups.indexOf(activePage).takeIf { it >= 0 } ?: 0
        activePage = groups[(current + delta).mod(groups.size)]
    }

    /** FMS inner knob: cycles sub-pages within the current page group. */
    fun onFmsInnerKnob(delta: Int) {
        val subPageGroups = mapOf(
            MfdPage.NRST_AIRPORTS to listOf(
                MfdPage.NRST_AIRPORTS, MfdPage.NRST_VORS, MfdPage.NRST_NDBS,
            ),
            MfdPage.AUX_TRIP to listOf(
                MfdPage.AUX_TRIP, MfdPage.AUX_UTILITY,
                MfdPage.AUX_GPS_STATUS, MfdPage.AUX_SYSTEM_STATUS,
            ),
        )
        for ((_, subPages) in subPageGroups) {
            val idx = subPages.indexOf(activePage)
            if (idx >= 0) {
                activePage = subPages[(idx + delta).mod(subPages.size)]
                return
            }
        }
    }
}
