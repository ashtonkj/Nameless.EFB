# Nameless EFB — Master Implementation Plan

> Source: `docs/EFB_Functional_Requirements.docx` v1.0, February 2026

## Feature Inventory

| Area | DIFF | COMP | TABLE | Total |
|---|---|---|---|---|
| Flight Planning & Map (FP/MM/IP/CH/UT) | 3 | 26 | 8 | 37 |
| Steam Gauge Panel (SG) | 22 | 1 | 0 | 23 |
| G1000 PFD/MFD (G) | 29 | 2 | 1 | 32 |
| Connectivity & Infrastructure (XC/ND/UI) | 3 | 7 | 10 | 20 |
| Non-Functional Requirements (NFR) | 0 | 0 | 14 | 14 |
| **TOTAL** | **57** | **36** | **33** | **126** |

---

## Implementation Phases

### Phase 1 — Foundation (plan files 01–03)
All subsequent phases depend on this. Nothing else can start until the project compiles and the Rust plugin can stream data.

| Plan | File | Key outputs |
|---|---|---|
| 1a | `01_project_foundation.md` | Android Gradle project, Cargo workspace, CI/CD, Room DB schema stubs |
| 1b | `02_rust_plugin.md` | Rust plugin with mock XPLM shim, binary UDP protocol, dataref subscription |
| 1c | `03_opengl_framework.md` | Shared EGL context, GLSurfaceView lifecycle, shader compilation, VAO/VBO helpers |

**Definition of done:** `./gradlew assembleDebug` passes; `cargo test` passes; `cargo build --target x86_64-unknown-linux-gnu` produces an `.xpl`.

---

### Phase 2 — Navigation Data Pipeline (plan file 04)
Must complete before any map or flight-planning feature can show real data.

| Plan | File | Key outputs |
|---|---|---|
| 2 | `04_nav_database.md` | Rust CLI tools (navdata parser, terrain preprocessor), Room DB with R-tree, OpenAIP obstacle parser |

**Definition of done:** `cargo test` passes; Room DB queryable with SA airport data; terrain tiles generated for Southern Africa.

---

### Phase 3 — Connectivity Layer (plan file 05)
Needed by all three display modes before live sim data can flow.

| Plan | File | Key outputs |
|---|---|---|
| 3 | `05_connectivity.md` | DataSourceManager, UDP fallback, GDL-90 parser, BECN auto-discovery, connection health UI |

**Definition of done:** `./gradlew test` passes; app connects to X-Plane and receives live datarefs.

---

### Phase 4 — Steam Gauge Panel (plan files 06–07)
Self-contained; depends only on Phases 1 & 3. Can be developed in parallel with Phases 5–6 on separate branches.

| Plan | File | Key outputs |
|---|---|---|
| 4a | `06_steam_gauges.md` | All 14 gauge GLSL shaders, VAO geometry, dataref bindings |
| 4b | `07_steam_gauge_ux.md` | Touch gestures, haptic feedback, panel config, aircraft profiles |

**Definition of done:** All 14 gauges render at 60fps; all touch interactions write to sim within 50ms.

---

### Phase 5 — Moving Map & Flight Planning (plan files 08–10)
Depends on Phases 1–3. The map engine (08) must complete before overlays (09) and planning tools (10).

| Plan | File | Key outputs |
|---|---|---|
| 5a | `08_moving_map_engine.md` | MBTiles tile engine, OpenGL tile rendering, ownship tracking, orientation modes |
| 5b | `09_map_overlays.md` | Airport/navaid/airspace/TAWS/weather/traffic overlays, instrument strip |
| 5c | `10_flight_planning.md` | Route builder, ICAO parser, FMS sync, charts/plates, planning utilities |

**Definition of done:** Map renders offline at 60fps; flight plan round-trips to X-Plane FMS; TAWS terrain alerts fire correctly.

---

### Phase 6 — G1000 PFD/MFD (plan files 11–13)
Most complex phase. Depends on Phases 1–3. PFD (11) must complete before MFD (12) and controls (13).

| Plan | File | Key outputs |
|---|---|---|
| 6a | `11_g1000_pfd.md` | Attitude indicator with SVS, airspeed/altitude tapes, HSI (360° and arc), nav status box |
| 6b | `12_g1000_mfd.md` | Full-screen map with TAWS, EIS strip, FPL page, procedure pages, NRST, AUX, terrain/traffic pages |
| 6c | `13_g1000_controls.md` | Softkey bar, FMS knob, frequency knobs, HDG/CRS/BARO, autopilot modes, COM/NAV panels |

**Definition of done:** G1000 PFD/MFD renders at 60fps; all softkey hierarchies correct per CRG; autopilot modes write to sim correctly.

---

### Phase 7 — Integration & Polish
No separate plan file. Cross-cutting concerns resolved here:

- Split-screen modes (UI-02): Map+Gauges, PFD+MFD, Map+Plate
- Day/Night/Red-cockpit theme propagation across all modes (UI-05, SG-22)
- Landscape/portrait reflow (UI-03)
- Full NFR validation: frame rate, memory, battery, crash-free rate
- Disclaimer screen (NFR-R04)
- Android Backup API for preferences (UI-06)
- Security review: Keystore credentials (NFR-S01), packet validation (NFR-S03)

---

## Key Architectural Constraints (non-negotiable)

1. **All real-time rendering** (gauges, G1000, moving map, TAWS) → OpenGL ES 3.0. Jetpack Compose is only for settings/menus.
2. **Shared EGL context** across all GLSurfaceViews in the same panel mode (texture reuse).
3. **GLSL vertex shaders** drive all needle rotation, tape scrolling, arc drawing via uniforms — no CPU geometry rebuild per frame.
4. **60fps / 4ms render budget** on Huawei MatePad (Kirin 990).
5. **Rust plugin** is primary data channel; `DataSourceManager` abstracts the source from display layers.
6. **Global nav data** — SA (FAOR/FACT/FALA) is primary validation region; US-only sources are supplementary only.
7. **SA defaults**: hPa, Celsius, litres, kg, FL180 transition altitude, VFR squawk 7000, RNAV (GNSS) approach naming.

---

## Cross-Cutting Data Flow

```
X-Plane sim
    │
    ├── Rust plugin (primary, 20Hz binary UDP on :49100)
    ├── GDL-90 (secondary, X-Plane 12.3+)
    └── UDP broadcast fallback (:49000)
          │
    DataSourceManager (Kotlin)
          │
    ┌─────┼─────────────┐
    │     │             │
 Steam   Moving      G1000
 Gauge   Map         PFD/MFD
 Panel   Engine      Engine
    │     │             │
    └─────┴─────────────┘
         OpenGL ES 3.0
         Render Thread
```

---

## Dependency Graph

```
01 (Foundation) ──────────────────────────────────────┐
  └── 02 (Rust Plugin)  ──────────────────────────────┤
  └── 03 (OpenGL Framework) ─────────────────────────┤
        └── 04 (Nav DB)  ──────────────────────────── ┤
              └── 05 (Connectivity) ─────────────────┤
                    ├── 06+07 (Steam Gauges) ─────────┤
                    ├── 08+09+10 (Map & Planning) ─── ┤
                    └── 11+12+13 (G1000) ─────────────┘
                                                      │
                                              Phase 7 (Polish)
```

---

## Plan File Index

| File | Phase | Description |
|---|---|---|
| `00_overview.md` | — | This file. Master plan and dependency map |
| `01_project_foundation.md` | 1a | Android + Rust project scaffold, CI |
| `02_rust_plugin.md` | 1b | X-Plane Rust plugin and UDP protocol |
| `03_opengl_framework.md` | 1c | OpenGL ES rendering infrastructure |
| `04_nav_database.md` | 2 | Navigation data pipeline and Room DB |
| `05_connectivity.md` | 3 | DataSourceManager and connectivity |
| `06_steam_gauges.md` | 4a | All 14 steam gauge GLSL shaders |
| `07_steam_gauge_ux.md` | 4b | Gauge touch interaction and panel config |
| `08_moving_map_engine.md` | 5a | MBTiles tile engine and map rendering |
| `09_map_overlays.md` | 5b | Map overlays, weather, TAWS, traffic |
| `10_flight_planning.md` | 5c | Route builder, charts, planning utilities |
| `11_g1000_pfd.md` | 6a | G1000 PFD display elements |
| `12_g1000_mfd.md` | 6b | G1000 MFD pages |
| `13_g1000_controls.md` | 6c | G1000 bezel controls and autopilot |
