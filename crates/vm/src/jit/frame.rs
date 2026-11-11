//! The code generator's frame: registers, the context it reads, exits, and
//! the shared prologue and epilogue. The instruction templates are in
//! `ops.rs`.
//!
//! Compiled code is `extern "C" fn(ctx: *mut Ctx, entry: *const u8) -> u32`.
//! It keeps `ctx` in `rbx`, the frame's locals in `r12` and its operand stack
//! in `r13` (callee-saved on both ABIs, so they survive helper calls), and
//! holds no Java value in a register between instructions: the stack's shape
//! is known at every pc (`flow.rs`), so each entry is at a fixed offset from
//! `r13`. It returns `AT` with `ctx.pc`/`ctx.sp` set where the interpreter
//! must take over, or `BACKWARD` at a loop's head when a safepoint is due.

use std::mem::{MaybeUninit, offset_of};

use super::asm::{Alu, Asm, Cc, Label, R12, R13, R14, R15, RAX, RBP, RBX, RDX, RSP};
use super::layout::SIZE;
use crate::class::MethodRef;
use crate::value::Value;

/// Values of scratch for inlined methods' locals and stacks (`inline.rs`).
pub(super) const SCRATCH: usize = 256;
/// Inlining depth: the prologue keeps a save slot for r12/r13 per level.
pub(super) const MAX_INLINE: usize = 3;
/// rsp-relative save slots for r12/r13, above Windows' shadow space.
const SAVES: i32 = 40;
const FRAME: i32 = SAVES + 16 * MAX_INLINE as i32;

/// The context compiled code runs with.
#[repr(C)]
pub(super) struct Ctx {
    pub locals: *mut crate::value::Value,
    pub stack: *mut crate::value::Value,
    pub heap: *mut crate::heap::Heap,
    /// The GIL's "a thread waits" flag (a `bool` byte).
    pub wanted: *const u8,
    pub pc: u32,
    pub sp: u32,
    /// The VM, for `run::transfer`; reached only through this pointer while
    /// compiled code runs.
    pub vm: *mut crate::Vm,
    /// `Vm::run`'s base: a return to below it is the interpreter's.
    pub base: u32,
    /// Inlined methods' locals and stacks while their bodies run.
    pub scratch: [MaybeUninit<Value>; SCRATCH],
}

pub(super) const CTX_SCRATCH: i32 = offset_of!(Ctx, scratch) as i32;

/// One inlined call the code being emitted is inside (outermost first):
/// where its frame would be, for `Deopt`.
pub(super) struct Inlined {
    pub method: MethodRef,
    /// The invoke in the method it is inlined into, and its length.
    pub invoke_pc: usize,
    pub invoke_len: usize,
    /// That method's stack entries at the invoke, and the call's arguments.
    pub parent_depth: usize,
    pub nargs: usize,
    /// Its locals and stack in the scratch area, in Values.
    pub locals_off: usize,
    pub nlocals: usize,
    pub stack_off: usize,
}

/// The frames an exit inside inlined code must build before the
/// interpreter takes over (`run::materialize`): the real frame's state at
/// the outermost invoke, then each inlined method's frame.
#[derive(Clone)]
pub(super) struct Deopt {
    pub root_pc: u32,
    pub root_next_pc: u32,
    pub root_stack: u32,
    pub levels: Vec<Level>,
}

#[derive(Clone)]
pub(super) struct Level {
    pub method: MethodRef,
    pub pc: u32,
    pub next_pc: u32,
    pub stack: u32,
    pub locals_off: u32,
    pub nlocals: u32,
    pub stack_off: u32,
}

pub(super) const CTX_HEAP: i32 = 16;
pub(super) const CTX_WANTED: i32 = 24;
const CTX_PC: i32 = 32;
const CTX_SP: i32 = 36;

const CTX_VM: i32 = offset_of!(Ctx, vm) as i32;

/// A heap helper (`helpers.rs`).
pub(super) type Helper =
    extern "C" fn(*mut crate::heap::Heap, *mut crate::value::Value, u32) -> u32;
/// A helper that needs the classes too: it gets the VM.
pub(super) type VmHelper = extern "C" fn(*mut crate::Vm, *mut crate::value::Value, u32) -> u32;

/// Exit reasons.
pub(crate) const AT: u32 = 0;
pub(crate) const BACKWARD: u32 = 1;
/// The frames changed (a call or return was done) and the top one is for
/// the interpreter; the frames are already as they must be.
pub(crate) const CONTINUE: u32 = 2;

/// The first two argument registers.
#[cfg(windows)]
pub(super) const ARGS: [u8; 3] = [super::asm::RCX, RDX, super::asm::R8];
#[cfg(not(windows))]
pub(super) const ARGS: [u8; 3] = [super::asm::RDI, super::asm::RSI, RDX];

/// A conditional exit compiled out of line: where control resumes in the
/// interpreter and why.
struct Stub {
    label: Label,
    pc: u32,
    sp: u32,
    reason: u32,
}

pub(super) struct Gen {
    pub a: Asm,
    epilogue: Label,
    stubs: Vec<Stub>,
    /// Exits after a transfer that changed the frames (`CONTINUE`).
    leaves: Vec<Label>,
    /// Whether calls may be inlined (`inline.rs`).
    pub inlining: bool,
    /// The inlined calls being emitted, outermost first.
    pub inline: Vec<Inlined>,
    /// Scratch Values in use by those calls.
    pub scratch_used: usize,
    /// Exits from inlined code, indexed by their stubs.
    pub deopts: Vec<Deopt>,
    /// (stub label, deopt index)
    deopt_stubs: Vec<(Label, u32)>,
}

/// The stack entry `i` (from the bottom) and local `i`, relative to r13/r12.
pub(super) fn s(i: usize) -> i32 {
    i as i32 * SIZE
}

pub(super) fn l(i: usize) -> i32 {
    i as i32 * SIZE
}

impl Gen {
    /// Starts the function: the prologue, then a jump to `entry`.
    pub(super) fn new() -> Gen {
        let mut a = Asm::default();
        for r in [RBX, R12, R13, R14, R15, RBP] {
            a.push(r);
        }
        // Six pushes and the return address leave rsp 8 off a 16-byte
        // boundary; FRAME (40 + 16 per inlining level) restores it, leaves
        // Windows its 32-byte shadow space for helper calls, and holds the
        // r12/r13 save slots of inlined calls above it.
        a.alu_reg_imm(Alu::Sub, true, RSP, FRAME);
        a.mov_rr(true, RBX, ARGS[0]);
        a.load(true, R12, RBX, 0);
        a.load(true, R13, RBX, 8);
        a.jmp_reg(ARGS[1]);
        let epilogue = a.label();
        Gen {
            a,
            epilogue,
            stubs: Vec::new(),
            leaves: Vec::new(),
            inline: Vec::new(),
            inlining: true,
            scratch_used: 0,
            deopts: Vec::new(),
            deopt_stubs: Vec::new(),
        }
    }

    /// Saves r12/r13 for inlining level `level`, or restores them.
    pub(super) fn save_bases(&mut self, level: usize) {
        let at = SAVES + 16 * level as i32;
        self.a.store(true, RSP, at, R12);
        self.a.store(true, RSP, at + 8, R13);
    }

    pub(super) fn restore_bases(&mut self, level: usize) {
        let at = SAVES + 16 * level as i32;
        self.a.load(true, R12, RSP, at);
        self.a.load(true, R13, RSP, at + 8);
    }

    /// The frames an exit at `pc` with `sp` entries inside the innermost
    /// inlined call must build.
    fn deopt(&mut self, pc: usize, sp: usize) -> u32 {
        let root = &self.inline[0];
        let mut levels = Vec::new();
        for (j, cur) in self.inline.iter().enumerate() {
            let (pc, next_pc, stack) = match self.inline.get(j + 1) {
                Some(n) => (
                    n.invoke_pc,
                    n.invoke_pc + n.invoke_len,
                    n.parent_depth - n.nargs,
                ),
                None => (pc, 0, sp),
            };
            levels.push(Level {
                method: cur.method,
                pc: pc as u32,
                next_pc: next_pc as u32,
                stack: stack as u32,
                locals_off: cur.locals_off as u32,
                nlocals: cur.nlocals as u32,
                stack_off: cur.stack_off as u32,
            });
        }
        self.deopts.push(Deopt {
            root_pc: root.invoke_pc as u32,
            root_next_pc: (root.invoke_pc + root.invoke_len) as u32,
            root_stack: (root.parent_depth - root.nargs) as u32,
            levels,
        });
        (self.deopts.len() - 1) as u32
    }

    /// Leaves for the interpreter now, at `pc` with `sp` stack entries.
    pub(super) fn exit(&mut self, pc: usize, sp: usize, reason: u32) {
        if !self.inline.is_empty() {
            let out = self.exit_stub(pc, sp, reason);
            self.a.jmp(out);
            return;
        }
        self.a.store_imm(false, RBX, CTX_PC, pc as i32);
        self.a.store_imm(false, RBX, CTX_SP, sp as i32);
        self.a.mov_imm64(RAX, u64::from(reason));
        self.a.jmp(self.epilogue);
    }

    /// A label that leaves for the interpreter at `pc` with `sp` entries,
    /// emitted after the method body.
    pub(super) fn exit_stub(&mut self, pc: usize, sp: usize, reason: u32) -> Label {
        let label = self.a.label();
        if !self.inline.is_empty() {
            // Inside inlined code: build its frames, then the interpreter
            // runs the instruction (any reason ends up as CONTINUE).
            let index = self.deopt(pc, sp);
            self.deopt_stubs.push((label, index));
            return label;
        }
        self.stubs.push(Stub {
            label,
            pc: pc as u32,
            sp: sp as u32,
            reason,
        });
        label
    }

    /// Polls the safepoint flag, leaving at `target` (with `sp` entries)
    /// if a thread waits; for every backward branch.
    pub(super) fn poll(&mut self, target: usize, sp: usize) {
        let out = self.exit_stub(target, sp, BACKWARD);
        self.a.load(true, RAX, RBX, CTX_WANTED);
        self.a.cmp_byte(RAX, 0, 0);
        self.a.jcc(Cc::Ne, out);
    }

    /// Calls `f(heap, &stack[at], arg) -> u32`, leaving at `pc` (with `sp`
    /// entries, nothing changed) when it returns 0.
    pub(super) fn helper(&mut self, f: Helper, at: usize, arg: u32, pc: usize, sp: usize) {
        let out = self.exit_stub(pc, sp, AT);
        self.call(CTX_HEAP, f as usize, at, arg, out);
    }

    /// `helper` for a helper that gets the VM.
    pub(super) fn vm_helper(&mut self, f: VmHelper, at: usize, arg: u32, pc: usize, sp: usize) {
        let out = self.exit_stub(pc, sp, AT);
        self.call(CTX_VM, f as usize, at, arg, out);
    }

    /// Calls `f(heap, &stack[at], arg) -> u32` and goes to `fail` on 0.
    pub(super) fn check(&mut self, f: Helper, at: usize, arg: u32, fail: Label) {
        self.call(CTX_HEAP, f as usize, at, arg, fail);
    }

    /// Calls `f(ctx[first], &stack[at], arg)`, going to `fail` on 0.
    fn call(&mut self, first: i32, f: usize, at: usize, arg: u32, fail: Label) {
        self.a.load(true, ARGS[0], RBX, first);
        self.a.lea(ARGS[1], R13, s(at));
        self.a.mov_imm64(ARGS[2], u64::from(arg));
        self.a.mov_imm64(RAX, f as u64);
        self.a.call_reg(RAX);
        self.a.alu_rr(Alu::Or, false, RAX, RAX);
        self.a.jcc(Cc::E, fail);
    }

    /// An invoke or return at `pc` with `sp` entries, through
    /// `run::transfer`: on into the next frame's code if it is compiled.
    /// Inside inlined code it is an exit: the interpreter does it.
    pub(super) fn transfer(&mut self, pc: usize, sp: usize) {
        if !self.inline.is_empty() {
            self.exit(pc, sp, AT);
            return;
        }
        let out = self.exit_stub(pc, sp, AT);
        let leave = self.label();
        self.a.mov_rr(true, ARGS[0], RBX);
        self.a.mov_imm64(ARGS[1], pc as u64);
        self.a.mov_imm64(ARGS[2], sp as u64);
        let f: extern "C" fn(*mut Ctx, u32, u32) -> usize = super::run::transfer;
        self.a.mov_imm64(RAX, f as usize as u64);
        self.a.call_reg(RAX);
        self.a.alu_reg_imm(Alu::Cmp, true, RAX, 1);
        self.a.jcc(Cc::B, out);
        self.a.jcc(Cc::E, leave);
        self.a.load(true, R12, RBX, 0);
        self.a.load(true, R13, RBX, 8);
        self.a.jmp_reg(RAX);
        self.leaves.push(leave);
    }

    fn label(&mut self) -> Label {
        self.a.label()
    }

    /// The stubs and the epilogue; the code bytes.
    pub(super) fn finish(mut self) -> Option<Vec<u8>> {
        for st in std::mem::take(&mut self.stubs) {
            self.a.bind(st.label);
            self.a.store_imm(false, RBX, CTX_PC, st.pc as i32);
            self.a.store_imm(false, RBX, CTX_SP, st.sp as i32);
            self.a.mov_imm64(RAX, u64::from(st.reason));
            self.a.jmp(self.epilogue);
        }
        for (label, index) in std::mem::take(&mut self.deopt_stubs) {
            self.a.bind(label);
            self.a.mov_rr(true, ARGS[0], RBX);
            self.a.mov_imm64(ARGS[1], u64::from(index));
            let f: extern "C" fn(*mut Ctx, u32) -> u32 = super::run::materialize;
            self.a.mov_imm64(RAX, f as usize as u64);
            self.a.call_reg(RAX);
            self.a.mov_imm64(RAX, u64::from(CONTINUE));
            self.a.jmp(self.epilogue);
        }
        for l in std::mem::take(&mut self.leaves) {
            self.a.bind(l);
            self.a.mov_imm64(RAX, u64::from(CONTINUE));
            self.a.jmp(self.epilogue);
        }
        self.a.bind(self.epilogue);
        self.a.alu_reg_imm(Alu::Add, true, RSP, FRAME);
        for r in [RBP, R15, R14, R13, R12, RBX] {
            self.a.pop(r);
        }
        self.a.ret();
        self.a.finish()
    }
}
