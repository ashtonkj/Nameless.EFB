# Plan 02 — X-Plane Rust Plugin

**Phase:** 1b
**Depends on:** Plan 01 (Cargo workspace exists)
**Blocks:** Plan 05 (connectivity), all display plans that need live data

---

## Goals

Implement the X-Plane EFB plugin as a Rust `cdylib` that:
- Streams binary UDP datagrams at 20Hz (max 60Hz) to the tablet
- Accepts JSON command packets for dataref writes
- Has a mock XPLM shim so all logic is testable without X-Plane
- Passes ASAN/UBSAN in debug builds
- Cross-compiles to Linux and Windows `.xpl`

Requirements covered: XC-01, and all of Section 1.3.

---

## 1. Crate Structure

### `dataref-schema` (lib)

Shared struct definitions. No X-Plane dependency.

```rust
// dataref-schema/src/lib.rs

/// All datarefs streamed from plugin to tablet.
/// Field order and sizes are part of the protocol — do not reorder.
#[repr(C)]
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct SimSnapshot {
    // Position
    pub latitude: f64,
    pub longitude: f64,
    pub elevation_m: f64,
    pub groundspeed_ms: f32,

    // Attitude
    pub pitch_deg: f32,
    pub roll_deg: f32,
    pub mag_heading_deg: f32,
    pub ground_track_deg: f32,

    // Air data
    pub ias_kts: f32,
    pub tas_kts: f32,
    pub vvi_fpm: f32,
    pub turn_rate_deg_sec: f32,
    pub slip_deg: f32,
    pub oat_degc: f32,
    pub barometer_inhg: f32,

    // Engine (index 0)
    pub rpm: f32,
    pub map_inhg: f32,
    pub fuel_flow_kg_sec: f32,
    pub oil_press_psi: f32,
    pub oil_temp_degc: f32,
    pub egt_degc: [f32; 6],
    pub fuel_qty_kg: [f32; 2],
    pub bus_volts: f32,
    pub battery_amps: f32,
    pub suction_inhg: f32,

    // Navigation
    pub nav1_hdef_dot: f32,
    pub nav1_vdef_dot: f32,
    pub nav1_obs_deg: f32,
    pub gps_dist_nm: f32,
    pub gps_bearing_deg: f32,

    // Autopilot
    pub ap_state_flags: i32,
    pub fd_pitch_deg: f32,
    pub fd_roll_deg: f32,
    pub ap_heading_bug_deg: f32,
    pub ap_altitude_ft: f32,
    pub ap_vs_fpm: f32,

    // Radios
    pub com1_active_hz: i32,
    pub com1_standby_hz: i32,
    pub com2_active_hz: i32,
    pub nav1_active_hz: i32,
    pub nav1_standby_hz: i32,
    pub transponder_code: i32,
    pub transponder_mode: i32,

    // Markers
    pub outer_marker: bool,
    pub middle_marker: bool,
    pub inner_marker: bool,

    // Weather
    pub wind_dir_deg: f32,
    pub wind_speed_kt: f32,

    // Traffic (up to 20 targets)
    pub traffic_lat: [f32; 20],
    pub traffic_lon: [f32; 20],
    pub traffic_ele_m: [f32; 20],
    pub traffic_count: u8,

    // HSI source
    pub hsi_source: i32,
}
```

### `efb-protocol` (lib)

Binary UDP packet codec. Used by both the Rust plugin and (via code generation or FFI) the Kotlin app.

```rust
// efb-protocol/src/lib.rs

pub const MAGIC: u32 = 0xEFB12345;
pub const PROTOCOL_VERSION: u16 = 1;

/// Binary packet header — always at the start of every datagram.
#[repr(C, packed)]
pub struct PacketHeader {
    pub magic: u32,           // MAGIC constant
    pub version: u16,
    pub packet_type: u8,      // PacketType enum value
    pub payload_len: u16,
    pub sequence: u32,
    pub checksum: u32,        // CRC32 of payload bytes
}

#[repr(u8)]
pub enum PacketType {
    SimData     = 0x01,   // plugin → tablet: SimSnapshot payload
    CommandJson = 0x02,   // tablet → plugin: JSON command payload
    Ack         = 0x03,   // tablet → plugin: heartbeat ACK
    Reload      = 0x04,   // tablet → plugin: reload dataref list
}

pub fn encode_sim_data(seq: u32, snapshot: &SimSnapshot) -> Vec<u8> { ... }
pub fn decode_packet(buf: &[u8]) -> Result<(PacketHeader, PacketType, &[u8]), ProtocolError> { ... }
pub fn verify_checksum(header: &PacketHeader, payload: &[u8]) -> bool { ... }
```

**Tests in `efb-protocol`:**
- Encode then decode round-trip for valid `SimSnapshot`
- Decode correctly rejects: wrong magic, wrong version, truncated payload, bad checksum
- All field types encoded correctly (endianness, padding)

### `xplane-efb-plugin` (cdylib)

#### XPLM abstraction layer (mock shim)

To allow unit testing without X-Plane, hide all XPLM calls behind a trait:

```rust
// xplane-efb-plugin/src/xplm_shim.rs

pub trait XplmApi: Send + Sync {
    fn find_dataref(&self, path: &str) -> Option<DataRefHandle>;
    fn get_float(&self, handle: DataRefHandle) -> f32;
    fn get_double(&self, handle: DataRefHandle) -> f64;
    fn get_int(&self, handle: DataRefHandle) -> i32;
    fn get_float_array(&self, handle: DataRefHandle, offset: usize, out: &mut [f32]);
    fn set_float(&self, handle: DataRefHandle, value: f32);
    fn set_int(&self, handle: DataRefHandle, value: i32);
    fn log(&self, message: &str);
}

/// Real implementation — wraps xplm-sys unsafe calls.
pub struct RealXplm;
impl XplmApi for RealXplm { ... }

/// Test implementation — returns configurable values.
pub struct MockXplm { pub datarefs: HashMap<String, DataRefValue> }
impl XplmApi for MockXplm { ... }
```

#### Plugin state machine

```rust
pub struct EfbPlugin {
    xplm: Box<dyn XplmApi>,
    udp_socket: UdpSocket,
    tablet_addr: Option<SocketAddr>,
    last_ack_time: Instant,
    sequence: AtomicU32,
    dataref_handles: DataRefHandles,   // cached handles from find_dataref
    streaming_rate_hz: u8,             // default 20, max 60
}
```

#### Flight loop callback (registered via `XPLMRegisterFlightLoopCallback`)

Called every sim frame on the flight model thread:
1. Read all datarefs into `SimSnapshot`
2. If no ACK received in 5 seconds: pause streaming (watchdog, XC-01)
3. Encode packet via `efb-protocol`
4. Send UDP datagram to `tablet_addr`

#### Command server (separate thread)

Listens on UDP port 49100 for:
- `PacketType::CommandJson` → parse JSON, validate, write to datarefs
- `PacketType::Ack` → update `last_ack_time` (resets watchdog)
- `PacketType::Reload` → re-run `find_dataref` for updated subscription list

JSON command format:
```json
{
  "cmd": "set_dataref",
  "path": "sim/cockpit/autopilot/heading_mag",
  "value": 270.0
}
```
```json
{
  "cmd": "swap_freq",
  "radio": "COM1"
}
```

#### XPLM entry points
```rust
#[no_mangle]
pub extern "C" fn XPluginStart(name: *mut c_char, sig: *mut c_char, desc: *mut c_char) -> c_int
#[no_mangle]
pub extern "C" fn XPluginStop()
#[no_mangle]
pub extern "C" fn XPluginEnable() -> c_int
#[no_mangle]
pub extern "C" fn XPluginDisable()
#[no_mangle]
pub extern "C" fn XPluginReceiveMessage(from: c_int, msg: c_int, param: *mut c_void)
```

---

## 2. Dataref Handle Cache

At plugin enable time, call `find_dataref` for every path in the subscription list and cache the handles. Log any path that returns `None` via XPLM log API. Cached handles are used in the flight loop (no string lookup per frame).

All 50+ datarefs from Section 7 of the requirements are included. Array datarefs (traffic, EGT per cylinder, fuel tanks) use `get_float_array`.

---

## 3. Protocol Safety Rules (NFR-S03)

The plugin MUST:
- Validate `MAGIC` constant on every incoming packet — drop silently if wrong
- Validate `version` — drop if unsupported, log the mismatch
- Verify CRC32 checksum — drop if mismatch
- Never `panic!` in response to a malformed packet; use `Result` and log errors
- Enforce maximum payload length (reject packets > 64KB)

---

## 4. Build & Distribution

### Cargo features
```toml
[features]
default = []
mock_xplm = []   # enables MockXplm; used in tests
```

### Build output
- Linux: `target/x86_64-unknown-linux-gnu/release/libxplane_efb_plugin.so` → renamed to `EFB.xpl` for Linux
- Windows: `target/x86_64-pc-windows-gnu/release/xplane_efb_plugin.dll` → renamed to `EFB.xpl` for Windows

### Installed to
```
X-Plane 12/Resources/plugins/EFB/
  ├── lin_x64/EFB.xpl
  └── win_x64/EFB.xpl
```

---

## 5. Tests

### `efb-protocol` tests (unit)
```rust
#[test]
fn round_trip_sim_snapshot() { ... }

#[test]
fn rejects_wrong_magic() { ... }

#[test]
fn rejects_truncated_payload() { ... }

#[test]
fn rejects_bad_checksum() { ... }

#[test]
fn all_packet_types_encode_decode() { ... }
```

### `xplane-efb-plugin` integration tests (with `MockXplm`)
```rust
#[test]
fn flight_loop_reads_all_datarefs() { ... }

#[test]
fn watchdog_pauses_streaming_after_5s() { ... }

#[test]
fn malformed_command_packet_silently_dropped() { ... }

#[test]
fn valid_set_dataref_command_calls_xplm_set() { ... }

#[test]
fn reload_command_refreshes_handles() { ... }
```

---

## Acceptance Criteria Mapping

| Req | Satisfied by |
|---|---|
| XC-01 (plugin primary channel) | `EfbPlugin` flight loop + UDP stream |
| §1.3.1 (UDP server, 20Hz, versioned) | `efb-protocol` + command server thread |
| §1.3.1 (watchdog 5s) | `last_ack_time` check in flight loop |
| §1.3.1 (hot-reload) | `PacketType::Reload` handler |
| §1.3.1 (XPLM4, ASAN/UBSAN) | build flags in CI |
| §1.3.2 (crate structure, cross-compile) | Cargo workspace, `.cargo/config.toml` |
| NFR-R05 (zero memory errors) | ASAN/UBSAN CI job |
| NFR-S03 (packet validation) | magic/version/checksum checks |
