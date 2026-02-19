//! X-Plane 12 EFB plugin.
//!
//! Compiles to a `.xpl` shared library loaded by X-Plane. The `XPLM*` entry
//! points below are only present in non-test builds; unit tests use `MockXplm`
//! and call `EfbPlugin` methods directly.

pub mod plugin;
pub mod xplm_shim;

// Raw X-Plane SDK extern declarations — only needed for production builds.
// Symbols are resolved at runtime by X-Plane when the .xpl is loaded.
#[cfg(not(test))]
pub(crate) mod xplm_sys {
    use std::ffi::{c_char, c_float, c_int, c_void};

    pub type XPLMDataRef = *mut c_void;

    extern "C" {
        pub fn XPLMFindDataRef(inDataRefName: *const c_char) -> XPLMDataRef;
        pub fn XPLMGetDataf(inDataRef: XPLMDataRef) -> c_float;
        pub fn XPLMGetDatad(inDataRef: XPLMDataRef) -> f64;
        pub fn XPLMGetDatai(inDataRef: XPLMDataRef) -> c_int;
        pub fn XPLMGetDatavf(
            inDataRef:  XPLMDataRef,
            outValues:  *mut c_float,
            inOffset:   c_int,
            inMax:      c_int,
        ) -> c_int;
        pub fn XPLMSetDataf(inDataRef: XPLMDataRef, inValue: c_float);
        pub fn XPLMSetDatai(inDataRef: XPLMDataRef, inValue: c_int);
        pub fn XPLMDebugString(inString: *const c_char);
        pub fn XPLMRegisterFlightLoopCallback(
            inFlightLoop: Option<
                unsafe extern "C" fn(f32, f32, c_int, *mut c_void) -> f32,
            >,
            inInterval: c_float,
            inRefcon:   *mut c_void,
        );
        pub fn XPLMUnregisterFlightLoopCallback(
            inFlightLoop: Option<
                unsafe extern "C" fn(f32, f32, c_int, *mut c_void) -> f32,
            >,
            inRefcon: *mut c_void,
        );
    }
}

// ── XPLM entry points (production only) ──────────────────────────────────────

#[cfg(not(test))]
mod entry {
    use super::plugin::{EfbPlugin, DEFAULT_HZ, STREAM_PORT};
    use super::xplm_shim::RealXplm;
    use std::ffi::{c_int, c_void, CString};
    use std::net::UdpSocket;
    use std::sync::{Mutex, OnceLock};

    static PLUGIN: OnceLock<Mutex<EfbPlugin>> = OnceLock::new();

    #[no_mangle]
    pub unsafe extern "C" fn XPluginStart(
        out_name: *mut std::ffi::c_char,
        out_sig:  *mut std::ffi::c_char,
        out_desc: *mut std::ffi::c_char,
    ) -> c_int {
        write_cstr(out_name, "Nameless EFB");
        write_cstr(out_sig,  "za.co.efb.plugin");
        write_cstr(out_desc, "Streams sim data to the Nameless EFB Android tablet");

        let socket = match UdpSocket::bind(format!("0.0.0.0:{STREAM_PORT}")) {
            Ok(s) => s,
            Err(e) => {
                log(&format!("EFB: failed to bind UDP socket: {e}"));
                return 0;
            }
        };
        if let Err(e) = socket.set_nonblocking(true) {
            log(&format!("EFB: set_nonblocking failed: {e}"));
            return 0;
        }

        let plugin = EfbPlugin::new(Box::new(RealXplm), socket);
        if PLUGIN.set(Mutex::new(plugin)).is_err() {
            log("EFB: PLUGIN already initialized");
            return 0;
        }

        log("EFB: XPluginStart OK");
        1
    }

    #[no_mangle]
    pub unsafe extern "C" fn XPluginStop() {
        log("EFB: XPluginStop");
        // Plugin state is dropped automatically via OnceLock; no explicit cleanup needed.
    }

    #[no_mangle]
    pub unsafe extern "C" fn XPluginEnable() -> c_int {
        if let Some(plugin) = PLUGIN.get() {
            let mut p = plugin.lock().unwrap();
            p.find_handles();
            p.start_command_server();

            super::xplm_sys::XPLMRegisterFlightLoopCallback(
                Some(flight_loop_cb),
                1.0 / DEFAULT_HZ as f32,
                std::ptr::null_mut(),
            );
            log("EFB: XPluginEnable OK");
            1
        } else {
            log("EFB: XPluginEnable — plugin not initialized");
            0
        }
    }

    #[no_mangle]
    pub unsafe extern "C" fn XPluginDisable() {
        super::xplm_sys::XPLMUnregisterFlightLoopCallback(
            Some(flight_loop_cb),
            std::ptr::null_mut(),
        );
        log("EFB: XPluginDisable");
    }

    #[no_mangle]
    pub unsafe extern "C" fn XPluginReceiveMessage(
        _from:  c_int,
        _msg:   c_int,
        _param: *mut c_void,
    ) {
        // No inter-plugin messages handled in v1.
    }

    unsafe extern "C" fn flight_loop_cb(
        _since_last_call:  f32,
        _since_last_floop: f32,
        _counter:          std::ffi::c_int,
        _refcon:           *mut c_void,
    ) -> f32 {
        if let Some(plugin) = PLUGIN.get() {
            if let Ok(mut p) = plugin.lock() {
                return p.flight_loop_tick();
            }
        }
        1.0 / DEFAULT_HZ as f32
    }

    fn log(msg: &str) {
        if let Ok(c) = CString::new(msg) {
            unsafe { super::xplm_sys::XPLMDebugString(c.as_ptr()) }
        }
    }

    unsafe fn write_cstr(dst: *mut std::ffi::c_char, s: &str) {
        let bytes = s.as_bytes();
        let len = bytes.len().min(255);
        std::ptr::copy_nonoverlapping(bytes.as_ptr() as *const i8, dst, len);
        *dst.add(len) = 0;
    }
}

// ── Re-exports used by integration tests ─────────────────────────────────────

pub use dataref_schema::SimSnapshot;
pub use efb_protocol::{MAGIC, PROTOCOL_VERSION};
pub use plugin::{DEFAULT_HZ, MAX_HZ, STREAM_PORT, WATCHDOG_TIMEOUT};
