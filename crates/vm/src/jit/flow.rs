//! The operand stack's shape at every instruction of a method: how many
//! entries, and which are wide (`long`, `double`; one entry each on LazyVM's
//! stack). Compiled code addresses stack entries at fixed offsets, so this
//! must be exact; any doubt (an instruction it does not know, `jsr`, shapes
//! that disagree where paths meet, a stack past `max_stack`) gives None and
//! the method stays interpreted. It assumes verified code and checks only
//! what it needs.

use classfile::{Code, Constant, ConstantPool, descriptor};

/// Per byte of code: the stack shape before the instruction starting there,
/// or None for a byte inside an instruction or a dead instruction.
pub(super) type Shapes = Vec<Option<Vec<bool>>>;

/// The length of the instruction at `pc`, or None at a bad one.
pub(super) fn length(code: &[u8], pc: usize) -> Option<usize> {
    let op = *code.get(pc)?;
    Some(match op {
        0x10 | 0x12 | 0x15..=0x19 | 0x36..=0x3a | 0xa9 | 0xbc => 2,
        0x11 | 0x13 | 0x14 | 0x84 | 0x99..=0xa8 | 0xb2..=0xb8 | 0xbb | 0xbd | 0xc0 | 0xc1 => 3,
        0xc6 | 0xc7 => 3,
        0xc5 => 4,
        0xb9 | 0xba | 0xc8 | 0xc9 => 5,
        0xc4 => {
            if *code.get(pc + 1)? == 0x84 {
                6
            } else {
                4
            }
        }
        0xaa | 0xab => {
            let base = (pc + 4) & !3;
            let word = |at: usize| -> Option<i32> {
                let b = code.get(at..at + 4)?;
                Some(i32::from_be_bytes([b[0], b[1], b[2], b[3]]))
            };
            let n = if op == 0xaa {
                let (lo, hi) = (word(base + 4)?, word(base + 8)?);
                12 + 4 * usize::try_from(i64::from(hi) - i64::from(lo) + 1).ok()?
            } else {
                8 + 8 * usize::try_from(word(base + 4)?).ok()?
            };
            base + n - pc
        }
        0xca..=0xff => return None,
        _ => 1,
    })
}

fn u2(code: &[u8], at: usize) -> Option<u16> {
    Some(u16::from_be_bytes([*code.get(at)?, *code.get(at + 1)?]))
}

fn i2(code: &[u8], at: usize) -> Option<i16> {
    u2(code, at).map(|v| v as i16)
}

fn i4(code: &[u8], at: usize) -> Option<i32> {
    let b = code.get(at..at + 4)?;
    Some(i32::from_be_bytes([b[0], b[1], b[2], b[3]]))
}

fn wide_type(t: &descriptor::FieldType) -> bool {
    matches!(
        t,
        descriptor::FieldType::Long | descriptor::FieldType::Double
    )
}

fn field_wide(pool: &ConstantPool, index: u16) -> Option<bool> {
    let r = pool.member_ref(index).ok()?;
    Some(wide_type(&descriptor::parse_field(&r.descriptor).ok()?))
}

/// (arguments as stack entries, result: None for void, Some(wide)).
fn call_shape(desc: &str) -> Option<(usize, Option<bool>)> {
    let md = descriptor::parse_method(desc).ok()?;
    Some((md.params.len(), md.ret.as_ref().map(wide_type)))
}

/// The successors of the instruction at `pc` and the shape after it.
struct Step {
    after: Vec<bool>,
    next: bool,
    jumps: Vec<usize>,
}

fn pop(s: &mut Vec<bool>, n: usize) -> Option<()> {
    let len = s.len().checked_sub(n)?;
    s.truncate(len);
    Some(())
}

fn target(pc: usize, off: i32) -> Option<usize> {
    usize::try_from(pc as i64 + i64::from(off)).ok()
}

/// The stack shuffles of JVMS 6.5, on categories (as `ops_basic::stack_op`).
fn shuffle(s: &mut Vec<bool>, op: u8) -> Option<()> {
    let top = |s: &Vec<bool>, d: usize| s.len().checked_sub(d + 1).map(|i| s[i]);
    match op {
        0x57 => pop(s, 1),
        0x58 => pop(s, if top(s, 0)? { 1 } else { 2 }),
        0x59 => {
            let v = top(s, 0)?;
            s.push(v);
            Some(())
        }
        0x5f => {
            let n = s.len();
            if n < 2 {
                return None;
            }
            s.swap(n - 1, n - 2);
            Some(())
        }
        _ => {
            // dup_x1, dup_x2, dup2, dup2_x1, dup2_x2: copy the top `copy`
            // entries `under` entries down.
            let w0 = top(s, 0)?;
            let (copy, under) = match op {
                0x5a => (1, 1),
                0x5b => (1, if top(s, 1)? { 1 } else { 2 }),
                0x5c => (if w0 { 1 } else { 2 }, 0),
                0x5d => (if w0 { 1 } else { 2 }, 1),
                0x5e => {
                    let copy = if w0 { 1 } else { 2 };
                    let below = top(s, copy)?;
                    (copy, if below { 1 } else { 2 })
                }
                _ => return None,
            };
            let at = s.len().checked_sub(copy + under)?;
            let top_part: Vec<bool> = s[s.len() - copy..].to_vec();
            for (i, v) in top_part.into_iter().enumerate() {
                s.insert(at + i, v);
            }
            Some(())
        }
    }
}

fn step(code: &[u8], pool: &ConstantPool, pc: usize, before: &[bool]) -> Option<Step> {
    let op = code[pc];
    let mut s = before.to_vec();
    let mut next = true;
    let mut jumps = Vec::new();
    let push = |s: &mut Vec<bool>, w: bool| s.push(w);
    match op {
        0x00 | 0x84 => {}
        0x01..=0x08 | 0x0b..=0x0d | 0x10 | 0x11 | 0xbb => push(&mut s, false),
        0x09 | 0x0a | 0x0e | 0x0f | 0x14 => push(&mut s, true),
        0x12 | 0x13 => {
            let index = if op == 0x12 {
                u16::from(*code.get(pc + 1)?)
            } else {
                u2(code, pc + 1)?
            };
            let wide = matches!(pool.get(index)?, Constant::Long(_) | Constant::Double(_));
            push(&mut s, wide);
        }
        0x15 | 0x17 | 0x19 | 0x1a..=0x1d | 0x22..=0x25 | 0x2a..=0x2d => push(&mut s, false),
        0x16 | 0x18 | 0x1e..=0x21 | 0x26..=0x29 => push(&mut s, true),
        0x2e..=0x35 => {
            pop(&mut s, 2)?;
            push(&mut s, op == 0x2f || op == 0x31);
        }
        0x36..=0x3a | 0x3b..=0x4e | 0xc2 | 0xc3 => pop(&mut s, 1)?,
        0x4f..=0x56 => pop(&mut s, 3)?,
        0x57..=0x5f => shuffle(&mut s, op)?,
        0x60..=0x73 | 0x78..=0x83 => {
            pop(&mut s, 2)?;
            let kind = if op >= 0x78 {
                (op - 0x78) % 2
            } else {
                (op - 0x60) % 4
            };
            push(&mut s, kind == 1 || kind == 3);
        }
        0x74..=0x77 => {
            pop(&mut s, 1)?;
            push(&mut s, op == 0x75 || op == 0x77);
        }
        0x85..=0x93 => {
            pop(&mut s, 1)?;
            push(
                &mut s,
                matches!(op, 0x85 | 0x87 | 0x8a | 0x8c | 0x8d | 0x8f),
            );
        }
        0x94..=0x98 => {
            pop(&mut s, 2)?;
            push(&mut s, false);
        }
        0x99..=0x9e | 0xc6 | 0xc7 => {
            pop(&mut s, 1)?;
            jumps.push(target(pc, i32::from(i2(code, pc + 1)?))?);
        }
        0x9f..=0xa6 => {
            pop(&mut s, 2)?;
            jumps.push(target(pc, i32::from(i2(code, pc + 1)?))?);
        }
        0xa7 | 0xc8 => {
            let off = if op == 0xa7 {
                i32::from(i2(code, pc + 1)?)
            } else {
                i4(code, pc + 1)?
            };
            jumps.push(target(pc, off)?);
            next = false;
        }
        0xaa | 0xab => {
            pop(&mut s, 1)?;
            let base = (pc + 4) & !3;
            jumps.push(target(pc, i4(code, base)?)?);
            let (count, first, stride) = if op == 0xaa {
                let (lo, hi) = (i4(code, base + 4)?, i4(code, base + 8)?);
                (
                    usize::try_from(i64::from(hi) - i64::from(lo) + 1).ok()?,
                    base + 12,
                    4,
                )
            } else {
                (usize::try_from(i4(code, base + 4)?).ok()?, base + 12, 8)
            };
            for k in 0..count {
                jumps.push(target(pc, i4(code, first + k * stride)?)?);
            }
            next = false;
        }
        0xac..=0xb1 | 0xbf => next = false,
        0xb2 | 0xb4 => {
            if op == 0xb4 {
                pop(&mut s, 1)?;
            }
            push(&mut s, field_wide(pool, u2(code, pc + 1)?)?);
        }
        0xb3 | 0xb5 => pop(&mut s, if op == 0xb5 { 2 } else { 1 })?,
        0xb6..=0xba => {
            let index = u2(code, pc + 1)?;
            let desc = if op == 0xba {
                match pool.get(index)? {
                    Constant::InvokeDynamic { name_and_type, .. } => {
                        pool.name_and_type(*name_and_type).ok()?.1.clone()
                    }
                    _ => return None,
                }
            } else {
                pool.member_ref(index).ok()?.descriptor
            };
            let (args, ret) = call_shape(&desc)?;
            let receiver = usize::from(op != 0xb8 && op != 0xba);
            pop(&mut s, args + receiver)?;
            if let Some(w) = ret {
                push(&mut s, w);
            }
        }
        0xbc | 0xbd | 0xbe | 0xc0 | 0xc1 => {
            pop(&mut s, 1)?;
            push(&mut s, false);
        }
        0xc4 => match code.get(pc + 1)? {
            0x15 | 0x17 | 0x19 => push(&mut s, false),
            0x16 | 0x18 => push(&mut s, true),
            0x36..=0x3a => pop(&mut s, 1)?,
            0x84 => {}
            _ => return None,
        },
        0xc5 => {
            pop(&mut s, usize::from(*code.get(pc + 3)?))?;
            push(&mut s, false);
        }
        _ => return None, // jsr, ret, jsr_w and anything unknown
    }
    Some(Step {
        after: s,
        next,
        jumps,
    })
}

/// One past the last local the instruction at `pc` uses (0 if none), or
/// None if its operands run past the code.
fn locals_end(bc: &[u8], pc: usize) -> Option<usize> {
    let op = *bc.get(pc)?;
    // Kinds 1 and 3 (long, double) take two locals.
    let width = |kind: u8| if kind == 1 || kind == 3 { 2 } else { 1 };
    let (index, n) = match op {
        0x15..=0x19 => (usize::from(*bc.get(pc + 1)?), width(op - 0x15)),
        0x36..=0x3a => (usize::from(*bc.get(pc + 1)?), width(op - 0x36)),
        0x1a..=0x2d => (usize::from((op - 0x1a) % 4), width((op - 0x1a) / 4)),
        0x3b..=0x4e => (usize::from((op - 0x3b) % 4), width((op - 0x3b) / 4)),
        0x84 | 0xa9 => (usize::from(*bc.get(pc + 1)?), 1),
        0xc4 => {
            let inner = *bc.get(pc + 1)?;
            let index = usize::from(u2(bc, pc + 2)?);
            match inner {
                0x15..=0x19 => (index, width(inner - 0x15)),
                0x36..=0x3a => (index, width(inner - 0x36)),
                _ => (index, 1),
            }
        }
        _ => return Some(0),
    };
    Some(index + n)
}

/// The shapes of `code`, or None if this method cannot be compiled.
pub(super) fn shapes(code: &Code, pool: &ConstantPool) -> Option<Shapes> {
    let bc = &code.bytecode;
    let mut shapes: Shapes = vec![None; bc.len()];
    let mut work = vec![0usize];
    shapes[0] = Some(Vec::new());
    let max = usize::from(code.max_stack);
    let merge = |at: usize, s: &[bool], shapes: &mut Shapes, work: &mut Vec<usize>| -> Option<()> {
        let slot = shapes.get_mut(at)?;
        match slot {
            Some(old) => (old.as_slice() == s).then_some(()),
            None => {
                *slot = Some(s.to_vec());
                work.push(at);
                Some(())
            }
        }
    };
    while let Some(pc) = work.pop() {
        let before = shapes[pc].clone()?;
        if before.len() > max {
            return None;
        }
        // An instruction covered by a handler can throw into it.
        for h in &code.handlers {
            if usize::from(h.start_pc) <= pc && pc < usize::from(h.end_pc) {
                merge(usize::from(h.handler_pc), &[false], &mut shapes, &mut work)?;
            }
        }
        // Compiled code addresses locals without a bounds check.
        if locals_end(bc, pc)? > usize::from(code.max_locals) {
            return None;
        }
        let st = step(bc, pool, pc, &before)?;
        if st.after.len() > max {
            return None;
        }
        if st.next {
            merge(pc + length(bc, pc)?, &st.after, &mut shapes, &mut work)?;
        }
        for j in st.jumps {
            merge(j, &st.after, &mut shapes, &mut work)?;
        }
    }
    // Every recorded shape must be at an instruction start.
    let mut pc = 0;
    let mut starts = vec![false; bc.len()];
    while pc < bc.len() {
        starts[pc] = true;
        pc += length(bc, pc)?;
    }
    // The last instruction must end with the code: templates read operands
    // without a bounds check.
    if pc != bc.len() {
        return None;
    }
    shapes
        .iter()
        .enumerate()
        .all(|(i, s)| s.is_none() || starts[i])
        .then_some(shapes)
}
