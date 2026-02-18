# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Nameless.EFB is an Android Electronic Flight Bag for the X-Plane 12 flight simulator, targeting a Huawei MatePad (Kirin 990 GPU, 6GB RAM). It has three main display modes: **Flight Planning / Moving Map**, **Steam Gauge Panel** (14 traditional round-dial instruments), and **Garmin G1000 PFD/MFD** simulation. Geographic focus is global with South Africa (SACAA / sub-Saharan Africa) as primary validation region.

## Technology Stack

| Layer | Language / Runtime |
|---|---|
| Android application | Kotlin 2.x (JVM) |
| UI chrome, settings, menus | Kotlin + Jetpack Compose (Material3) |
| Real-time instrument rendering | OpenGL ES 3.0 (GLSL ES 3.00) |
| X-Plane plugin | Rust cdylib (`xplm-sys` / XPLM4) |
| Nav data parser, terrain preprocessor | Rust standalone CLI tools |
| Local persistence | SQLite via Room |
| Shaders | GLSL ES 3.00 (`.glsl` assets) |

## Build Commands

*(Project is pre-scaffolding. Expected commands once set up:)*

```bash
# Android app
./gradlew assembleDebug
./gradlew test                    # JVM unit tests
./gradlew connectedAndroidTest    # instrumented tests (requires device/emulator)

# Rust plugin + CLIs (Cargo workspace)
cargo build
cargo test
cargo build --target x86_64-unknown-linux-gnu   # X-Plane Linux .xpl
cargo build --target x86_64-pc-windows-gnu      # X-Plane Windows .xpl
```

## Testing Requirements

### Test scope by layer

- **Kotlin (JVM unit tests):** Business logic, navigation calculations, route parsing, METAR decoding, unit conversions, fuel planning, dataref protocol encoding/decoding. Use JUnit 5 + MockK.
- **Kotlin (instrumented tests):** Room DB queries and migrations, Android-specific behaviour (DataStore, WorkManager, ContentProvider). Run on device or emulator via `./gradlew connectedAndroidTest`.
- **Rust:** All crates must have unit tests (`cargo test`). The plugin crate must use a mock XPLM shim for integration tests so tests run without a live X-Plane installation. All UDP packet codec paths (valid, malformed, wrong version) must be covered.
- **OpenGL rendering:** Not unit-tested directly; validate via screenshot comparison tests on a physical device or emulator with GPU support.

### What must be tested

- Dataref packet encode/decode (all field types, array indices, versioning)
- Navigation math: great-circle distance (Vincenty), magnetic variation, TAWS terrain clearance logic, density altitude formula
- Route parsing: ICAO route string tokeniser, FMS v11 export/import, unresolved fix flagging
- SA-specific logic: fuel unit conversions (kg ↔ L using AVGAS/JetA-1 densities), hPa ↔ inHg, transition altitude FL180 annunciation
- Plugin safety: ASAN/UBSAN must pass in debug builds; malformed UDP packets must be silently dropped without panic

### Definition of done

Before marking any task complete:
1. Run `./gradlew test` (Kotlin) — must pass with no failures.
2. Run `cargo test` (Rust) — must pass with no failures.
3. Run `./gradlew assembleDebug` — build must succeed with no errors.
4. If the change touches the Rust plugin, also verify `cargo build --target x86_64-unknown-linux-gnu` succeeds.

Do not mark a task complete if any build or test step fails.

## Critical Architectural Constraints

### Rendering — Non-Negotiable Rules

**All real-time instrument rendering MUST use OpenGL ES 3.0 directly.** This includes: all 14 steam gauges, G1000 PFD/MFD, moving map overlays, terrain coloring, and synthetic vision.

**Jetpack Compose is explicitly prohibited for real-time gauge elements.** Compose may only be used for static UI chrome (settings screens, menu navigation, connection dialogs).

Specific OpenGL requirements:
- Each instrument panel view (Steam Gauge Panel, G1000 PFD, G1000 MFD) MUST use a dedicated `GLSurfaceView` or `SurfaceView` with an EGL context created on a background render thread.
- A single shared EGL context MUST be used across all gauge views within the same panel mode to allow texture sharing (dial faces, font atlases, aviation symbol sprites).
- GLSL vertex shaders MUST handle all needle rotation, tape scrolling, and arc drawing via uniform matrix transforms — not CPU-side geometry recalculation per frame.
- All gauge dial faces and static backgrounds MUST be pre-rendered to textures at startup and cached; redrawn only on theme change.
- Performance target: **60fps sustained**, max **4ms per frame** on render thread.
- Fallback gracefully to OpenGL ES 2.0 if ES 3.0 is unavailable (no crash).

### X-Plane Plugin (Rust)

The plugin is a `cdylib` compiled against XPLM 4.x (X-Plane 12). It replaces XPlaneConnect as the primary data channel.

**Cargo workspace structure:**
- `xplane-efb-plugin` — the `.xpl` cdylib
- `dataref-schema` — shared struct definitions
- `efb-protocol` — UDP packet codec shared with Kotlin app

**Plugin behaviour:**
- Streams binary UDP datagrams at configurable rate (default 20 Hz, max 60 Hz) on port 49100
- Accepts JSON command packets for dataref writes (knob adjustments, frequency changes, AP mode)
- Binary datagram format is versioned with magic bytes and checksum
- Registers X-Plane flight loop callbacks (reads datarefs on flight model thread)
- Watchdog: pauses streaming after 5s without tablet ACK
- Supports hot-reload of dataref subscription list via reload command (no X-Plane restart)
- Must pass ASAN and UBSAN in debug builds; must never cause an X-Plane crash
- Distributed as `.xpl` in `X-Plane 12/Resources/plugins/EFB/`

**Cross-compilation targets:** `x86_64-unknown-linux-gnu` and `x86_64-pc-windows-gnu`.

### Connectivity Architecture

Priority order: **Rust plugin > GDL-90 > UDP broadcast fallback**

A `DataSourceManager` Kotlin class abstracts the data source from display layers. The hybrid protocol operates simultaneously (plugin writes, UDP/GDL-90 reads) with automatic failover within 500ms.

UDP broadcast fallback uses X-Plane native output on port 49000 (Groups 3, 6, 17 for speed/attitude/position).

X-Plane auto-discovery uses BECN multicast on `239.255.1.1:49707`. Requires `WifiManager.MulticastLock` on Android.

## Data Sources

| Data Type | Primary Source | License |
|---|---|---|
| Airport & navaids | OurAirports CSV + X-Plane `earth_nav.dat` | CC0 |
| Airspace | OpenAIP XML | CC BY-SA 4.0 |
| Obstacles | OpenAIP | CC BY-SA 4.0 |
| Procedures (SIDs/STARs/approaches) | Navigraph FMS Data (subscription) | Commercial |
| Terrain elevation | Copernicus DEM GLO-30 (~30m global) | ESA free |
| Base map tiles | OpenStreetMap via MBTiles | ODbL |
| Weather (METAR/TAF) | aviationweather.gov ADDS API | Free |

Navigation database is built at build time by Rust CLI tools (parse OurAirports CSV + `earth_nav.dat` + `earth_fix.dat` + `earth_awy.dat` → SQLite with R-tree spatial index, ~250MB uncompressed).

OpenAIP APIs require an API key. The API key can be found in the file openaip.key (which is ignored via .gitignore to prevent leaking credentials).

Terrain tiles are preprocessed by a Rust CLI from Copernicus HGT files to app-internal 512×512 float16 tile format.

## South Africa Conventions (Default Units)

- **Pressure:** hPa (not inHg) — per SACAA/ICAO
- **Temperature:** Celsius
- **Fuel:** litres and kg (not US gallons); AVGAS density 0.72 kg/L, JetA-1 0.80 kg/L
- **Transition altitude:** FL180 (18,000ft)
- **VFR squawk:** 7000
- **Approach type:** RNAV (GNSS) — not RNAV (GPS) — per SACAA convention
- **Magnetic variation:** ~22–25° West

## Local Persistence

All navigation data, flight plans, logbook, aircraft profiles, and user preferences use **SQLite via Room**. Navigraph credentials must be stored in **Android Keystore** (never SharedPreferences or logs).

## Performance Targets (NFRs)

| Metric | Target |
|---|---|
| Frame rate | 60fps (>58fps average over 5 min) |
| Dataref → display latency | <100ms |
| Touch → sim write latency | <100ms |
| Cold start to first frame | <4 seconds |
| Peak heap (split mode) | <512MB |
| Battery drain (active flight) | <8% per hour |
| Rust plugin CPU overhead | <1% of X-Plane frame time |

## Key Domain Notes

- The app is a **simulator training tool and is NOT certified for real-world aviation use**. A disclaimer must appear on first launch.
- G1000 implementation derives from Garmin G1000 CRG Rev. R (P/N 190-00498-00). All UI elements must match physical G1000 geometry within 2% dimensional tolerance; colors must match CRG specification exactly.
- Steam gauges use a standard GLSL uniform interface: `dataref_value: float`, `range_min: float`, `range_max: float`, `needle_angle: float`. No bitmap sprites for moving elements.
- The app must handle gracefully (no crash): WiFi disconnect in flight, screen rotation during OpenGL rendering, X-Plane quit/restart, and Android `onTrimMemory(TRIM_MEMORY_COMPLETE)`.

# General rules to follow

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.