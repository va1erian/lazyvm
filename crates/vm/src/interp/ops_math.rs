//! Arithmetic, shifts, logic, conversions and comparisons (JVMS 6.5).
//! Integer arithmetic wraps; float to integer conversion saturates and maps
//! NaN to zero, which is exactly Rust's `as`.
//!
//! `binary` and `unary` compute a result from operand values and nothing
//! else, so `step` (here) and the fast loop (`fast_wide.rs`) share them: the
//! fast loop peeks at the operands and only pops when there is a result.

use std::cmp::Ordering;

use super::{Flow, Frame};
use crate::value::Value;
use crate::{Vm, VmError, VmResult};

const DIV_ZERO: &str = "/ by zero";

impl Vm {
    pub(super) fn op_math(&mut self, op: u8) -> VmResult<Flow> {
        let f = self.top()?;
        math(f, op)?;
        f.advance(1);
        Ok(Flow::Next)
    }
}

/// Whether `op` (0x60-0x98, not `iinc`) takes one operand.
pub(super) fn is_unary(op: u8) -> bool {
    matches!(op, 0x74..=0x77 | 0x85..=0x93)
}

/// Runs one of 0x60-0x98 but `iinc` on `f`'s stack; the caller moves `pc`.
fn math(f: &mut Frame, op: u8) -> VmResult<()> {
    if !(0x60..=0x98).contains(&op) || op == 0x84 {
        return Err(VmError::verify(format!("op_math cannot run {op:#04x}")));
    }
    let v = if is_unary(op) {
        let a = f.pop()?;
        unary(op, a).ok_or_else(|| wrong_kinds(op))?
    } else {
        let b = f.pop()?;
        let a = f.pop()?;
        match binary(op, a, b) {
            Some(v) => v,
            None if divides(op, a, b) => {
                return Err(VmError::exception(
                    "java/lang/ArithmeticException",
                    DIV_ZERO,
                ));
            }
            None => return Err(wrong_kinds(op)),
        }
    };
    f.push(v)
}

fn wrong_kinds(op: u8) -> VmError {
    VmError::verify(format!("operands of the wrong kind for {op:#04x}"))
}

/// idiv, ldiv, irem or lrem, with operands of the right kind and a zero divisor.
fn divides(op: u8, a: Value, b: Value) -> bool {
    matches!(
        (op, a, b),
        (0x6c | 0x70, Value::Int(_), Value::Int(0)) | (0x6d | 0x71, Value::Long(_), Value::Long(0))
    )
}

/// The result of a two-operand instruction (`a` below `b` on the stack), or
/// None when an operand has the wrong kind or an integer divisor is zero.
#[inline]
pub(super) fn binary(op: u8, a: Value, b: Value) -> Option<Value> {
    use Value::{Double as D, Float as F, Int as I, Long as L};
    Some(match (op, a, b) {
        (0x60, I(a), I(b)) => I(a.wrapping_add(b)),
        (0x61, L(a), L(b)) => L(a.wrapping_add(b)),
        (0x62, F(a), F(b)) => F(a + b),
        (0x63, D(a), D(b)) => D(a + b),
        (0x64, I(a), I(b)) => I(a.wrapping_sub(b)),
        (0x65, L(a), L(b)) => L(a.wrapping_sub(b)),
        (0x66, F(a), F(b)) => F(a - b),
        (0x67, D(a), D(b)) => D(a - b),
        (0x68, I(a), I(b)) => I(a.wrapping_mul(b)),
        (0x69, L(a), L(b)) => L(a.wrapping_mul(b)),
        (0x6a, F(a), F(b)) => F(a * b),
        (0x6b, D(a), D(b)) => D(a * b),
        (0x6c, I(a), I(b)) if b != 0 => I(a.wrapping_div(b)),
        (0x6d, L(a), L(b)) if b != 0 => L(a.wrapping_div(b)),
        (0x6e, F(a), F(b)) => F(a / b),
        (0x6f, D(a), D(b)) => D(a / b),
        (0x70, I(a), I(b)) if b != 0 => I(a.wrapping_rem(b)),
        (0x71, L(a), L(b)) if b != 0 => L(a.wrapping_rem(b)),
        (0x72, F(a), F(b)) => F(a % b),
        (0x73, D(a), D(b)) => D(a % b),
        // Shift counts use the low 5 or 6 bits.
        (0x78, I(a), I(s)) => I(a.wrapping_shl(s as u32 & 31)),
        (0x79, L(a), I(s)) => L(a.wrapping_shl(s as u32 & 63)),
        (0x7a, I(a), I(s)) => I(a.wrapping_shr(s as u32 & 31)),
        (0x7b, L(a), I(s)) => L(a.wrapping_shr(s as u32 & 63)),
        (0x7c, I(a), I(s)) => I(((a as u32) >> (s as u32 & 31)) as i32),
        (0x7d, L(a), I(s)) => L(((a as u64) >> (s as u32 & 63)) as i64),
        (0x7e, I(a), I(b)) => I(a & b),
        (0x7f, L(a), L(b)) => L(a & b),
        (0x80, I(a), I(b)) => I(a | b),
        (0x81, L(a), L(b)) => L(a | b),
        (0x82, I(a), I(b)) => I(a ^ b),
        (0x83, L(a), L(b)) => L(a ^ b),
        // lcmp, fcmpl, fcmpg, dcmpl, dcmpg: NaN gives -1 for `l`, 1 for `g`.
        (0x94, L(a), L(b)) => I(order(Some(a.cmp(&b)), 0)),
        (0x95, F(a), F(b)) => I(order(a.partial_cmp(&b), -1)),
        (0x96, F(a), F(b)) => I(order(a.partial_cmp(&b), 1)),
        (0x97, D(a), D(b)) => I(order(a.partial_cmp(&b), -1)),
        (0x98, D(a), D(b)) => I(order(a.partial_cmp(&b), 1)),
        _ => return None,
    })
}

/// The result of a one-operand instruction (negation, conversion), or None
/// when the operand has the wrong kind.
#[inline]
pub(super) fn unary(op: u8, a: Value) -> Option<Value> {
    use Value::{Double as D, Float as F, Int as I, Long as L};
    Some(match (op, a) {
        (0x74, I(a)) => I(a.wrapping_neg()),
        (0x75, L(a)) => L(a.wrapping_neg()),
        (0x76, F(a)) => F(-a),
        (0x77, D(a)) => D(-a),
        (0x85, I(a)) => L(i64::from(a)),
        (0x86, I(a)) => F(a as f32),
        (0x87, I(a)) => D(f64::from(a)),
        (0x88, L(a)) => I(a as i32),
        (0x89, L(a)) => F(a as f32),
        (0x8a, L(a)) => D(a as f64),
        (0x8b, F(a)) => I(a as i32),
        (0x8c, F(a)) => L(a as i64),
        (0x8d, F(a)) => D(f64::from(a)),
        (0x8e, D(a)) => I(a as i32),
        (0x8f, D(a)) => L(a as i64),
        (0x90, D(a)) => F(a as f32),
        (0x91, I(a)) => I(i32::from(a as i8)),
        (0x92, I(a)) => I(i32::from(a as u16)),
        (0x93, I(a)) => I(i32::from(a as i16)),
        _ => return None,
    })
}

fn order(o: Option<Ordering>, nan: i32) -> i32 {
    match o {
        Some(Ordering::Less) => -1,
        Some(Ordering::Equal) => 0,
        Some(Ordering::Greater) => 1,
        None => nan,
    }
}
