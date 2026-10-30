//! Conditional and unconditional branches, switches and returns.

use super::{Flow, Frame};
use crate::{Vm, VmError, VmResult};

/// Switch tables larger than the code they live in are malformed; this caps
/// the work a hostile `tableswitch`/`lookupswitch` can ask for.
const MAX_SWITCH_ENTRIES: i64 = 65536;

impl Vm {
    pub(super) fn op_branch(&mut self, op: u8) -> VmResult<Flow> {
        let f = self.top()?;
        let start = f.pc;
        match op {
            0x99..=0x9e => {
                let v = f.pop_int()?;
                branch_if(f, cond(op - 0x99, v.cmp(&0)))?;
            }
            0x9f..=0xa4 => {
                let (b, a) = (f.pop_int()?, f.pop_int()?);
                branch_if(f, cond(op - 0x9f, a.cmp(&b)))?;
            }
            0xa5 | 0xa6 => {
                let (b, a) = (f.pop_ref()?, f.pop_ref()?);
                branch_if(f, (a == b) == (op == 0xa5))?;
            }
            0xa7 => {
                let off = i32::from(f.i2(1)?);
                f.jump(off)?;
            }
            0xc8 => {
                let off = f.i4(1)?;
                f.jump(off)?;
            }
            0xa8 | 0xa9 | 0xc9 => {
                return Err(VmError::verify(
                    "jsr/ret are not supported (class files before version 50)",
                ));
            }
            0xaa => table_switch(f)?,
            0xab => lookup_switch(f)?,
            0xac..=0xb1 => return ret(f, op),
            0xc6 | 0xc7 => {
                let v = f.pop_ref()?;
                branch_if(f, v.is_none() == (op == 0xc6))?;
            }
            _ => return Err(VmError::verify(format!("op_branch cannot run {op:#04x}"))),
        }
        // A backward branch is a safepoint, so every loop has one.
        Ok(if f.pc <= start {
            Flow::Poll
        } else {
            Flow::Next
        })
    }
}

/// The six comparisons in opcode order: eq, ne, lt, ge, gt, le.
fn cond(which: u8, o: std::cmp::Ordering) -> bool {
    use std::cmp::Ordering::*;
    match which {
        0 => o == Equal,
        1 => o != Equal,
        2 => o == Less,
        3 => o != Less,
        4 => o == Greater,
        _ => o != Greater,
    }
}

fn branch_if(f: &mut Frame, taken: bool) -> VmResult<()> {
    if taken {
        let off = i32::from(f.i2(1)?);
        f.jump(off)
    } else {
        f.advance(3);
        Ok(())
    }
}

/// Offset from the opcode to the first 4-byte aligned operand.
fn padding(pc: usize) -> usize {
    1 + (3 - pc % 4)
}

fn table_switch(f: &mut Frame) -> VmResult<()> {
    let key = f.pop_int()?;
    let base = padding(f.pc);
    let default = f.i4(base)?;
    let low = f.i4(base + 4)?;
    let high = f.i4(base + 8)?;
    if low > high || i64::from(high) - i64::from(low) >= MAX_SWITCH_ENTRIES {
        return Err(VmError::verify("bad tableswitch bounds"));
    }
    let off = if key < low || key > high {
        default
    } else {
        let i = (i64::from(key) - i64::from(low)) as usize;
        f.i4(base + 12 + i * 4)?
    };
    f.jump(off)
}

fn lookup_switch(f: &mut Frame) -> VmResult<()> {
    let key = f.pop_int()?;
    let base = padding(f.pc);
    let default = f.i4(base)?;
    let npairs = f.i4(base + 4)?;
    if !(0..MAX_SWITCH_ENTRIES as i32).contains(&npairs) {
        return Err(VmError::verify("bad lookupswitch pair count"));
    }
    let mut off = default;
    for i in 0..npairs as usize {
        let at = base + 8 + i * 8;
        if f.i4(at)? == key {
            off = f.i4(at + 4)?;
            break;
        }
    }
    f.jump(off)
}

fn ret(f: &mut Frame, op: u8) -> VmResult<Flow> {
    let v = match op {
        0xb1 => None,
        _ => {
            let v = f.pop()?;
            let ok = match op {
                0xac => v.int().is_ok(),
                0xad => v.long().is_ok(),
                0xae => v.float().is_ok(),
                0xaf => v.double().is_ok(),
                _ => v.reference().is_ok(),
            };
            if !ok {
                return Err(VmError::verify(format!("return of the wrong type {v:?}")));
            }
            Some(v)
        }
    };
    Ok(Flow::Return(v))
}
