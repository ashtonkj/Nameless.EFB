//! Binary UDP packet codec for the EFB plugin ↔ Android app protocol.
//!
//! Every datagram starts with a fixed 17-byte header followed by the payload.
//!
//! Header layout (little-endian):
//! ```text
//! [0..4]    magic       : u32  = 0xEFB12345
//! [4..6]    version     : u16  = 1
//! [6]       packet_type : u8   (see PacketType)
//! [7..9]    payload_len : u16  (bytes)
//! [9..13]   sequence    : u32
//! [13..17]  checksum    : u32  CRC-32 of payload bytes
//! ```

use dataref_schema::SimSnapshot;

pub const MAGIC: u32 = 0xEFB1_2345;
pub const PROTOCOL_VERSION: u16 = 1;

/// Size of the fixed packet header in bytes.
pub const HEADER_LEN: usize = 17;

/// Maximum accepted payload length (64 KiB − 1).
pub const MAX_PAYLOAD_LEN: usize = 65535;

// ── PacketHeader ─────────────────────────────────────────────────────────────

/// Fixed-size packet header present at the start of every datagram.
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct PacketHeader {
    pub magic: u32,
    pub version: u16,
    pub packet_type: u8,
    pub payload_len: u16,
    pub sequence: u32,
    pub checksum: u32, // CRC-32 of payload bytes
}

// ── PacketType ───────────────────────────────────────────────────────────────

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PacketType {
    SimData     = 0x01, // plugin → tablet: SimSnapshot payload
    CommandJson = 0x02, // tablet → plugin: JSON command payload
    Ack         = 0x03, // tablet → plugin: heartbeat ACK
    Reload      = 0x04, // tablet → plugin: reload dataref list
}

impl PacketType {
    fn from_u8(v: u8) -> Option<Self> {
        match v {
            0x01 => Some(Self::SimData),
            0x02 => Some(Self::CommandJson),
            0x03 => Some(Self::Ack),
            0x04 => Some(Self::Reload),
            _ => None,
        }
    }
}

// ── ProtocolError ─────────────────────────────────────────────────────────────

#[derive(Debug, PartialEq, Eq)]
pub enum ProtocolError {
    TooShort,
    BadMagic,
    BadVersion,
    UnknownPacketType(u8),
    PayloadTooLarge,
    TruncatedPayload,
    BadChecksum,
}

impl std::fmt::Display for ProtocolError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::TooShort          => write!(f, "packet too short"),
            Self::BadMagic          => write!(f, "bad magic bytes"),
            Self::BadVersion        => write!(f, "unsupported protocol version"),
            Self::UnknownPacketType(t) => write!(f, "unknown packet type 0x{t:02X}"),
            Self::PayloadTooLarge   => write!(f, "payload exceeds 64 KiB limit"),
            Self::TruncatedPayload  => write!(f, "payload truncated"),
            Self::BadChecksum       => write!(f, "CRC-32 mismatch"),
        }
    }
}

// ── Public API ────────────────────────────────────────────────────────────────

/// Encode a [`SimSnapshot`] into a framed UDP datagram.
pub fn encode_sim_data(seq: u32, snapshot: &SimSnapshot) -> Vec<u8> {
    let payload = serialize_snapshot(snapshot);
    build_packet(seq, PacketType::SimData, &payload)
}

/// Decode any incoming datagram.
///
/// Returns `(header, packet_type, payload_slice)` on success.
/// Every validation failure is reported as a [`ProtocolError`]; callers
/// should log the error and silently drop the packet.
pub fn decode_packet(buf: &[u8]) -> Result<(PacketHeader, PacketType, &[u8]), ProtocolError> {
    if buf.len() < HEADER_LEN {
        return Err(ProtocolError::TooShort);
    }

    let magic   = read_u32(buf, 0);
    let version = read_u16(buf, 4);
    let ptype   = buf[6];
    let plen    = read_u16(buf, 7) as usize;
    let seq     = read_u32(buf, 9);
    let chk     = read_u32(buf, 13);

    if magic != MAGIC {
        return Err(ProtocolError::BadMagic);
    }
    if version != PROTOCOL_VERSION {
        return Err(ProtocolError::BadVersion);
    }
    let packet_type = PacketType::from_u8(ptype)
        .ok_or(ProtocolError::UnknownPacketType(ptype))?;
    if plen > MAX_PAYLOAD_LEN {
        return Err(ProtocolError::PayloadTooLarge);
    }
    if buf.len() < HEADER_LEN + plen {
        return Err(ProtocolError::TruncatedPayload);
    }

    let payload = &buf[HEADER_LEN..HEADER_LEN + plen];
    if crc32(payload) != chk {
        return Err(ProtocolError::BadChecksum);
    }

    let header = PacketHeader {
        magic,
        version,
        packet_type: ptype,
        payload_len: plen as u16,
        sequence: seq,
        checksum: chk,
    };

    Ok((header, packet_type, payload))
}

/// Verify the CRC-32 checksum recorded in `header` against `payload`.
pub fn verify_checksum(header: &PacketHeader, payload: &[u8]) -> bool {
    // Access packed fields via copy to avoid unaligned reference UB.
    let expected = header.checksum;
    crc32(payload) == expected
}

/// Decode a SimData payload back into a [`SimSnapshot`].
///
/// Used by tests and future Kotlin FFI bridge.
pub fn decode_sim_data(payload: &[u8]) -> Result<SimSnapshot, ProtocolError> {
    deserialize_snapshot(payload).ok_or(ProtocolError::TruncatedPayload)
}

// ── Internal helpers ──────────────────────────────────────────────────────────

fn build_packet(seq: u32, ptype: PacketType, payload: &[u8]) -> Vec<u8> {
    let checksum = crc32(payload);
    let mut pkt = Vec::with_capacity(HEADER_LEN + payload.len());
    pkt.extend_from_slice(&MAGIC.to_le_bytes());
    pkt.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
    pkt.push(ptype as u8);
    pkt.extend_from_slice(&(payload.len() as u16).to_le_bytes());
    pkt.extend_from_slice(&seq.to_le_bytes());
    pkt.extend_from_slice(&checksum.to_le_bytes());
    pkt.extend_from_slice(payload);
    pkt
}

// Serialize SimSnapshot fields in declaration order (all little-endian).
fn serialize_snapshot(s: &SimSnapshot) -> Vec<u8> {
    let mut v = Vec::with_capacity(512);
    // Position
    v.extend_from_slice(&s.latitude.to_le_bytes());
    v.extend_from_slice(&s.longitude.to_le_bytes());
    v.extend_from_slice(&s.elevation_m.to_le_bytes());
    v.extend_from_slice(&s.groundspeed_ms.to_le_bytes());
    // Attitude
    v.extend_from_slice(&s.pitch_deg.to_le_bytes());
    v.extend_from_slice(&s.roll_deg.to_le_bytes());
    v.extend_from_slice(&s.mag_heading_deg.to_le_bytes());
    v.extend_from_slice(&s.ground_track_deg.to_le_bytes());
    // Air data
    v.extend_from_slice(&s.ias_kts.to_le_bytes());
    v.extend_from_slice(&s.tas_kts.to_le_bytes());
    v.extend_from_slice(&s.vvi_fpm.to_le_bytes());
    v.extend_from_slice(&s.turn_rate_deg_sec.to_le_bytes());
    v.extend_from_slice(&s.slip_deg.to_le_bytes());
    v.extend_from_slice(&s.oat_degc.to_le_bytes());
    v.extend_from_slice(&s.barometer_inhg.to_le_bytes());
    // Engine
    v.extend_from_slice(&s.rpm.to_le_bytes());
    v.extend_from_slice(&s.map_inhg.to_le_bytes());
    v.extend_from_slice(&s.fuel_flow_kg_sec.to_le_bytes());
    v.extend_from_slice(&s.oil_press_psi.to_le_bytes());
    v.extend_from_slice(&s.oil_temp_degc.to_le_bytes());
    for x in &s.egt_degc   { v.extend_from_slice(&x.to_le_bytes()); }
    for x in &s.fuel_qty_kg { v.extend_from_slice(&x.to_le_bytes()); }
    v.extend_from_slice(&s.bus_volts.to_le_bytes());
    v.extend_from_slice(&s.battery_amps.to_le_bytes());
    v.extend_from_slice(&s.suction_inhg.to_le_bytes());
    // Navigation
    v.extend_from_slice(&s.nav1_hdef_dot.to_le_bytes());
    v.extend_from_slice(&s.nav1_vdef_dot.to_le_bytes());
    v.extend_from_slice(&s.nav1_obs_deg.to_le_bytes());
    v.extend_from_slice(&s.gps_dist_nm.to_le_bytes());
    v.extend_from_slice(&s.gps_bearing_deg.to_le_bytes());
    // Autopilot
    v.extend_from_slice(&s.ap_state_flags.to_le_bytes());
    v.extend_from_slice(&s.fd_pitch_deg.to_le_bytes());
    v.extend_from_slice(&s.fd_roll_deg.to_le_bytes());
    v.extend_from_slice(&s.ap_heading_bug_deg.to_le_bytes());
    v.extend_from_slice(&s.ap_altitude_ft.to_le_bytes());
    v.extend_from_slice(&s.ap_vs_fpm.to_le_bytes());
    // Radios
    v.extend_from_slice(&s.com1_active_hz.to_le_bytes());
    v.extend_from_slice(&s.com1_standby_hz.to_le_bytes());
    v.extend_from_slice(&s.com2_active_hz.to_le_bytes());
    v.extend_from_slice(&s.nav1_active_hz.to_le_bytes());
    v.extend_from_slice(&s.nav1_standby_hz.to_le_bytes());
    v.extend_from_slice(&s.transponder_code.to_le_bytes());
    v.extend_from_slice(&s.transponder_mode.to_le_bytes());
    // Markers (bool as u8)
    v.push(s.outer_marker as u8);
    v.push(s.middle_marker as u8);
    v.push(s.inner_marker as u8);
    // Weather
    v.extend_from_slice(&s.wind_dir_deg.to_le_bytes());
    v.extend_from_slice(&s.wind_speed_kt.to_le_bytes());
    // Traffic
    for x in &s.traffic_lat { v.extend_from_slice(&x.to_le_bytes()); }
    for x in &s.traffic_lon { v.extend_from_slice(&x.to_le_bytes()); }
    for x in &s.traffic_ele_m { v.extend_from_slice(&x.to_le_bytes()); }
    v.push(s.traffic_count);
    // HSI
    v.extend_from_slice(&s.hsi_source.to_le_bytes());
    v
}

#[allow(unused_assignments)] // p is advanced by macros; last increment is intentionally unused
fn deserialize_snapshot(buf: &[u8]) -> Option<SimSnapshot> {
    let mut p = 0usize;

    macro_rules! rd_f32 {
        () => {{ let v = f32::from_le_bytes(buf.get(p..p+4)?.try_into().ok()?); p += 4; v }};
    }
    macro_rules! rd_f64 {
        () => {{ let v = f64::from_le_bytes(buf.get(p..p+8)?.try_into().ok()?); p += 8; v }};
    }
    macro_rules! rd_i32 {
        () => {{ let v = i32::from_le_bytes(buf.get(p..p+4)?.try_into().ok()?); p += 4; v }};
    }
    macro_rules! rd_u8 {
        () => {{ let v = *buf.get(p)?; p += 1; v }};
    }

    let snap = SimSnapshot {
        latitude:          rd_f64!(),
        longitude:         rd_f64!(),
        elevation_m:       rd_f64!(),
        groundspeed_ms:    rd_f32!(),
        pitch_deg:         rd_f32!(),
        roll_deg:          rd_f32!(),
        mag_heading_deg:   rd_f32!(),
        ground_track_deg:  rd_f32!(),
        ias_kts:           rd_f32!(),
        tas_kts:           rd_f32!(),
        vvi_fpm:           rd_f32!(),
        turn_rate_deg_sec: rd_f32!(),
        slip_deg:          rd_f32!(),
        oat_degc:          rd_f32!(),
        barometer_inhg:    rd_f32!(),
        rpm:               rd_f32!(),
        map_inhg:          rd_f32!(),
        fuel_flow_kg_sec:  rd_f32!(),
        oil_press_psi:     rd_f32!(),
        oil_temp_degc:     rd_f32!(),
        egt_degc:          [rd_f32!(), rd_f32!(), rd_f32!(), rd_f32!(), rd_f32!(), rd_f32!()],
        fuel_qty_kg:       [rd_f32!(), rd_f32!()],
        bus_volts:         rd_f32!(),
        battery_amps:      rd_f32!(),
        suction_inhg:      rd_f32!(),
        nav1_hdef_dot:     rd_f32!(),
        nav1_vdef_dot:     rd_f32!(),
        nav1_obs_deg:      rd_f32!(),
        gps_dist_nm:       rd_f32!(),
        gps_bearing_deg:   rd_f32!(),
        ap_state_flags:    rd_i32!(),
        fd_pitch_deg:      rd_f32!(),
        fd_roll_deg:       rd_f32!(),
        ap_heading_bug_deg: rd_f32!(),
        ap_altitude_ft:    rd_f32!(),
        ap_vs_fpm:         rd_f32!(),
        com1_active_hz:    rd_i32!(),
        com1_standby_hz:   rd_i32!(),
        com2_active_hz:    rd_i32!(),
        nav1_active_hz:    rd_i32!(),
        nav1_standby_hz:   rd_i32!(),
        transponder_code:  rd_i32!(),
        transponder_mode:  rd_i32!(),
        outer_marker:      rd_u8!() != 0,
        middle_marker:     rd_u8!() != 0,
        inner_marker:      rd_u8!() != 0,
        wind_dir_deg:      rd_f32!(),
        wind_speed_kt:     rd_f32!(),
        traffic_lat: {
            let mut a = [0f32; 20];
            for x in &mut a { *x = rd_f32!(); }
            a
        },
        traffic_lon: {
            let mut a = [0f32; 20];
            for x in &mut a { *x = rd_f32!(); }
            a
        },
        traffic_ele_m: {
            let mut a = [0f32; 20];
            for x in &mut a { *x = rd_f32!(); }
            a
        },
        traffic_count: rd_u8!(),
        hsi_source:    rd_i32!(),
    };
    Some(snap)
}

// ── CRC-32 (ISO 3309 / Ethernet polynomial 0xEDB88320) ────────────────────────

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

// ── Raw header field readers (avoid unaligned reference to packed struct) ─────

fn read_u32(buf: &[u8], off: usize) -> u32 {
    u32::from_le_bytes(buf[off..off + 4].try_into().unwrap())
}
fn read_u16(buf: &[u8], off: usize) -> u16 {
    u16::from_le_bytes(buf[off..off + 2].try_into().unwrap())
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn make_snap() -> SimSnapshot {
        let mut s = SimSnapshot::default();
        s.latitude          = -26.1367;
        s.longitude         = 28.2411;
        s.elevation_m       = 1694.0;
        s.ias_kts           = 120.5;
        s.mag_heading_deg   = 270.0;
        s.barometer_inhg    = 29.92;
        s.oat_degc          = 22.0;
        s.egt_degc          = [680.0, 690.0, 695.0, 685.0, 688.0, 692.0];
        s.fuel_qty_kg       = [75.0, 75.0];
        s.traffic_count     = 2;
        s.traffic_lat[0]    = -26.14;
        s.traffic_lon[0]    = 28.25;
        s.traffic_lat[1]    = -26.20;
        s.traffic_lon[1]    = 28.30;
        s.transponder_code  = 7000;
        s.com1_active_hz    = 118_025_000;
        s
    }

    #[test]
    fn round_trip_sim_snapshot() {
        let original = make_snap();
        let pkt = encode_sim_data(1, &original);
        let (hdr, ptype, payload) = decode_packet(&pkt).expect("decode failed");
        assert_eq!(ptype, PacketType::SimData);
        assert_eq!({ hdr.sequence }, 1u32);

        let decoded = decode_sim_data(payload).expect("decode_sim_data failed");
        assert!((decoded.latitude  - original.latitude).abs()  < 1e-9);
        assert!((decoded.longitude - original.longitude).abs() < 1e-9);
        assert!((decoded.ias_kts   - original.ias_kts).abs()   < 0.001);
        assert!((decoded.barometer_inhg - original.barometer_inhg).abs() < 0.001);
        assert_eq!(decoded.traffic_count, original.traffic_count);
        assert!((decoded.egt_degc[2] - original.egt_degc[2]).abs() < 0.001);
        assert_eq!(decoded.transponder_code, original.transponder_code);
        assert_eq!(decoded.outer_marker, original.outer_marker);
    }

    #[test]
    fn rejects_wrong_magic() {
        let mut pkt = encode_sim_data(0, &SimSnapshot::default());
        pkt[0] ^= 0xFF;
        assert_eq!(decode_packet(&pkt).unwrap_err(), ProtocolError::BadMagic);
    }

    #[test]
    fn rejects_wrong_version() {
        let mut pkt = encode_sim_data(0, &SimSnapshot::default());
        pkt[4] = 99; // version field (bytes 4..6)
        assert_eq!(decode_packet(&pkt).unwrap_err(), ProtocolError::BadVersion);
    }

    #[test]
    fn rejects_truncated_payload() {
        let pkt = encode_sim_data(0, &SimSnapshot::default());
        // Truncate to just the header
        assert_eq!(decode_packet(&pkt[..HEADER_LEN]).unwrap_err(), ProtocolError::TruncatedPayload);
    }

    #[test]
    fn rejects_bad_checksum() {
        let mut pkt = encode_sim_data(0, &SimSnapshot::default());
        // Flip a byte in the payload region
        pkt[HEADER_LEN + 4] ^= 0xFF;
        assert_eq!(decode_packet(&pkt).unwrap_err(), ProtocolError::BadChecksum);
    }

    #[test]
    fn all_packet_types_encode_decode() {
        // SimData via encode_sim_data
        let pkt = encode_sim_data(42, &SimSnapshot::default());
        let (hdr, ptype, _) = decode_packet(&pkt).unwrap();
        assert_eq!(ptype, PacketType::SimData);
        assert_eq!({ hdr.magic }, MAGIC);
        assert_eq!({ hdr.sequence }, 42u32);

        // CommandJson, Ack, Reload — build manually
        for (expected, byte) in [
            (PacketType::CommandJson, 0x02u8),
            (PacketType::Ack,         0x03u8),
            (PacketType::Reload,      0x04u8),
        ] {
            let payload = b"{}";
            let pkt = build_packet(0, expected, payload);
            let (_, ptype, _) = decode_packet(&pkt).unwrap();
            assert_eq!(ptype, expected);
            assert_eq!(byte, expected as u8);
        }
    }

    #[test]
    fn verify_checksum_ok_and_fails() {
        let pkt = encode_sim_data(0, &SimSnapshot::default());
        let (hdr, _, payload) = decode_packet(&pkt).unwrap();
        assert!(verify_checksum(&hdr, payload));

        // Mutate the header checksum copy to force failure
        let bad_hdr = PacketHeader { checksum: hdr.checksum ^ 1, ..hdr };
        assert!(!verify_checksum(&bad_hdr, payload));
    }

    #[test]
    fn empty_buffer_returns_too_short() {
        assert_eq!(decode_packet(&[]).unwrap_err(), ProtocolError::TooShort);
    }
}
