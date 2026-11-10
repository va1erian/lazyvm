//! A tight loop for the instructions that dominate hot code: constants, local
//! loads and stores, `int` arithmetic, compares and branches, and array element
//! access. `step` stays the one full implementation of every instruction.
//!
//! The loop checks exactly what `step` checks and, when anything is off (an
//! operand of the wrong kind, a full or empty stack, a null or out-of-range
//! access, a branch outside the code, an instruction it does not know), returns
//! *before changing any state*. `step` then runs the same instruction and
//! raises the same error or exception, so no check is dropped and there is a
//! single place that decides what malformed code does.
//!
//! It never allocates, resolves a constant-pool entry or calls a method, so it
//! needs no collector root. Static field access only goes ahead for a class
//! already initialised (JVMS 5.5); any other state takes the full path. A taken backward
//! branch ends the run and polls for a safepoint, as `op_branch` does.

use super::Frame;
use super::fast_field::{get_field, get_static, put_field, put_static};
use super::ops_array::kind_matches;
use super::ops_branch::cond;
use crate::Vm;
use crate::class::Class;
use crate::heap::Heap;
use crate::value::{Ref, Value};

impl Vm {
    /// Executes simple instructions of the top frame until one needs the full
    /// path, then returns to the dispatch loop. After a taken backward branch
    /// it polls for a safepoint and carries on, so a hot loop stays in here;
    /// the frame is borrowed again afterwards, as a thread switch may have run
    /// other code meanwhile. A pending collection goes back to the dispatch loop.
    #[inline]
    pub(super) fn run_simple(&mut self) {
        loop {
            let Vm {
                frames,
                heap,
                classes,
                ..
            } = self;
            let Some(f) = frames.last_mut() else { return };
            if !run(f, heap, classes) {
                return;
            }
            self.safepoint();
            if self.heap.wants_gc() {
                return;
            }
        }
    }
}

fn int_at(f: &Frame, depth: usize) -> Option<i32> {
    match f.stack.len().checked_sub(depth + 1).map(|i| f.stack[i]) {
        Some(Value::Int(v)) => Some(v),
        _ => None,
    }
}

/// The reference `depth` entries below the top (null is `Some(None)`).
fn ref_at(f: &Frame, depth: usize) -> Option<Option<Ref>> {
    match f.stack.len().checked_sub(depth + 1).map(|i| f.stack[i]) {
        Some(Value::Ref(r)) => Some(r),
        _ => None,
    }
}

pub(super) fn can_push(f: &Frame) -> bool {
    f.stack.len() < usize::from(f.code.max_stack)
}

fn push_int(f: &mut Frame, v: i32, len: usize) -> bool {
    if !can_push(f) {
        return false;
    }
    f.stack.push(Value::Int(v));
    f.pc += len;
    true
}

/// `iload`/`aload` of local `i`; `want_ref` picks the kind.
fn load(f: &mut Frame, i: usize, want_ref: bool, len: usize) -> bool {
    let Some(&v) = f.locals.get(i) else {
        return false;
    };
    let ok = match v {
        Value::Int(_) => !want_ref,
        Value::Ref(_) => want_ref,
        _ => false,
    };
    if !ok || !can_push(f) {
        return false;
    }
    f.stack.push(v);
    f.pc += len;
    true
}

/// `istore`/`astore` into local `i`, with `Frame::set_local`'s handling of a
/// wide value in the slot before.
fn store(f: &mut Frame, i: usize, want_ref: bool, len: usize) -> bool {
    let ok = match f.stack.last() {
        Some(Value::Int(_)) => !want_ref,
        Some(Value::Ref(_)) => want_ref,
        _ => false,
    };
    if !ok || i >= f.locals.len() {
        return false;
    }
    let Some(v) = f.stack.pop() else { return false };
    if i > 0 && f.locals[i - 1].is_wide() {
        f.locals[i - 1] = Value::Top;
    }
    f.locals[i] = v;
    f.pc += len;
    true
}

fn iinc(f: &mut Frame, i: usize, delta: i32) -> bool {
    let Some(Value::Int(v)) = f.locals.get(i).copied() else {
        return false;
    };
    if i > 0 && f.locals[i - 1].is_wide() {
        f.locals[i - 1] = Value::Top;
    }
    f.locals[i] = Value::Int(v.wrapping_add(delta));
    f.pc += 3;
    true
}

/// Two `int`s on the stack replaced by `r(a, b)`; `None` leaves the stack alone.
fn binary(f: &mut Frame, r: impl FnOnce(i32, i32) -> Option<i32>) -> bool {
    let (Some(b), Some(a)) = (int_at(f, 0), int_at(f, 1)) else {
        return false;
    };
    let Some(v) = r(a, b) else { return false };
    f.stack.pop();
    f.stack.pop();
    f.stack.push(Value::Int(v));
    f.pc += 1;
    true
}

fn unary(f: &mut Frame, r: impl FnOnce(i32) -> i32) -> bool {
    let Some(a) = int_at(f, 0) else { return false };
    f.stack.pop();
    f.stack.push(Value::Int(r(a)));
    f.pc += 1;
    true
}

/// Moves to `pc + offset` if that is inside the code; says whether it did.
fn jump(f: &mut Frame, offset: i32) -> bool {
    let Some(target) = f.target(offset) else {
        return false;
    };
    f.pc = target;
    true
}

/// A conditional branch that pops `pops` operands and goes to the offset in
/// the next two bytes when `taken`.
fn take(f: &mut Frame, taken: bool, pops: usize) -> bool {
    let Some(bytes) = f.code.bytecode.get(f.pc + 1..f.pc + 3) else {
        return false;
    };
    let offset = i32::from(i16::from_be_bytes([bytes[0], bytes[1]]));
    let target = if taken {
        let Some(target) = f.target(offset) else {
            return false;
        };
        target
    } else {
        f.pc + 3
    };
    let keep = f.stack.len() - pops;
    f.stack.truncate(keep);
    f.pc = target;
    true
}

fn array_load(f: &mut Frame, heap: &Heap, kind: u8) -> bool {
    let Some(index) = int_at(f, 0) else {
        return false;
    };
    let n = f.stack.len();
    let Some(Value::Ref(Some(array))) = n.checked_sub(2).map(|i| f.stack[i]) else {
        return false;
    };
    let Ok(a) = heap.array(array) else {
        return false;
    };
    let Some(i) = usize::try_from(index).ok().filter(|&i| i < a.len()) else {
        return false;
    };
    if !kind_matches(kind, a) {
        return false;
    }
    let Some(v) = a.load(i) else { return false };
    f.stack.truncate(n - 2);
    f.stack.push(v);
    f.pc += 1;
    true
}

/// Primitive element stores; `bastore` and `aastore` need the array's class
/// and stay on the full path.
fn array_store(f: &mut Frame, heap: &mut Heap, kind: u8) -> bool {
    let n = f.stack.len();
    let (Some(value), Some(index)) = (n.checked_sub(1).map(|i| f.stack[i]), int_at(f, 1)) else {
        return false;
    };
    let Some(Value::Ref(Some(array))) = n.checked_sub(3).map(|i| f.stack[i]) else {
        return false;
    };
    let Ok(a) = heap.array_mut(array) else {
        return false;
    };
    let Some(i) = usize::try_from(index).ok().filter(|&i| i < a.len()) else {
        return false;
    };
    if !kind_matches(kind, a) || a.store(i, value, false).is_err() {
        return false;
    }
    f.stack.truncate(n - 3);
    f.pc += 1;
    true
}

fn array_length(f: &mut Frame, heap: &Heap) -> bool {
    let Some(&Value::Ref(Some(array))) = f.stack.last() else {
        return false;
    };
    let Ok(a) = heap.array(array) else {
        return false;
    };
    let len = a.len() as i32;
    f.stack.pop();
    f.stack.push(Value::Int(len));
    f.pc += 1;
    true
}

/// Runs until an instruction is not simple or not valid; true if it stopped
/// after a taken backward branch.
fn run(f: &mut Frame, heap: &mut Heap, classes: &mut [Class]) -> bool {
    loop {
        let start = f.pc;
        let Some(&op) = f.code.bytecode.get(start) else {
            return false;
        };
        let operand = |at: usize| f.code.bytecode.get(start + at).copied();
        let done = match op {
            0x00 => {
                f.pc += 1;
                true
            }
            0x01 => {
                can_push(f) && {
                    f.stack.push(Value::NULL);
                    f.pc += 1;
                    true
                }
            }
            0x02..=0x08 => push_int(f, i32::from(op) - 3, 1),
            0x10 => operand(1).is_some_and(|b| push_int(f, i32::from(b as i8), 2)),
            0x11 => match (operand(1), operand(2)) {
                (Some(h), Some(l)) => push_int(f, i32::from(i16::from_be_bytes([h, l])), 3),
                _ => false,
            },
            0x15 | 0x19 => operand(1).is_some_and(|i| load(f, usize::from(i), op == 0x19, 2)),
            0x1a..=0x1d => load(f, usize::from(op - 0x1a), false, 1),
            0x2a..=0x2d => load(f, usize::from(op - 0x2a), true, 1),
            0x36 | 0x3a => operand(1).is_some_and(|i| store(f, usize::from(i), op == 0x3a, 2)),
            0x3b..=0x3e => store(f, usize::from(op - 0x3b), false, 1),
            0x4b..=0x4e => store(f, usize::from(op - 0x4b), true, 1),
            0x84 => match (operand(1), operand(2)) {
                (Some(i), Some(d)) => iinc(f, usize::from(i), i32::from(d as i8)),
                _ => false,
            },
            0x59 => match f.stack.last().copied() {
                Some(v) if !v.is_wide() && v != Value::Top && can_push(f) => {
                    f.stack.push(v);
                    f.pc += 1;
                    true
                }
                _ => false,
            },
            0x60 => binary(f, |a, b| Some(a.wrapping_add(b))),
            0x64 => binary(f, |a, b| Some(a.wrapping_sub(b))),
            0x68 => binary(f, |a, b| Some(a.wrapping_mul(b))),
            0x6c => binary(f, |a, b| (b != 0).then(|| a.wrapping_div(b))),
            0x70 => binary(f, |a, b| (b != 0).then(|| a.wrapping_rem(b))),
            0x74 => unary(f, i32::wrapping_neg),
            0x78 => binary(f, |a, b| Some(a.wrapping_shl(b as u32 & 31))),
            0x7a => binary(f, |a, b| Some(a.wrapping_shr(b as u32 & 31))),
            0x7c => binary(f, |a, b| {
                Some(((a as u32).wrapping_shr(b as u32 & 31)) as i32)
            }),
            0x7e => binary(f, |a, b| Some(a & b)),
            0x80 => binary(f, |a, b| Some(a | b)),
            0x82 => binary(f, |a, b| Some(a ^ b)),
            0x91 => unary(f, |a| i32::from(a as i8)),
            0x92 => unary(f, |a| i32::from(a as u16)),
            0x93 => unary(f, |a| i32::from(a as i16)),
            0x99..=0x9e => int_at(f, 0).is_some_and(|a| take(f, cond(op - 0x99, a.cmp(&0)), 1)),
            0x9f..=0xa4 => match (int_at(f, 1), int_at(f, 0)) {
                (Some(a), Some(b)) => take(f, cond(op - 0x9f, a.cmp(&b)), 2),
                _ => false,
            },
            0xa5 | 0xa6 => match (ref_at(f, 1), ref_at(f, 0)) {
                (Some(a), Some(b)) => take(f, (a == b) == (op == 0xa5), 2),
                _ => false,
            },
            0xc6 | 0xc7 => ref_at(f, 0).is_some_and(|a| take(f, a.is_none() == (op == 0xc6), 1)),
            0xa7 => match (operand(1), operand(2)) {
                (Some(h), Some(l)) => {
                    let offset = i32::from(i16::from_be_bytes([h, l]));
                    jump(f, offset)
                }
                _ => false,
            },
            0x2e => array_load(f, heap, 0),
            0x32 => array_load(f, heap, 4),
            0x33 => array_load(f, heap, 5),
            0x34 => array_load(f, heap, 6),
            0x35 => array_load(f, heap, 7),
            0x4f => array_store(f, heap, 0),
            0x55 => array_store(f, heap, 6),
            0x56 => array_store(f, heap, 7),
            0xbe => array_length(f, heap),
            0xb2 => get_static(f, classes),
            0xb3 => put_static(f, classes),
            0xb4 => get_field(f, heap, classes),
            0xb5 => put_field(f, heap, classes),
            _ => false,
        };
        if !done {
            return false;
        }
        if f.pc <= start {
            return true;
        }
    }
}
