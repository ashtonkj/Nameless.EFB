# Plan 04 — Navigation Data Pipeline

**Phase:** 2
**Depends on:** Plan 01 (Cargo workspace, Room DB schema)
**Blocks:** Plans 08, 09, 10 (all map and planning features need nav data)

---

## Goals

Build the offline navigation database that underpins all map, routing, and planning features:
- Rust CLI: parse OurAirports CSV + X-Plane nav data files → SQLite with R-tree
- Rust CLI: Copernicus DEM HGT → float16 512×512 tile format
- Rust CLI: OpenAIP XML → obstacle SQLite table
- Room DB populated from the bundled SQLite file
- Spatial queries (50nm radius) completing in <100ms

Requirements covered: ND-01, ND-04, ND-05, §1.4 (global coverage).

---

## 1. `nav-data-builder` CLI

### Input files (bundled at build time in `app/src/main/assets/navdata/`)
| File | Source | Format |
|---|---|---|
| `airports.csv` | OurAirports | CSV |
| `runways.csv` | OurAirports | CSV |
| `navaids.csv` | OurAirports | CSV |
| `frequencies.csv` | OurAirports | CSV |
| `earth_nav.dat` | X-Plane 12 | Custom text |
| `earth_fix.dat` | X-Plane 12 | Custom text |
| `earth_awy.dat` | X-Plane 12 | Custom text |
| `openairports_xml/` | OpenAIP | XML (per-region files) |

### Output
`navdata.db` — SQLite 3 database, ~250MB uncompressed. Shipped in `assets/navdata/navdata.db` (compressed by aapt).

### Tables created

```sql
-- Core airport table
CREATE TABLE airports (
    icao TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    elevation_ft INTEGER,
    airport_type TEXT,
    is_towered INTEGER,
    is_military INTEGER,
    country_code TEXT,
    source TEXT
);

-- R-tree spatial index on airports
CREATE VIRTUAL TABLE airports_rtree USING rtree(
    id,
    lat_min, lat_max,
    lon_min, lon_max
);
-- Populated with 1-row bbox per airport (point → tiny box)

CREATE TABLE runways (
    id INTEGER PRIMARY KEY,
    airport_icao TEXT REFERENCES airports(icao),
    ident TEXT,
    length_ft INTEGER,
    width_ft INTEGER,
    surface TEXT,
    lat_he REAL, lon_he REAL,   -- high-end threshold
    lat_le REAL, lon_le REAL,   -- low-end threshold
    heading_deg REAL,
    ils_freq_hz INTEGER
);

CREATE TABLE navaids (
    id INTEGER PRIMARY KEY,
    identifier TEXT,
    name TEXT,
    type TEXT,        -- "VOR","NDB","ILS","DME","FIX","RNAV"
    latitude REAL,
    longitude REAL,
    elevation_ft INTEGER,
    frequency_hz INTEGER,
    range_nm INTEGER,
    magnetic_var REAL,
    icao_region TEXT,
    airport_icao TEXT
);

CREATE VIRTUAL TABLE navaids_rtree USING rtree(
    id,
    lat_min, lat_max,
    lon_min, lon_max
);

CREATE TABLE airways (
    id INTEGER PRIMARY KEY,
    identifier TEXT,
    type TEXT    -- "V"=Victor, "J"=Jet, "Q"=RNAV
);

CREATE TABLE airway_segments (
    id INTEGER PRIMARY KEY,
    airway_id INTEGER REFERENCES airways(id),
    fix_from_id INTEGER REFERENCES navaids(id),
    fix_to_id INTEGER REFERENCES navaids(id),
    level TEXT,        -- "L"=low, "H"=high, "B"=both
    direction TEXT,    -- "F"=forward, "B"=backward, "N"=both
    min_alt_ft INTEGER,
    max_alt_ft INTEGER
);

CREATE TABLE airspaces (
    id INTEGER PRIMARY KEY,
    name TEXT,
    class TEXT,        -- "A","B","C","D","E","F","G","R","P","D"
    floor_ft INTEGER,
    ceiling_ft INTEGER,
    floor_ref TEXT,    -- "MSL","AGL","FL"
    ceiling_ref TEXT,
    geometry_json TEXT  -- GeoJSON polygon
);

CREATE VIRTUAL TABLE airspaces_rtree USING rtree(
    id,
    lat_min, lat_max,
    lon_min, lon_max
);

CREATE TABLE obstacles (
    id INTEGER PRIMARY KEY,
    type TEXT,         -- "tower","antenna","wind_turbine","power_line"
    latitude REAL,
    longitude REAL,
    height_agl_ft INTEGER,
    elevation_msl_ft INTEGER,
    source TEXT        -- "openaip"
);

CREATE VIRTUAL TABLE obstacles_rtree USING rtree(
    id,
    lat_min, lat_max,
    lon_min, lon_max
);

CREATE TABLE airport_frequencies (
    id INTEGER PRIMARY KEY,
    airport_icao TEXT REFERENCES airports(icao),
    type TEXT,         -- "TWR","GND","ATIS","APP","DEP","CTR"
    description TEXT,
    frequency_hz INTEGER
);
```

### Rust parser implementation

```rust
// nav-data-builder/src/main.rs
fn main() -> anyhow::Result<()> {
    let args = Args::parse();
    let conn = Connection::open(&args.output)?;

    run_migrations(&conn)?;   // creates all tables + indexes

    parse_ourairports_airports(&conn, &args.airports_csv)?;
    parse_ourairports_runways(&conn, &args.runways_csv)?;
    parse_ourairports_navaids(&conn, &args.navaids_csv)?;
    parse_ourairports_frequencies(&conn, &args.frequencies_csv)?;
    parse_xplane_nav_dat(&conn, &args.earth_nav_dat)?;
    parse_xplane_fix_dat(&conn, &args.earth_fix_dat)?;
    parse_xplane_awy_dat(&conn, &args.earth_awy_dat)?;
    parse_openaip_airspaces(&conn, &args.openaip_dir)?;
    parse_openaip_obstacles(&conn, &args.openaip_dir)?;

    populate_rtree_indexes(&conn)?;

    eprintln!("Done. Row counts:");
    for table in ["airports","navaids","airways","airspaces","obstacles"] {
        let count: i64 = conn.query_row(
            &format!("SELECT COUNT(*) FROM {table}"), [], |r| r.get(0))?;
        eprintln!("  {table}: {count}");
    }
    Ok(())
}
```

#### OurAirports CSV parsing
- `airports.csv`: map `ident` → `icao`, `type` → `airport_type`, `municipality` → context
- Filter: include all airports with non-empty ICAO code
- SA validation: assert FAOR, FACT, FALA, FALE, FAWB all present after parse

#### X-Plane earth_nav.dat parsing (v1200 format)
```
Type codes:
  2  = NDB
  3  = VOR
  4  = ILS localizer
  5  = ILS glideslope
  6  = Marker outer
  7  = Marker middle
  8  = Marker inner
  9  = DME
  12 = FMS waypoint (RNAV)
  13 = VOR-DME
```
Parse each line, extract lat/lon/freq/identifier, insert into `navaids`.

#### X-Plane earth_awy.dat parsing
Format: `FIXFROM REGION_FROM TYPE_FROM FIXTO REGION_TO TYPE_TO DIRECTION LEVEL ALT_LOW ALT_HIGH IDENT`
Build `airways` + `airway_segments` tables.

#### OpenAIP XML parsing
```rust
// Each OpenAIP airspace XML file has <Airspace> elements with:
// <Category> A/B/C/D/E/F/G/R/P/D
// <AltLimit REFERENCE="STD|MSL|AGL"> <ALT UNIT="FL|FT">
// <Polygon> comma-separated lat,lon pairs
fn parse_openaip_airspace(conn: &Connection, xml_path: &Path) -> anyhow::Result<()> {
    // Use quick-xml crate for streaming XML parse
    // Insert polygon as GeoJSON string
    // Compute bounding box for R-tree entry
}
```

---

## 2. `terrain-preprocessor` CLI

### Input
Copernicus DEM GLO-30 HGT files (SRTM-compatible `.hgt` format, 1°×1° tiles, 3601×3601 samples at 30m)

### Output
App-internal tile format: `{lat}_{lon}.f16` files — 512×512 array of `float16` elevation values in metres, big-endian.

### Downsampling strategy
Each output pixel = bilinear average of a 7×7 window of input samples (3601/512 ≈ 7). This preserves peak elevations (critical for TAWS).

```rust
// terrain-preprocessor/src/main.rs
fn hgt_to_f16_tiles(hgt_path: &Path, output_dir: &Path) -> anyhow::Result<()> {
    let hgt = read_hgt(hgt_path)?;   // 3601×3601 i16 array
    let tile = downsample_to_512(&hgt);  // 512×512 f32
    let f16_bytes = encode_f16(&tile);   // half::f16 encoding
    let output_name = tile_name_from_path(hgt_path);
    std::fs::write(output_dir.join(output_name), f16_bytes)?;
    Ok(())
}
```

### Region pack for Southern Africa
Tiles covering lat -35 to -15, lon 15 to 40 (roughly FAOR to FACT corridor + Namibia):
- ~100 HGT files → ~100 × 512KB ≈ 50MB of float16 tiles (before compression)
- Tiles downloaded from Copernicus DataSpace Ecosystem (free, ESA)
- Preprocessing runs as a build-time Gradle task that invokes the CLI

### Tile loading on Android
```kotlin
// rendering/terrain/TerrainTileCache.kt

class TerrainTileCache(private val tileDir: File) {
    private val cache = LruCache<TileKey, ShortArray>(maxSize = 48)  // 48 × 512KB ≈ 24MB

    fun getElevationGrid(lat: Int, lon: Int): ShortArray? { ... }

    fun queryElevation(latDeg: Double, lonDeg: Double): Float {
        val tileKey = TileKey(lat = latDeg.toInt(), lon = lonDeg.toInt())
        val grid = getElevationGrid(tileKey.lat, tileKey.lon) ?: return Float.NaN
        // bilinear interpolation within tile
        ...
    }
}
```

---

## 3. Room DB Integration

The `navdata.db` file built by the Rust CLI is copied to `assets/navdata/navdata.db`. Room reads it via `createFromAsset()`:

```kotlin
val db = Room.databaseBuilder(context, EfbDatabase::class.java, "navdata.db")
    .createFromAsset("navdata/navdata.db")
    .fallbackToDestructiveMigration()  // for dev; replace with migration in release
    .build()
```

### DAOs (stub in plan 01, implemented here)

```kotlin
@Dao
interface AirportDao {
    @RawQuery
    suspend fun nearbyAirports(query: SupportSQLiteQuery): List<AirportEntity>

    // R-tree query helper:
    // SELECT a.* FROM airports a
    // JOIN airports_rtree r ON r.id = rowid
    // WHERE r.lat_min >= ? AND r.lat_max <= ?
    //   AND r.lon_min >= ? AND r.lon_max <= ?

    @Query("SELECT * FROM airports WHERE icao = :icao")
    suspend fun byIcao(icao: String): AirportEntity?

    @Query("SELECT * FROM airport_frequencies WHERE airport_icao = :icao")
    suspend fun frequenciesFor(icao: String): List<FrequencyEntity>
}

@Dao
interface NavaidDao {
    @RawQuery
    suspend fun nearbyNavaids(query: SupportSQLiteQuery): List<NavaidEntity>

    @Query("SELECT * FROM navaids WHERE identifier = :id AND icao_region = :region")
    suspend fun byIdentAndRegion(id: String, region: String): NavaidEntity?
}
```

### Spatial query helper
```kotlin
// domain/nav/SpatialQuery.kt

object SpatialQuery {
    /**
     * Returns a SupportSQLiteQuery for airports within [radiusNm] of [center].
     * Uses R-tree bounding box as pre-filter; caller should apply exact
     * great-circle distance filter to the results.
     */
    fun nearbyAirports(center: LatLon, radiusNm: Double): SupportSQLiteQuery {
        val bbox = center.boundingBox(radiusNm)
        return SimpleSQLiteQuery(
            """
            SELECT a.* FROM airports a
            JOIN airports_rtree r ON r.id = a.rowid
            WHERE r.lat_min >= ? AND r.lat_max <= ?
              AND r.lon_min >= ? AND r.lon_max <= ?
            """,
            arrayOf(bbox.latMin, bbox.latMax, bbox.lonMin, bbox.lonMax)
        )
    }
}
```

---

## 4. Navigraph Integration Stubs (ND-03)

Navigraph procedures are optional (subscription). The database schema includes procedure tables, but they are empty in the bundled DB and populated on Navigraph sync:

```sql
CREATE TABLE procedures (
    id INTEGER PRIMARY KEY,
    airport_icao TEXT,
    type TEXT,          -- "SID","STAR","APPROACH"
    identifier TEXT,    -- e.g., "FORT1A"
    runway TEXT,
    transition TEXT,
    waypoints_json TEXT, -- ordered list of fix IDs + altitude/speed constraints
    airac_cycle TEXT
);
```

Navigraph credentials are stored in Android Keystore (NFR-S01). The sync flow (download AIRAC data, parse, insert) is implemented in Plan 10 (flight planning).

---

## 5. Validation Tests

```rust
// nav-data-builder tests
#[test]
fn sa_airports_present() {
    let conn = build_test_db();
    for icao in ["FAOR", "FACT", "FALA", "FALE", "FAWB"] {
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM airports WHERE icao = ?", [icao], |r| r.get(0)).unwrap();
        assert_eq!(count, 1, "Missing airport {icao}");
    }
}

#[test]
fn sa_vor_navaids_present() {
    let conn = build_test_db();
    // ORT VOR 113.0, JHB VOR, CT VOR
    for ident in ["ORT", "JHB"] {
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM navaids WHERE identifier = ? AND type = 'VOR'",
            [ident], |r| r.get(0)).unwrap();
        assert!(count > 0, "Missing VOR {ident}");
    }
}

#[test]
fn rtree_query_returns_sa_airports() {
    let conn = build_test_db();
    // Bounding box around Johannesburg
    let results: Vec<String> = ...;
    assert!(results.contains(&"FAOR".to_string()));
}

#[test]
fn terrain_tile_roundtrip() {
    let hgt = synthetic_hgt(elevation_m: 1753.0);  // FAOR elevation
    let tile = hgt_to_f16_tile(hgt);
    let decoded = decode_f16_tile(tile);
    assert!((decoded[center] - 1753.0).abs() < 5.0);  // ≤5m rounding
}
```

---

## Acceptance Criteria Mapping

| Req | Satisfied by |
|---|---|
| ND-01 (bundled nav DB, 28k+ airports) | `nav-data-builder` CLI + bundled `navdata.db` |
| ND-01 (R-tree spatial index, <100ms) | `airports_rtree` + `SpatialQuery` helper |
| ND-04 (Copernicus DEM, Southern Africa) | `terrain-preprocessor` CLI + `TerrainTileCache` |
| ND-05 (OpenAIP obstacles) | `parse_openaip_obstacles` in `nav-data-builder` |
| §1.4 (global coverage, SA primary) | `sa_airports_present` test + SA assertions |
| ND-03 (Navigraph procedures schema) | `procedures` table stub |
