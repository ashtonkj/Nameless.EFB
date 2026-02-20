package com.nameless.efb.rendering.g1000

import com.nameless.efb.data.connectivity.CommandSink
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.rendering.g1000.mfd.MfdPageManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for G1000 bezel controls and autopilot logic (G-19 through G-36).
 *
 * All tests run on the JVM — no Android or OpenGL dependencies.
 */
class G1000ControlsTest {

    // ── G-21: COM frequency tuning (25 kHz steps) ─────────────────────────────

    @Test
    fun comKhzTuning_25kHzSteps() {
        val handler = FrequencyKnobHandler(mockk(relaxed = true))
        handler.currentStandbyFreq["COM1"] = 118_100_000
        handler.onComKhzDelta(Radio.COM1, delta = 1)
        assertEquals(118_125_000, handler.currentStandbyFreq["COM1"])
    }

    @Test
    fun comKhzTuning_negativeStep() {
        val handler = FrequencyKnobHandler(mockk(relaxed = true))
        handler.currentStandbyFreq["COM1"] = 118_125_000
        handler.onComKhzDelta(Radio.COM1, delta = -1)
        assertEquals(118_100_000, handler.currentStandbyFreq["COM1"])
    }

    @Test
    fun comMhzTuning_incrementsMhz() {
        val handler = FrequencyKnobHandler(mockk(relaxed = true))
        handler.currentStandbyFreq["COM1"] = 118_100_000
        handler.onComMhzDelta(Radio.COM1, delta = 1)
        assertEquals(119_100_000, handler.currentStandbyFreq["COM1"])
    }

    @Test
    fun comMhzTuning_clampsAtMax() {
        val handler = FrequencyKnobHandler(mockk(relaxed = true))
        handler.currentStandbyFreq["COM1"] = 137_000_000
        handler.onComMhzDelta(Radio.COM1, delta = 5)
        assertEquals(137_000_000, handler.currentStandbyFreq["COM1"])
    }

    @Test
    fun comFrequencySwap_sendsSwapCommand() {
        val sink = mockk<CommandSink>(relaxed = true)
        val handler = FrequencyKnobHandler(sink)
        handler.onFrequencySwap(Radio.COM2)
        verify { sink.sendCommand(match { it.contains("swap_freq") && it.contains("COM2") }) }
    }

    // ── G-33: Transponder SA VFR default ─────────────────────────────────────

    @Test
    fun transponder_saVfrDefault() {
        val handler = TransponderHandler(mockk(relaxed = true))
        assertEquals(7000, handler.saVfrDefaultCode)
    }

    @Test
    fun transponder_initialCodeIsSaVfr() {
        val handler = TransponderHandler(mockk(relaxed = true))
        assertEquals(7000, handler.currentCode)
    }

    @Test
    fun transponder_digitDelta_incrementsOnes() {
        val handler = TransponderHandler(mockk(relaxed = true), initialCode = 7000)
        handler.onSquawkDigitDelta(digitIndex = 3, delta = 1)
        assertEquals(7001, handler.currentCode)
    }

    @Test
    fun transponder_digitDelta_wrapsOctal() {
        // Ones digit at 7 + 1 → wraps to 0 (octal).
        val handler = TransponderHandler(mockk(relaxed = true), initialCode = 7007)
        handler.onSquawkDigitDelta(digitIndex = 3, delta = 1)
        assertEquals(7000, handler.currentCode)
    }

    // ── G-31: AP mode annunciator ─────────────────────────────────────────────

    @Test
    fun apModeState_hdgActive() {
        val snapshot = SimSnapshot(apStateFlags = AP_ENGAGED_MASK or HDG_MODE)
        val state = buildApModeState(snapshot)
        assertEquals("HDG", state.lateralActive)
        assertTrue(state.verticalActive in listOf("ALT", "PIT", "VS", "FLC", "GS"))
    }

    @Test
    fun apModeState_navActive() {
        val snapshot = SimSnapshot(apStateFlags = AP_ENGAGED_MASK or NAV_MODE)
        val state = buildApModeState(snapshot)
        assertEquals("NAV", state.lateralActive)
    }

    @Test
    fun apModeState_rollDefault_whenNoLateralMode() {
        val snapshot = SimSnapshot(apStateFlags = AP_ENGAGED_MASK)
        val state = buildApModeState(snapshot)
        assertEquals("ROL", state.lateralActive)
    }

    @Test
    fun apModeState_altArmed() {
        val snapshot = SimSnapshot(apStateFlags = AP_ENGAGED_MASK or VS_MODE or ALT_ARMED)
        val state = buildApModeState(snapshot)
        assertEquals("VS", state.verticalActive)
        assertEquals("ALT", state.verticalArmed)
    }

    @Test
    fun apModeState_navArmed() {
        val snapshot = SimSnapshot(apStateFlags = AP_ENGAGED_MASK or HDG_MODE or NAV_ARMED)
        val state = buildApModeState(snapshot)
        assertEquals("HDG", state.lateralActive)
        assertEquals("NAV", state.lateralArmed)
    }

    // ── G-35: CDI source cycling ──────────────────────────────────────────────

    @Test
    fun cdiSource_cyclesGpsNav1Nav2() {
        val sink = mockk<CommandSink>(relaxed = true)
        val handler = CdiSourceHandler(sink, initialSource = 0)

        handler.onCdiSoftkey()   // GPS → NAV1
        verify { sink.sendCommand(match { it.contains("\"value\":1") }) }

        handler.onCdiSoftkey()   // NAV1 → NAV2
        verify { sink.sendCommand(match { it.contains("\"value\":2") }) }

        handler.onCdiSoftkey()   // NAV2 → GPS
        verify { sink.sendCommand(match { it.contains("\"value\":0") }) }
    }

    @Test
    fun cdiSource_startsAtGps() {
        val handler = CdiSourceHandler(mockk(relaxed = true))
        assertEquals(0, handler.currentSource)
    }

    // ── G-19: Softkey bar ─────────────────────────────────────────────────────

    @Test
    fun softkeyBar_12KeysPerContext() {
        val bar = SoftkeyBar()
        assertEquals(12, bar.softkeyContexts[SoftkeyContext.PFD_MAIN]?.size)
    }

    @Test
    fun softkeyBar_allContextsHave12Keys() {
        val bar = SoftkeyBar()
        for ((context, keys) in bar.softkeyContexts) {
            assertEquals(12, keys.size, "Context $context should have exactly 12 softkeys")
        }
    }

    // ── G-24: Direct-To ───────────────────────────────────────────────────────

    @Test
    fun directTo_entPressReturnsIdentifier() {
        val sink = mockk<CommandSink>(relaxed = true)
        val handler = DirectToHandler(sink)
        handler.onDirectToPress()
        handler.onCharEntry('F')
        handler.onCharEntry('A')
        handler.onCharEntry('O')
        handler.onCharEntry('R')
        val result = handler.onEntPress()
        assertEquals("FAOR", result)
        verify { sink.sendCommand(match { it.contains("\"FAOR\"") }) }
    }

    @Test
    fun directTo_clrCancelsEntry() {
        val handler = DirectToHandler(mockk(relaxed = true))
        handler.onDirectToPress()
        handler.onCharEntry('F')
        handler.onClrPress()
        assertTrue(!handler.isActive)
        assertTrue(handler.enteredIdentifier.isEmpty())
    }

    // ── G-22: BARO knob ───────────────────────────────────────────────────────

    @Test
    fun baroKnob_hpaStep_isOneHpa() {
        val sink = mockk<CommandSink>(relaxed = true)
        val handler = PfdKnobHandler(sink)
        val startBaro = 29.92f
        val result = handler.onBaroDelta(startBaro, delta = 1f, unit = BaroUnit.HPA)
        // 1 hPa = 1/33.8639 inHg ≈ 0.02953 inHg
        assertEquals(startBaro + 1f / 33.8639f, result, 0.0001f)
    }

    @Test
    fun baroKnob_stdPush_sets2992() {
        val sink = mockk<CommandSink>(relaxed = true)
        val handler = PfdKnobHandler(sink)
        handler.onBaroStd()
        verify { sink.sendCommand(match { it.contains("barometer_setting") && it.contains("29.92") }) }
    }

    // ── G-23: ALT knob ────────────────────────────────────────────────────────

    @Test
    fun altKnob_innerRing_1000ftSteps() {
        val handler = PfdKnobHandler(mockk(relaxed = true))
        val result = handler.onAltDelta(currentAltFt = 5000f, delta = 1, isInnerRing = true)
        assertEquals(6000f, result, 0.1f)
    }

    @Test
    fun altKnob_outerRing_100ftSteps() {
        val handler = PfdKnobHandler(mockk(relaxed = true))
        val result = handler.onAltDelta(currentAltFt = 5000f, delta = 1, isInnerRing = false)
        assertEquals(5100f, result, 0.1f)
    }

    // ── G-20: FMS knob character entry ────────────────────────────────────────

    @Test
    fun fmsKnob_characterEntry_cyclesAlphanumeric() {
        val mfdPageManager = MfdPageManager {}
        val fplRenderer = com.nameless.efb.rendering.g1000.mfd.FplPageRenderer()
        val handler = FmsKnobHandler(mfdPageManager, fplRenderer)
        // Start at 'A' (index 0), advance by 1 → 'B'
        handler.onCharacterEntry(delta = 1)
        assertEquals('B', handler.currentChar)
    }

    @Test
    fun fmsKnob_characterEntry_wrapsAround() {
        val mfdPageManager = MfdPageManager {}
        val fplRenderer = com.nameless.efb.rendering.g1000.mfd.FplPageRenderer()
        val handler = FmsKnobHandler(mfdPageManager, fplRenderer)
        // 36 total chars (26 letters + 10 digits); cycling 36 steps returns to 'A'
        handler.onCharacterEntry(delta = 36)
        assertEquals('A', handler.currentChar)
    }
}
