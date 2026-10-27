//! Constants, local loads and stores, stack shuffles, `iinc` and `wide`.

use super::Flow;
use crate::value::Value;
use crate::{Vm, VmError, VmResult};

impl Vm {
    pub(super) fn op_basic(&mut self, op: u8) -> VmResult<Flow> {
        let f = self.top()?;
        match op {
            0x00 => f.advance(1),
            0x01 => push(f, Value::NULL, 1)?,
            0x02..=0x08 => push(f, Value::Int(i32::from(op) - 0x03), 1)?,
            0x09 | 0x0a => push(f, Value::Long(i64::from(op - 0x09)), 1)?,
            0x0b..=0x0d => push(f, Value::Float(f32::from(op - 0x0b)), 1)?,
            0x0e | 0x0f => push(f, Value::Double(f64::from(op - 0x0e)), 1)?,
            0x10 => {
                let v = Value::Int(i32::from(f.u1(1)? as i8));
                push(f, v, 2)?;
            }
            0x11 => {
                let v = Value::Int(i32::from(f.i2(1)?));
                push(f, v, 3)?;
            }
            0x15..=0x19 => {
                let i = usize::from(f.u1(1)?);
                load(f, i, op - 0x15, 2)?;
            }
            0x1a..=0x2d => {
                let n = op - 0x1a;
                load(f, usize::from(n % 4), n / 4, 1)?;
            }
            0x36..=0x3a => {
                let i = usize::from(f.u1(1)?);
                store(f, i, op - 0x36, 2)?;
            }
            0x3b..=0x4e => {
                let n = op - 0x3b;
                store(f, usize::from(n % 4), n / 4, 1)?;
            }
            0x57..=0x5f => stack_op(f, op)?,
            0x84 => {
                let i = usize::from(f.u1(1)?);
                let delta = i32::from(f.u1(2)? as i8);
                iinc(f, i, delta, 3)?;
            }
            0xc4 => wide(f)?,
            _ => return Err(VmError::verify(format!("op_basic cannot run {op:#04x}"))),
        }
        Ok(Flow::Next)
    }
}

type Frame = super::Frame;

fn push(f: &mut Frame, v: Value, len: usize) -> VmResult<()> {
    f.push(v)?;
    f.advance(len);
    Ok(())
}

/// `kind`: 0 int, 1 long, 2 float, 3 double, 4 reference.
fn check_kind(v: Value, kind: u8) -> VmResult<Value> {
    let ok = matches!(
        (kind, v),
        (0, Value::Int(_))
            | (1, Value::Long(_))
            | (2, Value::Float(_))
            | (3, Value::Double(_))
            | (4, Value::Ref(_))
    );
    if ok {
        Ok(v)
    } else {
        Err(VmError::verify(format!("local of kind {kind} holds {v:?}")))
    }
}

fn load(f: &mut Frame, i: usize, kind: u8, len: usize) -> VmResult<()> {
    let v = check_kind(f.local(i)?, kind)?;
    push(f, v, len)
}

fn store(f: &mut Frame, i: usize, kind: u8, len: usize) -> VmResult<()> {
    let v = f.pop()?;
    // `astore` may also store a return address (jsr), which LazyVM rejects.
    let v = check_kind(v, kind)?;
    f.set_local(i, v)?;
    f.advance(len);
    Ok(())
}

fn iinc(f: &mut Frame, i: usize, delta: i32, len: usize) -> VmResult<()> {
    let v = f.local(i)?.int()?;
    f.set_local(i, Value::Int(v.wrapping_add(delta)))?;
    f.advance(len);
    Ok(())
}

fn wide(f: &mut Frame) -> VmResult<()> {
    let op = f.u1(1)?;
    let i = usize::from(f.u2(2)?);
    match op {
        0x15..=0x19 => load(f, i, op - 0x15, 4),
        0x36..=0x3a => store(f, i, op - 0x36, 4),
        0x84 => {
            let delta = i32::from(f.i2(4)?);
            iinc(f, i, delta, 6)
        }
        _ => Err(VmError::verify(format!("wide cannot modify {op:#04x}"))),
    }
}

/// The JVMS 6.5 stack instructions, which care about value categories.
fn stack_op(f: &mut Frame, op: u8) -> VmResult<()> {
    let cat1 = |v: Value| -> VmResult<Value> {
        if v.is_wide() {
            Err(VmError::verify(
                "category 2 value where category 1 was required",
            ))
        } else {
            Ok(v)
        }
    };
    match op {
        0x57 => {
            cat1(f.pop()?)?;
        }
        0x58 => {
            if !f.pop()?.is_wide() {
                cat1(f.pop()?)?;
            }
        }
        0x59 => {
            let v = cat1(f.peek(0)?)?;
            f.push(v)?;
        }
        0x5a => {
            let v1 = cat1(f.pop()?)?;
            let v2 = cat1(f.pop()?)?;
            push_all(f, &[v1, v2, v1])?;
        }
        0x5b => {
            let v1 = cat1(f.pop()?)?;
            let v2 = f.pop()?;
            if v2.is_wide() {
                push_all(f, &[v1, v2, v1])?;
            } else {
                let v3 = cat1(f.pop()?)?;
                push_all(f, &[v1, v3, v2, v1])?;
            }
        }
        0x5c => {
            let v1 = f.pop()?;
            if v1.is_wide() {
                push_all(f, &[v1, v1])?;
            } else {
                let v2 = cat1(f.pop()?)?;
                push_all(f, &[v2, v1, v2, v1])?;
            }
        }
        0x5d => {
            let v1 = f.pop()?;
            if v1.is_wide() {
                let v2 = cat1(f.pop()?)?;
                push_all(f, &[v1, v2, v1])?;
            } else {
                let v2 = cat1(f.pop()?)?;
                let v3 = cat1(f.pop()?)?;
                push_all(f, &[v2, v1, v3, v2, v1])?;
            }
        }
        0x5e => dup2_x2(f)?,
        _ => {
            let v1 = cat1(f.pop()?)?;
            let v2 = cat1(f.pop()?)?;
            push_all(f, &[v1, v2])?;
        }
    }
    f.advance(1);
    Ok(())
}

fn dup2_x2(f: &mut Frame) -> VmResult<()> {
    let v1 = f.pop()?;
    let v2 = f.pop()?;
    match (v1.is_wide(), v2.is_wide()) {
        // Form 4: both category 2.
        (true, true) => push_all(f, &[v1, v2, v1]),
        // Form 2: v1 category 2, v2 and v3 category 1.
        (true, false) => {
            let v3 = f.pop()?;
            push_all(f, &[v1, v3, v2, v1])
        }
        (false, false) => {
            let v3 = f.pop()?;
            if v3.is_wide() {
                // Form 3.
                push_all(f, &[v2, v1, v3, v2, v1])
            } else {
                // Form 1.
                let v4 = f.pop()?;
                push_all(f, &[v2, v1, v4, v3, v2, v1])
            }
        }
        (false, true) => Err(VmError::verify("dup2_x2 on a split category 2 value")),
    }
}

/// Pushes `values` in order, the last one ending on top.
fn push_all(f: &mut Frame, values: &[Value]) -> VmResult<()> {
    for &v in values {
        f.push(v)?;
    }
    Ok(())
}
