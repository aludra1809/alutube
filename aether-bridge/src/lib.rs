// SPDX-FileCopyrightText: 2026 Alutube contributors
// SPDX-License-Identifier: AGPL-3.0-only
//
// AetherBridge: a thin JNI layer over Aether's C FFI.
//
// Alutube's Kotlin side calls these Java_..._AetherBridge_* symbols so the
// app never touches C pointers or Aether internals directly. Each method:
//   1. marshals a UTF-8 JSON payload from Java,
//   2. invokes the corresponding aether::ffi entry point,
//   3. converts the returned caller-owned C string into a Java String,
//   4. frees the C string with aether_string_free, and
//   5. returns that JSON string unchanged (it already carries {"ok":true}
//      or {"ok":false,"error":...}; aether_job_poll replies are also
//      ok-wrapped by ffi.rs::respond).
//
// The Aether crate catches panics inside respond(); we additionally catch
// panics here so bridge mistakes cannot unwind through the JNI boundary.

use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};

use aether::ffi as af;

/// Reads a caller-owned reply produced by Aether's FFI into a Rust String,
/// then frees the C string with aether_string_free.
fn take_c_string(raw: *mut c_char) -> String {
    if raw.is_null() {
        return "{\"ok\":false,\"error\":\"null reply\"}".to_string();
    }
    let out = unsafe { CStr::from_ptr(raw) }.to_string_lossy().into_owned();
    unsafe { af::aether_string_free(raw) };
    out
}

fn to_jstring(env: &mut JNIEnv, text: &str) -> jstring {
    match env.new_string(text) {
        Ok(value) => value.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

/// Runs an FFI call that returns a caller-owned reply; converts panics into
/// an ok:false reply. The closure must not capture `env`.
fn guarded_call(call: impl FnOnce() -> String) -> String {
    match catch_unwind(AssertUnwindSafe(call)) {
        Ok(reply) => reply,
        Err(_) => "{\"ok\":false,\"error\":\"the bridge panicked\"}".to_string(),
    }
}

/// Reads a Java payload string and passes it to an FFI call taking
/// `*const c_char`. The closure must not capture `env`.
fn with_payload(env: &mut JNIEnv, raw: &JString, call: impl FnOnce(*const c_char) -> String) -> String {
    let payload_text = match env.get_string(raw) {
        Ok(value) => match value.to_str() {
            Ok(text) => text.to_owned(),
            Err(_) => {
                return "{\"ok\":false,\"error\":\"payload is not valid UTF-8\"}".to_string();
            }
        },
        Err(e) => {
            return format!("{{\"ok\":false,\"error\":\"cannot read payload: {e}\"}}");
        }
    };
    let c = match CString::new(payload_text) {
        Ok(value) => value,
        Err(_) => {
            return "{\"ok\":false,\"error\":\"payload contains a NUL byte\"}".to_string();
        }
    };
    call(c.as_ptr())
}

// ---------------------------------------------------------------------------
// Exported JNI methods (Java class: org.schabi.newpipe.aether.AetherBridge)
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_aether_NativeAetherBridge_version(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let mut env = env;
    let reply = guarded_call(|| take_c_string(af::aether_version()));
    to_jstring(&mut env, &reply)
}

#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_aether_NativeAetherBridge_identityOpen(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    payload: JString<'_>,
) -> jstring {
    let mut env = env;
    let reply = guarded_call(|| {
        with_payload(&mut env, &payload, |c| unsafe {
            take_c_string(af::aether_identity_open(c))
        })
    });
    to_jstring(&mut env, &reply)
}

#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_aether_NativeAetherBridge_identitySummary(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    id: jlong,
) -> jstring {
    let mut env = env;
    let reply = guarded_call(|| {
        take_c_string(unsafe { af::aether_identity_summary(id as u64) })
    });
    to_jstring(&mut env, &reply)
}

#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_aether_NativeAetherBridge_identityFree(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    id: jlong,
) -> jstring {
    let mut env = env;
    let reply = guarded_call(|| take_c_string(unsafe { af::aether_identity_free(id as u64) }));
    to_jstring(&mut env, &reply)
}

#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_aether_NativeAetherBridge_scanStart(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    identity: jlong,
    payload: JString<'_>,
) -> jstring {
    let mut env = env;
    let reply = guarded_call(|| {
        with_payload(&mut env, &payload, |c| unsafe {
            take_c_string(af::aether_scan_start(identity as u64, c))
        })
    });
    to_jstring(&mut env, &reply)
}

#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_aether_NativeAetherBridge_verifyStart(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    identity: jlong,
    payload: JString<'_>,
) -> jstring {
    let mut env = env;
    let reply = guarded_call(|| {
        with_payload(&mut env, &payload, |c| unsafe {
            take_c_string(af::aether_verify_start(identity as u64, c))
        })
    });
    to_jstring(&mut env, &reply)
}

#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_aether_NativeAetherBridge_tunnelStart(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    identity: jlong,
    payload: JString<'_>,
) -> jstring {
    let mut env = env;
    let reply = guarded_call(|| {
        with_payload(&mut env, &payload, |c| unsafe {
            take_c_string(af::aether_tunnel_start(identity as u64, c))
        })
    });
    to_jstring(&mut env, &reply)
}

#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_aether_NativeAetherBridge_coreStart(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    args: JString<'_>,
) -> jstring {
    let mut env = env;
    let reply = guarded_call(|| {
        with_payload(&mut env, &args, |c| unsafe {
            take_c_string(af::aether_core_start(c))
        })
    });
    to_jstring(&mut env, &reply)
}

#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_aether_NativeAetherBridge_jobPoll(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    id: jlong,
) -> jstring {
    let mut env = env;
    let reply = guarded_call(|| take_c_string(unsafe { af::aether_job_poll(id as u64) }));
    to_jstring(&mut env, &reply)
}

#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_aether_NativeAetherBridge_jobCancel(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    id: jlong,
) -> jstring {
    let mut env = env;
    let reply = guarded_call(|| take_c_string(unsafe { af::aether_job_cancel(id as u64) }));
    to_jstring(&mut env, &reply)
}

#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_aether_NativeAetherBridge_jobFree(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    id: jlong,
) -> jstring {
    let mut env = env;
    let reply = guarded_call(|| take_c_string(unsafe { af::aether_job_free(id as u64) }));
    to_jstring(&mut env, &reply)
}