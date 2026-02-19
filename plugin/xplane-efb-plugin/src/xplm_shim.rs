//! XPLM abstraction layer.
//!
//! All X-Plane SDK calls go through the `XplmApi` trait so that unit tests can
//! substitute `MockXplm` without a running simulator.

use std::collections::HashMap;
use std::sync::Mutex;

/// Opaque handle to a cached X-Plane dataref (pointer-sized).
pub type DataRefHandle = usize;

// ── DataRefValue (mock storage) ───────────────────────────────────────────────

/// Value stored in the mock shim for a single dataref.
#[derive(Debug, Clone)]
pub enum DataRefValue {
    Float(f32),
    Double(f64),
    Int(i32),
    FloatArray(Vec<f32>),
}

// ── Trait ─────────────────────────────────────────────────────────────────────

/// Abstraction over all XPLM dataref operations used by the plugin.
///
/// `Send + Sync` so the trait object can live in a global `OnceLock`.
pub trait XplmApi: Send + Sync {
    fn find_dataref(&self, path: &str) -> Option<DataRefHandle>;
    fn get_float(&self, handle: DataRefHandle) -> f32;
    fn get_double(&self, handle: DataRefHandle) -> f64;
    fn get_int(&self, handle: DataRefHandle) -> i32;
    /// Read up to `out.len()` floats starting at `offset`.
    fn get_float_array(&self, handle: DataRefHandle, offset: usize, out: &mut [f32]);
    fn set_float(&self, handle: DataRefHandle, value: f32);
    fn set_int(&self, handle: DataRefHandle, value: i32);
    fn log(&self, message: &str);
}

// ── MockXplm ─────────────────────────────────────────────────────────────────

struct MockInner {
    datarefs: HashMap<String, DataRefValue>,
    /// handle → canonical path (assigned on first `find_dataref` call)
    handles: Vec<String>,
    /// recorded set_float calls: (path, value)
    set_float_log: Vec<(String, f32)>,
    /// recorded set_int calls: (path, value)
    set_int_log: Vec<(String, i32)>,
    log_messages: Vec<String>,
}

/// Test implementation — returns configurable values, records writes.
pub struct MockXplm {
    inner: Mutex<MockInner>,
}

impl MockXplm {
    pub fn new() -> Self {
        MockXplm {
            inner: Mutex::new(MockInner {
                datarefs:     HashMap::new(),
                handles:      Vec::new(),
                set_float_log: Vec::new(),
                set_int_log:   Vec::new(),
                log_messages:  Vec::new(),
            }),
        }
    }

    /// Pre-populate a dataref value before calling `find_dataref`.
    pub fn set_dataref(&self, path: &str, value: DataRefValue) {
        self.inner.lock().unwrap().datarefs.insert(path.to_string(), value);
    }

    /// Snapshot the recorded `set_float` calls (path, value).
    pub fn set_float_calls(&self) -> Vec<(String, f32)> {
        self.inner.lock().unwrap().set_float_log.clone()
    }

    /// Snapshot the recorded `set_int` calls (path, value).
    pub fn set_int_calls(&self) -> Vec<(String, i32)> {
        self.inner.lock().unwrap().set_int_log.clone()
    }

    /// Snapshot all logged messages.
    pub fn log_messages(&self) -> Vec<String> {
        self.inner.lock().unwrap().log_messages.clone()
    }
}

impl Default for MockXplm {
    fn default() -> Self {
        Self::new()
    }
}

impl XplmApi for MockXplm {
    fn find_dataref(&self, path: &str) -> Option<DataRefHandle> {
        let mut g = self.inner.lock().unwrap();
        if !g.datarefs.contains_key(path) {
            return None;
        }
        // Reuse existing handle if already assigned.
        if let Some(idx) = g.handles.iter().position(|p| p == path) {
            return Some(idx);
        }
        let idx = g.handles.len();
        g.handles.push(path.to_string());
        Some(idx)
    }

    fn get_float(&self, handle: DataRefHandle) -> f32 {
        let g = self.inner.lock().unwrap();
        let path = g.handles.get(handle).cloned().unwrap_or_default();
        match g.datarefs.get(&path) {
            Some(DataRefValue::Float(v))  => *v,
            Some(DataRefValue::Double(v)) => *v as f32,
            Some(DataRefValue::Int(v))    => *v as f32,
            _ => 0.0,
        }
    }

    fn get_double(&self, handle: DataRefHandle) -> f64 {
        let g = self.inner.lock().unwrap();
        let path = g.handles.get(handle).cloned().unwrap_or_default();
        match g.datarefs.get(&path) {
            Some(DataRefValue::Double(v)) => *v,
            Some(DataRefValue::Float(v))  => *v as f64,
            Some(DataRefValue::Int(v))    => *v as f64,
            _ => 0.0,
        }
    }

    fn get_int(&self, handle: DataRefHandle) -> i32 {
        let g = self.inner.lock().unwrap();
        let path = g.handles.get(handle).cloned().unwrap_or_default();
        match g.datarefs.get(&path) {
            Some(DataRefValue::Int(v))   => *v,
            Some(DataRefValue::Float(v)) => *v as i32,
            _ => 0,
        }
    }

    fn get_float_array(&self, handle: DataRefHandle, offset: usize, out: &mut [f32]) {
        let g = self.inner.lock().unwrap();
        let path = g.handles.get(handle).cloned().unwrap_or_default();
        if let Some(DataRefValue::FloatArray(arr)) = g.datarefs.get(&path) {
            for (i, slot) in out.iter_mut().enumerate() {
                *slot = arr.get(offset + i).copied().unwrap_or(0.0);
            }
        }
    }

    fn set_float(&self, handle: DataRefHandle, value: f32) {
        let mut g = self.inner.lock().unwrap();
        let path = g.handles.get(handle).cloned().unwrap_or_default();
        g.datarefs.insert(path.clone(), DataRefValue::Float(value));
        g.set_float_log.push((path, value));
    }

    fn set_int(&self, handle: DataRefHandle, value: i32) {
        let mut g = self.inner.lock().unwrap();
        let path = g.handles.get(handle).cloned().unwrap_or_default();
        g.datarefs.insert(path.clone(), DataRefValue::Int(value));
        g.set_int_log.push((path, value));
    }

    fn log(&self, message: &str) {
        self.inner.lock().unwrap().log_messages.push(message.to_string());
    }
}

// ── RealXplm — only compiled in production (not test) builds ─────────────────

#[cfg(not(test))]
pub use real::RealXplm;

#[cfg(not(test))]
mod real {
    use super::{DataRefHandle, XplmApi};
    use std::ffi::CString;

    /// Production implementation — wraps raw XPLM extern calls.
    pub struct RealXplm;

    impl XplmApi for RealXplm {
        fn find_dataref(&self, path: &str) -> Option<DataRefHandle> {
            let c = CString::new(path).ok()?;
            let h = unsafe { crate::xplm_sys::XPLMFindDataRef(c.as_ptr()) };
            if h.is_null() { None } else { Some(h as usize) }
        }

        fn get_float(&self, handle: DataRefHandle) -> f32 {
            unsafe { crate::xplm_sys::XPLMGetDataf(handle as _) }
        }

        fn get_double(&self, handle: DataRefHandle) -> f64 {
            unsafe { crate::xplm_sys::XPLMGetDatad(handle as _) }
        }

        fn get_int(&self, handle: DataRefHandle) -> i32 {
            unsafe { crate::xplm_sys::XPLMGetDatai(handle as _) }
        }

        fn get_float_array(&self, handle: DataRefHandle, offset: usize, out: &mut [f32]) {
            unsafe {
                crate::xplm_sys::XPLMGetDatavf(
                    handle as _,
                    out.as_mut_ptr(),
                    offset as i32,
                    out.len() as i32,
                );
            }
        }

        fn set_float(&self, handle: DataRefHandle, value: f32) {
            unsafe { crate::xplm_sys::XPLMSetDataf(handle as _, value) }
        }

        fn set_int(&self, handle: DataRefHandle, value: i32) {
            unsafe { crate::xplm_sys::XPLMSetDatai(handle as _, value) }
        }

        fn log(&self, message: &str) {
            if let Ok(c) = CString::new(message) {
                unsafe { crate::xplm_sys::XPLMDebugString(c.as_ptr()) }
            }
        }
    }
}
