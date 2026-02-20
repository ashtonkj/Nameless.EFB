package com.nameless.efb.ui.steam

import com.nameless.efb.domain.gauge.AlertType

/**
 * Snapshot of display values for the steam gauge panel.
 *
 * This is the data class from SteamGaugePanelViewModel.kt, extracted here as a
 * standalone stub so visual-tests can compile without the Android ViewModel.
 */
data class GaugeState(
    val airspeedKts: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val altFt: Float = 0f,
    val headingDeg: Float = 0f,
    val vsiFpm: Float = 0f,
    val displayedVsiFpm: Float = 0f,
    val slipDeg: Float = 0f,
    val turnRateDegSec: Float = 0f,
    val rpmEng0: Float = 0f,
    val mapInhg: Float = 0f,
    val oilTempDegC: Float = 0f,
    val oilPressPsi: Float = 0f,
    val fuelFlowKgSec: Float = 0f,
    val fuelQtyKg: FloatArray = FloatArray(2),
    val egtDegC: FloatArray = FloatArray(6),
    val busVolts: Float = 0f,
    val battAmps: Float = 0f,
    val suctionInhg: Float = 0f,
    val hobbsSeconds: Float = 0f,
    val alerts: Set<AlertType> = emptySet(),
)
