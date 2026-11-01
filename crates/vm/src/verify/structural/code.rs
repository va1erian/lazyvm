//! Static constraints on `code` (JVMS 4.9.1): where instructions start, branch
//! targets, exception ranges, and which constant each opcode may name.

use classfile::descriptor::{MethodDescriptor, parse_field, parse_method};
use classfile::{ClassFile, Code, Constant, Handler};

use crate::verify::decode::{self, OP_GOTO_W, OP_JSR, OP_JSR_W, OP_NEW, OP_RET, OP_WIDE};
use crate::verify::{Fail, fail_at};

const MAX_DIMENSIONS: usize = 255;

/// Which offsets start an instruction; the type checker walks the same list.
pub(crate) struct Layout {
    starts: Vec<bool>,
}

impl Layout {
    pub fn is_start(&self, pc: usize) -> bool {
        self.starts.get(pc).copied().unwrap_or(false)
    }

    fn target_ok(&self, target: i64) -> bool {
        usize::try_from(target).is_ok_and(|t| self.is_start(t))
    }
}

/// Checks one method body, returning its instruction layout.
pub(crate) fn check_code(cf: &ClassFile, code: &Code) -> Result<Layout, Fail> {
    let bytes = &code.bytecode;
    if bytes.is_empty() || bytes.len() > 65535 {
        return Err(fail_at(
            0,
            format!("code_length {} out of range", bytes.len()),
        ));
    }
    let layout = layout(bytes)?;
    let mut pc = 0;
    while pc < bytes.len() {
        let len = decode::length(bytes, pc).map_err(|e| fail_at(pc, e))?;
        check_instruction(cf, code, &layout, pc).map_err(|e| fail_at(pc, e))?;
        pc += len;
    }
    for h in &code.handlers {
        check_handler(cf, h, &layout).map_err(|e| fail_at(usize::from(h.start_pc), e))?;
    }
    Ok(layout)
}

/// JVMS 4.9.1: the first opcode is at 0, each next one follows its
/// predecessor, and the last instruction ends exactly at the code's end.
fn layout(bytes: &[u8]) -> Result<Layout, Fail> {
    let mut starts = vec![false; bytes.len()];
    let mut pc = 0;
    while pc < bytes.len() {
        starts[pc] = true;
        let len = decode::length(bytes, pc).map_err(|e| fail_at(pc, e))?;
        pc += len;
    }
    if pc != bytes.len() {
        return Err(fail_at(pc, "the last instruction runs past the code"));
    }
    Ok(Layout { starts })
}

fn check_instruction(
    cf: &ClassFile,
    code: &Code,
    layout: &Layout,
    pc: usize,
) -> Result<(), String> {
    let bytes = &code.bytecode;
    let op = decode::u1(bytes, pc)?;
    let target_of = |offset: i64| {
        if layout.target_ok(pc as i64 + offset) {
            Ok(())
        } else {
            Err(format!(
                "branch to {}, not an instruction",
                pc as i64 + offset
            ))
        }
    };
    let pool_index = || decode::u2(bytes, pc + 1);
    match op {
        OP_JSR | OP_JSR_W | OP_RET => Err("jsr and ret are not supported (decisions.md D1)".into()),
        0x99..=0xa6 | decode::OP_GOTO | 0xc6 | 0xc7 => {
            target_of(i64::from(decode::i2(bytes, pc + 1)?))
        }
        OP_GOTO_W => target_of(i64::from(decode::i4(bytes, pc + 1)?)),
        decode::OP_TABLESWITCH | decode::OP_LOOKUPSWITCH => {
            let sw = decode::switch(bytes, pc)?;
            if !sw.sorted {
                return Err("lookupswitch keys are not sorted".into());
            }
            for &t in &sw.targets {
                if !layout.target_ok(t) {
                    return Err(format!("switch target {t} is not an instruction"));
                }
            }
            Ok(())
        }
        OP_WIDE => check_wide(code, pc),
        0x15..=0x19 | 0x36..=0x3a => {
            let index = u16::from(decode::u1(bytes, pc + 1)?);
            check_local(code, index, op_slots(op))
        }
        0x84 => check_local(code, u16::from(decode::u1(bytes, pc + 1)?), 1),
        0x1a..=0x2d | 0x3b..=0x4e => check_implicit_local(code, op),
        0x12 => check_ldc(cf, u16::from(decode::u1(bytes, pc + 1)?)),
        0x13 => check_ldc(cf, pool_index()?),
        0x14 => match cf.pool.get(pool_index()?) {
            Some(Constant::Long(_) | Constant::Double(_)) => Ok(()),
            other => Err(format!("ldc2_w of {other:?}")),
        },
        0xb2..=0xb5 => match cf.pool.get(pool_index()?) {
            Some(Constant::FieldRef { .. }) => Ok(()),
            other => Err(format!("field instruction names {other:?}")),
        },
        0xb6..=0xb9 => check_invoke(cf, code, pc, op),
        0xba => check_invokedynamic(cf, code, pc),
        OP_NEW => {
            let name = cf.pool.class_name(pool_index()?)?;
            if name.starts_with('[') {
                return Err(format!("new of the array type {name}"));
            }
            Ok(())
        }
        0xbc => match decode::u1(bytes, pc + 1)? {
            4..=11 => Ok(()),
            t => Err(format!("newarray of unknown type {t}")),
        },
        0xbd => {
            let name = cf.pool.class_name(pool_index()?)?;
            let dims = name.bytes().take_while(|&b| b == b'[').count();
            if dims >= MAX_DIMENSIONS {
                return Err("anewarray of more than 255 dimensions".into());
            }
            Ok(())
        }
        0xc0 | 0xc1 => cf.pool.class_name(pool_index()?).map(|_| ()),
        0xc5 => {
            let name = cf.pool.class_name(pool_index()?)?;
            let wanted = usize::from(decode::u1(bytes, pc + 3)?);
            let dims = name.bytes().take_while(|&b| b == b'[').count();
            if wanted == 0 || wanted > dims {
                return Err(format!("multianewarray of {wanted} dimensions of {name}"));
            }
            parse_field(name).map(|_| ())
        }
        _ => Ok(()),
    }
}

/// Slots a load or store with an explicit index touches: long and double two.
fn op_slots(op: u8) -> u16 {
    match op {
        0x16 | 0x18 | 0x37 | 0x39 => 2,
        _ => 1,
    }
}

fn check_local(code: &Code, index: u16, slots: u16) -> Result<(), String> {
    if u32::from(index) + u32::from(slots) > u32::from(code.max_locals) {
        return Err(format!(
            "local {index} does not fit max_locals {}",
            code.max_locals
        ));
    }
    Ok(())
}

/// `iload_0` and friends: five opcodes per type, the index in the low two bits.
fn check_implicit_local(code: &Code, op: u8) -> Result<(), String> {
    let (base, slots) = match op {
        0x1a..=0x1d => (0x1a, 1),
        0x1e..=0x21 => (0x1e, 2),
        0x22..=0x25 => (0x22, 1),
        0x26..=0x29 => (0x26, 2),
        0x2a..=0x2d => (0x2a, 1),
        0x3b..=0x3e => (0x3b, 1),
        0x3f..=0x42 => (0x3f, 2),
        0x43..=0x46 => (0x43, 1),
        0x47..=0x4a => (0x47, 2),
        _ => (0x4b, 1),
    };
    check_local(code, u16::from(op - base), slots)
}

fn check_wide(code: &Code, pc: usize) -> Result<(), String> {
    let bytes = &code.bytecode;
    let op = decode::u1(bytes, pc + 1)?;
    let index = decode::u2(bytes, pc + 2)?;
    match op {
        0x15..=0x19 | 0x36..=0x3a => check_local(code, index, op_slots(op)),
        0x84 => check_local(code, index, 1),
        OP_RET => Err("jsr and ret are not supported (decisions.md D1)".into()),
        _ => Err(format!("wide of opcode {op:#04x}")),
    }
}

/// JVMS 4.9.1: which constants `ldc` may load depends on the class version.
fn check_ldc(cf: &ClassFile, index: u16) -> Result<(), String> {
    let needs = match cf.pool.get(index) {
        Some(Constant::Integer(_) | Constant::Float(_) | Constant::String(_)) => 0,
        Some(Constant::Class(_)) => 49,
        Some(Constant::MethodType(_) | Constant::MethodHandle { .. }) => 51,
        other => return Err(format!("ldc of {other:?}")),
    };
    if cf.major < needs {
        return Err(format!(
            "ldc of constant {index} needs class version {needs}"
        ));
    }
    Ok(())
}

fn method_descriptor(cf: &ClassFile, index: u16) -> Result<(String, MethodDescriptor), String> {
    let r = cf.pool.member_ref(index)?;
    Ok((r.name.to_string(), parse_method(&r.descriptor)?))
}

fn check_invoke(cf: &ClassFile, code: &Code, pc: usize, op: u8) -> Result<(), String> {
    let bytes = &code.bytecode;
    let index = decode::u2(bytes, pc + 1)?;
    let constant = cf.pool.get(index);
    let kind_ok = match (op, constant) {
        (0xb6, Some(Constant::MethodRef { .. })) => true,
        (0xb7 | 0xb8, Some(Constant::MethodRef { .. })) => true,
        // Version 52 lets these two name interface methods (JVMS 4.9.1).
        (0xb7 | 0xb8, Some(Constant::InterfaceMethodRef { .. })) => cf.major >= 52,
        (0xb9, Some(Constant::InterfaceMethodRef { .. })) => true,
        _ => false,
    };
    if !kind_ok {
        return Err(format!("invoke opcode {op:#04x} names {constant:?}"));
    }
    let (name, md) = method_descriptor(cf, index)?;
    if name.starts_with('<') && (op != 0xb7 || name != "<init>") {
        return Err(format!("{name} may not be called this way"));
    }
    if op == 0xb9 {
        let count = u16::from(decode::u1(bytes, pc + 3)?);
        if count != md.arg_slots() + 1 {
            return Err(format!(
                "invokeinterface count {count} does not fit its descriptor"
            ));
        }
        if decode::u1(bytes, pc + 4)? != 0 {
            return Err("invokeinterface's last operand is not zero".into());
        }
    }
    Ok(())
}

fn check_invokedynamic(cf: &ClassFile, code: &Code, pc: usize) -> Result<(), String> {
    let bytes = &code.bytecode;
    if cf.major < 51 {
        return Err("invokedynamic needs class version 51".into());
    }
    let index = decode::u2(bytes, pc + 1)?;
    match cf.pool.get(index) {
        Some(Constant::InvokeDynamic { .. }) => {}
        other => return Err(format!("invokedynamic names {other:?}")),
    }
    if decode::u1(bytes, pc + 3)? != 0 || decode::u1(bytes, pc + 4)? != 0 {
        return Err("invokedynamic's last operands are not zero".into());
    }
    Ok(())
}

/// JVMS 4.7.3: the range starts and ends on instructions (or at the code's
/// end), and the handler is an instruction.
fn check_handler(cf: &ClassFile, h: &Handler, layout: &Layout) -> Result<(), String> {
    let (start, end, target) = (
        usize::from(h.start_pc),
        usize::from(h.end_pc),
        usize::from(h.handler_pc),
    );
    let at_end = end == layout.starts.len();
    if start >= end || !layout.is_start(start) || !(layout.is_start(end) || at_end) {
        return Err(format!("bad exception range {start}..{end}"));
    }
    if !layout.is_start(target) {
        return Err(format!("exception handler {target} is not an instruction"));
    }
    if h.catch_type != 0 {
        let name = cf.pool.class_name(h.catch_type)?;
        if name.starts_with('[') {
            return Err(format!("handler catches the array type {name}"));
        }
    }
    Ok(())
}
