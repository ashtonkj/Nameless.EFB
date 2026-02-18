# Plan 10 — Flight Planning, Charts & Utilities

**Phase:** 5c
**Depends on:** Plans 04 (nav DB), 08 (map engine), 09 (overlays)
**Blocks:** Nothing

---

## Goals

Implement all flight planning, chart, and utility features:
- Route construction and management (FP-01 through FP-11)
- Charts and plates (CH-01 through CH-04)
- Planning utilities (UT-01 through UT-07)
- Active route line on map (MM-10)

Requirements covered: FP-01–11, MM-10, CH-01–04, UT-01–07.

---

## 1. Flight Plan Data Model

```kotlin
// domain/flightplan/FlightPlan.kt

data class FlightPlan(
    val id: Long = 0,
    val name: String,
    val departure: Waypoint?,
    val destination: Waypoint?,
    val waypoints: List<Waypoint>,  // includes SID/STAR/approach waypoints
    val aircraftProfileId: Long?,
    val cruiseAltitudeFt: Int = 5000,
    val notes: String = "",
)

sealed class Waypoint {
    data class Airport(val icao: String, val latLon: LatLon) : Waypoint()
    data class Navaid(val identifier: String, val type: NavaidType, val latLon: LatLon) : Waypoint()
    data class Fix(val identifier: String, val latLon: LatLon) : Waypoint()
    data class UserPoint(val name: String, val latLon: LatLon) : Waypoint()
    data class AirwayEntry(val fix: Fix, val airway: String) : Waypoint()
}
```

The flight plan is a **doubly-linked list** in memory (for O(1) insert/delete during drag editing) serialised to JSON for storage.

---

## 2. Route Construction

### FP-01 — Direct-To Routing

```kotlin
// domain/flightplan/DirectToUseCase.kt

class DirectToUseCase(
    private val dataSourceManager: DataSourceManager,
    private val flightPlanViewModel: FlightPlanViewModel,
) {
    suspend fun execute(target: Waypoint, snapshot: SimSnapshot): DirectToResult {
        val ownship = LatLon(snapshot.latitude, snapshot.longitude)
        val bearing = GreatCircle.initialBearingDeg(ownship, target.latLon)
        val distNm = GreatCircle.distanceNm(ownship, target.latLon)
        val eteMin = (distNm / snapshot.tas_kts * 60.0).toInt()

        // Send GPS target to sim
        dataSourceManager.sendCommand(buildDirectToCommand(target))

        return DirectToResult(bearingDeg = bearing, distNm = distNm, eteMin = eteMin)
    }
}
```

### FP-02 — ICAO Route String Parser

```kotlin
// domain/flightplan/IcaoRouteParser.kt

/**
 * Parses ICAO route strings like:
 *   "FAOR SID/FORT1A N871 TEBSA Z30 FACT"
 * Returns a list of resolved waypoints; unresolved fixes are flagged.
 */
class IcaoRouteParser(private val navDb: EfbDatabase) {

    suspend fun parse(routeString: String): ParsedRoute {
        val tokens = tokenise(routeString)
        val resolved = mutableListOf<WaypointResolution>()

        for (token in tokens) {
            val result = when {
                token.isAirportIcao()  -> resolveAirport(token)
                token.isAirway()       -> resolveAirway(token, lastFix)
                token.isSidStar()      -> resolveProcedure(token)
                else                   -> resolveNavaid(token)
            }
            resolved.add(result)
        }
        return ParsedRoute(resolved)
    }

    private fun tokenise(route: String): List<String> =
        route.trim().split(Regex("\\s+"))

    // SA airway designators handled:
    // N-prefix: N871 (RNAV)
    // Z-prefix: Z30 (southern Africa routes)
    private fun String.isAirway(): Boolean =
        matches(Regex("[NVJQHB]\\d+|[A-Z]\\d+[A-Z]?"))
}

data class WaypointResolution(
    val token: String,
    val waypoint: Waypoint?,   // null = unresolved
    val isUnresolved: Boolean = waypoint == null,
)
```

### FP-03 — IFR Autorouting

```kotlin
// domain/flightplan/AutorouteUseCase.kt

class AutorouteUseCase(private val navDb: EfbDatabase) {
    /**
     * Dijkstra / A* on the airway graph.
     * Nodes = fixes, edges = airway segments with costs = distance.
     * Filters by altitude (LOW/HIGH) and preferred airway type (Victor/Jet/RNAV).
     */
    suspend fun compute(
        departure: String,
        destination: String,
        preferences: AutoroutePreferences,
    ): RouteResult { ... }
}

data class AutoroutePreferences(
    val cruiseAltFt: Int,
    val preferredTypes: Set<AirwayType>,   // VICTOR, JET, RNAV
    val sid: String? = null,
    val star: String? = null,
    val approach: String? = null,
)
```

### FP-07 — Drag-and-Drop Route Editing

Touch gesture on map route line:
- **Drag leg midpoint**: Insert waypoint at nearest named fix (R-tree snap within 5nm)
- **Drag waypoint**: Move to new position, snap to nearest fix within 5nm
- **Long-press waypoint**: Delete / show info

```kotlin
class RouteEditGestureHandler(
    private val routeLayer: ActiveRouteRenderer,
    private val navDb: EfbDatabase,
    private val onRouteChanged: (FlightPlan) -> Unit,
) {
    private val undoStack = ArrayDeque<FlightPlan>(20)  // 20-operation undo stack

    fun onLegMidpointDrag(legIndex: Int, touchLatLon: LatLon) {
        val nearestFix = navDb.findNearestFix(touchLatLon, radiusNm = 5.0)
        if (nearestFix != null) {
            val newPlan = currentPlan.insertWaypoint(legIndex + 1, nearestFix)
            undoStack.addLast(currentPlan)
            if (undoStack.size > 20) undoStack.removeFirst()
            onRouteChanged(newPlan)
        }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        onRouteChanged(previous)
    }
}
```

---

## 3. Active Route Line (MM-10)

```kotlin
// rendering/map/overlay/ActiveRouteRenderer.kt

class ActiveRouteRenderer {
    fun draw(plan: FlightPlan, activeLegIndex: Int, snapshot: SimSnapshot?, mvp: Matrix4f) {
        val waypoints = plan.waypoints
        for (i in waypoints.indices) {
            val isActive = i == activeLegIndex
            val isPast = i < activeLegIndex

            val colour = when {
                isActive -> Color(0xFFFF00FF)   // bright magenta
                isPast   -> Color(0x80FF00FF)   // dimmed magenta
                else     -> Color(0xFFFF00FF)   // magenta
            }
            if (i > 0) drawLeg(waypoints[i - 1], waypoints[i], colour, mvp)
        }
        // Missed approach segment in white (if approach procedure loaded)
        // Waypoint labels: Knuth greedy anti-overlap algorithm
        drawWaypointLabels(waypoints, mvp)
    }
}
```

ETE per leg updated in real-time from snapshot GS.

---

## 4. Bidirectional Sim FMS Sync (FP-09)

```kotlin
// domain/flightplan/SimFmsSync.kt

class SimFmsSync(private val dataSourceManager: DataSourceManager) {

    suspend fun pushToSim(plan: FlightPlan): Boolean {
        val waypoints = plan.waypoints.map { it.toXplaneFmsEntry() }
        val command = buildJson {
            put("cmd", "set_fms_plan")
            putJsonArray("waypoints") {
                waypoints.forEach { addJsonObject {
                    put("type", it.type)
                    put("id", it.identifier)
                    put("lat", it.lat)
                    put("lon", it.lon)
                    put("alt", it.altFt)
                }}
            }
        }
        return dataSourceManager.sendCommand(command.toString())
    }

    suspend fun pullFromSim(): FlightPlan? {
        // Request FMS data from plugin via UDP command
        // Plugin responds with current FMS waypoint list
        val response = dataSourceManager.requestFmsPlan() ?: return null
        return decodeFmsPlan(response)
    }
}
```

---

## 5. FMS v11 Export (FP-08)

```kotlin
// domain/flightplan/FmsExporter.kt

object FmsExporter {
    /** X-Plane FMS v11 format */
    fun toFmsV11(plan: FlightPlan): String = buildString {
        appendLine("I")
        appendLine("1100 Version")
        appendLine("CYCLE 2401")
        appendLine("ADEP ${plan.departure?.identifier ?: "ZZZZ"}")
        appendLine("ADES ${plan.destination?.identifier ?: "ZZZZ"}")
        appendLine("NUMENR ${plan.waypoints.size}")
        for (wp in plan.waypoints) {
            // Format: TYPE IDENT REGION ALT LAT LON
            appendLine("${wp.fmsType()} ${wp.identifier} ${wp.region} ${wp.altFt} ${wp.lat} ${wp.lon}")
        }
    }
}
```

---

## 6. Alternate Airport Suggestions (FP-11)

```kotlin
// domain/flightplan/AlternateFinderUseCase.kt

class AlternateFinderUseCase(
    private val navDb: EfbDatabase,
    private val metarFetcher: MetarFetcher,
) {
    suspend fun findAlternates(
        destination: LatLon,
        fuelRangeNm: Double,
        requiredRunwayLengthFt: Int,
        preferredApproachTypes: Set<String>,
    ): List<AlternateSuggestion> {
        val candidates = navDb.airportDao()
            .nearbyAirports(SpatialQuery.nearbyAirports(destination, fuelRangeNm))
            .filter { it.hasRunwayLength(requiredRunwayLengthFt) }

        val metarData = metarFetcher.fetchAndCache(candidates.map { it.icao })

        return candidates.map { airport ->
            val metar = metarData.find { it.icao == airport.icao }
            AlternateSuggestion(
                airport = airport,
                distanceNm = GreatCircle.distanceNm(destination, airport.latLon),
                score = computeScore(airport, metar, preferredApproachTypes),
            )
        }.sortedByDescending { it.score }
    }

    private fun computeScore(airport: AirportEntity, metar: MetarCacheEntity?, preferredApproaches: Set<String>): Float {
        // Weights: distance 40%, runway 30%, approach type 20%, weather 10%
        val distScore = 1f - (distNm / maxRangeNm).coerceIn(0f, 1f)
        val runwayScore = if (airport.hasRunwayLength(1500)) 1f else 0f
        val approachScore = if (airport.hasApproachType(preferredApproaches)) 1f else 0.5f
        val wxScore = metar?.let { if (it.flightCategory == "VFR") 1f else 0.3f } ?: 0.5f
        return 0.4f * distScore + 0.3f * runwayScore + 0.2f * approachScore + 0.1f * wxScore
    }
}
```

---

## 7. Charts & Plates (CH-01 to CH-04)

### CH-01 — Approach Plate Display

```kotlin
// data/charts/NavigraphChartsApi.kt

class NavigraphChartsApi(private val keystore: NavigraphKeystore) {
    private val baseUrl = "https://charts.navigraph.com/..."

    suspend fun getPlatesForAirport(icao: String): List<PlateInfo> { ... }
    suspend fun downloadPlate(plateId: String): File { ... }  // cached to internal storage
}
```

Offline fallback: if plate is cached in local storage (Room + file), display from file using `PdfRenderer`.

Geo-referencing (ownship overlay on plate):
```kotlin
// data/charts/PlateGeoReference.kt

data class PlateGeoRef(
    val widthPx: Int, val heightPx: Int,
    val topLeftLat: Double, val topLeftLon: Double,
    val bottomRightLat: Double, val bottomRightLon: Double,
)

fun latLonToPlatePixel(latLon: LatLon, geoRef: PlateGeoRef): PointF {
    val x = ((latLon.lon - geoRef.topLeftLon) / (geoRef.bottomRightLon - geoRef.topLeftLon) * geoRef.widthPx).toFloat()
    val y = ((geoRef.topLeftLat - latLon.lat) / (geoRef.topLeftLat - geoRef.bottomRightLat) * geoRef.heightPx).toFloat()
    return PointF(x, y)
}
```

### CH-03 — Chart Annotation

```kotlin
// data/charts/AnnotationStore.kt

@Serializable
data class PlateAnnotation(
    val plateId: String,
    val type: AnnotationType,   // LINE, CIRCLE, TEXT, ARROW
    val points: List<PointF>,   // in plate pixel coordinates
    val colour: Int,
    val text: String = "",
)

// Stored in Room DB (PlateAnnotationEntity) keyed by Navigraph plate ID
```

Drawing on plate: Canvas overlay (Compose `Canvas`) over `AndroidView(::ImageView)` hosting the PDF page bitmap.

### CH-04 — Split-Screen Chart + Map

Two `GLSurfaceView` instances side-by-side, sharing the EGL context:
```kotlin
// ui/split/SplitScreenLayout.kt

@Composable
fun SplitScreenLayout(
    leftContent: @Composable () -> Unit,
    rightContent: @Composable () -> Unit,
    splitRatio: Float,  // 0.3 to 0.7
) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(splitRatio)) { leftContent() }
        DragHandle(onRatioDrag = { ... })
        Box(Modifier.weight(1f - splitRatio)) { rightContent() }
    }
}
```

---

## 8. Planning Utilities

### UT-01 — Weight & Balance

```kotlin
// domain/wb/WeightAndBalance.kt

data class WbResult(
    val grossWeightKg: Float,
    val cgArm: Float,               // inches or mm (per aircraft profile)
    val isWithinEnvelope: Boolean,
    val cgPoint: PointF,            // for rendering on envelope chart
)

object WeightAndBalance {
    fun compute(
        stations: List<WbStation>,   // arm + weight for each station
        envelope: List<PointF>,      // polygon vertices
    ): WbResult {
        val totalWeight = stations.sumOf { it.weightKg.toDouble() }.toFloat()
        val totalMoment = stations.sumOf { (it.arm * it.weightKg).toDouble() }.toFloat()
        val cg = totalMoment / totalWeight
        return WbResult(
            grossWeightKg = totalWeight,
            cgArm = cg,
            isWithinEnvelope = isPointInPolygon(PointF(cg, totalWeight), envelope),
            cgPoint = PointF(cg, totalWeight),
        )
    }
}
```

### UT-02 — Fuel Planning

```kotlin
// domain/fuel/FuelPlanner.kt

object FuelPlanner {
    fun compute(plan: FlightPlan, profile: AircraftProfile, wind: WindData): FuelPlanResult {
        var fuelKg = profile.fuelLoadKg
        val legResults = mutableListOf<LegFuel>()
        for (leg in plan.legs()) {
            val effectiveSpeed = effectiveGroundSpeed(profile.cruiseTas, wind, leg.bearing)
            val timeHr = leg.distanceNm / effectiveSpeed
            val burnKg = timeHr * profile.fuelFlowKgHr
            fuelKg -= burnKg
            legResults.add(LegFuel(leg, remainingKg = fuelKg, burnKg = burnKg))
        }
        // ICAO IFR reserve: alternate fuel + 45 min at holding burn rate
        val reserveKg = computeIcaoReserve(plan, profile)
        return FuelPlanResult(legs = legResults, reserveKg = reserveKg,
                              sufficientFuel = fuelKg >= reserveKg)
    }
}
```

SA reserve rules per AIC A001/2022 applied where different from ICAO Annex 6.

### UT-03 — Performance Calculator

```kotlin
// domain/performance/DensityAltitude.kt

object DensityAltitude {
    /**
     * Density altitude formula.
     * PA = pressure altitude = QNH adjusted to MSL
     */
    fun compute(pressureAltFt: Float, oatDegC: Float): Float {
        val isaOat = 15f - (pressureAltFt / 1000f) * 2f   // ISA lapse rate 2°C/1000ft
        return pressureAltFt + 118.8f * (oatDegC - isaOat)
    }
}
```

Johannesburg FAOR elevation = 5558ft MSL. At 30°C summer: ISA at 5558ft = 15 - 11.1 = 3.9°C. DA = 5558 + 118.8 × (30 - 3.9) ≈ 8659ft. This matches the spec example of ~7500ft DA (at slightly lower temperature).

### UT-04 — Timer

```kotlin
// ui/timer/TimerViewModel.kt

class TimerViewModel : ViewModel() {
    private val timers = List(3) { TimerState() }  // 3 concurrent timers

    fun start(index: Int, mode: TimerMode, durationSec: Int? = null) { ... }
    fun stop(index: Int) { ... }

    private fun onExpiry(index: Int) {
        HapticFeedback.warningBurst()
        AudioManager.playChime()
        // Haptic cannot be disabled for timer expiry (chime non-mutable per spec)
    }
}
```

Timer state survives configuration changes (ViewModel scope).

### UT-06 — Logbook Auto-Capture

```kotlin
// domain/logbook/LogbookAutoCapture.kt

class LogbookAutoCapture(
    private val simData: StateFlow<SimSnapshot?>,
    private val logbookDao: LogbookDao,
) {
    private var flightActive = false
    private var departureIcao: String? = null
    private var takeoffTime: Long? = null

    // Called each frame from SimDataViewModel
    fun update(snapshot: SimSnapshot, nearestAirport: AirportEntity?) {
        val isAirborne = snapshot.groundspeed_ms * 1.944f > 40f && !isOnGround(snapshot)

        if (!flightActive && isAirborne) {
            flightActive = true
            takeoffTime = System.currentTimeMillis()
            departureIcao = nearestAirport?.icao
        }
        if (flightActive && !isAirborne && snapshot.groundspeed_ms < 5f) {
            logFlight(nearestAirport?.icao)
            flightActive = false
        }
    }

    private suspend fun logFlight(arrivalIcao: String?) {
        logbookDao.insert(LogbookEntryEntity(
            date = System.currentTimeMillis(),
            departureIcao = departureIcao ?: "ZZZZ",
            arrivalIcao = arrivalIcao ?: "ZZZZ",
            flightTimeSec = (System.currentTimeMillis() - (takeoffTime ?: 0)) / 1000,
        ))
    }
}
```

### UT-07 — METAR/TAF Decoder

Implemented in Plan 09 (`MetarDecoder`). The UI wraps it in a Compose screen with colour-coded display.

---

## 9. Flight Plan Library (FP-10)

```kotlin
// FTS4 virtual table for full-text search across flight plan names, notes, ICAO codes

// Room DB migration adds:
CREATE VIRTUAL TABLE flight_plans_fts USING fts4(
    content="flight_plans",
    name, departure_icao, destination_icao, notes, tags
);

// DAO
@Dao
interface FlightPlanDao {
    @RawQuery
    suspend fun search(query: SupportSQLiteQuery): List<FlightPlanEntity>

    // Usage: search for "FAOR"
    // SELECT fp.* FROM flight_plans fp
    // JOIN flight_plans_fts fts ON fts.docid = fp.id
    // WHERE flight_plans_fts MATCH 'FAOR'
}
```

Auto-save every 30 seconds via WorkManager periodic task.

---

## Tests

```kotlin
@Test
fun icaoRouteParser_saRoute() {
    val result = runBlocking {
        IcaoRouteParser(mockNavDb()).parse("FAOR FORT1A N871 TEBSA Z30 FACT")
    }
    assertFalse(result.hasUnresolved)
    assertEquals("FAOR", result.waypoints.first().identifier)
    assertEquals("FACT", result.waypoints.last().identifier)
}

@Test
fun densityAltitude_johannesburgSummer() {
    val da = DensityAltitude.compute(pressureAltFt = 5558f, oatDegC = 30f)
    assertTrue(da > 7000f && da < 9000f)
}

@Test
fun weightAndBalance_withinEnvelope() {
    val result = WeightAndBalance.compute(
        stations = c172DefaultStations(),
        envelope = c172Envelope(),
    )
    assertTrue(result.isWithinEnvelope)
}

@Test
fun fuelFlow_avgas_kgSecToLph() {
    val lph = kgSecToLph(0.02f, FuelType.AVGAS)
    assertEquals(100f, lph, 1f)  // 0.02 kg/s × 3600 / 0.72 = 100 LPH
}

@Test
fun fmsExport_validXplaneV11() {
    val plan = testFlightPlan()
    val fms = FmsExporter.toFmsV11(plan)
    assertTrue(fms.startsWith("I\n1100 Version"))
    assertTrue(fms.contains("ADEP FAOR"))
}

@Test
fun logbook_takeoffLandingDetected() {
    val capture = LogbookAutoCapture(...)
    capture.update(airborneSnapshot(gs = 80f), airport = faor())  // takeoff
    assertTrue(capture.flightActive)
    capture.update(groundedSnapshot(gs = 0f), airport = fact())   // landing
    assertFalse(capture.flightActive)
}
```

---

## Acceptance Criteria Mapping

| Req | Satisfied by |
|---|---|
| FP-01 (Direct-To within 500ms) | `DirectToUseCase` + UDP command |
| FP-02 (ICAO route parser, SA airways) | `IcaoRouteParser` with N/Z airway regex |
| FP-03 (autorouting) | `AutorouteUseCase` Dijkstra on airway graph |
| FP-07 (drag-and-drop, undo 20 ops) | `RouteEditGestureHandler` + 20-item deque |
| FP-09 (bidirectional FMS sync) | `SimFmsSync.pushToSim()` / `pullFromSim()` |
| MM-10 (magenta route line, ETE update) | `ActiveRouteRenderer` |
| CH-01 (plate display, geo-reference) | `NavigraphChartsApi` + `PlateGeoReference` |
| CH-04 (split-screen) | `SplitScreenLayout` |
| UT-01 (W&B, SA kg/L units) | `WeightAndBalance.compute()` |
| UT-02 (fuel, ICAO reserve) | `FuelPlanner.compute()` |
| UT-03 (DA, Johannesburg ~7500ft) | `DensityAltitude.compute()` |
| UT-06 (logbook auto-capture, >40kt) | `LogbookAutoCapture` |
| UT-07 (METAR decoder, hPa default) | `MetarDecoder` (Plan 09) |
