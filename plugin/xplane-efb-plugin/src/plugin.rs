//! EfbPlugin state machine — the core of the X-Plane plugin.
//!
//! This module is free of any XPLM types so it can be fully unit-tested via
//! the `MockXplm` shim.

use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{mpsc, Arc};
use std::time::{Duration, Instant};

use dataref_schema::SimSnapshot;
use efb_protocol::{decode_packet, encode_sim_data, PacketType};
use serde::Deserialize;

use crate::xplm_shim::{DataRefHandle, XplmApi};

// ── Constants ─────────────────────────────────────────────────────────────────

pub const STREAM_PORT: u16 = 49100;
pub const DEFAULT_HZ: u8   = 20;
pub const MAX_HZ: u8        = 60;
pub const WATCHDOG_TIMEOUT: Duration = Duration::from_secs(5);

// ── X-Plane dataref paths ─────────────────────────────────────────────────────

mod paths {
    // Position
    pub const LATITUDE:       &str = "sim/flightmodel/position/latitude";
    pub const LONGITUDE:      &str = "sim/flightmodel/position/longitude";
    pub const ELEVATION_M:    &str = "sim/flightmodel/position/elevation";
    pub const GROUNDSPEED_MS: &str = "sim/flightmodel/position/groundspeed";
    // Attitude
    pub const PITCH_DEG:        &str = "sim/flightmodel/position/theta";
    pub const ROLL_DEG:         &str = "sim/flightmodel/position/phi";
    pub const MAG_HEADING_DEG:  &str = "sim/flightmodel/position/mag_psi";
    pub const GROUND_TRACK_DEG: &str = "sim/flightmodel/position/hpath";
    // Air data
    pub const IAS_KTS:           &str = "sim/flightmodel/position/indicated_airspeed";
    pub const TRUE_AIRSPEED_MS:  &str = "sim/flightmodel/position/true_airspeed";
    pub const VVI_FPM:           &str = "sim/flightmodel/position/vh_ind_fpm";
    pub const TURN_RATE_DEG_SEC: &str = "sim/cockpit2/gauges/indicators/turn_rate_heading_deg_pilot";
    pub const SLIP_DEG:          &str = "sim/cockpit/gyros/slip_deg";
    pub const OAT_DEGC:          &str = "sim/weather/temperature_ambient_c";
    pub const BAROMETER_INHG:    &str = "sim/cockpit2/gauges/actuators/barometer_setting_in_hg_pilot";
    // Engine arrays (read index 0 for engine 1)
    pub const ENGINE_RPM:        &str = "sim/cockpit2/engine/indicators/engine_speed_rpm";
    pub const MANIFOLD_INHG:     &str = "sim/cockpit2/engine/indicators/manifold_pressure_inhg";
    pub const FUEL_FLOW_KG_SEC:  &str = "sim/cockpit2/engine/indicators/fuel_flow_kg_sec";
    pub const OIL_PRESS_PSI:     &str = "sim/cockpit2/engine/indicators/oil_pressure_psi";
    pub const OIL_TEMP_DEGC:     &str = "sim/cockpit2/engine/indicators/oil_temp_deg_c";
    pub const EGT_DEGC:          &str = "sim/cockpit2/engine/indicators/EGT_deg_c";
    pub const FUEL_QTY_KG:       &str = "sim/flightmodel/weight/m_fuel";
    pub const BUS_VOLTS:         &str = "sim/cockpit2/electrical/bus_volts";
    pub const BATTERY_AMPS:      &str = "sim/cockpit2/electrical/battery_amps_total";
    pub const SUCTION_INHG:      &str = "sim/cockpit2/gauges/indicators/airspeed_vacuum_in_hg_pilot";
    // Navigation
    pub const NAV1_HDEF_DOT:   &str = "sim/cockpit2/radios/indicators/nav1_hdef_dots_pilot";
    pub const NAV1_VDEF_DOT:   &str = "sim/cockpit2/radios/indicators/nav1_vdef_dots_pilot";
    pub const NAV1_OBS_DEG:    &str = "sim/cockpit/radios/nav1_course_degm";
    pub const GPS_DIST_NM:     &str = "sim/cockpit2/radios/indicators/gps_dme_distance_nm";
    pub const GPS_BEARING_DEG: &str = "sim/cockpit2/radios/indicators/gps_bearing_deg_mag";
    // Autopilot
    pub const AP_STATE_FLAGS:     &str = "sim/cockpit/autopilot/autopilot_state";
    pub const FD_PITCH_DEG:       &str = "sim/cockpit2/autopilot/flight_director_pitch_deg";
    pub const FD_ROLL_DEG:        &str = "sim/cockpit2/autopilot/flight_director_roll_deg";
    pub const AP_HEADING_BUG_DEG: &str = "sim/cockpit/autopilot/heading_mag";
    pub const AP_ALTITUDE_FT:     &str = "sim/cockpit/autopilot/altitude";
    pub const AP_VS_FPM:          &str = "sim/cockpit/autopilot/vertical_velocity";
    // Radios
    pub const COM1_ACTIVE_HZ:    &str = "sim/cockpit2/radios/actuators/com1_frequency_hz";
    pub const COM1_STANDBY_HZ:   &str = "sim/cockpit2/radios/actuators/com1_standby_frequency_hz";
    pub const COM2_ACTIVE_HZ:    &str = "sim/cockpit2/radios/actuators/com2_frequency_hz";
    pub const NAV1_ACTIVE_HZ:    &str = "sim/cockpit2/radios/actuators/nav1_frequency_hz";
    pub const NAV1_STANDBY_HZ:   &str = "sim/cockpit2/radios/actuators/nav1_standby_frequency_hz";
    pub const TRANSPONDER_CODE:  &str = "sim/cockpit/radios/transponder_code";
    pub const TRANSPONDER_MODE:  &str = "sim/cockpit/radios/transponder_mode";
    // Markers
    pub const OUTER_MARKER:  &str = "sim/cockpit2/annunciators/outer_marker";
    pub const MIDDLE_MARKER: &str = "sim/cockpit2/annunciators/middle_marker";
    pub const INNER_MARKER:  &str = "sim/cockpit2/annunciators/inner_marker";
    // Weather
    pub const WIND_DIR_DEG:  &str = "sim/weather/wind_direction_degt";
    pub const WIND_SPEED_KT: &str = "sim/weather/wind_speed_kt";
    // Traffic (TCAS)
    pub const TRAFFIC_LAT:   &str = "sim/cockpit2/tcas/targets/position/lat";
    pub const TRAFFIC_LON:   &str = "sim/cockpit2/tcas/targets/position/lon";
    pub const TRAFFIC_ELE_M: &str = "sim/cockpit2/tcas/targets/position/ele";
    pub const TRAFFIC_COUNT: &str = "sim/cockpit2/tcas/targets/N_targets_max";
    // HSI source
    pub const HSI_SOURCE: &str = "sim/cockpit2/radios/actuators/HSI_source_select_pilot";
}

// ── DataRefHandles ────────────────────────────────────────────────────────────

/// Cached dataref handles looked up once at plugin enable time.
#[derive(Default)]
pub struct DataRefHandles {
    pub latitude:          Option<DataRefHandle>,
    pub longitude:         Option<DataRefHandle>,
    pub elevation_m:       Option<DataRefHandle>,
    pub groundspeed_ms:    Option<DataRefHandle>,
    pub pitch_deg:         Option<DataRefHandle>,
    pub roll_deg:          Option<DataRefHandle>,
    pub mag_heading_deg:   Option<DataRefHandle>,
    pub ground_track_deg:  Option<DataRefHandle>,
    pub ias_kts:           Option<DataRefHandle>,
    pub true_airspeed_ms:  Option<DataRefHandle>,
    pub vvi_fpm:           Option<DataRefHandle>,
    pub turn_rate_deg_sec: Option<DataRefHandle>,
    pub slip_deg:          Option<DataRefHandle>,
    pub oat_degc:          Option<DataRefHandle>,
    pub barometer_inhg:    Option<DataRefHandle>,
    pub engine_rpm:        Option<DataRefHandle>,
    pub manifold_inhg:     Option<DataRefHandle>,
    pub fuel_flow_kg_sec:  Option<DataRefHandle>,
    pub oil_press_psi:     Option<DataRefHandle>,
    pub oil_temp_degc:     Option<DataRefHandle>,
    pub egt_degc:          Option<DataRefHandle>,
    pub fuel_qty_kg:       Option<DataRefHandle>,
    pub bus_volts:         Option<DataRefHandle>,
    pub battery_amps:      Option<DataRefHandle>,
    pub suction_inhg:      Option<DataRefHandle>,
    pub nav1_hdef_dot:     Option<DataRefHandle>,
    pub nav1_vdef_dot:     Option<DataRefHandle>,
    pub nav1_obs_deg:      Option<DataRefHandle>,
    pub gps_dist_nm:       Option<DataRefHandle>,
    pub gps_bearing_deg:   Option<DataRefHandle>,
    pub ap_state_flags:    Option<DataRefHandle>,
    pub fd_pitch_deg:      Option<DataRefHandle>,
    pub fd_roll_deg:       Option<DataRefHandle>,
    pub ap_heading_bug_deg: Option<DataRefHandle>,
    pub ap_altitude_ft:    Option<DataRefHandle>,
    pub ap_vs_fpm:         Option<DataRefHandle>,
    pub com1_active_hz:    Option<DataRefHandle>,
    pub com1_standby_hz:   Option<DataRefHandle>,
    pub com2_active_hz:    Option<DataRefHandle>,
    pub nav1_active_hz:    Option<DataRefHandle>,
    pub nav1_standby_hz:   Option<DataRefHandle>,
    pub transponder_code:  Option<DataRefHandle>,
    pub transponder_mode:  Option<DataRefHandle>,
    pub outer_marker:      Option<DataRefHandle>,
    pub middle_marker:     Option<DataRefHandle>,
    pub inner_marker:      Option<DataRefHandle>,
    pub wind_dir_deg:      Option<DataRefHandle>,
    pub wind_speed_kt:     Option<DataRefHandle>,
    pub traffic_lat:       Option<DataRefHandle>,
    pub traffic_lon:       Option<DataRefHandle>,
    pub traffic_ele_m:     Option<DataRefHandle>,
    pub traffic_count:     Option<DataRefHandle>,
    pub hsi_source:        Option<DataRefHandle>,
}

// ── Internal message bus (flight-loop ↔ command-server thread) ────────────────

enum InternalMsg {
    Ack(SocketAddr),
    Command(Vec<u8>),   // raw JSON payload
    Reload,
}

// ── JSON command format ───────────────────────────────────────────────────────

#[derive(Deserialize)]
#[serde(tag = "cmd")]
enum Command {
    #[serde(rename = "set_dataref")]
    SetDataref { path: String, value: f64 },
    #[serde(rename = "swap_freq")]
    SwapFreq { radio: String },
}

// ── EfbPlugin ─────────────────────────────────────────────────────────────────

pub struct EfbPlugin {
    xplm:             Box<dyn XplmApi>,
    udp_socket:       Arc<UdpSocket>,
    tablet_addr:      Option<SocketAddr>,
    /// Last time a valid ACK was received from the tablet.
    pub(crate) last_ack_time: Instant,
    sequence:         AtomicU32,
    handles:          DataRefHandles,
    streaming_rate_hz: u8,
    cmd_rx:           Option<mpsc::Receiver<InternalMsg>>,
}

impl EfbPlugin {
    /// Create a plugin bound to the given socket (use port 0 in tests).
    pub fn new(xplm: Box<dyn XplmApi>, socket: UdpSocket) -> Self {
        EfbPlugin {
            xplm,
            udp_socket: Arc::new(socket),
            tablet_addr: None,
            last_ack_time: Instant::now(),
            sequence: AtomicU32::new(0),
            handles: DataRefHandles::default(),
            streaming_rate_hz: DEFAULT_HZ,
            cmd_rx: None,
        }
    }

    // ── Handle caching ────────────────────────────────────────────────────────

    /// (Re-)fetch all dataref handles. Call once at enable, and again on Reload.
    pub fn find_handles(&mut self) {
        macro_rules! find {
            ($field:ident, $path:expr) => {
                self.handles.$field = self.xplm.find_dataref($path);
                if self.handles.$field.is_none() {
                    self.xplm.log(&format!("EFB: dataref not found: {}", $path));
                }
            };
        }

        find!(latitude,          paths::LATITUDE);
        find!(longitude,         paths::LONGITUDE);
        find!(elevation_m,       paths::ELEVATION_M);
        find!(groundspeed_ms,    paths::GROUNDSPEED_MS);
        find!(pitch_deg,         paths::PITCH_DEG);
        find!(roll_deg,          paths::ROLL_DEG);
        find!(mag_heading_deg,   paths::MAG_HEADING_DEG);
        find!(ground_track_deg,  paths::GROUND_TRACK_DEG);
        find!(ias_kts,           paths::IAS_KTS);
        find!(true_airspeed_ms,  paths::TRUE_AIRSPEED_MS);
        find!(vvi_fpm,           paths::VVI_FPM);
        find!(turn_rate_deg_sec, paths::TURN_RATE_DEG_SEC);
        find!(slip_deg,          paths::SLIP_DEG);
        find!(oat_degc,          paths::OAT_DEGC);
        find!(barometer_inhg,    paths::BAROMETER_INHG);
        find!(engine_rpm,        paths::ENGINE_RPM);
        find!(manifold_inhg,     paths::MANIFOLD_INHG);
        find!(fuel_flow_kg_sec,  paths::FUEL_FLOW_KG_SEC);
        find!(oil_press_psi,     paths::OIL_PRESS_PSI);
        find!(oil_temp_degc,     paths::OIL_TEMP_DEGC);
        find!(egt_degc,          paths::EGT_DEGC);
        find!(fuel_qty_kg,       paths::FUEL_QTY_KG);
        find!(bus_volts,         paths::BUS_VOLTS);
        find!(battery_amps,      paths::BATTERY_AMPS);
        find!(suction_inhg,      paths::SUCTION_INHG);
        find!(nav1_hdef_dot,     paths::NAV1_HDEF_DOT);
        find!(nav1_vdef_dot,     paths::NAV1_VDEF_DOT);
        find!(nav1_obs_deg,      paths::NAV1_OBS_DEG);
        find!(gps_dist_nm,       paths::GPS_DIST_NM);
        find!(gps_bearing_deg,   paths::GPS_BEARING_DEG);
        find!(ap_state_flags,    paths::AP_STATE_FLAGS);
        find!(fd_pitch_deg,      paths::FD_PITCH_DEG);
        find!(fd_roll_deg,       paths::FD_ROLL_DEG);
        find!(ap_heading_bug_deg, paths::AP_HEADING_BUG_DEG);
        find!(ap_altitude_ft,    paths::AP_ALTITUDE_FT);
        find!(ap_vs_fpm,         paths::AP_VS_FPM);
        find!(com1_active_hz,    paths::COM1_ACTIVE_HZ);
        find!(com1_standby_hz,   paths::COM1_STANDBY_HZ);
        find!(com2_active_hz,    paths::COM2_ACTIVE_HZ);
        find!(nav1_active_hz,    paths::NAV1_ACTIVE_HZ);
        find!(nav1_standby_hz,   paths::NAV1_STANDBY_HZ);
        find!(transponder_code,  paths::TRANSPONDER_CODE);
        find!(transponder_mode,  paths::TRANSPONDER_MODE);
        find!(outer_marker,      paths::OUTER_MARKER);
        find!(middle_marker,     paths::MIDDLE_MARKER);
        find!(inner_marker,      paths::INNER_MARKER);
        find!(wind_dir_deg,      paths::WIND_DIR_DEG);
        find!(wind_speed_kt,     paths::WIND_SPEED_KT);
        find!(traffic_lat,       paths::TRAFFIC_LAT);
        find!(traffic_lon,       paths::TRAFFIC_LON);
        find!(traffic_ele_m,     paths::TRAFFIC_ELE_M);
        find!(traffic_count,     paths::TRAFFIC_COUNT);
        find!(hsi_source,        paths::HSI_SOURCE);
    }

    // ── Snapshot assembly ─────────────────────────────────────────────────────

    /// Read all datarefs and assemble a [`SimSnapshot`].
    pub fn read_snapshot(&self) -> SimSnapshot {
        let gf  = |h: Option<DataRefHandle>| h.map_or(0.0_f32, |h| self.xplm.get_float(h));
        let gd  = |h: Option<DataRefHandle>| h.map_or(0.0_f64, |h| self.xplm.get_double(h));
        let gi  = |h: Option<DataRefHandle>| h.map_or(0_i32,   |h| self.xplm.get_int(h));
        let gfa = |h: Option<DataRefHandle>, out: &mut [f32]| {
            if let Some(h) = h { self.xplm.get_float_array(h, 0, out); }
        };

        let mut egt      = [0f32; 6];
        let mut fuel     = [0f32; 2];
        let mut bus_a    = [0f32; 1];
        let mut bat_a    = [0f32; 1];
        let mut suc_a    = [0f32; 1];
        let mut eng_rpm  = [0f32; 1];
        let mut mani     = [0f32; 1];
        let mut ff       = [0f32; 1];
        let mut oil_p    = [0f32; 1];
        let mut oil_t    = [0f32; 1];

        gfa(self.handles.egt_degc,         &mut egt);
        gfa(self.handles.fuel_qty_kg,      &mut fuel);
        gfa(self.handles.bus_volts,        &mut bus_a);
        gfa(self.handles.battery_amps,     &mut bat_a);
        gfa(self.handles.suction_inhg,     &mut suc_a);
        gfa(self.handles.engine_rpm,       &mut eng_rpm);
        gfa(self.handles.manifold_inhg,    &mut mani);
        gfa(self.handles.fuel_flow_kg_sec, &mut ff);
        gfa(self.handles.oil_press_psi,    &mut oil_p);
        gfa(self.handles.oil_temp_degc,    &mut oil_t);

        let traffic_count = (gi(self.handles.traffic_count) as usize).min(20) as u8;
        let mut traffic_lat = [0f32; 20];
        let mut traffic_lon = [0f32; 20];
        let mut traffic_ele = [0f32; 20];
        if traffic_count > 0 {
            gfa(self.handles.traffic_lat,   &mut traffic_lat);
            gfa(self.handles.traffic_lon,   &mut traffic_lon);
            gfa(self.handles.traffic_ele_m, &mut traffic_ele);
        }

        // X-Plane true_airspeed is in m/s → convert to knots
        let tas_kts = gf(self.handles.true_airspeed_ms) * 1.943_84;

        SimSnapshot {
            latitude:          gd(self.handles.latitude),
            longitude:         gd(self.handles.longitude),
            elevation_m:       gd(self.handles.elevation_m),
            groundspeed_ms:    gf(self.handles.groundspeed_ms),
            pitch_deg:         gf(self.handles.pitch_deg),
            roll_deg:          gf(self.handles.roll_deg),
            mag_heading_deg:   gf(self.handles.mag_heading_deg),
            ground_track_deg:  gf(self.handles.ground_track_deg),
            ias_kts:           gf(self.handles.ias_kts),
            tas_kts,
            vvi_fpm:           gf(self.handles.vvi_fpm),
            turn_rate_deg_sec: gf(self.handles.turn_rate_deg_sec),
            slip_deg:          gf(self.handles.slip_deg),
            oat_degc:          gf(self.handles.oat_degc),
            barometer_inhg:    gf(self.handles.barometer_inhg),
            rpm:               eng_rpm[0],
            map_inhg:          mani[0],
            fuel_flow_kg_sec:  ff[0],
            oil_press_psi:     oil_p[0],
            oil_temp_degc:     oil_t[0],
            egt_degc:          egt,
            fuel_qty_kg:       fuel,
            bus_volts:         bus_a[0],
            battery_amps:      bat_a[0],
            suction_inhg:      suc_a[0],
            nav1_hdef_dot:     gf(self.handles.nav1_hdef_dot),
            nav1_vdef_dot:     gf(self.handles.nav1_vdef_dot),
            nav1_obs_deg:      gf(self.handles.nav1_obs_deg),
            gps_dist_nm:       gf(self.handles.gps_dist_nm),
            gps_bearing_deg:   gf(self.handles.gps_bearing_deg),
            ap_state_flags:    gi(self.handles.ap_state_flags),
            fd_pitch_deg:      gf(self.handles.fd_pitch_deg),
            fd_roll_deg:       gf(self.handles.fd_roll_deg),
            ap_heading_bug_deg: gf(self.handles.ap_heading_bug_deg),
            ap_altitude_ft:    gf(self.handles.ap_altitude_ft),
            ap_vs_fpm:         gf(self.handles.ap_vs_fpm),
            com1_active_hz:    gi(self.handles.com1_active_hz),
            com1_standby_hz:   gi(self.handles.com1_standby_hz),
            com2_active_hz:    gi(self.handles.com2_active_hz),
            nav1_active_hz:    gi(self.handles.nav1_active_hz),
            nav1_standby_hz:   gi(self.handles.nav1_standby_hz),
            transponder_code:  gi(self.handles.transponder_code),
            transponder_mode:  gi(self.handles.transponder_mode),
            outer_marker:      gi(self.handles.outer_marker) != 0,
            middle_marker:     gi(self.handles.middle_marker) != 0,
            inner_marker:      gi(self.handles.inner_marker) != 0,
            wind_dir_deg:      gf(self.handles.wind_dir_deg),
            wind_speed_kt:     gf(self.handles.wind_speed_kt),
            traffic_lat,
            traffic_lon,
            traffic_ele_m:     traffic_ele,
            traffic_count,
            hsi_source:        gi(self.handles.hsi_source),
        }
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    /// Returns `true` if the tablet has sent an ACK within the watchdog window.
    pub fn is_streaming_active(&self) -> bool {
        self.last_ack_time.elapsed() <= WATCHDOG_TIMEOUT
    }

    // ── Flight loop tick ──────────────────────────────────────────────────────

    /// Called from the X-Plane flight loop callback.
    ///
    /// Returns the seconds until next desired call (positive = wall-clock seconds).
    pub fn flight_loop_tick(&mut self) -> f32 {
        let interval = 1.0 / self.streaming_rate_hz as f32;

        // Drain any messages from the command server thread.
        self.drain_messages();

        if !self.is_streaming_active() {
            return interval; // watchdog tripped — keep ticking but don't stream
        }

        if let Some(addr) = self.tablet_addr {
            let snap = self.read_snapshot();
            let seq = self.sequence.fetch_add(1, Ordering::Relaxed);
            let pkt = encode_sim_data(seq, &snap);
            let _ = self.udp_socket.send_to(&pkt, addr);
        }

        interval
    }

    fn drain_messages(&mut self) {
        let rx = match &self.cmd_rx {
            Some(rx) => {
                // Collect all pending messages without blocking.
                let msgs: Vec<_> = rx.try_iter().collect();
                msgs
            }
            None => return,
        };
        for msg in rx {
            self.handle_internal_msg(msg);
        }
    }

    fn handle_internal_msg(&mut self, msg: InternalMsg) {
        match msg {
            InternalMsg::Ack(addr) => {
                self.last_ack_time = Instant::now();
                self.tablet_addr = Some(addr);
            }
            InternalMsg::Command(payload) => {
                self.handle_command(&payload);
            }
            InternalMsg::Reload => {
                self.find_handles();
            }
        }
    }

    // ── Incoming packet handling (also called directly in tests) ──────────────

    /// Process a raw UDP datagram received from the tablet.
    ///
    /// Malformed packets are silently dropped (never panics).
    pub fn handle_incoming_packet(&mut self, buf: &[u8], from: SocketAddr) {
        let result = decode_packet(buf);
        match result {
            Ok((_, PacketType::Ack, _)) => {
                self.last_ack_time = Instant::now();
                self.tablet_addr = Some(from);
            }
            Ok((_, PacketType::CommandJson, payload)) => {
                let payload = payload.to_vec();
                self.handle_command(&payload);
            }
            Ok((_, PacketType::Reload, _)) => {
                self.find_handles();
            }
            Ok((_, PacketType::SimData, _)) => {
                // SimData is outbound only — ignore inbound
            }
            Err(e) => {
                self.xplm.log(&format!("EFB: dropped packet: {e}"));
            }
        }
    }

    // ── Command execution ─────────────────────────────────────────────────────

    fn handle_command(&mut self, payload: &[u8]) {
        let Ok(text) = std::str::from_utf8(payload) else { return };
        let Ok(cmd)  = serde_json::from_str::<Command>(text) else { return };

        match cmd {
            Command::SetDataref { path, value } => {
                if let Some(h) = self.xplm.find_dataref(&path) {
                    self.xplm.set_float(h, value as f32);
                }
            }
            Command::SwapFreq { radio } => {
                self.swap_freq(&radio);
            }
        }
    }

    fn swap_freq(&mut self, radio: &str) {
        let (active_h, standby_h) = match radio {
            "COM1" => (self.handles.com1_active_hz,  self.handles.com1_standby_hz),
            "COM2" => (self.handles.com2_active_hz,  None),   // COM2 standby not cached; skip
            "NAV1" => (self.handles.nav1_active_hz,  self.handles.nav1_standby_hz),
            _      => return,
        };
        if let (Some(ah), Some(sh)) = (active_h, standby_h) {
            let a = self.xplm.get_int(ah);
            let s = self.xplm.get_int(sh);
            self.xplm.set_int(ah, s);
            self.xplm.set_int(sh, a);
        }
    }

    // ── Command server thread ─────────────────────────────────────────────────

    /// Spawn the UDP command-server thread.
    ///
    /// Receives packets on the shared socket and forwards decoded messages via
    /// the internal mpsc channel so the flight loop thread can process them.
    pub fn start_command_server(&mut self) {
        let (tx, rx) = mpsc::channel::<InternalMsg>();
        self.cmd_rx = Some(rx);

        let socket = Arc::clone(&self.udp_socket);
        std::thread::spawn(move || {
            let mut buf = [0u8; 65535 + efb_protocol::HEADER_LEN];
            loop {
                match socket.recv_from(&mut buf) {
                    Ok((n, from)) => {
                        let data = &buf[..n];
                        match decode_packet(data) {
                            Ok((_, PacketType::Ack, _)) => {
                                let _ = tx.send(InternalMsg::Ack(from));
                            }
                            Ok((_, PacketType::CommandJson, payload)) => {
                                let _ = tx.send(InternalMsg::Command(payload.to_vec()));
                            }
                            Ok((_, PacketType::Reload, _)) => {
                                let _ = tx.send(InternalMsg::Reload);
                            }
                            Ok(_) | Err(_) => {} // silently drop
                        }
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(1));
                    }
                    Err(_) => break, // socket closed — exit thread
                }
            }
        });
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::xplm_shim::{DataRefValue, MockXplm};
    use efb_protocol::{MAGIC, PROTOCOL_VERSION, HEADER_LEN};

    fn make_mock() -> MockXplm {
        let m = MockXplm::new();
        // Populate every dataref the plugin will try to read.
        m.set_dataref(paths::LATITUDE,          DataRefValue::Double(-26.1367));
        m.set_dataref(paths::LONGITUDE,         DataRefValue::Double(28.2411));
        m.set_dataref(paths::ELEVATION_M,       DataRefValue::Double(1694.0));
        m.set_dataref(paths::GROUNDSPEED_MS,    DataRefValue::Float(51.4));
        m.set_dataref(paths::PITCH_DEG,         DataRefValue::Float(-2.0));
        m.set_dataref(paths::ROLL_DEG,          DataRefValue::Float(5.0));
        m.set_dataref(paths::MAG_HEADING_DEG,   DataRefValue::Float(270.0));
        m.set_dataref(paths::GROUND_TRACK_DEG,  DataRefValue::Float(268.0));
        m.set_dataref(paths::IAS_KTS,           DataRefValue::Float(120.0));
        m.set_dataref(paths::TRUE_AIRSPEED_MS,  DataRefValue::Float(64.0)); // ~124 kts
        m.set_dataref(paths::VVI_FPM,           DataRefValue::Float(-200.0));
        m.set_dataref(paths::TURN_RATE_DEG_SEC, DataRefValue::Float(0.5));
        m.set_dataref(paths::SLIP_DEG,          DataRefValue::Float(1.0));
        m.set_dataref(paths::OAT_DEGC,          DataRefValue::Float(22.0));
        m.set_dataref(paths::BAROMETER_INHG,    DataRefValue::Float(29.92));
        m.set_dataref(paths::ENGINE_RPM,        DataRefValue::FloatArray(vec![2350.0]));
        m.set_dataref(paths::MANIFOLD_INHG,     DataRefValue::FloatArray(vec![24.0]));
        m.set_dataref(paths::FUEL_FLOW_KG_SEC,  DataRefValue::FloatArray(vec![0.025]));
        m.set_dataref(paths::OIL_PRESS_PSI,     DataRefValue::FloatArray(vec![65.0]));
        m.set_dataref(paths::OIL_TEMP_DEGC,     DataRefValue::FloatArray(vec![90.0]));
        m.set_dataref(paths::EGT_DEGC,          DataRefValue::FloatArray(vec![680.0,690.0,695.0,685.0,688.0,692.0]));
        m.set_dataref(paths::FUEL_QTY_KG,       DataRefValue::FloatArray(vec![75.0, 75.0]));
        m.set_dataref(paths::BUS_VOLTS,         DataRefValue::FloatArray(vec![28.0]));
        m.set_dataref(paths::BATTERY_AMPS,      DataRefValue::FloatArray(vec![5.0]));
        m.set_dataref(paths::SUCTION_INHG,      DataRefValue::FloatArray(vec![5.0]));
        m.set_dataref(paths::NAV1_HDEF_DOT,     DataRefValue::Float(0.5));
        m.set_dataref(paths::NAV1_VDEF_DOT,     DataRefValue::Float(-0.3));
        m.set_dataref(paths::NAV1_OBS_DEG,      DataRefValue::Float(180.0));
        m.set_dataref(paths::GPS_DIST_NM,       DataRefValue::Float(15.0));
        m.set_dataref(paths::GPS_BEARING_DEG,   DataRefValue::Float(90.0));
        m.set_dataref(paths::AP_STATE_FLAGS,    DataRefValue::Int(0));
        m.set_dataref(paths::FD_PITCH_DEG,      DataRefValue::Float(0.0));
        m.set_dataref(paths::FD_ROLL_DEG,       DataRefValue::Float(0.0));
        m.set_dataref(paths::AP_HEADING_BUG_DEG, DataRefValue::Float(270.0));
        m.set_dataref(paths::AP_ALTITUDE_FT,    DataRefValue::Float(5000.0));
        m.set_dataref(paths::AP_VS_FPM,         DataRefValue::Float(0.0));
        m.set_dataref(paths::COM1_ACTIVE_HZ,    DataRefValue::Int(118_025_000));
        m.set_dataref(paths::COM1_STANDBY_HZ,   DataRefValue::Int(121_500_000));
        m.set_dataref(paths::COM2_ACTIVE_HZ,    DataRefValue::Int(119_000_000));
        m.set_dataref(paths::NAV1_ACTIVE_HZ,    DataRefValue::Int(108_000_000));
        m.set_dataref(paths::NAV1_STANDBY_HZ,   DataRefValue::Int(109_900_000));
        m.set_dataref(paths::TRANSPONDER_CODE,  DataRefValue::Int(7000));
        m.set_dataref(paths::TRANSPONDER_MODE,  DataRefValue::Int(2));
        m.set_dataref(paths::OUTER_MARKER,      DataRefValue::Int(0));
        m.set_dataref(paths::MIDDLE_MARKER,     DataRefValue::Int(0));
        m.set_dataref(paths::INNER_MARKER,      DataRefValue::Int(0));
        m.set_dataref(paths::WIND_DIR_DEG,      DataRefValue::Float(240.0));
        m.set_dataref(paths::WIND_SPEED_KT,     DataRefValue::Float(15.0));
        m.set_dataref(paths::TRAFFIC_LAT,       DataRefValue::FloatArray(vec![-26.14, -26.20]));
        m.set_dataref(paths::TRAFFIC_LON,       DataRefValue::FloatArray(vec![28.25, 28.30]));
        m.set_dataref(paths::TRAFFIC_ELE_M,     DataRefValue::FloatArray(vec![1700.0, 1650.0]));
        m.set_dataref(paths::TRAFFIC_COUNT,     DataRefValue::Int(2));
        m.set_dataref(paths::HSI_SOURCE,        DataRefValue::Int(0));
        m
    }

    fn make_plugin(mock: MockXplm) -> EfbPlugin {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        EfbPlugin::new(Box::new(mock), socket)
    }

    #[test]
    fn flight_loop_reads_all_datarefs() {
        let mock = make_mock();
        let mut plugin = make_plugin(mock);
        plugin.find_handles();

        let snap = plugin.read_snapshot();

        assert!((snap.latitude  - -26.1367).abs() < 1e-6);
        assert!((snap.longitude -  28.2411).abs() < 1e-6);
        assert!((snap.ias_kts   - 120.0).abs() < 0.01);
        assert!((snap.rpm       - 2350.0).abs() < 0.01);
        assert!((snap.egt_degc[2] - 695.0).abs() < 0.01);
        assert!((snap.fuel_qty_kg[0] - 75.0).abs() < 0.01);
        assert_eq!(snap.traffic_count, 2);
        assert_eq!(snap.transponder_code, 7000);
        // TAS is converted from m/s: 64.0 * 1.94384 ≈ 124.4 kts
        assert!(snap.tas_kts > 120.0);
    }

    #[test]
    fn watchdog_pauses_streaming_after_5s() {
        let mock = make_mock();
        let mut plugin = make_plugin(mock);
        plugin.find_handles();

        // Simulate ACK timeout: push last_ack_time back 10 seconds.
        plugin.last_ack_time = Instant::now() - Duration::from_secs(10);

        assert!(!plugin.is_streaming_active(), "watchdog should have tripped");

        // Receiving a fresh ACK re-enables streaming.
        let addr: SocketAddr = "127.0.0.1:12345".parse().unwrap();
        let ack = build_ack_packet();
        plugin.handle_incoming_packet(&ack, addr);
        assert!(plugin.is_streaming_active(), "watchdog should have reset");
    }

    #[test]
    fn malformed_command_packet_silently_dropped() {
        let mock = make_mock();
        let mut plugin = make_plugin(mock);
        plugin.find_handles();

        let addr: SocketAddr = "127.0.0.1:12345".parse().unwrap();

        // Various malformed inputs — must not panic.
        plugin.handle_incoming_packet(&[], addr);
        plugin.handle_incoming_packet(&[0xFF; 10], addr);
        plugin.handle_incoming_packet(b"not a valid packet at all", addr);
        plugin.handle_incoming_packet(&[0u8; 4096], addr);
    }

    #[test]
    fn valid_set_dataref_command_calls_xplm_set() {
        let mock = make_mock();
        // Pre-populate the target dataref so find_dataref succeeds inside handle_command.
        mock.set_dataref("sim/cockpit/autopilot/heading_mag", DataRefValue::Float(0.0));

        let mut plugin = make_plugin(mock);
        plugin.find_handles();

        let json = br#"{"cmd":"set_dataref","path":"sim/cockpit/autopilot/heading_mag","value":270.0}"#;
        let pkt = build_command_json_packet(json);
        let addr: SocketAddr = "127.0.0.1:12345".parse().unwrap();
        plugin.handle_incoming_packet(&pkt, addr);

        // Verify the value was written back by reading through the same xplm trait object.
        let h = plugin.xplm.find_dataref("sim/cockpit/autopilot/heading_mag")
            .expect("dataref not found after set_dataref command");
        let v = plugin.xplm.get_float(h);
        assert!((v - 270.0).abs() < 0.1, "expected 270.0 got {v}");
    }

    #[test]
    fn reload_command_refreshes_handles() {
        let mock = make_mock();
        let mut plugin = make_plugin(mock);
        plugin.find_handles();

        // All handles should be populated after initial find.
        assert!(plugin.handles.latitude.is_some());

        // Simulate a Reload packet.
        let reload = build_reload_packet();
        let addr: SocketAddr = "127.0.0.1:12345".parse().unwrap();
        plugin.handle_incoming_packet(&reload, addr);

        // Handles should still be valid after reload.
        assert!(plugin.handles.latitude.is_some());
    }

    // ── Packet builders for tests ─────────────────────────────────────────────

    fn build_command_json_packet(json: &[u8]) -> Vec<u8> {
        let crc = efb_crc(json);
        let mut pkt = Vec::with_capacity(HEADER_LEN + json.len());
        pkt.extend_from_slice(&MAGIC.to_le_bytes());
        pkt.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
        pkt.push(0x02); // CommandJson
        pkt.extend_from_slice(&(json.len() as u16).to_le_bytes());
        pkt.extend_from_slice(&0u32.to_le_bytes()); // seq
        pkt.extend_from_slice(&crc.to_le_bytes());
        pkt.extend_from_slice(json);
        pkt
    }

    fn build_ack_packet() -> Vec<u8> {
        let crc = efb_crc(b"");
        let mut pkt = Vec::with_capacity(HEADER_LEN);
        pkt.extend_from_slice(&MAGIC.to_le_bytes());
        pkt.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
        pkt.push(0x03); // Ack
        pkt.extend_from_slice(&0u16.to_le_bytes()); // payload_len = 0
        pkt.extend_from_slice(&0u32.to_le_bytes()); // seq
        pkt.extend_from_slice(&crc.to_le_bytes());
        pkt
    }

    fn build_reload_packet() -> Vec<u8> {
        let crc = efb_crc(b"");
        let mut pkt = Vec::with_capacity(HEADER_LEN);
        pkt.extend_from_slice(&MAGIC.to_le_bytes());
        pkt.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
        pkt.push(0x04); // Reload
        pkt.extend_from_slice(&0u16.to_le_bytes());
        pkt.extend_from_slice(&0u32.to_le_bytes());
        pkt.extend_from_slice(&crc.to_le_bytes());
        pkt
    }

    /// CRC-32 (ISO 3309 — mirrors the one in efb_protocol).
    fn efb_crc(data: &[u8]) -> u32 {
        let mut crc: u32 = 0xFFFF_FFFF;
        for &byte in data {
            crc ^= u32::from(byte);
            for _ in 0..8 {
                crc = if crc & 1 == 1 { (crc >> 1) ^ 0xEDB8_8320 } else { crc >> 1 };
            }
        }
        !crc
    }
}
