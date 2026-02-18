# Plan 01 — Project Foundation

**Phase:** 1a
**Depends on:** Nothing (start here)
**Blocks:** All other plans

---

## Goals

Stand up a compilable Android + Rust project with:
- Gradle Android project structure (Kotlin 2.x)
- Cargo workspace (Rust)
- CI/CD pipeline that builds and tests both
- Room DB schema stubs (all tables, no business logic)
- Basic Jetpack Compose application shell with three-mode navigation

---

## 1. Android Project Structure

### Gradle setup
- `minSdk 26` (Android 8.0 — covers Huawei MatePad)
- `targetSdk 35`
- Kotlin 2.x; `kotlinOptions.jvmTarget = "17"`
- Compose BOM (latest stable)
- Room 2.x with KSP annotation processor
- WorkManager, DataStore, Coroutines

### Module layout
```
app/                          Android application module
  src/main/
    java/com/nameless/efb/
      MainActivity.kt         Single-activity host
      ui/
        MainNavigation.kt     Bottom-tab navigation (Map / Gauges / G1000)
        settings/             Compose settings screens
      data/
        db/                   Room database + DAOs
        datastore/            DataStore preferences
      rendering/
        gl/                   OpenGL helpers (see plan 03)
      domain/                 Business logic (pure Kotlin, no Android deps)
    assets/
      shaders/                .glsl files (loaded at runtime)
    res/
  src/test/                   JVM unit tests (JUnit 5 + MockK)
  src/androidTest/            Instrumented tests (Room migrations etc.)
```

### Key dependencies (build.gradle.kts)
```kotlin
// Compose
implementation(platform("androidx.compose:compose-bom:..."))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")

// Room
implementation("androidx.room:room-runtime:...")
implementation("androidx.room:room-ktx:...")
ksp("androidx.room:room-compiler:...")

// DataStore
implementation("androidx.datastore:datastore-preferences:...")

// WorkManager
implementation("androidx.work:work-runtime-ktx:...")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:...")

// JUnit 5 for unit tests
testImplementation("org.junit.jupiter:junit-jupiter:...")
testImplementation("io.mockk:mockk:...")
```

---

## 2. Cargo Workspace

### Workspace root: `plugin/Cargo.toml`
```toml
[workspace]
members = [
    "xplane-efb-plugin",   # cdylib — the .xpl
    "dataref-schema",      # shared struct definitions
    "efb-protocol",        # UDP packet codec (shared with Kotlin via generated code)
    "nav-data-builder",    # standalone CLI: OurAirports + X-Plane nav data → SQLite
    "terrain-preprocessor",# standalone CLI: Copernicus HGT → float16 tiles
]
resolver = "2"
```

### Crate roles

| Crate | Type | Purpose |
|---|---|---|
| `xplane-efb-plugin` | cdylib | X-Plane plugin. Depends on `xplm-sys`, `dataref-schema`, `efb-protocol` |
| `dataref-schema` | lib | Shared struct definitions with `#[repr(C)]` and serde derives |
| `efb-protocol` | lib | Binary UDP packet encode/decode. No X-Plane dependency |
| `nav-data-builder` | bin | Parses OurAirports CSV, earth_nav.dat, earth_fix.dat, earth_awy.dat, OpenAIP XML → SQLite |
| `terrain-preprocessor` | bin | Copernicus HGT → app-internal float16 512×512 tiles |

### Cross-compilation targets
Add to `plugin/.cargo/config.toml`:
```toml
[target.x86_64-unknown-linux-gnu]
linker = "x86_64-linux-gnu-gcc"

[target.x86_64-pc-windows-gnu]
linker = "x86_64-w64-mingw32-gcc"
```

---

## 3. Room Database Schema (stubs)

All tables are created here with correct columns and types; business logic is added in later plans. This ensures migrations are tracked from the start.

### Database version: 1

```kotlin
@Database(
    entities = [
        AirportEntity::class,
        RunwayEntity::class,
        NavaidEntity::class,
        FixEntity::class,
        AirwayEntity::class,
        AirwaySegmentEntity::class,
        AirspaceEntity::class,
        ObstacleEntity::class,
        FlightPlanEntity::class,
        FlightPlanWaypointEntity::class,
        AircraftProfileEntity::class,
        LogbookEntryEntity::class,
        MetarCacheEntity::class,
        PlateAnnotationEntity::class,
    ],
    version = 1,
    exportSchema = true
)
abstract class EfbDatabase : RoomDatabase() {
    abstract fun airportDao(): AirportDao
    abstract fun navaidDao(): NavaidDao
    abstract fun fixDao(): FixDao
    abstract fun airwayDao(): AirwayDao
    abstract fun airspaceDao(): AirspaceDao
    abstract fun obstacleDao(): ObstacleDao
    abstract fun flightPlanDao(): FlightPlanDao
    abstract fun aircraftProfileDao(): AircraftProfileDao
    abstract fun logbookDao(): LogbookDao
    abstract fun metarDao(): MetarDao
    abstract fun plateAnnotationDao(): PlateAnnotationDao
}
```

### Critical entity fields

**AirportEntity**
```kotlin
@Entity(tableName = "airports")
data class AirportEntity(
    @PrimaryKey val icao: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevationFt: Int,
    val airportType: String,      // "large_airport", "small_airport", "heliport", etc.
    val isTowered: Boolean,
    val isMilitary: Boolean,
    val countryCode: String,
    val source: String,           // "ourairports" | "xplane_apt"
)
```

**NavaidEntity**
```kotlin
@Entity(tableName = "navaids")
data class NavaidEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identifier: String,
    val name: String,
    val type: String,             // "VOR", "NDB", "ILS", "DME", "FIX"
    val latitude: Double,
    val longitude: Double,
    val elevationFt: Int,
    val frequencyHz: Int,         // 0 for fixes
    val magneticVariation: Float,
    val rangeNm: Int,
    val icaoRegion: String,
)
```

**FlightPlanEntity** (with FTS4 virtual table for search via FP-10)
```kotlin
@Entity(tableName = "flight_plans")
data class FlightPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val departureIcao: String,
    val destinationIcao: String,
    val aircraftProfileId: Long?,
    val createdAt: Long,          // epoch ms
    val updatedAt: Long,
    val waypointsJson: String,    // serialised list
    val notes: String,
    val tags: String,             // comma-separated
)
```

**MetarCacheEntity**
```kotlin
@Entity(tableName = "metar_cache")
data class MetarCacheEntity(
    @PrimaryKey val icao: String,
    val rawMetar: String,
    val flightCategory: String,   // "VFR","MVFR","IFR","LIFR"
    val fetchedAt: Long,          // epoch ms — TTL 30 min
    val windDirDeg: Int,
    val windSpeedKt: Int,
    val visibilityM: Int,
    val ceilingFt: Int?,
    val tempC: Float,
    val dewpointC: Float,
    val qnhHpa: Float,
)
```

R-tree spatial index tables are created in the Rust nav-data-builder (not Room) using SQLite's rtree module. Room reads from these tables via `@RawQuery`.

---

## 4. Application Shell

### MainActivity.kt
- Single `AppCompatActivity`
- Hosts a `ComposeView` for the tab navigation chrome
- Each tab corresponds to a `Fragment` with its own `GLSurfaceView`
- `onTrimMemory(TRIM_MEMORY_COMPLETE)` → release GL texture caches, notify all active renderers

### Tab navigation
Three tabs at the bottom (15dp strip per UI-01):
1. **Map** — `MapFragment`
2. **Gauges** — `SteamGaugeFragment`
3. **G1000** — `G1000Fragment`

Each Fragment is pre-created (not lazy) to keep GL contexts alive. Use `FragmentContainerView` with `setMaxLifecycle(STARTED)` on inactive tabs so `GLSurfaceView.onPause()` is called.

### ViewModels
- `SimDataViewModel` — holds the live dataref state as `StateFlow`; consumed by all three panel fragments
- `FlightPlanViewModel` — current active flight plan
- `SettingsViewModel` — units, theme, connectivity config

---

## 5. CI/CD Pipeline

### `.github/workflows/ci.yml` (or equivalent)

**Jobs:**

1. **android-build**
   ```bash
   ./gradlew assembleDebug
   ./gradlew test                   # JVM unit tests
   ```

2. **rust-build-test**
   ```bash
   cargo test --workspace
   cargo build --target x86_64-unknown-linux-gnu
   cargo build --target x86_64-pc-windows-gnu
   ```

3. **android-instrumented** (runs on emulator, manual trigger or nightly)
   ```bash
   ./gradlew connectedAndroidTest
   ```

**Definition of done for this plan:**
- [ ] `./gradlew assembleDebug` exits 0
- [ ] `./gradlew test` exits 0 (even if no tests yet — must not fail)
- [ ] `cargo test --workspace` exits 0
- [ ] `cargo build --target x86_64-unknown-linux-gnu` exits 0 (plugin stub)
- [ ] App launches on emulator showing three-tab navigation with placeholder screens

---

## Acceptance Criteria Mapping

| Req | Satisfied by |
|---|---|
| UI-01 (three-mode switching) | `MainNavigation.kt` tab structure |
| UI-06 (settings/preferences) | `DataStore` wiring in `SettingsViewModel` |
| UI-03 (landscape/portrait) | `configChanges` in manifest; ViewModel preservation |
| NFR-R04 (disclaimer) | First-launch dialog in `MainActivity` |
| NFR-S01 (Keystore for credentials) | `NavGraphKeystore` wrapper class (stub) |
