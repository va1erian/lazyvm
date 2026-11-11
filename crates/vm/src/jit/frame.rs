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

use super::asm::{Alu, Asm, Cc, Label, R12, R13, R14, R15, RAX, RBP, RBX, RDX, RSP};
use super::layout::SIZE;

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
}

pub(super) const CTX_HEAP: i32 = 16;
pub(super) const CTX_WANTED: i32 = 24;
const CTX_PC: i32 = 32;
const CTX_SP: i32 = 36;

/// A heap helper (`helpers.rs`).
pub(super) type Helper =
    extern "C" fn(*mut crate::heap::Heap, *mut crate::value::Value, u32) -> u32;

/// Exit reasons.
pub(crate) const AT: u32 = 0;
pub(crate) const BACKWARD: u32 = 1;

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
        // boundary; 40 more restores it and leaves Windows its 32-byte
        // shadow space for helper calls.
        a.alu_reg_imm(Alu::Sub, true, RSP, 40);
        a.mov_rr(true, RBX, ARGS[0]);
        a.load(true, R12, RBX, 0);
        a.load(true, R13, RBX, 8);
        a.jmp_reg(ARGS[1]);
        let epilogue = a.label();
        Gen {
            a,
            epilogue,
            stubs: Vec::new(),
        }
    }

    /// Leaves for the interpreter now, at `pc` with `sp` stack entries.
    pub(super) fn exit(&mut self, pc: usize, sp: usize, reason: u32) {
        self.a.store_imm(false, RBX, CTX_PC, pc as i32);
        self.a.store_imm(false, RBX, CTX_SP, sp as i32);
        self.a.mov_imm64(RAX, u64::from(reason));
        self.a.jmp(self.epilogue);
    }

    /// A label that leaves for the interpreter at `pc` with `sp` entries,
    /// emitted after the method body.
    pub(super) fn exit_stub(&mut self, pc: usize, sp: usize, reason: u32) -> Label {
        let label = self.a.label();
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
        self.a.load(true, ARGS[0], RBX, CTX_HEAP);
        self.a.lea(ARGS[1], R13, s(at));
        self.a.mov_imm64(ARGS[2], u64::from(arg));
        self.a.mov_imm64(RAX, f as usize as u64);
        self.a.call_reg(RAX);
        self.a.alu_rr(Alu::Or, false, RAX, RAX);
        self.a.jcc(Cc::E, out);
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
        self.a.bind(self.epilogue);
        self.a.alu_reg_imm(Alu::Add, true, RSP, 40);
        for r in [RBP, R15, R14, R13, R12, RBX] {
            self.a.pop(r);
        }
        self.a.ret();
        self.a.finish()
    }
}
