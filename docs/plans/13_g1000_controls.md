# Plan 13 — G1000 Bezel Controls, Autopilot & Communication Panels

**Phase:** 6c
**Depends on:** Plans 11 (PFD), 12 (MFD page manager)
**Blocks:** Nothing

---

## Goals

Implement all G1000 bezel controls:
- Softkey bar (12 context-sensitive keys) (G-19)
- Dual FMS knob (G-20)
- NAV/COM frequency knobs (G-21)
- HDG/CRS/BARO knobs (G-22)
- ALT/VS knobs (G-23)
- Direct-To button (G-24)
- MENU/CLR/ENT/FPL/PROC keys (G-25)
- Range/Map Pointer knob (G-26)
- Autopilot modes (G-27 through G-31)
- COM/NAV frequency display (G-32)
- Transponder control (G-33)
- Audio panel simulation (G-34)
- CDI source selection (G-35)
- TMR/REF page (G-36)

Requirements covered: G-19 through G-36.

---

## Bezel Layout

The G1000 bezel is rendered as a texture-mapped image (static artwork) with touch hit-test zones overlaid. The bezel occupies the bottom 200px of the display area and runs full-width.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  [SK1][SK2][SK3][SK4][SK5][SK6]      [SK7][SK8][SK9][SK10][SK11][SK12] │  Softkey bar
├────────────────────────────────────────────────────────────────────────  ┤
│ COM1: 118.100 ↔ 119.100  NAV1: 110.30 ↔ 110.50  │ HDG   CRS   BARO    │
│ COM2: 121.500 ↔ 121.900  NAV2: 108.10 ↔ 108.50  │  ◎     ◎     ◎     │
│                                                   │ ALT   VS            │
│ [FPL] [PROC] [MENU] [CLR] [ENT] [D→]            │  ◎     ◎            │
│                   [FMS outer/inner]               │                      │
│                   [RANGE knob]                    │ [AP] [FD] [YD]      │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 1. G-19 — Softkey Bar (12 Keys)

```kotlin
// rendering/g1000/SoftkeyBar.kt

class SoftkeyBar(
    private val fontAtlas: FontAtlas,
    private val pfdPageManager: PfdPageManager,
    private val mfdPageManager: MfdPageManager,
) {
    // 12 softkeys, each 64dp wide, 44dp tall
    // Labels change per context (page + active state)

    private val keyWidth = PFD_W / 12   // ~106px per key

    private val softkeyContexts: Map<SoftkeyContext, List<SoftkeyDefinition>> = mapOf(
        SoftkeyContext.PFD_MAIN to listOf(
            SoftkeyDefinition("INSET",   active = false, onPress = { toggleInsetMap() }),
            SoftkeyDefinition("PFD",     active = true,  onPress = { /* page menu */ }),
            SoftkeyDefinition("OBS",     active = false, onPress = { toggleObsMode() }),
            SoftkeyDefinition("CDI",     active = false, onPress = { cycleCdiSource() }),
            SoftkeyDefinition("DME",     active = false, onPress = { toggleDme() }),
            SoftkeyDefinition("TMRS",    active = false, onPress = { openTimers() }),
            SoftkeyDefinition("",        active = false, onPress = {}),   // blank
            SoftkeyDefinition("CLR",     active = false, onPress = { clearSoftkeys() }),
            SoftkeyDefinition("BRG1",    active = false, onPress = { cycleBrg1() }),
            SoftkeyDefinition("NRST",    active = false, onPress = { openNrst() }),
            SoftkeyDefinition("ALT\nUNITS", active = false, onPress = { toggleAltUnits() }),
            SoftkeyDefinition("PFD\nMENU",  active = false, onPress = { openPfdMenu() }),
        ),
        SoftkeyContext.MFD_MAP to listOf(
            SoftkeyDefinition("ENGINE",  active = false, onPress = { toggleEis() }),
            SoftkeyDefinition("MAP",     active = true,  onPress = { /* map options */ }),
            SoftkeyDefinition("NRST",   active = false, onPress = { openNrst() }),
            SoftkeyDefinition("PROC",   active = false, onPress = { openProc() }),
            // ... 8 more
        ),
    )

    fun draw(context: SoftkeyContext, mvp: Matrix4f) {
        val keys = softkeyContexts[context] ?: return
        for ((i, key) in keys.withIndex()) {
            drawSoftkeyLabel(key, x = i * keyWidth, mvp)
        }
    }

    fun onTap(x: Float) {
        val index = (x / keyWidth).toInt().coerceIn(0, 11)
        softkeyContexts[currentContext]?.getOrNull(index)?.onPress?.invoke()
    }

    private fun drawSoftkeyLabel(key: SoftkeyDefinition, x: Int, mvp: Matrix4f) {
        // Background: dark grey; active = lighter
        // Text: white monospaced from font atlas
        // Two-line labels split at "\n"
    }
}
```

---

## 2. G-20 — Dual FMS Knob

```kotlin
// rendering/g1000/FmsKnobHandler.kt

class FmsKnobHandler(
    private val mfdPageManager: MfdPageManager,
    private val fplPageRenderer: FplPageRenderer,
) {
    // Rendered as two concentric circular zones
    // Outer zone radius: 80dp–120dp from knob center
    // Inner zone radius: 0–80dp from knob center

    private val outerKnob = CircularKnobGestureDetector(knobCenter) { delta ->
        mfdPageManager.onFmsOuterKnob(delta.sign)  // page group selection
    }

    private val innerKnob = CircularKnobGestureDetector(knobCenter) { delta ->
        when (mfdPageManager.activePage) {
            MfdPage.FPL -> fplPageRenderer.moveCursor(delta.sign)
            else -> mfdPageManager.onFmsInnerKnob(delta.sign)
        }
    }

    // Centre push (tap with no movement within 100ms):
    fun onCentrePush() {
        fplPageRenderer.toggleCursor()
    }

    // Character entry via inner disc (when cursor on identifier field):
    // A-Z-0-9 cycling based on rotation direction
    fun onCharacterEntry(delta: Int) {
        val chars = ('A'..'Z').toList() + ('0'..'9').toList()
        currentChar = chars[(chars.indexOf(currentChar) + delta).mod(chars.size)]
    }
}
```

---

## 3. G-21 — NAV/COM Frequency Knobs

```kotlin
// rendering/g1000/FrequencyKnobHandler.kt

class FrequencyKnobHandler(
    private val dataSourceManager: DataSourceManager,
) {
    // Each COM/NAV has a large (MHz) + small (kHz) concentric knob
    // Layout: 4 knob pairs — COM1, COM2, NAV1, NAV2

    fun onComMhzDelta(radio: Radio, delta: Int) {
        val currentFreq = getCurrentFreq(radio)  // in Hz
        val newFreq = currentFreq + (delta * 1_000_000)  // ±1 MHz
        val clamped = newFreq.coerceIn(118_000_000, 137_000_000)
        writeFrequency(radio, isStandby = true, freqHz = clamped)
    }

    fun onComKhzDelta(radio: Radio, delta: Int) {
        val currentFreq = getCurrentFreq(radio)
        val stepHz = 25_000  // 25kHz steps for COM (8.33kHz ready for future)
        val khzPart = (currentFreq / stepHz) * stepHz
        val newFreq = khzPart + (delta * stepHz)
        writeFrequency(radio, isStandby = true, freqHz = newFreq)
    }

    fun onFrequencySwap(radio: Radio) {
        dataSourceManager.sendCommand("""{"cmd":"swap_freq","radio":"${radio.name}"}""")
        // Visual: standby frequency briefly highlights before swap animation
    }

    fun onNavObsDelta(delta: Int) {
        val currentObs = snapshot.nav1_obs_deg
        val newObs = (currentObs + delta + 360f) % 360f
        dataSourceManager.sendCommand("""{"cmd":"set_dataref",
            "path":"sim/cockpit/radios/nav1_obs_degm","value":$newObs}""")
    }
}

enum class Radio { COM1, COM2, NAV1, NAV2 }
```

---

## 4. G-22 — HDG / CRS / BARO Knobs

```kotlin
// rendering/g1000/PfdKnobHandler.kt

class PfdKnobHandler(private val dataSourceManager: DataSourceManager) {

    fun onHdgDelta(delta: Float) {
        val newHdg = (currentHdgBug + delta + 360f) % 360f
        write("sim/cockpit/autopilot/heading_mag", newHdg)
        HapticFeedback.detent()
    }

    fun onHdgSync() {
        // Push HDG SYNC: set bug to current aircraft heading
        write("sim/cockpit/autopilot/heading_mag", snapshot.mag_heading_deg)
    }

    fun onCrsDelta(delta: Float) {
        val newCrs = (currentNav1Obs + delta + 360f) % 360f
        write("sim/cockpit/radios/nav1_obs_degm", newCrs)
        HapticFeedback.detent()
    }

    fun onBaroDelta(delta: Float, unit: BaroUnit) {
        val step = when (unit) { BaroUnit.HPA -> 1 / 33.8639f; BaroUnit.INHG -> 0.01f }
        val newBaro = snapshot.barometer_inhg + delta * step
        write("sim/cockpit/misc/barometer_setting", newBaro)
    }

    fun onBaroStd() {
        // Push BARO: set to standard atmosphere 29.92 inHg = 1013.25 hPa
        write("sim/cockpit/misc/barometer_setting", 29.92f)
    }

    private fun write(path: String, value: Float) {
        dataSourceManager.sendCommand("""{"cmd":"set_dataref","path":"$path","value":$value}""")
    }
}
```

---

## 5. G-23 — ALT / VS Knobs

```kotlin
fun onAltDelta(delta: Int, isInnerRing: Boolean) {
    val step = if (isInnerRing) 1000 else 100  // inner = 1000ft, outer = 100ft
    val currentAlt = snapshot.ap_altitude_ft
    val newAlt = (currentAlt + delta * step).coerceIn(0f, 50000f)
    write("sim/cockpit/autopilot/altitude", newAlt)
}

fun onVsDelta(delta: Int) {
    val step = 100f  // 100fpm per unit
    val newVs = snapshot.ap_vs_fpm + delta * step
    write("sim/cockpit/autopilot/vertical_velocity", newVs)
}

fun onAltPush() {
    // Activate altitude alerting (triggers ALT CAP annunciation sequence)
    toggleAltitudeAlerting()
}

fun onVsPush() {
    // Engage VS mode on autopilot
    engageApVerticalMode(VerticalMode.VS)
}
```

---

## 6. G-24 — Direct-To Button

```kotlin
// Direct-to workflow:
// 1. Press D→ → opens identifier entry dialog overlay on PFD
// 2. Enter identifier via inner FMS knob (char cycling)
// 3. Press ENT → look up waypoint in nav DB → activate direct-to

class DirectToHandler(
    private val navDb: EfbDatabase,
    private val directToUseCase: DirectToUseCase,
) {
    private var directToActive = false
    private var enteredIdentifier = StringBuilder()

    fun onDirectToPress() {
        directToActive = true
        enteredIdentifier.clear()
        pfdRenderer.showDirectToOverlay(enteredIdentifier.toString())
    }

    fun onFmsCharEntry(char: Char) {
        if (directToActive) {
            enteredIdentifier.append(char)
            pfdRenderer.updateDirectToOverlay(enteredIdentifier.toString())
        }
    }

    suspend fun onEntPress() {
        if (!directToActive) return
        val waypoint = navDb.findWaypoint(enteredIdentifier.toString()) ?: return  // no match
        directToUseCase.execute(waypoint, currentSnapshot)
        directToActive = false
        pfdRenderer.hideDirectToOverlay()
    }
}
```

---

## 7. G-27 to G-31 — Autopilot

### AP / FD / YD Buttons (G-27)

```kotlin
class AutopilotHandler(private val dataSourceManager: DataSourceManager) {
    fun onApPress() {
        val engaged = snapshot.ap_state_flags and AP_ENGAGED_MASK != 0
        val newState = if (engaged) {
            snapshot.ap_state_flags and AP_ENGAGED_MASK.inv()  // disengage
        } else {
            snapshot.ap_state_flags or AP_ENGAGED_MASK         // engage
        }
        write("sim/cockpit/autopilot/autopilot_state", newState.toFloat())

        if (engaged) {
            // Disconnect alert: flash amber AP annunciator + audio beep
            apDisconnectAlert.trigger()
        }
    }

    fun onFdPress() {
        val fdOn = snapshot.ap_state_flags and FD_ON_MASK != 0
        val newState = snapshot.ap_state_flags xor FD_ON_MASK
        write("sim/cockpit/autopilot/autopilot_state", newState.toFloat())
        pfdRenderer.updateFlightDirectorVisibility(!fdOn)
    }
}
```

### Lateral Modes (G-28)

```kotlin
fun onHdgMode() { write("sim/cockpit2/autopilot/heading_mode", 1f) }
fun onNavMode() { write("sim/cockpit2/autopilot/nav_status", 1f) }
fun onAprMode() { write("sim/cockpit2/autopilot/approach_status", 1f) }
fun onBcMode()  { write("sim/cockpit2/autopilot/back_course_status", 1f) }
```

### Vertical Modes (G-29)

```kotlin
fun onAltMode() { write("sim/cockpit2/autopilot/altitude_mode", 1f) }
fun onVsMode()  { write("sim/cockpit2/autopilot/vvi_status", 1f) }
fun onFlcMode() { write("sim/cockpit2/autopilot/speed_status", 1f) }
fun onGsMode()  { /* Arms GS, arms APR */ }
```

### AP Mode Annunciator Strip (G-31)

```kotlin
data class ApModeState(
    val lateralActive: String,   // "HDG", "NAV", "ROL", etc.
    val lateralArmed: String?,   // "NAV" (white when armed)
    val verticalActive: String,  // "ALT", "VS", "PIT", etc.
    val verticalArmed: String?,
    val speedMode: String?,
)

fun buildApModeState(snapshot: SimSnapshot): ApModeState {
    val flags = snapshot.ap_state_flags
    return ApModeState(
        lateralActive = when {
            flags and HDG_MODE != 0 -> "HDG"
            flags and NAV_MODE != 0 -> "NAV"
            else -> "ROL"
        },
        lateralArmed = if (flags and NAV_ARMED != 0) "NAV" else null,
        verticalActive = when {
            flags and ALT_MODE != 0 -> "ALT"
            flags and VS_MODE != 0  -> "VS"
            else -> "PIT"
        },
        verticalArmed = if (flags and ALT_ARMED != 0) "ALT" else null,
        speedMode = null,
    )
}
```

---

## 8. G-32 — COM/NAV Frequency Display

Rendered as two columns (COM | NAV) on left side of bezel:
```
COM1  118.100 ↔ 119.100
COM2  121.500 ↔ 121.900
NAV1  110.300 ↔ 110.500
NAV2  108.100 ↔ 108.500
```
Active frequency in green; standby in white. Swap animation: brief colour flash.

---

## 9. G-33 — Transponder Control

```kotlin
class TransponderHandler(private val dataSourceManager: DataSourceManager) {
    // Squawk entry: 4 octal digits (0-7 each)
    // SA default: 7000 for VFR

    fun onSquawkDigitDelta(digitIndex: Int, delta: Int) {
        val digits = currentSquawk.toOctalDigits().toMutableList()
        digits[digitIndex] = (digits[digitIndex] + delta + 8) % 8  // octal wrap
        val newCode = digits.fromOctalDigits()
        write("sim/cockpit/radios/transponder_code", newCode.toFloat())
    }

    fun onIdent() {
        write("sim/cockpit/radios/transponder_mode", TransponderMode.IDENT.value.toFloat())
    }

    // Modes: OFF=0, STBY=1, GND=2, ON=3, ALT=4
    fun onModeSelect(mode: TransponderMode) {
        write("sim/cockpit/radios/transponder_mode", mode.value.toFloat())
    }

    // Reply light: flashes when sim transponder is being interrogated
    // sim/cockpit2/radios/indicators/transponder_reply
}
```

---

## 10. G-35 — CDI Source Selection

```kotlin
fun onCdiSoftkey() {
    val currentSource = snapshot.hsi_source  // 0=GPS, 1=NAV1, 2=NAV2
    val nextSource = (currentSource + 1) % 3
    write("sim/cockpit2/radios/actuators/HSI_source_select_pilot", nextSource.toFloat())
    pfdRenderer.hsiRenderer.updateNavSource(nextSource)
}
```

SA RNAV (GNSS) approaches: CDI source must be GPS. `nextSource` sequencing matches G1000 CRG behaviour.

---

## 11. G-36 — TMR/REF Page

```kotlin
// Accessible via TMRS softkey on PFD softkey bar

@Composable
fun TmrRefPage(snapshot: SimSnapshot?, aircraftProfile: AircraftProfile?) {
    Column {
        // V-speed references from aircraft profile
        aircraftProfile?.vspeeds?.let { v ->
            VspeedRow("VR", v.vr_kts)
            VspeedRow("VX", v.vx_kts)
            VspeedRow("VY", v.vy_kts)
            VspeedRow("VAPP", v.vapp_kts)
        }
        Divider()
        // Count-up/down timer (shared with UT-04)
        TimerControl(...)
        // Minimums setting (DA or DH)
        MinimumsControl(snapshot = snapshot)
        // OAT from sim
        snapshot?.let { Text("OAT: %.1f °C".format(it.oat_degc)) }
    }
}
```

---

## Full Interaction State Machine

The G1000 is driven by a **context state machine**. Each page defines which softkeys are visible, how the FMS knob behaves, and what ENT/CLR do.

```kotlin
sealed class G1000State {
    object PfdMain : G1000State()
    object MfdMap : G1000State()
    object MfdFpl : G1000State()
    data class MfdFplCursor(val cursorRow: Int) : G1000State()
    object MfdProc : G1000State()
    data class DirectToEntry(val partial: String) : G1000State()
    // ...
}
```

State transitions map precisely to the G1000 CRG page hierarchy.

---

## Tests

```kotlin
@Test
fun comKhzTuning_25kHzSteps() {
    val handler = FrequencyKnobHandler(mockDataSource())
    handler.currentStandbyFreq["COM1"] = 118_100_000
    handler.onComKhzDelta(Radio.COM1, delta = 1)
    assertEquals(118_125_000, handler.currentStandbyFreq["COM1"])
}

@Test
fun transponder_saVfrDefault() {
    val handler = TransponderHandler(mockDataSource())
    assertEquals(7000, handler.saVfrDefaultCode)
}

@Test
fun apModeState_hdgActive() {
    val snapshot = testSnapshot(ap_state_flags = AP_ENGAGED_MASK or HDG_MODE)
    val state = buildApModeState(snapshot)
    assertEquals("HDG", state.lateralActive)
    assertTrue(state.verticalActive in listOf("ALT", "PIT", "VS"))
}

@Test
fun cdiSource_cyclesGpsNav1Nav2() {
    val handler = CdiSourceHandler(mockDataSource())
    handler.onCdiSoftkey()   // GPS → NAV1
    verify { mockDataSource().sendCommand(match { it.contains("\"value\":1") }) }
    handler.onCdiSoftkey()   // NAV1 → NAV2
    verify { mockDataSource().sendCommand(match { it.contains("\"value\":2") }) }
    handler.onCdiSoftkey()   // NAV2 → GPS
    verify { mockDataSource().sendCommand(match { it.contains("\"value\":0") }) }
}

@Test
fun softkeyBar_12KeysPerContext() {
    val bar = SoftkeyBar(...)
    assertEquals(12, bar.softkeyContexts[SoftkeyContext.PFD_MAIN]?.size)
}
```

---

## Acceptance Criteria Mapping

| Req | Satisfied by |
|---|---|
| G-19 (12 softkeys, CRG hierarchy) | `SoftkeyBar` + `softkeyContexts` map |
| G-20 (dual FMS knob, character entry) | `FmsKnobHandler`, `onCharacterEntry()` |
| G-21 (COM MHz/kHz, 25kHz steps, swap) | `FrequencyKnobHandler` |
| G-22 (HDG/CRS/BARO, HDG SYNC, BARO STD) | `PfdKnobHandler` |
| G-23 (ALT 100ft/1000ft, VS 100fpm) | `onAltDelta()`, `onVsDelta()` |
| G-24 (D→ with FMS entry) | `DirectToHandler` |
| G-25 (FPL/PROC/MENU/CLR/ENT) | Softkey + state machine transitions |
| G-27 (AP/FD/YD, disconnect alert) | `AutopilotHandler`, `apDisconnectAlert` |
| G-28 (lateral modes, HDG→NAV arm/capture) | AP mode datarefs |
| G-29 (vertical modes, ALT CAP) | AP mode datarefs + ALT CAP logic |
| G-30 (FD command bars, lime green) | `G1000Colours.MAGENTA` FD bars in PFD |
| G-31 (mode annunciator strip) | `buildApModeState()` + text atlas |
| G-32 (COM/NAV freq display) | Frequency bezel rendering |
| G-33 (transponder, VFR 7000 SA) | `TransponderHandler` |
| G-34 (audio panel) | COM MIC selection datarefs |
| G-35 (CDI source, SA RNAV GNSS) | `onCdiSoftkey()` cycling |
| G-36 (TMR/REF, V-speeds, minimums) | `TmrRefPage` composable |
