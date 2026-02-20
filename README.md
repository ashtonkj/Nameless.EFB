# Nameless EFB

An Android Electronic Flight Bag for X-Plane 12, targeting a Huawei MatePad (Kirin 990 GPU, 6 GB RAM). Three main display modes: **Steam Gauge Panel** (14 traditional round-dial instruments), **G1000 PFD/MFD** simulation, and **Moving Map / Flight Planning**. Geographic focus is global; Southern Africa (SACAA/sub-Saharan Africa) is the primary validation region.

> **Disclaimer:** This is a simulator training tool and is NOT certified for real-world aviation use.

---

## Architecture

| Layer | Technology |
|---|---|
| Android application | Kotlin 2.x (JVM 17) |
| UI chrome, settings, menus | Jetpack Compose + Material 3 |
| Real-time instrument rendering | OpenGL ES 3.0 (GLSL ES 3.00) |
| X-Plane plugin | Rust `cdylib` (xplm-sys / XPLM 4) |
| Nav data builder, terrain preprocessor | Rust standalone CLI tools |
| Local persistence | SQLite via Room |

**Data flow:**
```
X-Plane sim
    ├── Rust plugin (primary, 20 Hz binary UDP on :49100)
    ├── GDL-90 (secondary, X-Plane 12.3+)
    └── UDP broadcast fallback (:49000, groups 3/6/17)
          │
    DataSourceManager (priority failover, <500 ms)
          │
    ┌─────┼──────────────┐
    │     │              │
 Steam  Moving       G1000
 Gauge  Map          PFD/MFD
 Panel  Engine       Engine
    │     │              │
    └─────┴──────────────┘
         OpenGL ES 3.0
         Render Thread (60 fps / 4 ms budget)
```

---

## Build Commands

### Android application
```bash
./gradlew assembleDebug          # build APK
./gradlew test                   # JVM unit tests
./gradlew connectedAndroidTest   # instrumented tests (device/emulator)
```

### Rust plugin + CLIs
```bash
cargo build --manifest-path plugin/Cargo.toml
cargo test  --manifest-path plugin/Cargo.toml --workspace
cargo build --manifest-path plugin/Cargo.toml --target x86_64-unknown-linux-gnu   # Linux .xpl
cargo build --manifest-path plugin/Cargo.toml --target x86_64-pc-windows-gnu      # Windows .xpl
```

### Visual test suite (headless OpenGL screenshots)
```bash
./gradlew :visual-tests:runVisualTests
# PNG output → visual-tests/output/
```
Requires Mesa with llvmpipe (`sudo apt install libegl-mesa0 libgl1-mesa-dri`). No display server or GPU needed — renders via Mesa EGL surfaceless pbuffer.

---

## Key Versions

| Component | Version |
|---|---|
| AGP | 8.7.3 |
| Kotlin | 2.1.0 |
| Compose BOM | 2024.12.01 |
| Room | 2.6.1 |
| Gradle | 8.11.1 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| Rust | 1.88.0 |
| LWJGL (visual tests) | 3.3.4 |

---

## Implementation Status

All 14 plan files are complete and verified.

| Plan | Description | Status |
|---|---|---|
| 01 | Project foundation — Android + Cargo workspace, CI/CD, Room schema | ✅ |
| 02 | X-Plane Rust plugin — binary UDP protocol, dataref subscription, mock XPLM | ✅ |
| 03 | OpenGL ES framework — shared EGL context, ShaderManager, VAO/VBO helpers | ✅ |
| 04 | Navigation data pipeline — Rust CLI tools, Room DB with R-tree, OpenAIP | ✅ |
| 05 | Connectivity layer — DataSourceManager, UDP fallback, GDL-90, BECN discovery | ✅ |
| 06 | Steam gauge panel — 14 GLSL shaders, ASI/AI/ALT/VSI/TC/DI/EGT/RPM/MAP/OIL | ✅ |
| 07 | Steam gauge UX — touch gestures, aircraft profiles, panel config, alerts | ✅ |
| 08 | Moving map engine — MBTiles tile renderer, ownship, orientation modes | ✅ |
| 09 | Map overlays — airports, navaids, airspace, TAWS, weather, traffic | ✅ |
| 10 | Flight planning — route builder, ICAO parser, FMS sync, charts, utilities | ✅ |
| 11 | G1000 PFD — attitude indicator, speed/altitude tapes, HSI (360°/arc), nav box | ✅ |
| 12 | G1000 MFD — full-screen map, EIS strip, FPL/NRST/AUX/terrain/traffic pages | ✅ |
| 13 | G1000 controls — softkey bar, FMS knob, frequency/heading/course/BARO knobs, AP | ✅ |

Remaining work: Phase 7 integration (split-screen modes, theme propagation, NFR validation, disclaimer screen).

---

## Project Structure

```
app/                         Android application module
  src/main/java/             Kotlin source
    data/connectivity/       SimSnapshot, DataSourceManager
    data/nav/                Room DAOs, entities, R-tree spatial queries
    domain/gauge/            GaugeMath, AircraftProfile, SpringDamper
    rendering/               OpenGL ES renderers
      gl/                    BaseRenderer, ShaderManager, GlBuffer, FontAtlas
      gauge/                 SteamGaugePanelRenderer + GaugePanelLayout
      g1000/                 G1000PfdRenderer, G1000MfdRenderer, G1000PfdMath
      map/                   MapRenderer, TileEngine, MbTilesReader
    ui/                      Compose screens, ViewModels, G1000 views
  src/main/assets/shaders/   GLSL ES 3.00 shader sources
  src/main/assets/navdata/   Pre-built SQLite navigation database

plugin/                      Cargo workspace
  xplane-efb-plugin/         X-Plane .xpl cdylib
  dataref-schema/            Shared dataref struct definitions
  efb-protocol/              Binary UDP packet codec (shared with Kotlin)

visual-tests/                JVM headless OpenGL screenshot harness
  src/main/kotlin/android/   Android API shims (GLES30, AssetManager, …)
  src/main/kotlin/com/…/visualtest/  GlContext, Readback, VisualTestRunner

docs/plans/                  14 implementation plan documents
```

---

## Rendering Rules (non-negotiable)

- **All real-time gauge/map rendering** uses OpenGL ES 3.0 directly. Compose is for settings and menus only.
- A **single shared EGL context** is used across all GLSurfaceViews in a panel mode for texture reuse.
- **GLSL vertex shaders** drive needle rotation, tape scrolling, and arc drawing via uniform matrices — no CPU geometry rebuild per frame.
- Performance target: **60 fps sustained, ≤4 ms per frame** on Kirin 990.

---

## South Africa Conventions (defaults)

| Item | Value |
|---|---|
| Pressure | hPa (SACAA/ICAO) |
| Temperature | Celsius |
| Fuel | litres and kg; AVGAS 0.72 kg/L, JetA-1 0.80 kg/L |
| Transition altitude | FL180 |
| VFR squawk | 7000 |
| Approach naming | RNAV (GNSS) |
| Magnetic variation | ~22–25° West |

---

## Data Sources

| Data | Source | Licence |
|---|---|---|
| Airports & navaids | OurAirports CSV + X-Plane `earth_nav.dat` | CC0 |
| Airspace / obstacles | OpenAIP XML | CC BY-SA 4.0 |
| Procedures | Navigraph FMS Data | Commercial |
| Terrain elevation | Copernicus DEM GLO-30 (~30 m global) | ESA free |
| Base map tiles | OpenStreetMap via MBTiles | ODbL |
| Weather (METAR/TAF) | aviationweather.gov ADDS API | Free |
