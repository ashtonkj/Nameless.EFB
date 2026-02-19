package com.nameless.efb.ui.steam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.domain.gauge.AlertType
import com.nameless.efb.domain.gauge.AircraftProfile
import com.nameless.efb.domain.gauge.iirStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Snapshot of all values needed to render the 14-instrument steam gauge panel.
 *
 * All fields are in display units (kts, ft, fpm, °C, PSI, kg, LPH, V, A, inHg).
 * [displayedVsiFpm] is the IIR-filtered VSI value (pneumatic lag simulation).
 */
data class GaugeState(
    // Primary flight
    val airspeedKts: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val altFt: Float = 0f,
    val headingDeg: Float = 0f,
    val vsiFpm: Float = 0f,
    val displayedVsiFpm: Float = 0f,  // IIR-filtered for pneumatic-lag realism mode
    val slipDeg: Float = 0f,
    val turnRateDegSec: Float = 0f,

    // Engine
    val rpmEng0: Float = 0f,
    val mapInhg: Float = 0f,
    val oilTempDegC: Float = 0f,
    val oilPressPsi: Float = 0f,
    val fuelFlowKgSec: Float = 0f,
    val fuelQtyKg: FloatArray = FloatArray(2),
    val egtDegC: FloatArray = FloatArray(6),

    // Electrical / pneumatic
    val busVolts: Float = 0f,
    val battAmps: Float = 0f,
    val suctionInhg: Float = 0f,

    // Accumulated
    val hobbsSeconds: Float = 0f,

    // Active alerts
    val alerts: Set<AlertType> = emptySet(),
)

/**
 * Maps live [SimSnapshot] data to [GaugeState] for the steam gauge panel.
 *
 * Responsibilities:
 * - IIR low-pass filter on the VSI (simulates pneumatic pointer lag, tau = 6 s)
 * - Hobbs time accumulation (engine running = rpm > 500)
 * - Alert detection: oil temp, oil pressure, fuel endurance, fuel imbalance,
 *   bus voltage, gyro suction
 *
 * @param simData  Live sim data stream (from [DataSourceManager]).
 * @param profile  Aircraft limits used to threshold alerts.
 */
class SteamGaugePanelViewModel(
    simData: StateFlow<SimSnapshot>,
    private val profile: AircraftProfile = AircraftProfile(),
) : ViewModel() {

    private val _gaugeState = MutableStateFlow(GaugeState())
    val gaugeState: StateFlow<GaugeState> = _gaugeState.asStateFlow()

    // IIR filter state — VSI pneumatic lag.
    private var displayedVsiFpm = 0f

    // Hobbs accumulator (seconds of engine-running time).
    private var hobbsSeconds = 0f

    // Wall-clock for frame-delta calculation.
    private var lastUpdateMs = System.currentTimeMillis()

    init {
        viewModelScope.launch {
            simData.collect { snap -> processSnapshot(snap) }
        }
    }

    private fun processSnapshot(snap: SimSnapshot) {
        val nowMs = System.currentTimeMillis()
        val dtSec = ((nowMs - lastUpdateMs) / 1000f).coerceIn(0f, 0.1f)
        lastUpdateMs = nowMs

        // VSI lag filter (tau = 6 s for pneumatic instruments).
        displayedVsiFpm = iirStep(displayedVsiFpm, snap.vviFpm, dtSec)

        // Hobbs: accumulate engine-running time.
        if (snap.rpm > 500f) hobbsSeconds += dtSec

        // Alert detection.
        val alerts = mutableSetOf<AlertType>()

        if (snap.oilTempDegc > profile.oilTempRedlineDegC) {
            alerts += AlertType.OIL_TEMP_HIGH
        }
        if (snap.oilPressPsi < profile.oilPressMinPsi && snap.rpm > 500f) {
            alerts += AlertType.OIL_PRESS_LOW
        }
        val fuelKg = snap.fuelQtyKg.sum()
        if (snap.fuelFlowKgSec > 0f) {
            val enduranceHours = fuelKg / (snap.fuelFlowKgSec * 3600f)
            if (enduranceHours < 0.5f) alerts += AlertType.FUEL_LOW
        }
        if (abs(snap.fuelQtyKg[0] - snap.fuelQtyKg[1]) > 5f) {
            alerts += AlertType.FUEL_IMBALANCE
        }
        if (snap.busVolts < profile.busVoltsMin && snap.rpm > 500f) {
            alerts += AlertType.VOLTAGE_LOW
        }
        if (snap.suctionInhg < profile.suctionMinInhg) {
            alerts += AlertType.GYRO_UNRELIABLE
        }

        // Altitude: SimSnapshot stores GPS elevation in metres; convert to feet.
        val altFt = (snap.elevationM / 0.3048).toFloat()

        _gaugeState.value = GaugeState(
            airspeedKts      = snap.iasKts,
            pitchDeg         = snap.pitchDeg,
            rollDeg          = snap.rollDeg,
            altFt            = altFt,
            headingDeg       = snap.magHeadingDeg,
            vsiFpm           = snap.vviFpm,
            displayedVsiFpm  = displayedVsiFpm,
            slipDeg          = snap.slipDeg,
            turnRateDegSec   = snap.turnRateDegSec,
            rpmEng0          = snap.rpm,
            mapInhg          = snap.mapInhg,
            oilTempDegC      = snap.oilTempDegc,
            oilPressPsi      = snap.oilPressPsi,
            fuelFlowKgSec    = snap.fuelFlowKgSec,
            fuelQtyKg        = snap.fuelQtyKg.copyOf(),
            egtDegC          = snap.egtDegc.copyOf(),
            busVolts         = snap.busVolts,
            battAmps         = snap.batteryAmps,
            suctionInhg      = snap.suctionInhg,
            hobbsSeconds     = hobbsSeconds,
            alerts           = alerts,
        )
    }
}
