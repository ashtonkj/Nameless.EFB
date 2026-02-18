//! Binary UDP packet codec for the EFB plugin ↔ Android app protocol.
//!
//! Datagram layout (little-endian):
//! ```text
//! [0..4]   magic     : u32  = 0xEFB1_2345
//! [4]      version   : u8   = 1
//! [5..7]   reserved  : u16  = 0
//! [7..N]   payload   : SimSnapshot fields (packed f32/f64)
//! [N..N+4] crc32     : u32  (CRC-32 of bytes 0..N)
//! ```

use dataref_schema::SimSnapshot;

pub const MAGIC: u32 = 0xEFB1_2345;
pub const VERSION: u8 = 1;

/// Total byte length of a serialised datagram.
pub const DATAGRAM_LEN: usize = 4 + 1 + 2 + PAYLOAD_LEN + 4;

// Payload: 15 × f32 (4 bytes) + 2 × f64 (8 bytes) = 76 bytes
const PAYLOAD_LEN: usize = 15 * 4 + 2 * 8;

/// Encode a [`SimSnapshot`] into a fixed-size UDP datagram.
///
/// Returns `None` if the internal buffer arithmetic is wrong (should never
/// happen; included for safety).
pub fn encode(snap: &SimSnapshot) -> [u8; DATAGRAM_LEN] {
    let mut buf = [0u8; DATAGRAM_LEN];
    let mut pos = 0;

    write_u32(&mut buf, &mut pos, MAGIC);
    buf[pos] = VERSION;
    pos += 1;
    write_u16(&mut buf, &mut pos, 0); // reserved

    // Payload — order must match decode()
    write_f32(&mut buf, &mut pos, snap.ias_kts);
    write_f32(&mut buf, &mut pos, snap.tas_kts);
    write_f32(&mut buf, &mut pos, snap.gs_kts);
    write_f32(&mut buf, &mut pos, snap.pitch_deg);
    write_f32(&mut buf, &mut pos, snap.roll_deg);
    write_f32(&mut buf, &mut pos, snap.hdg_mag_deg);
    write_f32(&mut buf, &mut pos, snap.hdg_true_deg);
    write_f32(&mut buf, &mut pos, snap.alt_msl_ft);
    write_f32(&mut buf, &mut pos, snap.alt_agl_ft);
    write_f32(&mut buf, &mut pos, snap.vs_fpm);
    write_f64(&mut buf, &mut pos, snap.lat_deg);
    write_f64(&mut buf, &mut pos, snap.lon_deg);
    write_f32(&mut buf, &mut pos, snap.baro_hpa);
    write_f32(&mut buf, &mut pos, snap.oat_c);
    write_f32(&mut buf, &mut pos, snap.eng1_power);
    write_f32(&mut buf, &mut pos, snap.fuel_kg);
    write_f32(&mut buf, &mut pos, snap.sim_time_sec);

    let crc = crc32(&buf[..pos]);
    write_u32(&mut buf, &mut pos, crc);

    debug_assert_eq!(pos, DATAGRAM_LEN);
    buf
}

/// Decode a UDP datagram into a [`SimSnapshot`].
///
/// Returns `None` on any parse error (wrong magic, version mismatch, bad CRC,
/// or wrong length) — malformed packets are silently dropped.
pub fn decode(data: &[u8]) -> Option<SimSnapshot> {
    if data.len() != DATAGRAM_LEN {
        return None;
    }

    let mut pos = 0;
    let magic = read_u32(data, &mut pos);
    if magic != MAGIC {
        return None;
    }

    let version = data[pos];
    pos += 1;
    if version != VERSION {
        return None;
    }
    pos += 2; // skip reserved

    let payload_end = pos + PAYLOAD_LEN;
    let expected_crc = crc32(&data[..payload_end]);
    let actual_crc = read_u32(data, &mut { payload_end });
    if expected_crc != actual_crc {
        return None;
    }

    // Re-read payload
    let mut p = 7usize;
    let snap = SimSnapshot {
        ias_kts: read_f32(data, &mut p),
        tas_kts: read_f32(data, &mut p),
        gs_kts: read_f32(data, &mut p),
        pitch_deg: read_f32(data, &mut p),
        roll_deg: read_f32(data, &mut p),
        hdg_mag_deg: read_f32(data, &mut p),
        hdg_true_deg: read_f32(data, &mut p),
        alt_msl_ft: read_f32(data, &mut p),
        alt_agl_ft: read_f32(data, &mut p),
        vs_fpm: read_f32(data, &mut p),
        lat_deg: read_f64(data, &mut p),
        lon_deg: read_f64(data, &mut p),
        baro_hpa: read_f32(data, &mut p),
        oat_c: read_f32(data, &mut p),
        eng1_power: read_f32(data, &mut p),
        fuel_kg: read_f32(data, &mut p),
        sim_time_sec: read_f32(data, &mut p),
    };
    Some(snap)
}

// ── helpers ──────────────────────────────────────────────────────────────────

fn write_u32(buf: &mut [u8], pos: &mut usize, v: u32) {
    buf[*pos..*pos + 4].copy_from_slice(&v.to_le_bytes());
    *pos += 4;
}

fn write_u16(buf: &mut [u8], pos: &mut usize, v: u16) {
    buf[*pos..*pos + 2].copy_from_slice(&v.to_le_bytes());
    *pos += 2;
}

fn write_f32(buf: &mut [u8], pos: &mut usize, v: f32) {
    buf[*pos..*pos + 4].copy_from_slice(&v.to_le_bytes());
    *pos += 4;
}

fn write_f64(buf: &mut [u8], pos: &mut usize, v: f64) {
    buf[*pos..*pos + 8].copy_from_slice(&v.to_le_bytes());
    *pos += 8;
}

fn read_u32(buf: &[u8], pos: &mut usize) -> u32 {
    let v = u32::from_le_bytes(buf[*pos..*pos + 4].try_into().unwrap());
    *pos += 4;
    v
}

fn read_f32(buf: &[u8], pos: &mut usize) -> f32 {
    let v = f32::from_le_bytes(buf[*pos..*pos + 4].try_into().unwrap());
    *pos += 4;
    v
}

fn read_f64(buf: &[u8], pos: &mut usize) -> f64 {
    let v = f64::from_le_bytes(buf[*pos..*pos + 8].try_into().unwrap());
    *pos += 8;
    v
}

/// Simple CRC-32 (ISO 3309 / Ethernet polynomial 0xEDB88320).
fn crc32(data: &[u8]) -> u32 {
    let mut crc: u32 = 0xFFFF_FFFF;
    for &byte in data {
        crc ^= u32::from(byte);
        for _ in 0..8 {
            crc = if crc & 1 == 1 {
                (crc >> 1) ^ 0xEDB8_8320
            } else {
                crc >> 1
            };
        }
    }
    !crc
}

#[cfg(test)]
mod tests {
    use super::*;

    fn round_trip(snap: SimSnapshot) -> Option<SimSnapshot> {
        let buf = encode(&snap);
        decode(&buf)
    }

    #[test]
    fn round_trip_default() {
        let snap = SimSnapshot::default();
        let decoded = round_trip(snap.clone()).expect("round-trip failed");
        assert!((decoded.baro_hpa - snap.baro_hpa).abs() < 0.001);
        assert!((decoded.lat_deg - snap.lat_deg).abs() < 1e-9);
    }

    #[test]
    fn round_trip_non_default_values() {
        let snap = SimSnapshot {
            ias_kts: 120.5,
            tas_kts: 125.0,
            gs_kts: 130.0,
            pitch_deg: -3.5,
            roll_deg: 15.0,
            hdg_mag_deg: 270.0,
            hdg_true_deg: 248.0,
            alt_msl_ft: 5500.0,
            alt_agl_ft: 1200.0,
            vs_fpm: -500.0,
            lat_deg: -26.1367,
            lon_deg: 28.2411,
            baro_hpa: 1023.0,
            oat_c: 22.0,
            eng1_power: 75.0,
            fuel_kg: 180.0,
            sim_time_sec: 36000.0,
        };
        let decoded = round_trip(snap.clone()).expect("round-trip failed");
        assert!((decoded.ias_kts - snap.ias_kts).abs() < 0.001);
        assert!((decoded.lat_deg - snap.lat_deg).abs() < 1e-9);
        assert!((decoded.lon_deg - snap.lon_deg).abs() < 1e-9);
    }

    #[test]
    fn wrong_length_returns_none() {
        assert!(decode(&[0u8; 10]).is_none());
    }

    #[test]
    fn wrong_magic_returns_none() {
        let mut buf = encode(&SimSnapshot::default());
        buf[0] ^= 0xFF; // corrupt magic
        assert!(decode(&buf).is_none());
    }

    #[test]
    fn wrong_version_returns_none() {
        let mut buf = encode(&SimSnapshot::default());
        buf[4] = 99; // corrupt version
        assert!(decode(&buf).is_none());
    }

    #[test]
    fn corrupted_payload_returns_none() {
        let mut buf = encode(&SimSnapshot::default());
        buf[10] ^= 0xFF; // flip a payload byte (CRC will fail)
        assert!(decode(&buf).is_none());
    }
}
