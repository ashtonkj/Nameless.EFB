# Plan 06 — Steam Gauge Panel: All 14 Instruments

**Phase:** 4a
**Depends on:** Plans 01, 03 (OpenGL framework), 05 (connectivity/live data)
**Blocks:** Plan 07 (touch interaction builds on gauge geometry)

---

## Goals

Implement all 14 steam gauge instruments as OpenGL ES 3.0 GLSL programs with the standard uniform interface. Each gauge is self-contained: a vertex shader generates geometry, a fragment shader applies shading. No bitmap sprites for moving elements.

Requirements covered: SG-01 through SG-14.

---

## Standard Gauge Uniform Interface

Every gauge shader receives:
```glsl
// Standard uniforms (all gauges)
uniform mat4  u_mvp;             // model-view-projection
uniform float u_dataref_value;   // current sensor value (raw units)
uniform float u_range_min;       // gauge minimum
uniform float u_range_max;       // gauge maximum
uniform float u_needle_angle;    // pre-computed rotation in radians
uniform float u_theme;           // 0=day, 1=night, 2=red
uniform float u_time_sec;        // wall-clock for animations

// Per-gauge extras passed via additional uniforms:
// e.g., u_bank_angle, u_pitch_deg, u_vspeed_fpm, etc.
```

The `u_needle_angle` is computed on the CPU:
```kotlin
fun needleAngle(value: Float, min: Float, max: Float,
                startDeg: Float, endDeg: Float): Float {
    val t = ((value - min) / (max - min)).coerceIn(0f, 1f)
    return Math.toRadians((startDeg + t * (endDeg - startDeg)).toDouble()).toFloat()
}
```

---

## 3.1 Primary Flight Instruments

### SG-01 — Airspeed Indicator (ASI)

**Geometry:**
- Dial face (pre-rendered texture from `GaugeTextureAtlas`): includes all static markings
- Coloured arcs (white/green/yellow) built once as VAO triangle strips via `buildArcStrip()`
- V-speed bug markers: thin rectangles at configurable angles
- Needle: unit quad scaled to needle dimensions, rotated by `u_needle_angle`

**GLSL needle angle mapping:**
```glsl
// 0 kts = -150 deg, 200 kts = +150 deg
float needle_angle = mix(-2.618, 2.618, (airspeed_kts - 0.0) / 200.0);
```

**Dataref:** `sim/cockpit2/gauges/indicators/airspeed_kts_pilot`

**Acceptance criteria check:**
- Needle sweep within 1kt for 0-200kt
- Coloured arcs with clean AA (MSAA 4x)
- V-speed bugs from aircraft JSON profile
- km/h mode: re-map range and arc geometry

**Aircraft profile V-speeds (JSON schema):**
```json
{
  "type": "C172",
  "vso_kts": 40, "vfe_kts": 85,
  "vs1_kts": 48, "vno_kts": 127,
  "vne_kts": 163,
  "vr_kts": 55, "vx_kts": 59, "vy_kts": 74, "vapp_kts": 65
}
```

---

### SG-02 — Attitude Indicator (AI)

**Geometry:**
- Sky half-plane (blue gradient GLSL) + ground half-plane (brown gradient)
- Pitch ladder: horizontal line segments at 5°/10°/20° intervals, generated as VAO
- Bank angle arc: 64-segment circle with tick marks at 10/20/30/60/90°
- Bank limit markers at 30° (separate colour)

**GLSL horizon position:**
```glsl
// Pitch offset: positive pitch moves horizon down
float horizon_y = u_pitch_deg * PIXELS_PER_DEG;
// Bank: rotate entire horizon disc
mat2 rot = mat2(cos(u_bank_rad), -sin(u_bank_rad),
                sin(u_bank_rad),  cos(u_bank_rad));
vec2 rotated = rot * (v_position.xy - vec2(0.0, horizon_y));
// Fragment: sky if rotated.y > 0, ground if <= 0
```

**Datarefs:**
- `sim/cockpit2/gauges/indicators/pitch_AHARS_deg_pilot`
- `sim/cockpit2/gauges/indicators/roll_AHARS_deg_pilot`

---

### SG-03 — Altimeter

**Geometry:**
- Three needles: 100s (thin, short), 1000s (medium), 10000s (long)
- Each needle has its own `u_needle_angle_N` uniform
- Kollsman window: textured drum showing two-digit readout (hPa or inHg)
- Crosshatch pattern (stencil buffer) below 10,000ft
- Transition altitude marker at 18,000ft (SA-specific)

**Needle angles:**
```kotlin
val altFt = snapshot.altitude_ft
val needle100  = needleAngle((altFt % 1000) / 10f,    0f, 100f, -150f, 210f)
val needle1000 = needleAngle((altFt % 10000) / 100f,  0f, 100f, -150f, 210f)
val needle10k  = needleAngle(altFt / 1000f,           0f, 50f,  -150f, 210f)
```

**Dataref (write):** `sim/cockpit/misc/barometer_setting` (inHg — convert from hPa for SA)

---

### SG-04 — Turn Coordinator

**Geometry:**
- Miniature aircraft symbol (wing + fuselage primitives as GL_LINES or thin quads)
- Inclinometer tube: curved path geometry; ball is a small circle sliding along the curve
- Standard rate marks at ±3°/sec

**GLSL ball position:**
```kotlin
// slip_deg maps to lateral offset along curved tube
val ballOffset = (snapshot.slip_deg / 10f).coerceIn(-1f, 1f)  // normalised
// Ball position along pre-built spline path
```

**Datarefs:**
- `sim/cockpit2/gauges/indicators/turn_rate_heading_deg_pilot`
- `sim/cockpit2/gauges/indicators/slip_deg`

---

### SG-05 — Heading Indicator / HSI

**Geometry:**
- Compass card: 64-segment disc with degree markings + cardinal letters (N/E/S/W)
- Fixed lubber line
- Heading bug: small arrow attached to compass ring at configurable angle
- HSI extensions (when in HSI mode): CDI bar, TO/FROM triangle flag, GS indicator

**Touch:** Circular swipe to set heading bug (see Plan 07).

**Datarefs:**
- `sim/cockpit2/gauges/indicators/heading_AHARS_deg_mag_pilot` (read)
- `sim/cockpit/autopilot/heading_mag` (write, for bug)
- `sim/cockpit/radios/nav1_hdef_dot` (CDI in HSI mode)
- `sim/cockpit/radios/nav1_vdef_dot` (GS in HSI mode)

---

### SG-06 — Vertical Speed Indicator (VSI)

**Geometry:**
- 0 to ±2000 fpm standard range (arc geometry)
- Extended range mode: 0 to ±6000 fpm (different arc geometry, loaded on mode change)
- Pneumatic lag mode: IIR filter on display value

**IIR lag filter (realism mode):**
```kotlin
// First-order IIR with tau = 6s
// Called once per frame, dt = frame delta in seconds
displayedVsi = displayedVsi + (1f - exp(-dt / 6f)) * (snapshot.vvi_fpm - displayedVsi)
```

**Dataref:** `sim/cockpit2/gauges/indicators/vvi_fpm_pilot`

---

## 3.2 Engine Instruments

### SG-07 — Tachometer (RPM)

**Geometry:**
- 0–3000 RPM range (standard for C172/PA-28)
- Green arc: 2100–2700 RPM
- Red radial: 2700 RPM
- Hobbs counter: 6-digit Odometer-style display using texture atlas digits
- Tach counter: separate slower-incrementing display

**Dataref:** `sim/cockpit2/engine/indicators/engine_speed_rpm[0]`

**Hobbs logic:**
```kotlin
// In SimDataViewModel — accumulate engine time
if (snapshot.rpm > 500f) {
    hobbsSeconds += frameDeltaSec
}
```

---

### SG-08 — Manifold Pressure (MAP)

**Geometry:** Standard dial, 10–30 inHg range, green arc 15–25 inHg.
Shown/hidden per aircraft profile flag `has_manifold_pressure`.

**Dataref:** `sim/cockpit2/engine/indicators/MPR_in_hg[0]`

---

### SG-09 — Oil Temperature & Pressure

**Geometry:**
- Two needles (temp + pressure) on either side of shared dial face
- Or two separate small dials depending on layout mode
- Green/yellow/red arcs configured per engine profile

**Alert logic:**
```kotlin
if (snapshot.oil_temp_degc > engineProfile.oilTempRedlineDegC) {
    alertManager.fire(AlertType.OIL_TEMP_HIGH)  // VibrationEffect + AudioManager
}
```

**Datarefs:**
- `sim/flightmodel/engine/ENGN_oil_press[0]`
- `sim/flightmodel/engine/ENGN_oil_temp[0]`

---

### SG-10 — Fuel Flow

**Dataref:** `sim/cockpit2/engine/indicators/fuel_flow_kg_sec[0]`

**Unit conversions (SA defaults to LPH):**
```kotlin
fun kgSecToLph(kgSec: Float, fuelType: FuelType): Float = when (fuelType) {
    FuelType.AVGAS  -> kgSec * 3600f / 0.72f   // 1 kg AVGAS = 1/0.72 L
    FuelType.JET_A1 -> kgSec * 3600f / 0.80f
}
```

Range at current flow:
```kotlin
val rangeNm = (snapshot.fuel_qty_kg.sum() / (kgSec * 3600f)) * snapshot.tas_kts
```

---

### SG-11 — Fuel Quantity

**Geometry:** Two arcs (left tank, right tank), low-fuel warning threshold line.

**Alert logic:**
```kotlin
val enduranceHours = snapshot.fuel_qty_kg.sum() / fuelFlowKgHr
if (enduranceHours < 0.5f) alertManager.fire(AlertType.FUEL_LOW)
if (abs(snapshot.fuel_qty_kg[0] - snapshot.fuel_qty_kg[1]) > 5f) {
    alertManager.fire(AlertType.FUEL_IMBALANCE)
}
```

**Datarefs:**
- `sim/cockpit2/fuel/fuel_quantity[0]` (left, kg)
- `sim/cockpit2/fuel/fuel_quantity[1]` (right, kg)

---

### SG-12 — EGT / CHT

**Geometry:** Bar graph, one bar per cylinder (up to 6). GL_QUADS per cylinder. Peak EGT line.

**Lean-assist mode:** Track maximum EGT sample per cylinder; highlight cylinder at peak.

**Datarefs:** `sim/flightmodel/engine/ENGN_EGT_c[0]` through `[5]`

---

### SG-13 — Electrical (Volts/Amps)

**Alert:** Fire when `bus_volts < 13.0f`.

**Datarefs:**
- `sim/cockpit2/electrical/bus_volts[0]`
- `sim/cockpit2/electrical/battery_amps[0]`

---

### SG-14 — Suction/Vacuum Gauge

**Geometry:** 0–10 inHg range, green arc 4.5–5.5 inHg.

**Alert:** GYRO UNRELIABLE overlay when `suction_inhg < 4.5f`.

**Dataref:** `sim/cockpit2/gauges/indicators/suction_ratio`

---

## Panel Layout System

The 14 gauges are arranged in a grid. Default six-pack layout for C172:

```
┌─────┬─────┬─────┐
│ ASI │  AI │ ALT │  (SG-01, SG-02, SG-03)
├─────┼─────┼─────┤
│ TC  │ DI  │ VSI │  (SG-04, SG-05, SG-06)
├─────┴─────┴─────┤
│   RPM   │ OIL  │
│  FUEL   │ ELEC │  Engine instruments
└─────────┴──────┘
```

Each gauge occupies a `GlViewport` (sub-region of the `GLSurfaceView` framebuffer):
```kotlin
data class GlViewport(val x: Int, val y: Int, val width: Int, val height: Int)

fun applyViewport(vp: GlViewport) {
    glViewport(vp.x, vp.y, vp.width, vp.height)
    // Update u_mvp orthographic projection for this viewport
}
```

---

## Tests

```kotlin
// JVM unit tests — pure math, no GL

@Test
fun needleAngle_midRange() {
    val angle = needleAngle(100f, 0f, 200f, -150f, 150f)
    assertEquals(0f, angle, 0.01f)  // 100/200 = 0.5 → midpoint = 0 deg
}

@Test
fun altimeterNeedle100s_correct() {
    val alt = 5500f
    val angle100 = needleAngle((alt % 1000f) / 10f, 0f, 100f, -150f, 210f)
    // 500 / 10 = 50 units → half-range → should be ~30 deg
}

@Test
fun fuelFlowLph_avgas() {
    assertEquals(100f, kgSecToLph(0.02f, FuelType.AVGAS), 0.1f)
}

@Test
fun iirLagFilter_approachesTarget() {
    var displayed = 0f
    repeat(60) { displayed += (1f - exp(-0.016f / 6f)) * (2000f - displayed) }
    assertTrue(displayed > 300f)  // after ~1s at 60fps, should be tracking
}
```

---

## Acceptance Criteria Mapping

| Req | Satisfied by |
|---|---|
| SG-01 (ASI arcs, V-speeds, 1kt accuracy) | Arc VAO geometry, aircraft profile JSON |
| SG-02 (AI pitch/roll 0.5° accuracy) | GLSL horizon math |
| SG-03 (three-pointer altimeter, hPa Kollsman) | Three needle uniforms, barometer write |
| SG-04 (turn coordinator, ball smooth) | Curved tube geometry, slip_deg mapping |
| SG-05 (HI/HSI, circular swipe bug) | Compass disc + CDI bar (swipe in Plan 07) |
| SG-06 (VSI lag mode) | IIR filter in ViewModel |
| SG-07 (RPM, Hobbs counter) | Engine RPM dataref, ViewModel accumulator |
| SG-08 through SG-14 | Per-gauge dataref bindings and alert logic |
