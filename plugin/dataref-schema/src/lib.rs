//! Shared dataref struct definitions used by both the X-Plane plugin and the
//! efb-protocol codec crate.

/// A snapshot of all sim state sent in each UDP datagram.
#[derive(Debug, Clone, PartialEq)]
pub struct SimSnapshot {
    /// Indicated airspeed, knots
    pub ias_kts: f32,
    /// True airspeed, knots
    pub tas_kts: f32,
    /// Ground speed, knots
    pub gs_kts: f32,
    /// Pitch, degrees (positive nose-up)
    pub pitch_deg: f32,
    /// Roll, degrees (positive right wing down)
    pub roll_deg: f32,
    /// Magnetic heading, degrees
    pub hdg_mag_deg: f32,
    /// True heading, degrees
    pub hdg_true_deg: f32,
    /// Altitude MSL, feet
    pub alt_msl_ft: f32,
    /// Altitude AGL, feet
    pub alt_agl_ft: f32,
    /// Vertical speed, feet per minute
    pub vs_fpm: f32,
    /// Latitude, decimal degrees
    pub lat_deg: f64,
    /// Longitude, decimal degrees
    pub lon_deg: f64,
    /// Barometric pressure setting, hPa
    pub baro_hpa: f32,
    /// Outside air temperature, Celsius
    pub oat_c: f32,
    /// Engine 1 RPM (piston) or N1 % (turbine)
    pub eng1_power: f32,
    /// Fuel quantity total, kg
    pub fuel_kg: f32,
    /// Sim time, seconds since midnight (local)
    pub sim_time_sec: f32,
}

impl Default for SimSnapshot {
    fn default() -> Self {
        Self {
            ias_kts: 0.0,
            tas_kts: 0.0,
            gs_kts: 0.0,
            pitch_deg: 0.0,
            roll_deg: 0.0,
            hdg_mag_deg: 0.0,
            hdg_true_deg: 0.0,
            alt_msl_ft: 0.0,
            alt_agl_ft: 0.0,
            vs_fpm: 0.0,
            lat_deg: 0.0,
            lon_deg: 0.0,
            baro_hpa: 1013.25,
            oat_c: 15.0,
            eng1_power: 0.0,
            fuel_kg: 0.0,
            sim_time_sec: 0.0,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_baro_is_standard_atmosphere() {
        let snap = SimSnapshot::default();
        assert!((snap.baro_hpa - 1013.25).abs() < 0.01);
    }
}
