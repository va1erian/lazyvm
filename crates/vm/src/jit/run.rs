//! Entering compiled code, and calls and returns that stay in it.
//!
//! An invoke or a return in compiled code calls `transfer`, which does what
//! the fast loop does (`Vm::fast_transfer`: select the target, push or pop
//! the frame) and, when the frame now on top is compiled and can be entered
//! at its pc, points the context at that frame and hands back its code: the
//! compiled code jumps there. All compiled methods share one prologue and
//! epilogue (`frame::Gen`), so this does not grow the native stack, and an
//! exit from any of them returns to `Vm::jit_enter`. While compiled code
//! runs, the VM is reached only through the context's raw pointer: no Rust
//! reference to it is alive across the call.

use super::frame::{self, CONTINUE, Ctx};
use super::{Compiled, Entry, NONE, State};
use crate::Vm;
use crate::interp::Frame;

impl Compiled {
    /// The code for `f` at its pc, if the frame is in the state the code
    /// expects there: the stack's shape, and the room the code addresses.
    fn entry(&self, f: &Frame) -> Option<*const u8> {
        let at = *self.entries.get(f.pc)?;
        let ok = at != NONE
            && usize::from(self.depth[f.pc]) == f.stack.len()
            && f.stack.capacity() >= self.max_stack
            && f.locals.len() >= self.locals;
        ok.then(|| self.code.at(at as usize))
    }
}

impl Vm {
    /// The top frame's compiled code and entry, if any, with `ctx` pointing
    /// at that frame.
    fn jit_prepare(&mut self, ctx: &mut Ctx) -> Option<(*const u8, *const u8)> {
        let Vm {
            frames, classes, ..
        } = self;
        let f = frames.last_mut()?;
        let slot = &classes[f.method.class.0 as usize].methods[usize::from(f.method.index)].jit;
        let State::Compiled(c) = &slot.state else {
            return None;
        };
        let entry = c.entry(f)?;
        ctx.locals = f.locals.as_mut_ptr();
        ctx.stack = f.stack.as_mut_ptr();
        Some((c.code.at(0), entry))
    }

    /// Runs the top frame in compiled code if it has some and can be entered
    /// at its pc; None if not (the interpreter runs it). Else the exit
    /// reason: `AT` (the top frame stopped at an instruction for the
    /// interpreter), `BACKWARD` (a safepoint is due at a loop's head) or
    /// `CONTINUE` (frames changed; the top one is for the interpreter).
    pub(crate) fn jit_enter(&mut self, base: usize) -> Option<u32> {
        let mut ctx = Ctx {
            locals: std::ptr::null_mut(),
            stack: std::ptr::null_mut(),
            heap: std::ptr::null_mut(),
            wanted: self.gil.wanted_flag(),
            pc: 0,
            sp: 0,
            vm: std::ptr::null_mut(),
            base: base as u32,
            ret: 0,
            scratch: [const { std::mem::MaybeUninit::uninit() }; frame::SCRATCH],
        };
        let (func, entry) = self.jit_prepare(&mut ctx)?;
        let vm: *mut Vm = self;
        ctx.vm = vm;
        // SAFETY: a field pointer of the VM we hold uniquely; used by the
        // heap helpers only while compiled code runs.
        ctx.heap = unsafe { &raw mut (*vm).heap };
        // `func` is the shared entry `frame::Gen` emits, with this signature;
        // `entry` is the code of an instruction whose stack shape matches the
        // top frame (`Compiled::entry`); the frame's buffers have the room the
        // code addresses (`flow` bounds the stack by max_stack, and verified
        // code keeps to its max_locals). No reference to the VM is held
        // across the call, which reaches it only through `ctx`.
        // SAFETY: as above.
        let reason = unsafe {
            let code: Entry = std::mem::transmute::<*const u8, Entry>(func);
            code(&mut ctx, entry)
        };
        if reason == CONTINUE {
            return Some(reason);
        }
        let f = self.frames.last_mut()?;
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
}

/// An invoke or return at `pc` with `sp` stack entries, from compiled code:
/// the next frame's code (the context pointing at it), 1 when the frames
/// changed but the next one is for the interpreter (or a safepoint is due),
/// 0 when nothing was done and the interpreter must run the instruction.
pub(super) extern "C" fn transfer(ctx: *mut Ctx, pc: u32, sp: u32) -> usize {
    // SAFETY: compiled code passes its own context, whose `vm` is the VM
    // `jit_enter` holds uniquely and reaches only through this pointer.
    let (ctx, vm) = unsafe { (&mut *ctx, &mut *(*ctx).vm) };
    let Some(f) = vm.frames.last_mut() else {
        return 0;
    };
    if sp as usize > f.stack.capacity() {
        return 0;
    }
    f.pc = pc as usize;
    // SAFETY: compiled code wrote every entry below `sp` (as at an exit).
    unsafe { f.stack.set_len(sp as usize) };
    let Some(&op) = f.code.bytecode.get(pc as usize) else {
        return 0;
    };
    // A waiting thread gets its turn through the interpreter's safepoint.
    if vm.gil.wanted() || !vm.fast_transfer(op, ctx.base as usize) {
        return 0;
    }
    if let Some(entry) = continuation(vm, ctx, op) {
        return entry;
    }
    vm.jit_tick();
    match vm.jit_prepare(ctx) {
        Some((_, entry)) => entry as usize,
        None => 1,
    }
}

/// An exit inside inlined code (`inline.rs`), from compiled code: makes the
/// frames the inlined calls stand for, from the scratch area, so the
/// interpreter can run on from that instruction. The real frame stops at
/// the outermost invoke with its arguments popped, as a call leaves it.
pub(super) extern "C" fn materialize(ctx: *mut Ctx, deopt: *const frame::Deopt) -> u32 {
    // SAFETY: as in `transfer`. `deopt` is one of the records of the code
    // that called, which live as long as it does (a recompile retires both).
    let (ctx, vm, d) = unsafe { (&mut *ctx, &mut *(*ctx).vm, (*deopt).clone()) };
    let Some(m) = vm.frames.last().map(|f| f.method) else {
        std::process::abort();
    };
    vm.class_mut(m.class).methods[usize::from(m.index)]
        .jit
        .exited();
    let Some(f) = vm.frames.last_mut() else {
        std::process::abort();
    };
    if d.root_stack as usize > f.stack.capacity() {
        std::process::abort();
    }
    f.pc = d.root_pc as usize;
    f.next_pc = d.root_next_pc as usize;
    // SAFETY: compiled code wrote every entry below the invoke's arguments.
    unsafe { f.stack.set_len(d.root_stack as usize) };
    for level in &d.levels {
        if vm.push_frame_rebuilt(level.method).is_err() {
            std::process::abort();
        }
        let Some(f) = vm.frames.last_mut() else {
            std::process::abort();
        };
        let take = |i: u32| {
            // SAFETY: inlined code set every local of its level (arguments,
            // then Top) and every stack entry below its depth there.
            unsafe { ctx.scratch[i as usize].assume_init() }
        };
        for i in 0..level.nlocals.min(f.locals.len() as u32) {
            f.locals[i as usize] = take(level.locals_off + i);
        }
        for i in 0..level.stack {
            f.stack.push(take(level.stack_off + i));
        }
        f.pc = level.pc as usize;
        f.next_pc = level.next_pc as usize;
    }
    1
}

/// After an invoke, records where the caller goes on (`ctx.ret`); after a
/// return, that code if the caller is back at that call. It is the caller's
/// own code (or code it had before a recompile, which stays), entered at the
/// stack shape a return there leaves, so it needs no lookup.
fn continuation(vm: &mut Vm, ctx: &mut Ctx, op: u8) -> Option<usize> {
    if matches!(op, 0xb6..=0xb9) {
        if let [.., caller, _] = &mut vm.frames[..] {
            caller.jit_ret = ctx.ret;
            caller.jit_ret_pc = caller.next_pc;
        }
        return None;
    }
    let f = vm.frames.last_mut()?;
    if f.jit_ret == 0 || f.jit_ret_pc != f.pc {
        return None;
    }
    let entry = std::mem::take(&mut f.jit_ret);
    ctx.locals = f.locals.as_mut_ptr();
    ctx.stack = f.stack.as_mut_ptr();
    Some(entry)
}
