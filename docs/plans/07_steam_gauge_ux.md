# Plan 07 — Steam Gauge Touch Interaction & Panel Configuration

**Phase:** 4b
**Depends on:** Plan 06 (gauge geometry and viewports defined)
**Blocks:** Nothing

---

## Goals

Implement all gauge touch interactions, haptic feedback, and panel configuration:
- Circular swipe gestures for knobs (SG-15)
- Tap zones for buttons (SG-16)
- Multi-touch simultaneous adjustment (SG-17)
- Haptic feedback on detents (SG-18)
- Visual feedback: glow, over-swing, button states (SG-19)
- Aircraft profile presets (SG-20)
- Custom gauge layout editor (SG-21)
- Day/Night/Red-cockpit mode (SG-22)
- Custom dataref mapping (SG-23)

Requirements covered: SG-15 through SG-23.

---

## 1. Circular Swipe Gesture (SG-15)

The circular swipe converts angular motion around a knob centre to a dataref write.

```kotlin
// rendering/gl/gesture/CircularKnobGestureDetector.kt

class CircularKnobGestureDetector(
    private val center: PointF,
    private val onRotation: (deltaUnits: Float) -> Unit,
) : View.OnTouchListener {

    private var lastAngleDeg: Float = 0f
    private var lastEventTime: Long = 0L

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val touchAngle = Math.toDegrees(
            atan2((event.y - center.y).toDouble(), (event.x - center.x).toDouble())
        ).toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastAngleDeg = touchAngle
                lastEventTime = event.eventTime
            }
            MotionEvent.ACTION_MOVE -> {
                val dt = (event.eventTime - lastEventTime) / 1000f  // seconds
                val dAngle = angularDelta(lastAngleDeg, touchAngle)  // -180 to +180 deg
                val angularVelocityDegSec = abs(dAngle) / dt

                // Velocity-sensitive acceleration:
                // slow (<50px/s angular equiv.) = 1x, fast (>200px/s) = 10x
                val acceleration = accelerationCurve(angularVelocityDegSec)
                val units = (dAngle / 36f) * acceleration  // 360 deg = 10 units at 1x

                onRotation(units)
                lastAngleDeg = touchAngle
                lastEventTime = event.eventTime
            }
        }
        return true
    }

    private fun accelerationCurve(degSec: Float): Float {
        return when {
            degSec < 50f  -> 1f
            degSec > 200f -> 10f
            else -> 1f + 9f * (degSec - 50f) / 150f  // linear interpolation
        }
    }

    private fun angularDelta(from: Float, to: Float): Float {
        var delta = to - from
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return delta
    }
}
```

### Knob → dataref write bindings

| Knob | Dataref written | Step |
|---|---|---|
| Altimeter Kollsman | `sim/cockpit/misc/barometer_setting` | 0.01 inHg / 1 hPa |
| Heading bug | `sim/cockpit/autopilot/heading_mag` | 1 deg |
| OBS | `sim/cockpit/radios/nav1_obs_degm` | 1 deg |
| Barometer | same as Kollsman | — |

All writes routed through `DataSourceManager.sendCommand(json)` → Rust plugin UDP.

---

## 2. Tap Zones for Buttons (SG-16)

```kotlin
// rendering/gl/HitTestRegion.kt

data class HitTestRegion(
    val centerX: Float,    // in GL normalized device coordinates or viewport pixels
    val centerY: Float,
    val radiusDp: Float,   // minimum 60dp per spec
    val onTap: () -> Unit,
    val onPress: () -> Unit = {},
)

class GaugeTouchHandler(private val regions: List<HitTestRegion>) : View.OnTouchListener {
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = regions.firstOrNull { it.contains(x, y, view.density) }
                hit?.onPress?.invoke()
                activeRegion = hit
            }
            MotionEvent.ACTION_UP -> {
                val hit = regions.firstOrNull { it.contains(x, y, view.density) }
                if (hit == activeRegion) hit?.onTap?.invoke()
                activeRegion = null
            }
        }
        return activeRegion != null
    }
}
```

**Button depression animation:** Pass `u_button_pressed = 1.0` uniform to gauge fragment shader, which applies a brightness/scale reduction for 100ms.

---

## 3. Multi-Touch Simultaneous Adjust (SG-17)

Use Android `MotionEvent.ACTION_POINTER_DOWN` to track two independent circular gesture recognisers simultaneously:

```kotlin
class DualKnobGestureHandler(
    private val knob1: CircularKnobGestureDetector,
    private val knob2: CircularKnobGestureDetector,
) : View.OnTouchListener {

    private val activePointers = mutableMapOf<Int, CircularKnobGestureDetector>()

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val knob = when {
                    knob1.center.distanceTo(x, y) < knob2.center.distanceTo(x, y) -> knob1
                    else -> knob2
                }
                activePointers[pointerId] = knob
                knob.startGesture(x, y, event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                repeat(event.pointerCount) { i ->
                    val id = event.getPointerId(i)
                    activePointers[id]?.updateGesture(event.getX(i), event.getY(i), event.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                activePointers.remove(pointerId)
            }
        }
        return true
    }
}
```

The two dataref writes (e.g., heading bug + OBS) are sent as a single atomic UDP command packet with two operations:
```json
{"cmd": "set_multi", "ops": [
  {"path": "sim/cockpit/autopilot/heading_mag", "value": 270.0},
  {"path": "sim/cockpit/radios/nav1_obs_degm", "value": 250.0}
]}
```

---

## 4. Haptic Feedback (SG-18)

```kotlin
// rendering/gl/HapticFeedback.kt

object HapticFeedback {
    private lateinit var vibrator: Vibrator

    fun init(context: Context) {
        vibrator = context.getSystemService(Vibrator::class.java)
    }

    /** Short pulse per knob detent (1 unit change). */
    fun detent() {
        if (Build.VERSION.SDK_INT >= 29) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    /** Sharp click for button press. */
    fun buttonPress() {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    /** Three-pulse burst for limit warnings. */
    fun warningBurst() {
        val pattern = longArrayOf(0, 80, 60, 80, 60, 80)
        val amplitudes = intArrayOf(0, 200, 0, 200, 0, 200)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
    }
}
```

Haptic feedback can be disabled independently in settings (UI-06).

---

## 5. Visual Feedback (SG-19)

### Radial glow on active knob
Multi-pass render:
1. Render knob normally
2. Render knob area to off-screen FBO with bright emission
3. Apply horizontal Gaussian blur pass
4. Apply vertical Gaussian blur pass
5. Additively blend blur result onto main framebuffer

GLSL uniform `u_knob_glow` (0.0–1.0) drives glow intensity, fading when gesture ends.

### Needle over-swing
Second-order spring-damper on displayed needle angle:
```kotlin
class SpringDamper(private val omega: Float = 15f, private val zeta: Float = 0.8f) {
    var position = 0f
    var velocity = 0f

    fun update(target: Float, dt: Float) {
        val f = -2f * zeta * omega * velocity - omega * omega * (position - target)
        velocity += f * dt
        position += velocity * dt
    }
}
```
Overshoot ≤5% (zeta = 0.8 is slightly underdamped — meets spec).

---

## 6. Aircraft Profile Presets (SG-20)

Bundled in `assets/aircraft_profiles/`:
```
c172sp.json
pa28_warrior.json
b58_baron.json
da40_ng.json
```

Each JSON defines:
```json
{
  "id": "c172sp",
  "display_name": "Cessna 172SP",
  "has_manifold_pressure": false,
  "engine_count": 1,
  "vspeeds": { "vso": 40, "vfe": 85, "vs1": 48, "vno": 127, "vne": 163 },
  "engine_limits": {
    "rpm_max": 2700,
    "rpm_green_min": 2100,
    "map_green_min_inhg": 15,
    "map_green_max_inhg": 25,
    "oil_temp_caution_c": 110,
    "oil_temp_red_c": 120,
    "oil_press_green_min_psi": 25,
    "oil_press_green_max_psi": 60
  },
  "fuel_type": "AVGAS",
  "gauge_layout": "c172_six_pack"
}
```

User-modified profiles stored in Room DB (`AircraftProfileEntity`), overriding bundled assets.

---

## 7. Custom Gauge Layout Editor (SG-21)

```kotlin
// A Compose-based drag-and-drop editor (static UI — Compose is permitted here)

@Composable
fun GaugeLayoutEditor(
    layout: List<GaugeLayoutItem>,
    onLayoutChanged: (List<GaugeLayoutItem>) -> Unit,
) {
    // 4-column or 6-column grid
    // Each item draggable to new cell
    // Resize handles: small/medium/large size classes
    // Grid snapping prevents overlaps
}

@Serializable
data class GaugeLayoutItem(
    val gaugeType: GaugeType,
    val gridCol: Int,
    val gridRow: Int,
    val sizeClass: GaugeSizeClass,  // SMALL, MEDIUM, LARGE
)
```

Layout JSON serialized to Room DB per aircraft profile. On layout change, `GLSurfaceView` recomputes viewports for each gauge.

---

## 8. Day/Night/Red-Cockpit Mode (SG-22)

```kotlin
// rendering/theme/ThemeManager.kt

object ThemeManager {
    var currentTheme: Theme = Theme.DAY
        set(value) {
            field = value
            _themeFlow.value = value
            // Trigger GaugeTextureAtlas.buildAll(value) on GL thread
        }

    val themeFlow: StateFlow<Theme> = _themeFlow.asStateFlow()

    // Auto-switch based on sim local time
    fun update(simLocalTimeSec: Float, latitude: Double) {
        val civilTwilightHour = computeCivilTwilight(latitude, simDate())
        val autoTheme = when {
            simLocalTimeSec / 3600f < civilTwilightHour -> Theme.DAY
            else -> Theme.NIGHT
        }
        if (!userOverride) currentTheme = autoTheme
    }
}

enum class Theme(val glUniform: Float) {
    DAY(0f), NIGHT(1f), RED_COCKPIT(2f)
}
```

The `u_theme` uniform in every shader adjusts colours via a LUT or conditional branch.

---

## 9. Custom Dataref Mapping (SG-23)

Advanced feature: map any X-Plane dataref to any gauge parameter.

```kotlin
// User enters: "laminar/B738/autopilot/vs_status" → VSI range_max
// Validation: send UDP packet to plugin, wait for response

data class CustomDatarefBinding(
    val gaugeType: GaugeType,
    val parameter: GaugeParameter,  // e.g., DATAREF_VALUE, RANGE_MIN, RANGE_MAX
    val datarefPath: String,
    val scale: Float = 1f,
    val offset: Float = 0f,
)

suspend fun validateDataref(path: String): DatarefValidation {
    val responseDeferred = CompletableDeferred<DatarefValidation>()
    dataSourceManager.sendCommand("""{"cmd":"validate_dataref","path":"$path"}""")
    // Wait up to 500ms for plugin echo response
    return withTimeoutOrNull(500) { responseDeferred.await() }
        ?: DatarefValidation.Timeout
}
```

Valid custom datarefs are added to the plugin's subscription list via a `reload` command packet.

---

## Tests

```kotlin
@Test
fun circularSwipe_fullCircleProduces10Units() {
    val detector = CircularKnobGestureDetector(PointF(100f, 100f)) { units -> result = units }
    // Simulate 360° arc at medium velocity
    simulateCircleSwipe(detector, centerX = 100f, centerY = 100f, radiusPx = 50f,
                        startDeg = 0f, endDeg = 360f, durationMs = 500)
    assertEquals(10f, result, 0.5f)
}

@Test
fun dualKnob_twoPointersTrackedIndependently() { ... }

@Test
fun springDamper_overshootLessThan5Percent() {
    val damper = SpringDamper(omega = 15f, zeta = 0.8f)
    // Step from 0 to 1.0
    var maxOvershoot = 0f
    repeat(200) {
        damper.update(1f, 0.016f)
        if (damper.position > 1f) maxOvershoot = max(maxOvershoot, damper.position - 1f)
    }
    assertTrue(maxOvershoot < 0.05f)
}

@Test
fun aircraftProfile_c172VspeedsLoad() {
    val profile = AircraftProfile.fromAsset("c172sp.json")
    assertEquals(163, profile.vspeeds.vne_kts)
    assertEquals(FuelType.AVGAS, profile.fuelType)
}
```

---

## Acceptance Criteria Mapping

| Req | Satisfied by |
|---|---|
| SG-15 (360° swipe = 10 units at medium speed) | `CircularKnobGestureDetector.accelerationCurve` |
| SG-15 (write within 50ms) | UDP command path, no buffering |
| SG-16 (60dp touch targets) | `HitTestRegion.radiusDp = 60` |
| SG-16 (depression animation 100ms) | `u_button_pressed` uniform + 100ms timer |
| SG-17 (two-finger simultaneous, atomic write) | `DualKnobGestureHandler` + set_multi command |
| SG-18 (haptic detent per unit) | `HapticFeedback.detent()` per rotation unit |
| SG-19 (glow, over-swing <5%) | Multi-pass bloom, `SpringDamper(zeta=0.8)` |
| SG-20 (C172/PA-28/Baron/DA40 presets) | Bundled JSON assets |
| SG-21 (drag-and-drop layout editor) | `GaugeLayoutEditor` Compose screen |
| SG-22 (auto day/night at civil twilight) | `ThemeManager.update()` with NOAA algorithm |
| SG-23 (custom dataref validation 500ms) | `validateDataref()` with 500ms timeout |
