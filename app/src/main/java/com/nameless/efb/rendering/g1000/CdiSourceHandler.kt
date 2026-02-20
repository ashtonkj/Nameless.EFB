package com.nameless.efb.rendering.g1000

import com.nameless.efb.data.connectivity.CommandSink

/**
 * CDI (Course Deviation Indicator) source for the G1000 HSI (G-35).
 *
 * SA RNAV (GNSS) approaches require GPS source; the sequence matches G1000 CRG.
 */
enum class CdiSource(val value: Int) {
    GPS(0), NAV1(1), NAV2(2)
}

/**
 * G1000 CDI source selection handler (G-35).
 *
 * Pressing the CDI softkey cycles: GPS → NAV1 → NAV2 → GPS.
 *
 * For SA RNAV (GNSS) approaches the source must be GPS (value 0).
 *
 * @param commandSink    Command channel to the X-Plane plugin (mockable).
 * @param initialSource  Initial CDI source (0 = GPS default).
 */
class CdiSourceHandler(
    private val commandSink: CommandSink,
    initialSource: Int = CdiSource.GPS.value,
) {

    /** Currently active CDI source. */
    var currentSource: Int = initialSource
        private set

    /**
     * Cycles to the next CDI source: GPS → NAV1 → NAV2 → GPS.
     *
     * Writes the new source to X-Plane via [commandSink].
     */
    fun onCdiSoftkey() {
        currentSource = (currentSource + 1) % 3
        commandSink.sendCommand(
            """{"cmd":"set_dataref","path":"sim/cockpit2/radios/actuators/HSI_source_select_pilot","value":$currentSource}"""
        )
    }
}
