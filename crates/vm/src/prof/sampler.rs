//! The sampler thread and the counters the interpreter bumps.
//!
//! Counters are bumped by whichever thread holds the VM lock, one at a time,
//! so a relaxed load and store is enough: a locked add on every instruction
//! would distort what is being measured.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU8, AtomicU64, Ordering::Relaxed};
use std::sync::{Mutex, Once};
use std::time::{Duration, Instant};

use super::Phase;
use crate::class::MethodRef;

/// The method id the sampler reads; `NONE` before the first frame.
pub(super) const NONE: u64 = u64::MAX;
static CURRENT: AtomicU64 = AtomicU64::new(NONE);
static PHASE: AtomicU8 = AtomicU8::new(Phase::Fast as u8);
static OP: AtomicU8 = AtomicU8::new(0);
static ON: AtomicBool = AtomicBool::new(false);
static START: Once = Once::new();

pub(super) static STEP_OPS: [AtomicU64; 256] = [const { AtomicU64::new(0) }; 256];
pub(super) static FAST_INSNS: AtomicU64 = AtomicU64::new(0);
pub(super) static FAST_RUNS: AtomicU64 = AtomicU64::new(0);
pub(super) static CALLS: AtomicU64 = AtomicU64::new(0);
pub(super) static NATIVES: AtomicU64 = AtomicU64::new(0);
pub(super) static GCS: AtomicU64 = AtomicU64::new(0);
pub(super) static GC_NANOS: AtomicU64 = AtomicU64::new(0);

/// Samples by (method id, phase, opcode in `step`, else 0).
pub(super) type Key = (u64, u8, u8);
pub(super) static SAMPLES: Mutex<Option<HashMap<Key, u64>>> = Mutex::new(None);

const PERIOD: Duration = Duration::from_micros(250);

#[inline(always)]
fn bump(a: &AtomicU64, n: u64) {
    a.store(a.load(Relaxed).wrapping_add(n), Relaxed);
}

pub(super) fn id(m: MethodRef) -> u64 {
    (u64::from(m.class.0) << 16) | u64::from(m.index)
}

#[inline(always)]
pub(super) fn enter(m: MethodRef) {
    CURRENT.store(id(m), Relaxed);
}

pub(super) fn count_call() {
    bump(&CALLS, 1);
}

#[inline(always)]
pub(super) fn phase(p: Phase) {
    PHASE.store(p as u8, Relaxed);
}

#[inline(always)]
pub(super) fn step(op: u8) {
    OP.store(op, Relaxed);
    PHASE.store(Phase::Step as u8, Relaxed);
    bump(&STEP_OPS[usize::from(op)], 1);
}

#[inline(always)]
pub(super) fn fast_run(n: u64) {
    bump(&FAST_INSNS, n);
    bump(&FAST_RUNS, 1);
}

pub(super) fn count_native() {
    bump(&NATIVES, 1);
}

pub(super) fn gc_done(nanos: u64) {
    bump(&GCS, 1);
    bump(&GC_NANOS, nanos);
}

pub(super) fn is_on() -> bool {
    ON.load(Relaxed)
}

pub(super) fn start() {
    START.call_once(|| {
        if std::env::var_os("LAZYVM_PROF").is_none() {
            return;
        }
        let every = std::env::var("LAZYVM_PROF_EVERY")
            .ok()
            .and_then(|s| s.parse::<u64>().ok())
            .filter(|&s| s > 0);
        if let Ok(mut s) = SAMPLES.lock() {
            *s = Some(HashMap::new());
        }
        ON.store(true, Relaxed);
        std::thread::spawn(move || sample_forever(every));
    });
}

fn sample_forever(every: Option<u64>) {
    let t0 = Instant::now();
    let mut next = every;
    loop {
        std::thread::sleep(PERIOD);
        let ph = PHASE.load(Relaxed);
        let op = if ph == Phase::Step as u8 {
            OP.load(Relaxed)
        } else {
            0
        };
        let key = (CURRENT.load(Relaxed), ph, op);
        if let Ok(mut s) = SAMPLES.lock()
            && let Some(map) = s.as_mut()
        {
            *map.entry(key).or_default() += 1;
        }
        if let Some(at) = next
            && t0.elapsed().as_secs() >= at
        {
            super::report::snapshot(at);
            next = every.map(|e| at + e);
        }
    }
}
