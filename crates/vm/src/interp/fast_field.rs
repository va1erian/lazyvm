//! Field instructions for `fast::run`, only for a constant-pool entry that is
//! already resolved (and, for statics, whose class is initialised): anything
//! else returns false with nothing changed, and `step` resolves, initialises
//! and reports errors itself (JVMS 5.4.3.2, 5.5).

use super::Frame;
use crate::class::{Class, ClassId, InitState, Resolved};
use crate::heap::Heap;
use crate::value::Value;

/// The resolved field behind the operand of the instruction at `f.pc`, if its
/// kind (static or not) is the one the instruction expects.
fn cached(f: &Frame, classes: &[Class], want_static: bool) -> Option<(ClassId, u32)> {
    let bytes = f.code.bytecode.get(f.pc + 1..f.pc + 3)?;
    let index = usize::from(u16::from_be_bytes([bytes[0], bytes[1]]));
    match classes.get(f.class.0 as usize)?.cp_cache.get(index) {
        Some(Some(Resolved::Field {
            class,
            slot,
            is_static,
        })) if *is_static == want_static => Some((*class, *slot)),
        _ => None,
    }
}

pub(super) fn get_field(f: &mut Frame, heap: &Heap, classes: &[Class]) -> bool {
    let Some((_, slot)) = cached(f, classes, false) else {
        return false;
    };
    let Some(&Value::Ref(Some(obj))) = f.stack.last() else {
        return false;
    };
    let Some(&v) = heap.fields(obj).ok().and_then(|fs| fs.get(slot as usize)) else {
        return false;
    };
    f.stack.pop();
    f.stack.push(v);
    f.pc += 3;
    true
}

pub(super) fn put_field(f: &mut Frame, heap: &mut Heap, classes: &[Class]) -> bool {
    let Some((_, slot)) = cached(f, classes, false) else {
        return false;
    };
    let n = f.stack.len();
    let (Some(v), Some(Value::Ref(Some(obj)))) = (
        n.checked_sub(1).map(|i| f.stack[i]),
        n.checked_sub(2).map(|i| f.stack[i]),
    ) else {
        return false;
    };
    let Some(field) = heap
        .fields_mut(obj)
        .ok()
        .and_then(|fs| fs.get_mut(slot as usize))
    else {
        return false;
    };
    if !field.same_kind(v) {
        return false;
    }
    *field = v;
    f.stack.truncate(n - 2);
    f.pc += 3;
    true
}

pub(super) fn get_static(f: &mut Frame, classes: &[Class]) -> bool {
    let Some((owner, slot)) = cached(f, classes, true) else {
        return false;
    };
    let Some(c) = classes.get(owner.0 as usize) else {
        return false;
    };
    if c.state != InitState::Initialized || f.stack.len() >= usize::from(f.code.max_stack) {
        return false;
    }
    let Some(&v) = c.statics.get(slot as usize) else {
        return false;
    };
    f.stack.push(v);
    f.pc += 3;
    true
}

pub(super) fn put_static(f: &mut Frame, classes: &mut [Class]) -> bool {
    let Some((owner, slot)) = cached(f, classes, true) else {
        return false;
    };
    let Some(&v) = f.stack.last() else {
        return false;
    };
    let Some(c) = classes.get_mut(owner.0 as usize) else {
        return false;
    };
    if c.state != InitState::Initialized {
        return false;
    }
    let Some(s) = c.statics.get_mut(slot as usize) else {
        return false;
    };
    if !s.same_kind(v) {
        return false;
    }
    *s = v;
    f.stack.pop();
    f.pc += 3;
    true
}
