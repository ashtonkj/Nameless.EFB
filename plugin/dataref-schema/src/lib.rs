//! Shared dataref struct definitions used by both the X-Plane plugin and the
//! efb-protocol codec crate.
//!
//! Field order and sizes are part of the wire protocol — do not reorder.

use serde::{Deserialize, Serialize};

/// A complete snapshot of all sim state streamed per UDP datagram.
#[repr(C)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SimSnapshot {
    // ── Position ──────────────────────────────────────────────────────────────
    pub latitude: f64,
    pub longitude: f64,
    pub elevation_m: f64,
    pub groundspeed_ms: f32,

    // ── Attitude ──────────────────────────────────────────────────────────────
    pub pitch_deg: f32,
    pub roll_deg: f32,
    pub mag_heading_deg: f32,
    pub ground_track_deg: f32,

    // ── Air data ──────────────────────────────────────────────────────────────
    pub ias_kts: f32,
    pub tas_kts: f32,
    pub vvi_fpm: f32,
    pub turn_rate_deg_sec: f32,
    pub slip_deg: f32,
    pub oat_degc: f32,
    pub barometer_inhg: f32,

    // ── Engine (index 0) ──────────────────────────────────────────────────────
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

    // ── Navigation ────────────────────────────────────────────────────────────
    pub nav1_hdef_dot: f32,
    pub nav1_vdef_dot: f32,
    pub nav1_obs_deg: f32,
    pub gps_dist_nm: f32,
    pub gps_bearing_deg: f32,

    // ── Autopilot ─────────────────────────────────────────────────────────────
    pub ap_state_flags: i32,
    pub fd_pitch_deg: f32,
    pub fd_roll_deg: f32,
    pub ap_heading_bug_deg: f32,
    pub ap_altitude_ft: f32,
    pub ap_vs_fpm: f32,

    // ── Radios ────────────────────────────────────────────────────────────────
    pub com1_active_hz: i32,
    pub com1_standby_hz: i32,
    pub com2_active_hz: i32,
    pub nav1_active_hz: i32,
    pub nav1_standby_hz: i32,
    pub transponder_code: i32,
    pub transponder_mode: i32,

    // ── Markers ───────────────────────────────────────────────────────────────
    pub outer_marker: bool,
    pub middle_marker: bool,
    pub inner_marker: bool,

    // ── Weather ───────────────────────────────────────────────────────────────
    pub wind_dir_deg: f32,
    pub wind_speed_kt: f32,

    // ── Traffic (up to 20 TCAS targets) ──────────────────────────────────────
    pub traffic_lat: [f32; 20],
    pub traffic_lon: [f32; 20],
    pub traffic_ele_m: [f32; 20],
    pub traffic_count: u8,

    // ── HSI source ────────────────────────────────────────────────────────────
    pub hsi_source: i32,
}

impl Default for SimSnapshot {
    fn default() -> Self {
        Self {
            latitude: 0.0,
            longitude: 0.0,
            elevation_m: 0.0,
            groundspeed_ms: 0.0,
            pitch_deg: 0.0,
            roll_deg: 0.0,
            mag_heading_deg: 0.0,
            ground_track_deg: 0.0,
            ias_kts: 0.0,
            tas_kts: 0.0,
            vvi_fpm: 0.0,
            turn_rate_deg_sec: 0.0,
            slip_deg: 0.0,
            oat_degc: 15.0,
            barometer_inhg: 29.92,
            rpm: 0.0,
            map_inhg: 0.0,
            fuel_flow_kg_sec: 0.0,
            oil_press_psi: 0.0,
            oil_temp_degc: 0.0,
            egt_degc: [0.0; 6],
            fuel_qty_kg: [0.0; 2],
            bus_volts: 0.0,
            battery_amps: 0.0,
            suction_inhg: 0.0,
            nav1_hdef_dot: 0.0,
            nav1_vdef_dot: 0.0,
            nav1_obs_deg: 0.0,
            gps_dist_nm: 0.0,
            gps_bearing_deg: 0.0,
            ap_state_flags: 0,
            fd_pitch_deg: 0.0,
            fd_roll_deg: 0.0,
            ap_heading_bug_deg: 0.0,
            ap_altitude_ft: 0.0,
            ap_vs_fpm: 0.0,
            com1_active_hz: 0,
            com1_standby_hz: 0,
            com2_active_hz: 0,
            nav1_active_hz: 0,
            nav1_standby_hz: 0,
            transponder_code: 0,
            transponder_mode: 0,
            outer_marker: false,
            middle_marker: false,
            inner_marker: false,
            wind_dir_deg: 0.0,
            wind_speed_kt: 0.0,
            traffic_lat: [0.0; 20],
            traffic_lon: [0.0; 20],
            traffic_ele_m: [0.0; 20],
            traffic_count: 0,
            hsi_source: 0,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_baro_is_standard_atmosphere() {
        let snap = SimSnapshot::default();
        assert!((snap.barometer_inhg - 29.92).abs() < 0.01);
    }

    #[test]
    fn default_oat_is_isa_sea_level() {
        let snap = SimSnapshot::default();
        assert!((snap.oat_degc - 15.0).abs() < 0.01);
    }
}
