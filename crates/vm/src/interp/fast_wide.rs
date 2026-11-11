//! The fast loop's (`fast.rs`) locals of every kind, `long`, `float` and
//! `double` arithmetic, numeric `ldc`, and `pop`/`pop2`/`dup2`.
//!
//! Arithmetic computes with the functions `step` uses (`ops_math::binary`
//! and `unary`), on operands read in place; the stack shuffles run
//! `ops_basic::stack_op` after a check that it cannot fail. When there is no
//! result, nothing has changed and `step` takes over.

use super::Frame;
use super::fast::can_push;
use super::ops_basic::{is_kind, stack_op};
use super::ops_math::{binary, is_unary, unary};
use crate::class::{Class, Resolved};
use crate::value::Value;
use classfile::Constant;

/// Kinds as `ops_basic` numbers them.
pub(super) const LONG: u8 = 1;
pub(super) const FLOAT: u8 = 2;
pub(super) const DOUBLE: u8 = 3;

/// Pushes `v` and moves on `len` bytes, if there is room.
#[inline(always)]
pub(super) fn push(f: &mut Frame, v: Value, len: usize) -> bool {
    if !can_push(f) {
        return false;
    }
    f.stack.push(v);
    f.pc += len;
    true
}

/// `xload` of local `i`; `kind` as `ops_basic` numbers them.
#[inline(always)]
pub(super) fn load(f: &mut Frame, i: usize, kind: u8, len: usize) -> bool {
    match f.locals.get(i) {
        Some(&v) if is_kind(v, kind) => push(f, v, len),
        _ => false,
    }
}

/// `xstore` into local `i`, with `Frame::set_local`'s rules: a wide value
/// takes two slots, and a wide value cut in half by the store is invalidated.
#[inline(always)]
pub(super) fn store(f: &mut Frame, i: usize, kind: u8, len: usize) -> bool {
    let Some(&v) = f.stack.last() else {
        return false;
    };
    // From the constant kind, not the value, so an inlined int store folds it away.
    let need = if kind == LONG || kind == DOUBLE { 2 } else { 1 };
    if !is_kind(v, kind) || i + need > f.locals.len() {
        return false;
    }
    f.stack.pop();
    if i > 0 && f.locals[i - 1].is_wide() {
        f.locals[i - 1] = Value::Top;
    }
    f.locals[i] = v;
    if need == 2 {
        f.locals[i + 1] = Value::Top;
    }
    f.pc += len;
    true
}

/// An arithmetic, logic, conversion or compare instruction (0x60-0x98 but
/// `iinc`): the operands are read in place and only replaced by a result.
#[inline(always)]
pub(super) fn arith(f: &mut Frame, op: u8) -> bool {
    let n = f.stack.len();
    let result = if is_unary(op) {
        n.checked_sub(1).and_then(|i| unary(op, f.stack[i]))
    } else {
        n.checked_sub(2)
            .and_then(|i| binary(op, f.stack[i], f.stack[i + 1]))
    };
    let Some(v) = result else { return false };
    let operands = if is_unary(op) { 1 } else { 2 };
    f.stack.truncate(n - operands);
    f.stack.push(v);
    f.pc += 1;
    true
}

/// `ldc`, `ldc_w` or `ldc2_w` of a number, or of a string already interned
/// (what `Vm::ldc` pushes for them); anything else is left to `step`.
pub(super) fn ldc(f: &mut Frame, classes: &[Class], index: u16, len: usize) -> bool {
    let Some(class) = classes.get(f.class.0 as usize) else {
        return false;
    };
    let v = match class.file.as_ref().and_then(|file| file.pool.get(index)) {
        Some(Constant::Integer(i)) => Value::Int(*i),
        Some(Constant::Float(x)) => Value::Float(*x),
        Some(Constant::Long(x)) => Value::Long(*x),
        Some(Constant::Double(x)) => Value::Double(*x),
        Some(Constant::String(_)) => match class.cp_cache.get(usize::from(index)) {
            Some(Some(Resolved::String(r))) => Value::Ref(Some(*r)),
            _ => return false,
        },
        _ => return false,
    };
    push(f, v, len)
}

/// `pop`, `pop2` and `dup2`, when `ops_basic::stack_op` cannot fail on them.
pub(super) fn shuffle(f: &mut Frame, op: u8) -> bool {
    let n = f.stack.len();
    let wide = |depth: usize| n.checked_sub(depth + 1).map(|i| f.stack[i].is_wide());
    let room = usize::from(f.code.max_stack).saturating_sub(n);
    let ready = match (op, wide(0), wide(1)) {
        (0x57, Some(false), _) => true,
        (0x58, Some(true), _) | (0x58, Some(false), Some(false)) => true,
        (0x5c, Some(true), _) => room >= 1,
        (0x5c, Some(false), Some(false)) => room >= 2,
        _ => false,
    };
    ready && stack_op(f, op).is_ok()
}
