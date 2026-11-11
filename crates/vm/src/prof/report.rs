//! The profiler's output: a readable report, plus raw sample tables and a
//! method-name table that `tools/prof.py` reads to report a time window.
//!
//! Files, for `LAZYVM_PROF=<file>`: `<file>` (the report), `<file>.raw` (all
//! samples), `<file>.raw.<seconds>` (snapshots) and `<file>.names`. A raw line
//! is `method-id TAB phase TAB opcode TAB count`.

use std::collections::HashMap;
use std::fmt::Write;
use std::path::PathBuf;
use std::sync::atomic::Ordering::Relaxed;

use super::sampler::{
    CALLS, FAST_INSNS, FAST_RUNS, GC_NANOS, GCS, Key, NATIVES, NONE, SAMPLES, STEP_OPS, is_on,
};
use crate::Vm;
use crate::class::{ClassId, MethodRef};

const PHASES: [&str; 4] = ["fast", "step", "native", "gc"];
const TOP_OPS: usize = 30;
const TOP_METHODS: usize = 80;

fn base() -> Option<PathBuf> {
    std::env::var_os("LAZYVM_PROF").map(PathBuf::from)
}

fn with_suffix(suffix: &str) -> Option<PathBuf> {
    let mut p = base()?.into_os_string();
    p.push(suffix);
    Some(p.into())
}

fn samples() -> HashMap<Key, u64> {
    SAMPLES
        .lock()
        .ok()
        .and_then(|s| s.clone())
        .unwrap_or_default()
}

fn raw(samples: &HashMap<Key, u64>) -> String {
    let mut out = String::new();
    for (&(id, ph, op), &n) in samples {
        let _ = writeln!(out, "{id}\t{ph}\t{op}\t{n}");
    }
    out
}

/// Writes the cumulative samples so far as `<file>.raw.<seconds>`.
pub(super) fn snapshot(seconds: u64) {
    if let Some(p) = with_suffix(&format!(".raw.{seconds:05}")) {
        let _ = std::fs::write(p, raw(&samples()));
    }
}

/// Writes the report, the raw samples and the names, once.
pub(super) fn write(vm: &Vm) {
    if !is_on() {
        return;
    }
    let s = samples();
    let mut names = String::new();
    let mut ids: Vec<u64> = s.keys().map(|k| k.0).collect();
    ids.sort_unstable();
    ids.dedup();
    for id in ids {
        let _ = writeln!(names, "{id}\t{}", name(vm, id));
    }
    if let (Some(r), Some(n), Some(b)) = (with_suffix(".raw"), with_suffix(".names"), base()) {
        let _ = std::fs::write(r, raw(&s));
        let _ = std::fs::write(n, names);
        let _ = std::fs::write(b, text(vm, &s));
    }
}

fn name(vm: &Vm, id: u64) -> String {
    let m = MethodRef {
        class: ClassId((id >> 16) as u32),
        index: (id & 0xffff) as u16,
    };
    if id == NONE || m.class.0 as usize >= vm.classes.len() {
        return "<none>".into();
    }
    let method = vm.method(m);
    format!(
        "{}.{}{}",
        vm.class(m.class).java_name(),
        method.name,
        method.descriptor
    )
}

fn pct(n: u64, total: u64) -> f64 {
    100.0 * n as f64 / total.max(1) as f64
}

fn text(vm: &Vm, s: &HashMap<Key, u64>) -> String {
    let total: u64 = s.values().sum();
    let mut by_phase = [0u64; 4];
    let mut by_method: HashMap<u64, [u64; 4]> = HashMap::new();
    let mut by_op: HashMap<u8, u64> = HashMap::new();
    for (&(id, ph, op), &n) in s {
        let ph = usize::from(ph).min(3);
        by_phase[ph] += n;
        by_method.entry(id).or_default()[ph] += n;
        if ph == 1 {
            *by_op.entry(op).or_default() += n;
        }
    }
    let mut out = String::new();
    let _ = writeln!(out, "samples {total}");
    for (i, p) in PHASES.iter().enumerate() {
        let _ = write!(out, "{p} {:.1}%  ", pct(by_phase[i], total));
    }
    counters(&mut out);
    let _ = writeln!(out, "\n== step: opcode, count, share of time ==");
    let mut ops: Vec<(usize, u64)> = STEP_OPS
        .iter()
        .enumerate()
        .map(|(i, a)| (i, a.load(Relaxed)))
        .filter(|p| p.1 > 0)
        .collect();
    ops.sort_by_key(|o| std::cmp::Reverse(o.1));
    for (op, n) in ops.iter().take(TOP_OPS) {
        let t = by_op.get(&(*op as u8)).copied().unwrap_or(0);
        let _ = writeln!(out, "  {op:#04x} {n:>14}  {:5.1}%", pct(t, total));
    }
    let _ = writeln!(out, "\n== methods (self): total fast step native gc ==");
    let mut ms: Vec<_> = by_method.into_iter().collect();
    ms.sort_by_key(|m| std::cmp::Reverse(m.1.iter().sum::<u64>()));
    for (id, p) in ms.iter().take(TOP_METHODS) {
        let _ = write!(out, "  {:5.1}% ", pct(p.iter().sum(), total));
        for n in p {
            let _ = write!(out, " {:4.0}", pct(*n, total));
        }
        let _ = writeln!(out, "  {}", name(vm, *id));
    }
    out
}

fn counters(out: &mut String) {
    let runs = FAST_RUNS.load(Relaxed);
    let fast = FAST_INSNS.load(Relaxed);
    let step: u64 = STEP_OPS.iter().map(|a| a.load(Relaxed)).sum();
    let _ = writeln!(
        out,
        "\ninstructions: fast {fast} in {runs} runs ({:.1} per run), step {step} ({:.1}%)",
        fast as f64 / runs.max(1) as f64,
        pct(step, fast + step)
    );
    let _ = writeln!(
        out,
        "calls {}  natives {}  collections {} ({} ms)",
        CALLS.load(Relaxed),
        NATIVES.load(Relaxed),
        GCS.load(Relaxed),
        GC_NANOS.load(Relaxed) / 1_000_000
    );
}
