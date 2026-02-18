# Plan 09 — Map Overlays, TAWS & Weather

**Phase:** 5b
**Depends on:** Plan 08 (map engine), Plan 04 (nav DB), Plan 05 (connectivity)
**Blocks:** Plan 10 (flight planning displays on top of these overlays)

---

## Goals

Implement all overlay layers rendered on top of the base map tile engine:
- Airport overlay with type colour coding (MM-04)
- Navaid overlay (VORs, NDBs, fixes, ILS feathers) (MM-05)
- Airspace overlay with floor/ceiling labels (MM-06)
- TAWS terrain elevation colouring (MM-07)
- Weather METAR/TAF overlay (MM-08)
- Wind barbs (MM-09)
- Sim AI/multiplayer traffic (MM-14)
- Instrument strip / data bar (IP-01)
- Mini HSI compass rose overlay (IP-02)
- Profile / vertical situation view (IP-03)
- Nearest airport quick-list (IP-04)

Requirements covered: MM-04 through MM-09, MM-14, IP-01 through IP-04.

---

## 1. Airport Overlay (MM-04)

```kotlin
// rendering/map/overlay/AirportOverlayRenderer.kt

class AirportOverlayRenderer(private val navDb: EfbDatabase) {
    // Point sprite rendering — each airport is one GL_POINTS vertex with size/colour
    // Symbol shapes rendered in fragment shader based on type

    private val typeColours = mapOf(
        "large_airport"  to Color(0xFF0080FF),  // blue — towered
        "medium_airport" to Color(0xFF00AA00),  // green — non-towered
        "small_airport"  to Color(0xFF00AA00),  // green
        "heliport"       to Color(0xFFFFCC00),  // yellow
        "military"       to Color(0xFF808080),  // grey
    )

    suspend fun loadForViewport(bbox: LatLonBbox): List<AirportEntity> =
        navDb.airportDao().nearbyAirports(SpatialQuery.forBbox(bbox))

    fun draw(airports: List<AirportEntity>, mvp: Matrix4f) {
        // Batch all airport points into one VBO
        // Render as GL_POINTS with point sprites
        // At zoom ≥ 12, also draw runway layout from RunwayEntity
    }
}
```

Tap detection: when user taps map, query airports within 20dp of tap point, show info panel.

---

## 2. Navaid Overlay (MM-05)

```kotlin
// rendering/map/overlay/NavaidOverlayRenderer.kt

enum class NavaidType { VOR, NDB, ILS, DME, FIX }

class NavaidOverlayRenderer(private val navDb: EfbDatabase) {

    fun draw(navaids: List<NavaidEntity>, mvp: Matrix4f) {
        // VOR: hexagonal symbol (6 vertices)
        // NDB: filled circle
        // Fix/intersection: open triangle
        // ILS feather: angled line extending from runway threshold
        // DME: square
        // Each type drawn as separate VAO batch
    }

    private fun drawIlsFeather(navaid: NavaidEntity, mvp: Matrix4f) {
        // ILS feather extends 8nm in inbound course direction
        // Cone shape (two converging lines)
        val inboundCourseRad = Math.toRadians(navaid.magneticVariation.toDouble())
        // Draw line pair
    }
}
```

Tap: show identifier, frequency, elevation in info card.

---

## 3. Airspace Overlay (MM-06)

```kotlin
// rendering/map/overlay/AirspaceOverlayRenderer.kt

class AirspaceOverlayRenderer(private val navDb: EfbDatabase) {
    // Each airspace polygon rendered as filled semi-transparent triangle fan
    // Outline drawn as GL_LINE_LOOP

    private val classColours = mapOf(
        "A" to Color(0x40FF0000),   // red, semi-transparent
        "B" to Color(0x400000FF),
        "C" to Color(0x40FF8800),
        "D" to Color(0x400088FF),
        "G" to Color(0x2000AA00),
        "R" to Color(0x80FF0000),   // restricted — more opaque
        "P" to Color(0xC0FF0000),   // prohibited — high opacity
    )

    fun draw(airspaces: List<AirspaceEntity>, mvp: Matrix4f) {
        for (airspace in airspaces) {
            val polygon = parseGeoJson(airspace.geometryJson)
            drawFilledPolygon(polygon, classColours[airspace.airspaceClass] ?: Color.GRAY, mvp)
            drawOutline(polygon, mvp)
            // Floor/ceiling label: positioned at polygon centroid
            drawLabel("${airspace.floorFt}ft - ${airspace.ceilingFt}ft", polygon.centroid, mvp)
        }
    }

    // Proximity alert: called each frame when airspace query finds airspace within 5nm
    fun checkProximity(ownshipLatLon: LatLon, airspaces: List<AirspaceEntity>): Boolean {
        return airspaces.any { airspace ->
            val distNm = distanceToPolygon(ownshipLatLon, parseGeoJson(airspace.geometryJson))
            distNm < 5.0
        }
    }
}
```

---

## 4. TAWS Terrain Colouring (MM-07)

The terrain overlay uses Copernicus DEM float16 tiles uploaded as a 2D texture, with a 1D LUT texture mapping relative clearance to colour.

```kotlin
// rendering/map/overlay/TawsRenderer.kt

class TawsRenderer(private val terrainCache: TerrainTileCache) {
    private lateinit var lutTextureId: Int   // 1D texture: clearance → RGBA

    override fun onGlReady() {
        // Upload TAWS LUT texture (256 pixels wide):
        // Index 0-50  = RED    (<100ft clearance)
        // Index 51-150= YELLOW (<500ft clearance)
        // Index 151-200=GREEN  (>500ft clearance)
        // Index 201-255= transparent (>2000ft above)
        lutTextureId = uploadLutTexture()
    }

    fun draw(ownshipAltFt: Float, viewportBbox: LatLonBbox, mvp: Matrix4f) {
        // For each visible 512x512 terrain tile:
        //   1. Upload tile as GL_TEXTURE_2D (float16 format)
        //   2. In fragment shader: sample elevation, compute clearance
        //      clearance = ownshipAlt - elevation_ft
        //      lutIndex = clearance mapped to 0-255
        //      color = texture(u_lut, lutIndex / 255.0)
        //   3. Blend over base map
    }
}
```

**GLSL fragment shader (`terrain_taws.frag`):**
```glsl
uniform sampler2D u_terrain;      // float16 elevation grid
uniform sampler1D u_taws_lut;     // RGBA LUT
uniform float     u_ownship_alt_ft;

in vec2 v_uv;
out vec4 frag_color;

void main() {
    float elev_m = texture(u_terrain, v_uv).r;
    float elev_ft = elev_m * 3.28084;
    float clearance_ft = u_ownship_alt_ft - elev_ft;

    // Map clearance to LUT index:
    // < 100ft → red; < 500ft → yellow; < 2500ft → green; > 2500ft → transparent
    float t = clamp((clearance_ft + 100.0) / 2600.0, 0.0, 1.0);
    frag_color = texture(u_taws_lut, t);
}
```

Terrain colour updates within 2 frames of altitude change (driven by `u_ownship_alt_ft` uniform — no texture re-upload).

---

## 5. Weather Overlay (MM-08)

```kotlin
// data/weather/MetarFetcher.kt

class MetarFetcher(
    private val httpClient: OkHttpClient,
    private val metarDao: MetarDao,
) {
    // Fetch from: https://aviationweather.gov/api/data/metar?ids=FAOR,FACT,FALA&format=json
    // Cache in Room DB with 30-minute TTL
    // Background fetch via WorkManager every 30 minutes

    suspend fun fetchAndCache(icaos: List<String>): List<MetarCacheEntity> { ... }

    fun parseFlightCategory(metar: String): FlightCategory {
        // Parse visibility and ceiling from raw METAR
        // VFR: vis ≥ 5sm AND ceiling ≥ 3000ft
        // MVFR: vis 3-5sm OR ceiling 1000-3000ft
        // IFR: vis 1-3sm OR ceiling 500-1000ft
        // LIFR: vis < 1sm OR ceiling < 500ft
    }
}
```

**Airport colour overlay:** The airport symbols drawn in MM-04 are coloured by flight category when weather data is available. In the airport VAO batch, add a per-vertex colour attribute sourced from the METAR cache.

```kotlin
// WorkManager task for METAR background fetch
class MetarRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val visibleAirports = airportDao.nearbyAirports(currentViewportBbox)
        metarFetcher.fetchAndCache(visibleAirports.map { it.icao })
        return Result.success()
    }
}
```

METAR/TAF decoder for UT-07 (separate from overlay, but shares the parser):
```kotlin
// domain/weather/MetarDecoder.kt

data class DecodedMetar(
    val icao: String,
    val windDirDeg: Int,
    val windSpeedKt: Int,
    val visibilityM: Int,
    val ceiling: Int?,        // null = no clouds / CAVOK
    val tempC: Float,
    val dewpointC: Float,
    val qnhHpa: Float,        // SA default hPa
    val isCavok: Boolean,
    val remarks: String,
    val flightCategory: FlightCategory,
)

object MetarDecoder {
    fun decode(raw: String): DecodedMetar { ... }  // regex tokeniser
}
```

---

## 6. Wind Barbs (MM-09)

```kotlin
// rendering/map/overlay/WindBarbRenderer.kt

class WindBarbRenderer {
    fun draw(stations: List<MetarCacheEntity>, mvp: Matrix4f) {
        for (station in stations) {
            drawBarb(
                lat = ...,       // from airport position lookup
                lon = ...,
                dirDeg = station.windDirDeg.toFloat(),
                speedKt = station.windSpeedKt.toFloat(),
                mvp = mvp,
            )
        }
    }

    private fun drawBarb(lat: Float, lon: Float, dirDeg: Float, speedKt: Float, mvp: Matrix4f) {
        // Wind barb geometry: shaft (10nm line) + barbs (full barb = 10kt, half = 5kt)
        // Shaft direction: points in wind direction (from)
        // Generate geometry in GLSL vertex shader based on uniforms
        // Animate: gentle rotation ±2° sinusoidal over u_time_sec
    }
}
```

Crosswind component per active route leg:
```kotlin
fun crosswindComponent(windDirDeg: Float, windSpeedKt: Float, legBearingDeg: Float): Float {
    val angleDiff = Math.toRadians((windDirDeg - legBearingDeg).toDouble())
    return (windSpeedKt * sin(angleDiff)).toFloat()
}
```

---

## 7. Traffic Overlay (MM-14)

```kotlin
// rendering/map/overlay/TrafficOverlayRenderer.kt

class TrafficOverlayRenderer {
    fun draw(snapshot: SimSnapshot, ownshipAltFt: Float, mvp: Matrix4f) {
        repeat(snapshot.traffic_count.toInt()) { i ->
            val lat = snapshot.traffic_lat[i]
            val lon = snapshot.traffic_lon[i]
            val altM = snapshot.traffic_ele_m[i]
            val altFt = altM * 3.28084f
            val relAltFt = (altFt - ownshipAltFt).roundToInt()

            val worldPos = WebMercator.toMeters(lat.toDouble(), lon.toDouble())
            drawTrafficSymbol(worldPos, relAltFt, mvp)
        }
    }

    private fun drawTrafficSymbol(worldPos: PointF, relAltFt: Int, mvp: Matrix4f) {
        // Diamond shape for traffic symbol
        // Label: "+150" or "-300" (relative altitude in ft)
        // Velocity vector line (if track available)
        // Colour: white (non-threat), amber (TA), red (RA)
    }
}
```

---

## 8. Instrument Strip / Data Bar (IP-01)

```kotlin
// ui/map/DataBarView.kt (Compose overlay over GL map view)

@Composable
fun DataBar(snapshot: SimSnapshot?, config: DataBarConfig, units: UnitPrefs) {
    // Up to 8 configurable fields per edge
    // Long-press field to show picker
    Row {
        for (field in config.leftFields) {
            DataField(
                label = field.label,
                value = field.format(snapshot, units),
            )
        }
    }
}

enum class DataField {
    GS, TAS, ALT, HDG, VS, ETE, DTK, TRK, DIST, WIND_COMPONENT, OAT, FUEL_REMAINING;

    fun format(snapshot: SimSnapshot?, units: UnitPrefs): String = when (this) {
        GS   -> snapshot?.let { "%.0f kt".format(it.groundspeed_ms * 1.944f) } ?: "---"
        ALT  -> snapshot?.let { "%.0f ft".format(it.elevation_m * 3.281f) } ?: "---"
        FUEL_REMAINING -> snapshot?.let {
            val kg = it.fuel_qty_kg.sum()
            when (units.fuel) {
                FuelUnit.LITRES -> "%.1f L".format(kg / 0.72f)  // AVGAS density
                FuelUnit.KG     -> "%.1f kg".format(kg)
                FuelUnit.USG    -> "%.1f gal".format(kg / 2.72f)
            }
        } ?: "---"
        // ... etc.
    }
}
```

---

## 9. Nearest Airport Quick-List (IP-04)

```kotlin
// ui/map/NearestAirportPanel.kt

@Composable
fun NearestAirportPanel(
    ownship: LatLon,
    altitudeFt: Float,
    navDb: EfbDatabase,
    onDirectTo: (AirportEntity) -> Unit,
) {
    val airports by produceState(emptyList<AirportWithDistance>()) {
        value = navDb.airportDao()
            .nearbyAirports(SpatialQuery.nearbyAirports(ownship, 50.0))
            .map { AirportWithDistance(it, GreatCircle.distanceNm(ownship, it.latLon)) }
            .sortedBy { it.distanceNm }
            .take(15)
    }

    LazyColumn {
        items(airports) { entry ->
            AirportRow(
                airport = entry.airport,
                distanceNm = entry.distanceNm,
                isInGlideRange = isInGlideRange(entry.distanceNm, altitudeFt),
                onDirectTo = { onDirectTo(entry.airport) },
            )
        }
    }
}

fun isInGlideRange(distanceNm: Double, altitudeFt: Float): Boolean {
    val glideRange = altitudeFt / 60.0  // 1:60 conservative default
    return distanceNm <= glideRange / 6076.0  // convert ft to nm (1nm = 6076ft)
}
```

---

## 10. Mini HSI Compass Rose Overlay (IP-02)

Small OpenGL sub-view overlaid on map (bottom-left corner). Shares the same EGL context as the map.

```kotlin
// rendering/map/overlay/MiniHsiRenderer.kt

class MiniHsiRenderer {
    // 120×120px GL viewport in map corner
    // Compass card: rotates with heading
    // CDI needle (NAV1 hdef_dot)
    // Bearing pointer 1: NAV1/GPS toggle
    // Bearing pointer 2: NAV2/GPS toggle
}
```

---

## 11. Profile View / Vertical Situation (IP-03)

```kotlin
// rendering/map/ProfileViewRenderer.kt

class ProfileViewRenderer(
    private val terrainCache: TerrainTileCache,
    private val navDb: EfbDatabase,
) {
    fun render(route: FlightPlan, ownshipAltFt: Float): ProfileViewData {
        // Sample Copernicus DEM at 1nm intervals along entire route
        // Compute TOD/TOC for each constrained segment
        // Return as list of (distanceNm, elevationFt) points
    }
}
```

Rendered as 2D line chart in OpenGL (not a GL3D scene) — terrain profile as filled polygon, route altitude as line, obstacle points as vertical bars.

---

## Tests

```kotlin
@Test
fun metarDecoder_cavok() {
    val metar = MetarDecoder.decode("FAOR 121300Z 22010KT CAVOK 28/09 Q1018")
    assertTrue(metar.isCavok)
    assertEquals(1018f, metar.qnhHpa, 0.1f)
    assertEquals(28f, metar.tempC, 0.1f)
    assertEquals(FlightCategory.VFR, metar.flightCategory)
}

@Test
fun tawsLut_redBelow100ft() {
    val lut = buildTawsLut()
    val color = lut.sample(clearanceFt = 50f)
    assertEquals(Color.RED, color)
}

@Test
fun crosswindComponent_perpendicular() {
    val xw = crosswindComponent(windDirDeg = 360f, windSpeedKt = 20f, legBearingDeg = 90f)
    assertEquals(-20f, xw, 0.5f)  // full crosswind from right
}

@Test
fun nearestAirport_glideRange() {
    assertTrue(isInGlideRange(distanceNm = 5.0, altitudeFt = 3000f))   // 3000/60 = 50nm glide
    assertFalse(isInGlideRange(distanceNm = 55.0, altitudeFt = 3000f))
}
```

---

## Acceptance Criteria Mapping

| Req | Satisfied by |
|---|---|
| MM-04 (airport type colours, SA airports) | `AirportOverlayRenderer` + type colour map |
| MM-05 (VOR/NDB/fix/ILS, SA navaids) | `NavaidOverlayRenderer` + navaid VAO batches |
| MM-06 (airspace polygons, proximity alert) | `AirspaceOverlayRenderer.checkProximity()` |
| MM-07 (TAWS terrain, 2-frame update) | `TawsRenderer` + `u_ownship_alt_ft` uniform |
| MM-08 (METAR flight category colours) | `MetarFetcher` + airport colour batch |
| MM-09 (wind barbs, crosswind component) | `WindBarbRenderer` |
| MM-14 (AI traffic display) | `TrafficOverlayRenderer` |
| IP-01 (data bar, 8 fields, SA units) | `DataBar` Compose composable |
| IP-02 (mini HSI) | `MiniHsiRenderer` sub-viewport |
| IP-03 (profile view, terrain) | `ProfileViewRenderer` |
| IP-04 (nearest airports, glide range) | `NearestAirportPanel` |
