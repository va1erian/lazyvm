//! Compiling a method: its stack shapes (`flow`), what the templates need
//! resolved beforehand, then one template per reachable instruction.

use super::{Compiled, NONE, flow, frame, layout, mem, ops};
use crate::Vm;
use crate::class::{InitState, MethodRef, Resolved};
use classfile::Constant;

pub(super) fn compile(vm: &Vm, m: MethodRef) -> Option<Compiled> {
    let class = vm.class(m.class);
    if !class.checked {
        return None;
    }
    let method = vm.method(m);
    let code = method.code.as_ref()?;
    let pool = &class.file.as_ref()?.pool;
    let shapes = flow::shapes(code, pool)?;
    let cache = &class.cp_cache;
    let cached = |i: u16| cache.get(usize::from(i)).copied().flatten();
    let constant = |i: u16| -> Option<(u32, u32, u64)> {
        Some(match pool.get(i)? {
            Constant::Integer(v) => (layout::INT, *v as u32, 0),
            Constant::Float(v) => (layout::FLOAT, v.to_bits(), 0),
            Constant::Long(v) => (layout::LONG, 0, *v as u64),
            Constant::Double(v) => (layout::DOUBLE, 0, v.to_bits()),
            Constant::String(_) => match cached(i)? {
                Resolved::String(r) => (layout::REF, r.index(), u64::from(r.generation)),
                _ => return None,
            },
            _ => return None,
        })
    };
    let field = |i: u16| match cached(i)? {
        Resolved::Field {
            slot,
            is_static: false,
            ..
        } => Some(slot),
        _ => None,
    };
    let statics = |i: u16| match cached(i)? {
        Resolved::Field {
            class,
            slot,
            is_static: true,
        } => {
            let c = vm.class(class);
            let ok = c.state == InitState::Initialized && (slot as usize) < c.statics.len();
            ok.then(|| c.statics.as_ptr().wrapping_add(slot as usize) as usize)
        }
        _ => None,
    };
    let bc = &code.bytecode;
    let mut g = frame::Gen::new();
    let labels: Vec<_> = shapes
        .iter()
        .map(|s| s.as_ref().map(|_| g.a.label()))
        .collect();
    let env = ops::Env {
        code: bc,
        shapes: &shapes,
        labels: &labels,
        constant: &constant,
        field: &field,
        statics: &statics,
    };
    for (pc, shape) in shapes.iter().enumerate() {
        if let (Some(shape), Some(l)) = (shape, labels[pc]) {
            g.a.bind(l);
            ops::emit(&mut g, &env, pc, shape);
        }
    }
    let entries: Vec<u32> = labels
        .iter()
        .map(|l| l.and_then(|l| g.a.pos(l)).map_or(NONE, |p| p as u32))
        .collect();
    let depth = shapes
        .iter()
        .map(|s| s.as_ref().map_or(u16::MAX, |s| s.len() as u16))
        .collect();
    let bytes = g.finish()?;
    Some(Compiled {
        code: mem::Code::install(&bytes)?,
        entries,
        depth,
        max_stack: usize::from(code.max_stack),
        locals: usize::from(code.max_locals),
    })
}
