package com.nameless.efb.domain.map

import com.nameless.efb.data.connectivity.SimSnapshot

/** Selectable fields for the instrument strip / data bar (IP-01). */
enum class DataField {
    GS, TAS, ALT, HDG, VS, ETE, DTK, TRK, DIST, WIND_COMPONENT, OAT, FUEL_REMAINING;

    /**
     * Formats the field value from [snapshot] for display, using [units] preferences.
     * Returns "---" when the snapshot is null or the value is unavailable.
     */
    fun format(snapshot: SimSnapshot?, units: UnitPrefs): String = when (this) {
        GS   -> snapshot?.let { "%.0f kt".format(it.groundspeedMs * 1.944f) } ?: "---"
        TAS  -> snapshot?.let { "%.0f kt".format(it.tasKts) } ?: "---"
        ALT  -> snapshot?.let {
            val ft = (it.elevationM * 3.28084).toFloat()
            "%.0f ft".format(ft)
        } ?: "---"
        HDG  -> snapshot?.let { "%.0f°".format(it.magHeadingDeg) } ?: "---"
        VS   -> snapshot?.let { "%+.0f fpm".format(it.vviFpm) } ?: "---"
        OAT  -> snapshot?.let { "%.0f°C".format(it.oatDegc) } ?: "---"
        FUEL_REMAINING -> snapshot?.let {
            val totalKg = it.fuelQtyKg.sum()
            when (units.fuel) {
                FuelUnit.LITRES -> "%.1f L".format(totalKg / 0.72f)   // AVGAS density
                FuelUnit.KG     -> "%.1f kg".format(totalKg)
                FuelUnit.USG    -> "%.1f gal".format(totalKg / 2.719f) // 1 USG AVGAS ≈ 2.719 kg
            }
        } ?: "---"
        // Navigation fields require a flight plan — return placeholder
        ETE, DTK, TRK, DIST, WIND_COMPONENT -> "---"
    }
}

/**
 * Configuration for the instrument strip data bar layout (IP-01).
 *
 * Up to 4 fields per edge; defaults reflect common VFR SA training needs.
 */
data class DataBarConfig(
    val leftFields:  List<DataField> = listOf(DataField.GS, DataField.ALT, DataField.HDG),
    val rightFields: List<DataField> = listOf(DataField.VS, DataField.OAT, DataField.FUEL_REMAINING),
)
