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
mod flow;
mod frame;
mod helpers;
mod layout;
mod mem;
mod ops;
mod stack;

use crate::Vm;
use crate::class::{InitState, MethodRef, Resolved};
use crate::heap::Heap;
use crate::interp::Frame;
use crate::thread::Gil;
use classfile::Constant;

pub(crate) use frame::BACKWARD;

/// Runs of a method's frames before it is compiled, by default.
pub const THRESHOLD: u32 = 200;

/// No entry at this pc.
const NONE: u32 = u32::MAX;

/// A method's JIT state, kept on its `Method`.
#[derive(Default)]
pub(crate) struct Slot {
    runs: u32,
    state: State,
}

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
    pub(crate) fn compiled(&self) -> Option<&Compiled> {
        match &self.state {
            State::Compiled(c) => Some(c),
            _ => None,
        }
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
        let state = match compile(self, m) {
            Some(c) => State::Compiled(Box::new(c)),
            None => State::Failed,
        };
        self.class_mut(m.class).methods[usize::from(m.index)]
            .jit
            .state = state;
    }
}

fn compile(vm: &Vm, m: MethodRef) -> Option<Compiled> {
    let class = vm.class(m.class);
    if !class.checked {
        return None;
    }
    let method = vm.method(m);
    let code = method.code.as_ref()?;
    let pool = &class.file.as_ref()?.pool;
    let shapes = flow::shapes(code, pool)?;
    let cache = &class.cp_cache;
    let cached = |i: u16| cache.get(usize::from(i)).copied().flatten();
    let constant = |i: u16| -> Option<(u32, u32, u64)> {
        Some(match pool.get(i)? {
            Constant::Integer(v) => (layout::INT, *v as u32, 0),
            Constant::Float(v) => (layout::FLOAT, v.to_bits(), 0),
            Constant::Long(v) => (layout::LONG, 0, *v as u64),
            Constant::Double(v) => (layout::DOUBLE, 0, v.to_bits()),
            Constant::String(_) => match cached(i)? {
                Resolved::String(r) => (layout::REF, r.index(), u64::from(r.generation)),
                _ => return None,
            },
            _ => return None,
        })
    };
    let field = |i: u16| match cached(i)? {
        Resolved::Field {
            slot,
            is_static: false,
            ..
        } => Some(slot),
        _ => None,
    };
    let statics = |i: u16| match cached(i)? {
        Resolved::Field {
            class,
            slot,
            is_static: true,
        } => {
            let c = vm.class(class);
            let ok = c.state == InitState::Initialized && (slot as usize) < c.statics.len();
            ok.then(|| c.statics.as_ptr().wrapping_add(slot as usize) as usize)
        }
        _ => None,
    };
    let bc = &code.bytecode;
    let mut g = frame::Gen::new();
    let labels: Vec<_> = shapes
        .iter()
        .map(|s| s.as_ref().map(|_| g.a.label()))
        .collect();
    let env = ops::Env {
        code: bc,
        shapes: &shapes,
        labels: &labels,
        constant: &constant,
        field: &field,
        statics: &statics,
    };
    for (pc, shape) in shapes.iter().enumerate() {
        if let (Some(shape), Some(l)) = (shape, labels[pc]) {
            g.a.bind(l);
            ops::emit(&mut g, &env, pc, shape);
        }
    }
    let entries: Vec<u32> = labels
        .iter()
        .map(|l| l.and_then(|l| g.a.pos(l)).map_or(NONE, |p| p as u32))
        .collect();
    let depth = shapes
        .iter()
        .map(|s| s.as_ref().map_or(u16::MAX, |s| s.len() as u16))
        .collect();
    let bytes = g.finish()?;
    Some(Compiled {
        code: mem::Code::install(&bytes)?,
        entries,
        depth,
        max_stack: usize::from(code.max_stack),
        locals: usize::from(code.max_locals),
    })
}

/// Runs compiled code from the frame's pc. None (nothing done) if the frame
/// is not in a state the code can take: then the interpreter runs it. Else
/// the exit reason, with the frame's pc and stack where compiled code left.
pub(crate) fn run(c: &Compiled, f: &mut Frame, heap: &mut Heap, gil: &Gil) -> Option<u32> {
    let pc = f.pc;
    let entry = *c.entries.get(pc)?;
    if entry == NONE
        || usize::from(c.depth[pc]) != f.stack.len()
        || f.stack.capacity() < c.max_stack
        || f.locals.len() < c.locals
    {
        return None;
    }
    let mut ctx = frame::Ctx {
        locals: f.locals.as_mut_ptr(),
        stack: f.stack.as_mut_ptr(),
        heap,
        wanted: gil.wanted_flag(),
        pc: 0,
        sp: 0,
    };
    // `code.at(0)` is the function `frame::Gen` emits, with this signature;
    // `entry` is the code of an instruction whose stack shape matches the
    // frame (checked above); the locals and the stack have the room the code
    // addresses (`flow` bounds the stack by max_stack, and verified code
    // reads and writes locals below max_locals); the heap is borrowed for
    // the call alone.
    // SAFETY: as above.
    let reason = unsafe {
        let f_entry: Entry = std::mem::transmute::<*const u8, Entry>(c.code.at(0));
        f_entry(&mut ctx, c.code.at(entry as usize))
    };
    let sp = ctx.sp as usize;
    if sp > f.stack.capacity() {
        // Cannot happen: compiled code only exits with a shape from `flow`.
        std::process::abort();
    }
    // SAFETY: compiled code wrote a whole Value (tag and payload) to every
    // entry below `sp` that it pushed, and the rest were already there.
    unsafe { f.stack.set_len(sp) };
    f.pc = ctx.pc as usize;
    Some(reason)
}
