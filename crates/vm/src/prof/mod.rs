//! A sampling profiler for tuning the interpreter (plan S11-06), built only
//! with the `prof` cargo feature: `cargo build --release -p launcher
//! --features vm/prof`. Without it every hook below is an empty inline
//! function, so the shipped VM carries no trace of it.
//!
//! With the feature, `LAZYVM_PROF=<file>` turns it on. A sampler thread reads,
//! every few hundred microseconds, which Java method is running and in which
//! part of the VM (the fast loop, the full `step`, a native or the
//! collector), and the interpreter counts instructions by path. The report is
//! written to `<file>` when the VM ends; `LAZYVM_PROF_EVERY=<seconds>` also
//! writes cumulative raw snapshots, which `tools/prof.py` turns into the
//! report of a time window (a game's play after its loading, say).

use crate::Vm;
use crate::class::MethodRef;

#[cfg(feature = "prof")]
mod report;
#[cfg(feature = "prof")]
mod sampler;

/// Where the running thread is, as the sampler sees it.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[repr(u8)]
#[cfg_attr(not(feature = "prof"), allow(dead_code))]
pub(crate) enum Phase {
    Fast = 0,
    Step = 1,
    Native = 2,
    Gc = 3,
}

/// The running method changed (a call, a return, a handler found).
#[inline(always)]
pub(crate) fn enter(m: MethodRef) {
    #[cfg(feature = "prof")]
    sampler::enter(m);
    #[cfg(not(feature = "prof"))]
    let _ = m;
}

/// A frame was pushed for `m`.
#[inline(always)]
pub(crate) fn call(m: MethodRef) {
    enter(m);
    #[cfg(feature = "prof")]
    sampler::count_call();
}

#[inline(always)]
pub(crate) fn phase(p: Phase) {
    #[cfg(feature = "prof")]
    sampler::phase(p);
    #[cfg(not(feature = "prof"))]
    let _ = p;
}

/// `step` is about to run `op`.
#[inline(always)]
pub(crate) fn step(op: u8) {
    #[cfg(feature = "prof")]
    sampler::step(op);
    #[cfg(not(feature = "prof"))]
    let _ = op;
}

/// Counts the fast loop's instructions; zero-sized without the feature.
#[derive(Default)]
pub(crate) struct FastCount {
    #[cfg(feature = "prof")]
    n: u64,
}

impl FastCount {
    /// An instruction is about to be tried.
    #[inline(always)]
    pub(crate) fn tick(&mut self) {
        #[cfg(feature = "prof")]
        {
            self.n += 1;
        }
    }

    /// The loop returned; `taken` if after a backward branch, else the last
    /// instruction tried was left to `step`.
    #[inline(always)]
    pub(crate) fn done(self, taken: bool) {
        #[cfg(feature = "prof")]
        sampler::fast_run(if taken {
            self.n
        } else {
            self.n.saturating_sub(1)
        });
        #[cfg(not(feature = "prof"))]
        let _ = taken;
    }
}

/// A native method is about to run.
#[inline(always)]
pub(crate) fn native(m: MethodRef) {
    enter(m);
    phase(Phase::Native);
    #[cfg(feature = "prof")]
    sampler::count_native();
}

/// Times a collection; zero-sized without the feature.
pub(crate) struct GcTimer {
    #[cfg(feature = "prof")]
    started: std::time::Instant,
}

/// A collection starts; `done` on the result when it ends.
#[inline(always)]
pub(crate) fn gc_started() -> GcTimer {
    phase(Phase::Gc);
    GcTimer {
        #[cfg(feature = "prof")]
        started: std::time::Instant::now(),
    }
}

impl GcTimer {
    #[inline(always)]
    pub(crate) fn done(self) {
        #[cfg(feature = "prof")]
        sampler::gc_done(self.started.elapsed().as_nanos() as u64);
    }
}

impl Vm {
    /// Points the sampler at the top frame's method, if there is one.
    #[inline(always)]
    pub(crate) fn prof_top(&self) {
        #[cfg(feature = "prof")]
        if let Some(f) = self.frames.last() {
            enter(f.method);
        }
    }

    /// Starts the sampler if `LAZYVM_PROF` asks for it; once per process.
    #[inline(always)]
    pub(crate) fn prof_start(&self) {
        #[cfg(feature = "prof")]
        sampler::start();
    }

    /// Writes the report, if the profiler is on. Called where the VM ends.
    pub fn prof_report(&self) {
        #[cfg(feature = "prof")]
        report::write(self);
    }
}
