//! X-Plane 12 EFB plugin — stub.
//!
//! This crate compiles to a `.xpl` shared library loaded by X-Plane.
//! The full XPLM integration is implemented in Phase 2 (plan 02).
//! This stub satisfies the Phase 1 build requirement: the crate must compile
//! and `cargo test` must pass.

// Re-export shared types so integration tests can reference them.
pub use dataref_schema::SimSnapshot;
pub use efb_protocol::{DATAGRAM_LEN, MAGIC, VERSION};

/// UDP port on which the plugin streams datagrams to the EFB tablet.
pub const STREAM_PORT: u16 = 49100;

/// Default streaming rate in Hz.
pub const DEFAULT_HZ: u32 = 20;

/// Maximum streaming rate in Hz.
pub const MAX_HZ: u32 = 60;

/// Seconds without a tablet ACK before the watchdog pauses streaming.
pub const WATCHDOG_TIMEOUT_SEC: u64 = 5;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constants_are_sane() {
        assert!(DEFAULT_HZ <= MAX_HZ);
        assert!(WATCHDOG_TIMEOUT_SEC > 0);
        assert_eq!(STREAM_PORT, 49100);
    }
}
