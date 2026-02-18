# Plan 08 — Moving Map Engine

**Phase:** 5a
**Depends on:** Plans 01, 03 (OpenGL framework), 04 (nav DB), 05 (connectivity)
**Blocks:** Plan 09 (overlays built on this engine), Plan 10 (route line rendering)

---

## Goals

Build the OpenGL ES tile rendering engine for the moving map:
- Offline MBTiles vector tile engine (MM-01)
- Ownship position tracking at 60fps with 20Hz interpolation (MM-02)
- North-Up / Track-Up / Heading-Up map orientations (MM-03)
- Airport diagram view at high zoom (MM-13)
- Pinch-zoom / pan gestures (UI-04)
- Range ring display (MM-11)
- Measurement tool (MM-12)

Requirements covered: MM-01, MM-02, MM-03, MM-11, MM-12, MM-13, UI-04.

---

## 1. Coordinate System

The map uses the Web Mercator projection (EPSG:3857), consistent with MBTiles / OpenStreetMap tiles.

```kotlin
// domain/nav/Projection.kt

object WebMercator {
    fun toMeters(lat: Double, lon: Double): PointF {
        val x = lon * 20037508.34 / 180.0
        val y = ln(tan((90.0 + lat) * Math.PI / 360.0)) / (Math.PI / 180.0) * 20037508.34 / 180.0
        return PointF(x.toFloat(), y.toFloat())
    }

    fun toLatLon(mx: Double, my: Double): LatLon { ... }
}

/** Tile XYZ coordinates for a given lat/lon at zoom level z */
fun latLonToTile(lat: Double, lon: Double, zoom: Int): TileXYZ { ... }
```

---

## 2. MBTiles Reader

```kotlin
// data/map/MbTilesReader.kt

class MbTilesReader(private val dbFile: File) {
    private val conn: SQLiteDatabase = SQLiteDatabase.openDatabase(
        dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    /**
     * Returns raw tile bytes for the given zoom/x/y.
     * MBTiles stores Y flipped (TMS convention) — flip it.
     */
    fun getTile(zoom: Int, x: Int, y: Int): ByteArray? {
        val tmsY = (1 shl zoom) - 1 - y  // flip Y
        val cursor = conn.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(zoom.toString(), x.toString(), tmsY.toString()))
        return cursor.use { if (it.moveToFirst()) it.getBlob(0) else null }
    }

    /** Returns metadata (bounds, min/max zoom, tile format). */
    fun metadata(): Map<String, String> { ... }
}
```

### Tile format
MBTiles tiles are Mapbox Vector Tiles (MVT — protobuf encoded). Decode with a lightweight Kotlin MVT parser:

```kotlin
// data/map/MvtDecoder.kt

data class MapLayer(val name: String, val features: List<MapFeature>)
data class MapFeature(val type: GeomType, val geometry: List<List<PointF>>, val tags: Map<String, Any>)

fun decodeMvt(bytes: ByteArray, extent: Int = 4096): List<MapLayer> {
    // Parse Mapbox Vector Tile protobuf
    // Convert tile coordinates (0..extent) to normalized [0,1] space
    // Return layers: "water", "roads", "buildings", "landuse", etc.
}
```

---

## 3. Tile Cache and GL Upload

```kotlin
// rendering/map/TileCache.kt

class TileCache(
    private val mbTilesReader: MbTilesReader,
    private val maxCacheSize: Int = 256,  // tiles
) {
    private val glTextureCache = LruCache<TileXYZ, Int>(maxCacheSize)  // tile → GL texture ID
    private val pendingUploads = Channel<Pair<TileXYZ, ByteArray>>(capacity = 32)

    /**
     * Called from GL thread each frame.
     * Drains pending uploads (max 4 per frame to stay within 4ms budget).
     */
    fun drainUploads(maxPerFrame: Int = 4) {
        repeat(maxPerFrame) {
            val (key, bitmap) = pendingUploads.tryReceive().getOrNull() ?: return
            val texId = uploadToGl(bitmap)
            glTextureCache.put(key, texId)
        }
    }

    fun requestTile(tile: TileXYZ) {
        if (glTextureCache.get(tile) != null) return  // already loaded
        scope.launch(Dispatchers.IO) {
            val bytes = mbTilesReader.getTile(tile.z, tile.x, tile.y) ?: return@launch
            // Rasterise MVT to RGBA bitmap on CPU, then queue for GL upload
            val bitmap = MvtRasteriser.rasterise(bytes, tileSize = 512)
            pendingUploads.send(Pair(tile, bitmap))
        }
    }
}
```

### MVT rasterisation
Tiles are decoded (protobuf) and rasterised to 512×512 RGBA bitmaps on the IO dispatcher using Android `Canvas`. The resulting bitmap is uploaded to a GL texture. This is simpler than GPU-side vector rendering and adequate for the map zoom levels needed.

```kotlin
// rendering/map/MvtRasteriser.kt

object MvtRasteriser {
    fun rasterise(mvtBytes: ByteArray, tileSize: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val layers = decodeMvt(mvtBytes)
        // Draw layers: water (blue), landuse, roads (grey lines), buildings
        // Apply aviation theme colours (muted to not compete with overlays)
        ...
        val byteArray = ByteArray(tileSize * tileSize * 4)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(byteArray))
        return byteArray
    }
}
```

---

## 4. Map Renderer

```kotlin
// rendering/map/MapRenderer.kt

class MapRenderer(
    private val tileCache: TileCache,
    private val simData: StateFlow<SimSnapshot?>,
) : BaseRenderer() {

    // Map state
    private var centerLat = -26.14f    // default: Johannesburg
    private var centerLon = 28.25f
    private var zoomLevel = 10
    private var orientationMode = OrientationMode.NORTH_UP
    private var mapRotationDeg = 0f    // for track-up/heading-up

    override fun drawFrame() {
        val snapshot = simData.value
        updateMapRotation(snapshot)
        val viewport = computeVisibleTiles()
        for (tile in viewport) {
            tileCache.requestTile(tile)
            val texId = tileCache.getTextureId(tile) ?: continue
            drawTile(tile, texId)
        }
        tileCache.drainUploads()
        // Draw ownship after tiles
        snapshot?.let { drawOwnship(it) }
    }

    private fun drawTile(tile: TileXYZ, texId: Int) {
        // Compute tile world-space bounds
        // Apply map view matrix (zoom + pan + rotation)
        // Render textured quad
    }

    private fun updateMapRotation(snapshot: SimSnapshot?) {
        mapRotationDeg = when (orientationMode) {
            OrientationMode.NORTH_UP    -> 0f
            OrientationMode.TRACK_UP   -> snapshot?.ground_track_deg ?: 0f
            OrientationMode.HEADING_UP -> snapshot?.mag_heading_deg ?: 0f
        }
    }
}
```

### View matrix
```kotlin
private fun computeViewMatrix(): Matrix4f {
    val scale = tilePixelSize * 2f.pow(zoomLevel) / screenWidthPx
    return Matrix4f()
        .translate(-centerX, -centerY, 0f)  // center on map position
        .scale(scale, scale, 1f)
        .rotate(mapRotationDeg.toRadians(), 0f, 0f, 1f)
}
```

The rotation is applied entirely in the OpenGL model matrix — no tile re-fetch on orientation change (MM-03 requirement).

---

## 5. Ownship Symbol (MM-02)

```kotlin
// rendering/map/OwnshipRenderer.kt

class OwnshipRenderer {
    // Aircraft symbol as 5 vertices forming a stylised arrow
    private val vao: GlVao
    private val vbo: GlBuffer

    fun draw(snapshot: SimSnapshot, mvp: Matrix4f) {
        val worldPos = WebMercator.toMeters(snapshot.latitude, snapshot.longitude)
        val symbolMvp = mvp
            .translate(worldPos.x, worldPos.y, 0f)
            .rotate(snapshot.mag_heading_deg.toRadians(), 0f, 0f, 1f)
            .scale(symbolSizeMeters, symbolSizeMeters, 1f)
        // Draw ownship polygon with GL
    }
}
```

### 60fps interpolation from 20Hz dataref updates

```kotlin
// In MapRenderer.drawFrame():
val now = System.nanoTime()
val t = ((now - lastDatarefTime) / datarefIntervalNs).coerceIn(0f, 1f)  // 0–1

val interpLat = lerp(prevSnapshot.latitude, currSnapshot.latitude, t.toDouble())
val interpLon = lerp(prevSnapshot.longitude, currSnapshot.longitude, t.toDouble())
val interpHdg = lerpAngle(prevSnapshot.mag_heading_deg, currSnapshot.mag_heading_deg, t)
```

---

## 6. Track-Up / Heading-Up with 30% Forward Offset (MM-02, MM-03)

In track-up mode, ownship is shown at 30% below screen center (forward offset):
```kotlin
val ownshipScreenY = when (orientationMode) {
    OrientationMode.TRACK_UP, OrientationMode.HEADING_UP ->
        screenHeight * 0.35f   // 35% from top = 30% below center
    else ->
        screenHeight * 0.5f    // centered
}
```

---

## 7. Range Rings (MM-11)

```kotlin
// rendering/map/RangeRingRenderer.kt

class RangeRingRenderer {
    private val defaultRanges = listOf(5f, 10f, 25f, 50f, 100f)  // nm

    fun draw(ownshipLatLon: LatLon, ranges: List<Float>, metersPerPixel: Float, mvp: Matrix4f) {
        for (rangeNm in ranges) {
            val radiusMeters = rangeNm * 1852f
            val radiusPx = radiusMeters / metersPerPixel
            drawCircle(ownshipLatLon, radiusPx, labelText = "${rangeNm.toInt()}nm", mvp)
        }
    }

    private fun drawCircle(center: LatLon, radiusPx: Float, labelText: String, mvp: Matrix4f) {
        // 64-segment triangle strip via buildCircleStrip()
        // Label positioned at top of circle using FontAtlas
    }
}
```

Custom range via long-press: opens number pad dialog (Compose), adds to `ranges` list.

---

## 8. Measurement Tool (MM-12)

Two-tap measurement using Vincenty great-circle:

```kotlin
// domain/nav/GreatCircle.kt

object GreatCircle {
    /** Vincenty formula — accurate to 0.5mm */
    fun distanceNm(from: LatLon, to: LatLon): Double { ... }

    fun initialBearingDeg(from: LatLon, to: LatLon): Double { ... }
    fun finalBearingDeg(from: LatLon, to: LatLon): Double { ... }

    /** Apply magnetic variation (World Magnetic Model 2025). */
    fun magneticBearing(trueBearing: Double, lat: Double, lon: Double): Double {
        val variation = MagneticVariation.compute(lat, lon)  // WMM 2025
        return (trueBearing - variation + 360.0) % 360.0
    }
}
```

Magnetic variation for SA (~25°W) is critical to display correctly. Use NOAA WMM 2025 coefficients (open, updated every 5 years).

---

## 9. Airport Diagram View (MM-13)

At zoom 14+, switch from rasterised OSM tiles to a high-detail airport diagram:

```kotlin
// rendering/map/AirportDiagramRenderer.kt

class AirportDiagramRenderer(private val navDb: EfbDatabase) {
    suspend fun loadDiagram(icao: String): AirportDiagram {
        // Query taxiway nodes/edges from Room DB (populated by Rust nav-data-builder from apt.dat)
        val runways = navDb.runwayDao().forAirport(icao)
        val taxiways = navDb.taxiwayDao().forAirport(icao)
        return AirportDiagram(runways, taxiways)
    }

    fun draw(diagram: AirportDiagram, mvp: Matrix4f) {
        // Runways: filled rectangles, grey asphalt texture, white centreline
        // Taxiways: GL_LINES in yellow
        // Parking: dots + labels
        // Hold-short bars: red hatching
    }
}
```

Priority airports per spec: FAOR, FACT, FALA. Their `apt.dat` data must be validated against actual aerodrome diagrams during testing.

---

## 10. Gesture Handling (UI-04)

```kotlin
// rendering/map/MapGestureHandler.kt

class MapGestureHandler(
    private val mapRenderer: MapRenderer,
) : View.OnTouchListener {

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleListener {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            mapRenderer.zoom(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            mapRenderer.pan(dx, dy)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            mapRenderer.zoom(2f, e.x, e.y)  // double-tap = 2× zoom
            return true
        }
        override fun onFling(e1: MotionEvent, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            mapRenderer.fling(vx, vy)  // momentum deceleration
            return true
        }
    })
}
```

---

## Tests

```kotlin
@Test
fun latLonToTile_faor() {
    val tile = latLonToTile(-26.1392, 28.2462, zoom = 12)
    assertEquals(2423, tile.x)
    assertEquals(2037, tile.y)  // expected for FAOR at z12
}

@Test
fun greatCircle_distanceNm_faorToFact() {
    val d = GreatCircle.distanceNm(
        LatLon(-26.1392, 28.2462),   // FAOR
        LatLon(-33.9648, 18.6017)    // FACT
    )
    assertEquals(740.0, d, 2.0)  // ~740nm published
}

@Test
fun greatCircle_magneticVariation_sa() {
    val magBearing = GreatCircle.magneticBearing(trueBearing = 45.0, lat = -26.0, lon = 28.0)
    // SA magnetic variation ~-25° (westerly), so magnetic bearing ≈ true + 25
    assertTrue(magBearing in 60.0..75.0)
}

@Test
fun interpolation_midpoint() {
    val interp = lerp(-26.0, -26.01, 0.5)
    assertEquals(-26.005, interp, 0.0001)
}
```

---

## Acceptance Criteria Mapping

| Req | Satisfied by |
|---|---|
| MM-01 (offline MBTiles, 60fps pan/zoom) | `MbTilesReader`, `TileCache`, `MapRenderer` |
| MM-02 (position within 50ms, smooth) | 20Hz interpolation in `drawFrame()` |
| MM-03 (orientation modes, 200ms animation) | `updateMapRotation()`, view matrix rotation |
| MM-11 (range rings) | `RangeRingRenderer` |
| MM-12 (Vincenty distance, magnetic bearing) | `GreatCircle.distanceNm()`, WMM 2025 |
| MM-13 (FAOR taxiway diagram at z16) | `AirportDiagramRenderer` |
| UI-04 (pinch-zoom, pan, fling, double-tap) | `MapGestureHandler` |
