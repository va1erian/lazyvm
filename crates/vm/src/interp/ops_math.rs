//! Arithmetic, shifts, logic, conversions and comparisons (JVMS 6.5).
//! Integer arithmetic wraps; float to integer conversion saturates and maps
//! NaN to zero, which is exactly Rust's `as`.

use super::{Flow, Frame};
use crate::value::Value;
use crate::{Vm, VmError, VmResult};

const DIV_ZERO: &str = "/ by zero";

impl Vm {
    pub(super) fn op_math(&mut self, op: u8) -> VmResult<Flow> {
        let f = self.top()?;
        match op {
            0x60..=0x77 => arith(f, op)?,
            0x78..=0x83 => bits(f, op)?,
            0x85..=0x93 => convert(f, op)?,
            0x94..=0x98 => compare(f, op)?,
            _ => return Err(VmError::verify(format!("op_math cannot run {op:#04x}"))),
        }
        f.advance(1);
        Ok(Flow::Next)
    }
}

/// add, sub, mul, div, rem, neg for int, long, float, double (0x60-0x77).
fn arith(f: &mut Frame, op: u8) -> VmResult<()> {
    let kind = (op - 0x60) % 4;
    let operation = (op - 0x60) / 4;
    if operation == 5 {
        let v = match kind {
            0 => Value::Int(f.pop_int()?.wrapping_neg()),
            1 => Value::Long(f.pop_long()?.wrapping_neg()),
            2 => Value::Float(-f.pop_float()?),
            _ => Value::Double(-f.pop_double()?),
        };
        return f.push(v);
    }
    let v = match kind {
        0 => {
            let (b, a) = (f.pop_int()?, f.pop_int()?);
            Value::Int(match operation {
                0 => a.wrapping_add(b),
                1 => a.wrapping_sub(b),
                2 => a.wrapping_mul(b),
                3 if b == 0 => {
                    return Err(VmError::exception(
                        "java/lang/ArithmeticException",
                        DIV_ZERO,
                    ));
                }
                3 => a.wrapping_div(b),
                _ if b == 0 => {
                    return Err(VmError::exception(
                        "java/lang/ArithmeticException",
                        DIV_ZERO,
                    ));
                }
                _ => a.wrapping_rem(b),
            })
        }
        1 => {
            let (b, a) = (f.pop_long()?, f.pop_long()?);
            Value::Long(match operation {
                0 => a.wrapping_add(b),
                1 => a.wrapping_sub(b),
                2 => a.wrapping_mul(b),
                3 if b == 0 => {
                    return Err(VmError::exception(
                        "java/lang/ArithmeticException",
                        DIV_ZERO,
                    ));
                }
                3 => a.wrapping_div(b),
                _ if b == 0 => {
                    return Err(VmError::exception(
                        "java/lang/ArithmeticException",
                        DIV_ZERO,
                    ));
                }
                _ => a.wrapping_rem(b),
            })
        }
        2 => {
            let (b, a) = (f.pop_float()?, f.pop_float()?);
            Value::Float(match operation {
                0 => a + b,
                1 => a - b,
                2 => a * b,
                3 => a / b,
                _ => a % b,
            })
        }
        _ => {
            let (b, a) = (f.pop_double()?, f.pop_double()?);
            Value::Double(match operation {
                0 => a + b,
                1 => a - b,
                2 => a * b,
                3 => a / b,
                _ => a % b,
            })
        }
    };
    f.push(v)
}

/// Shifts and bitwise logic (0x78-0x83). Shift counts use the low 5 or 6 bits.
fn bits(f: &mut Frame, op: u8) -> VmResult<()> {
    let v = match op {
        0x78..=0x7d => {
            let s = f.pop_int()?;
            if op.is_multiple_of(2) {
                let a = f.pop_int()?;
                let s = (s & 31) as u32;
                Value::Int(match op {
                    0x78 => a.wrapping_shl(s),
                    0x7a => a.wrapping_shr(s),
                    _ => ((a as u32) >> s) as i32,
                })
            } else {
                let a = f.pop_long()?;
                let s = (s & 63) as u32;
                Value::Long(match op {
                    0x79 => a.wrapping_shl(s),
                    0x7b => a.wrapping_shr(s),
                    _ => ((a as u64) >> s) as i64,
                })
            }
        }
        _ if op.is_multiple_of(2) => {
            let (b, a) = (f.pop_int()?, f.pop_int()?);
            Value::Int(match op {
                0x7e => a & b,
                0x80 => a | b,
                _ => a ^ b,
            })
        }
        _ => {
            let (b, a) = (f.pop_long()?, f.pop_long()?);
            Value::Long(match op {
                0x7f => a & b,
                0x81 => a | b,
                _ => a ^ b,
            })
        }
    };
    f.push(v)
}

fn convert(f: &mut Frame, op: u8) -> VmResult<()> {
    let v = match op {
        0x85 => Value::Long(i64::from(f.pop_int()?)),
        0x86 => Value::Float(f.pop_int()? as f32),
        0x87 => Value::Double(f64::from(f.pop_int()?)),
        0x88 => Value::Int(f.pop_long()? as i32),
        0x89 => Value::Float(f.pop_long()? as f32),
        0x8a => Value::Double(f.pop_long()? as f64),
        0x8b => Value::Int(f.pop_float()? as i32),
        0x8c => Value::Long(f.pop_float()? as i64),
        0x8d => Value::Double(f64::from(f.pop_float()?)),
        0x8e => Value::Int(f.pop_double()? as i32),
        0x8f => Value::Long(f.pop_double()? as i64),
        0x90 => Value::Float(f.pop_double()? as f32),
        0x91 => Value::Int(i32::from(f.pop_int()? as i8)),
        0x92 => Value::Int(i32::from(f.pop_int()? as u16)),
        _ => Value::Int(i32::from(f.pop_int()? as i16)),
    };
    f.push(v)
}

/// lcmp, fcmpl, fcmpg, dcmpl, dcmpg: -1, 0 or 1; NaN gives -1 for `l`, 1 for `g`.
fn compare(f: &mut Frame, op: u8) -> VmResult<()> {
    let order = |o: Option<std::cmp::Ordering>, nan: i32| match o {
        Some(std::cmp::Ordering::Less) => -1,
        Some(std::cmp::Ordering::Equal) => 0,
        Some(std::cmp::Ordering::Greater) => 1,
        None => nan,
    };
    let r = match op {
        0x94 => {
            let (b, a) = (f.pop_long()?, f.pop_long()?);
            order(Some(a.cmp(&b)), 0)
        }
        0x95 | 0x96 => {
            let (b, a) = (f.pop_float()?, f.pop_float()?);
            order(a.partial_cmp(&b), if op == 0x95 { -1 } else { 1 })
        }
        _ => {
            let (b, a) = (f.pop_double()?, f.pop_double()?);
            order(a.partial_cmp(&b), if op == 0x97 { -1 } else { 1 })
        }
    };
    f.push(Value::Int(r))
}
