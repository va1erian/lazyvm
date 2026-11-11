//! Instruction templates. Each instruction either runs inline or, for
//! everything else (calls, returns, allocation, exceptions, unresolved
//! constants), leaves for the interpreter at its own pc with the stack as
//! the interpreter left it, so `step` runs it as if compiled code never ran.
//! Anything that could fail (a zero divisor, a null or stale reference, an
//! index out of range) also leaves before changing anything.

use super::asm::{Alu, Cc, Label, R12, R13, RAX, RCX};
use super::frame::{AT, Gen, Helper, VmHelper, l, s};
use super::helpers;
use super::layout::{DOUBLE, FLOAT, HIGH, INT, LONG, LOW, REF, TAG, TOP};

/// What compiling needs to know beyond the bytecode, resolved beforehand.
pub(super) struct Env<'a> {
    pub code: &'a [u8],
    pub shapes: &'a super::flow::Shapes,
    pub labels: &'a [Option<Label>],
    /// The constant at a pool index as (tag, low word, high quad), if `ldc`
    /// may push it without help: a number or an interned string.
    pub constant: &'a dyn Fn(u16) -> Option<(u32, u32, u64)>,
    /// A resolved instance field's slot.
    pub field: &'a dyn Fn(u16) -> Option<u32>,
    /// The address of a resolved static field of an initialised class.
    pub statics: &'a dyn Fn(u16) -> Option<usize>,
    /// A resolved class's id.
    pub class: &'a dyn Fn(u16) -> Option<u32>,
    /// The class a `new`, `newarray` or `anewarray` at a pc makes, if
    /// compiled code can make it (`helpers::new_object`, `new_array`).
    pub new_class: &'a dyn Fn(usize) -> Option<u32>,
    /// Inlines the invoke at a pc, if it can (`inline.rs`).
    pub inline: &'a dyn Fn(&mut Gen, usize, &[bool]) -> bool,
    /// For an inlined method: where its returns go, the value in xmm0.
    pub ret: Option<Label>,
}

fn u2(code: &[u8], at: usize) -> u16 {
    u16::from_be_bytes([code[at], code[at + 1]])
}

fn tag(g: &mut Gen, at: usize, t: u32) {
    g.a.store_imm(false, R13, s(at) + TAG, t as i32);
}

/// Copies a whole Value between two places (locals/stack).
fn copy(g: &mut Gen, from_base: u8, from: i32, to_base: u8, to: i32) {
    g.a.movups_load(0, from_base, from);
    g.a.movups_store(to_base, to, 0);
}

fn push_const(g: &mut Gen, d: usize, t: u32, low: u32, high: u64) {
    tag(g, d, t);
    if t == LONG || t == DOUBLE {
        g.a.mov_imm64(RAX, high);
        g.a.store(true, R13, s(d) + HIGH, RAX);
    } else {
        g.a.store_imm(false, R13, s(d) + LOW, low as i32);
        if t == REF {
            g.a.store_imm(false, R13, s(d) + HIGH, high as i32);
        }
    }
}

/// Branch to `target`: a backward one polls the safepoint first.
fn branch(g: &mut Gen, env: &Env, pc: usize, target: usize, cc: Option<Cc>) -> bool {
    let Some(&Some(to)) = env.labels.get(target) else {
        return false;
    };
    let Some(Some(shape)) = env.shapes.get(target) else {
        return false;
    };
    if target > pc {
        match cc {
            Some(cc) => g.a.jcc(cc, to),
            None => g.a.jmp(to),
        }
        return true;
    }
    let skip = g.a.label();
    if let Some(cc) = cc {
        g.a.jcc(invert(cc), skip);
    }
    g.poll(target, shape.len());
    g.a.jmp(to);
    g.a.bind(skip);
    true
}

fn invert(cc: Cc) -> Cc {
    match cc {
        Cc::E => Cc::Ne,
        Cc::Ne => Cc::E,
        Cc::L => Cc::Ge,
        Cc::Ge => Cc::L,
        Cc::G => Cc::Le,
        Cc::Le => Cc::G,
        Cc::B => Cc::Ae,
        Cc::Ae => Cc::B,
        Cc::A => Cc::Be,
        Cc::Be => Cc::A,
        Cc::P => Cc::Np,
        Cc::Np => Cc::P,
    }
}

const BRANCH_CC: [Cc; 6] = [Cc::E, Cc::Ne, Cc::L, Cc::Ge, Cc::G, Cc::Le];

/// Emits the instruction at `pc`, whose stack shape is `sh`.
pub(super) fn emit(g: &mut Gen, env: &Env, pc: usize, sh: &[bool]) {
    if !inline(g, env, pc, sh) {
        g.exit(pc, sh.len(), AT);
    }
}

fn inline(g: &mut Gen, env: &Env, pc: usize, sh: &[bool]) -> bool {
    let code = env.code;
    let op = code[pc];
    let d = sh.len();
    match op {
        0x00 => {}
        0x01 => push_const(g, d, REF, 0, 0),
        0x02..=0x08 => push_const(g, d, INT, (i32::from(op) - 3) as u32, 0),
        0x09 | 0x0a => push_const(g, d, LONG, 0, u64::from(op - 0x09)),
        0x0b..=0x0d => push_const(g, d, FLOAT, f32::from(op - 0x0b).to_bits(), 0),
        0x0e | 0x0f => push_const(g, d, DOUBLE, 0, f64::from(op - 0x0e).to_bits()),
        0x10 => push_const(g, d, INT, i32::from(code[pc + 1] as i8) as u32, 0),
        0x11 => push_const(g, d, INT, i32::from(u2(code, pc + 1) as i16) as u32, 0),
        0x12..=0x14 => {
            let index = if op == 0x12 {
                u16::from(code[pc + 1])
            } else {
                u2(code, pc + 1)
            };
            let Some((t, low, high)) = (env.constant)(index) else {
                return false;
            };
            push_const(g, d, t, low, high);
        }
        0x15..=0x19 => copy(g, R12, l(usize::from(code[pc + 1])), R13, s(d)),
        0x1a..=0x2d => copy(g, R12, l(usize::from((op - 0x1a) % 4)), R13, s(d)),
        0x36..=0x4e => {
            let (i, kind) = if op <= 0x3a {
                (usize::from(code[pc + 1]), op - 0x36)
            } else {
                (usize::from((op - 0x3b) % 4), (op - 0x3b) / 4)
            };
            copy(g, R13, s(d - 1), R12, l(i));
            if kind == 1 || kind == 3 {
                g.a.store_imm(false, R12, l(i + 1) + TAG, TOP as i32);
            }
        }
        0x84 => {
            let i = usize::from(code[pc + 1]);
            g.a.alu_mem_imm(
                Alu::Add,
                false,
                R12,
                l(i) + LOW,
                i32::from(code[pc + 2] as i8),
            );
        }
        0x57..=0x5f => return super::stack::shuffle(g, op, sh),
        0x60..=0x83 => return super::arith::emit(g, op, pc, sh),
        0x85..=0x93 => super::arith::convert(g, op, d),
        0x94..=0x98 => super::arith::compare(g, op, d),
        0x99..=0x9e => {
            g.a.alu_mem_imm(Alu::Cmp, false, R13, s(d - 1) + LOW, 0);
            let off = i32::from(u2(code, pc + 1) as i16);
            return branch(
                g,
                env,
                pc,
                offset(pc, off),
                Some(BRANCH_CC[usize::from(op - 0x99)]),
            );
        }
        0x9f..=0xa6 => {
            g.a.load(false, RAX, R13, s(d - 2) + LOW);
            g.a.alu_mem(Alu::Cmp, false, RAX, R13, s(d - 1) + LOW);
            let cc = if op >= 0xa5 {
                BRANCH_CC[usize::from(op - 0xa5)]
            } else {
                BRANCH_CC[usize::from(op - 0x9f)]
            };
            let off = i32::from(u2(code, pc + 1) as i16);
            return branch(g, env, pc, offset(pc, off), Some(cc));
        }
        0xc6 | 0xc7 => {
            g.a.alu_mem_imm(Alu::Cmp, false, R13, s(d - 1) + LOW, 0);
            let off = i32::from(u2(code, pc + 1) as i16);
            let cc = if op == 0xc6 { Cc::E } else { Cc::Ne };
            return branch(g, env, pc, offset(pc, off), Some(cc));
        }
        0xa7 => {
            let off = i32::from(u2(code, pc + 1) as i16);
            return branch(g, env, pc, offset(pc, off), None);
        }
        0xb2 | 0xb3 => {
            let Some(addr) = (env.statics)(u2(code, pc + 1)) else {
                return false;
            };
            g.a.mov_imm64(RCX, addr as u64);
            if op == 0xb2 {
                copy(g, RCX, 0, R13, s(d));
            } else {
                copy(g, R13, s(d - 1), RCX, 0);
            }
        }
        0xb4 | 0xb5 => {
            let Some(slot) = (env.field)(u2(code, pc + 1)) else {
                return false;
            };
            let (f, at) = if op == 0xb4 {
                (helpers::get_field as Helper, d - 1)
            } else {
                (helpers::put_field as Helper, d - 2)
            };
            g.helper(f, at, slot, pc, d);
        }
        0x2e..=0x35 => g.helper(
            helpers::array_load as Helper,
            d - 2,
            u32::from(op - 0x2e),
            pc,
            d,
        ),
        0x4f..=0x52 | 0x55 | 0x56 => {
            g.helper(
                helpers::array_store as Helper,
                d - 3,
                u32::from(op - 0x4f),
                pc,
                d,
            );
        }
        0x53 | 0x54 => g.vm_helper(
            helpers::array_store_checked as VmHelper,
            d - 3,
            u32::from(op - 0x4f),
            pc,
            d,
        ),
        0xbb..=0xbd => {
            let Some(c) = (env.new_class)(pc) else {
                return false;
            };
            if op == 0xbb {
                g.vm_helper(helpers::new_object as VmHelper, d, c, pc, d);
            } else {
                g.vm_helper(helpers::new_array as VmHelper, d - 1, c, pc, d);
            }
        }
        0xbe => g.helper(helpers::array_length as Helper, d - 1, 0, pc, d),
        0xc0 | 0xc1 => {
            let Some(c) = (env.class)(u2(code, pc + 1)).filter(|c| c & helpers::INSTANCEOF == 0)
            else {
                return false;
            };
            let arg = if op == 0xc1 {
                c | helpers::INSTANCEOF
            } else {
                c
            };
            g.vm_helper(helpers::type_check as VmHelper, d - 1, arg, pc, d);
        }
        // An inlined method's return: the value to xmm0, on to its end.
        0xac..=0xb1 if env.ret.is_some() => {
            if op != 0xb1 {
                g.a.movups_load(0, R13, s(d - 1));
            }
            if let Some(end) = env.ret {
                g.a.jmp(end);
            }
        }
        0xb6..=0xb9 if (env.inline)(g, pc, sh) => {}
        // Calls and returns stay in compiled code when they can (`run.rs`).
        0xac..=0xb1 | 0xb6..=0xb9 => g.transfer(pc, d),
        _ => return false,
    }
    true
}

fn offset(pc: usize, off: i32) -> usize {
    (pc as i64 + i64::from(off)) as usize
}
