//! A baseline JIT for x86-64 (plan S11-07): a hot method's bytecode becomes
//! machine code, one template per instruction, that works on the
//! interpreter's own frame. Locals and operand stack stay where the
//! interpreter keeps them, as Values, so the collector, exceptions, the
//! debugger and thread switches see exactly what they see when the method
//! is interpreted. Compiled code leaves for the interpreter at any
//! instruction it does not do (calls, returns, allocation, anything that
//! throws) and is entered again at the next one; a loop polls the safepoint.
//!
//! Only verified methods are compiled: the stack's shape at each pc must be
//! known (`flow.rs`). `-Xjit:` (or `LAZYVM_JIT`) picks the policy: `off`,
//! `all` (compile at the first run, for tests) or a number of runs.
#![allow(unsafe_code)]

mod arith;
mod asm;
mod call;
mod compile;
mod flow;
mod frame;
mod helpers;
mod inline;
mod layout;
mod mem;
mod ops;
mod run;
mod stack;

use crate::Vm;

pub(crate) use frame::{BACKWARD, CONTINUE};

/// Runs of a method's frames before it is compiled, by default.
pub const THRESHOLD: u32 = 200;

/// No entry at this pc.
const NONE: u32 = u32::MAX;

/// A method's JIT state, kept on its `Method`.
#[derive(Default)]
pub(crate) struct Slot {
    runs: u32,
    state: State,
    /// Exits from inlined code since it was compiled (`run::materialize`).
    exits: u32,
    /// Code replaced by a recompile. Kept: an exit or a transfer may still
    /// be returning into it.
    retired: Vec<Compiled>,
}

/// Exits from inlined code after which a method is compiled again, from the
/// call sites as they are now (one that saw a second receiver class is no
/// longer inlined); the last time without inlining.
const RECOMPILE_EXITS: u32 = 1000;
const RECOMPILES: usize = 2;

#[derive(Default)]
enum State {
    #[default]
    Cold,
    Compiled(Box<Compiled>),
    Failed,
}

pub(crate) struct Compiled {
    code: mem::Code,
    /// Per pc, the code offset of the instruction there, or NONE.
    entries: Vec<u32>,
    /// Per pc, the stack entries there.
    depth: Vec<u16>,
    /// Exits from inlined code: the frames to build (`run::materialize`).
    /// Read only through the addresses compiled code holds (boxed, so they
    /// stay put); owned here so they live as long as the code.
    #[allow(dead_code)]
    deopts: Box<[frame::Deopt]>,
    /// The call sites, held the same way (`call.rs`).
    #[allow(dead_code)]
    sites: Box<[call::CallSite]>,
    max_stack: usize,
    locals: usize,
}

// SAFETY: the type of compiled code's entry (`frame::Gen`); calling one is
// unsafe, and `run` says why its call is sound.
type Entry = unsafe extern "C" fn(*mut frame::Ctx, *const u8) -> u32;

/// A JIT policy, as `-Xjit:` and `LAZYVM_JIT` spell it: `off`, `all` (at the
/// first run) or a number of runs. None for anything else.
pub fn parse_policy(text: &str) -> Option<Option<u32>> {
    match text {
        "off" => Some(None),
        "all" => Some(Some(0)),
        n => n.parse().ok().map(Some),
    }
}

impl Slot {
    /// Counts an exit from inlined code; past `RECOMPILE_EXITS`, retires
    /// the code for `jit_tick` to compile again.
    fn exited(&mut self) {
        self.exits += 1;
        if self.exits < RECOMPILE_EXITS || self.retired.len() >= RECOMPILES {
            return;
        }
        if let State::Compiled(c) = std::mem::take(&mut self.state) {
            self.retired.push(*c);
        }
        self.exits = 0;
        self.runs = 0;
    }
}

impl Vm {
    /// Counts a run of the top frame's method and compiles it once hot.
    #[inline]
    pub(crate) fn jit_tick(&mut self) {
        let Some(limit) = self.options.jit else {
            return;
        };
        let Some(m) = self.frames.last().map(|f| f.method) else {
            return;
        };
        let slot = &mut self.class_mut(m.class).methods[usize::from(m.index)].jit;
        if !matches!(slot.state, State::Cold) {
            return;
        }
        slot.runs = slot.runs.saturating_add(1);
        if slot.runs <= limit {
            return;
        }
        let inlining = slot.retired.len() < RECOMPILES;
        let state = match compile::compile(self, m, inlining) {
            Some(c) => State::Compiled(Box::new(c)),
            None => State::Failed,
        };
        self.class_mut(m.class).methods[usize::from(m.index)]
            .jit
            .state = state;
    }
}
