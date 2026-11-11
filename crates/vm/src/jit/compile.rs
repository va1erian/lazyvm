//! Compiling a method: its stack shapes (`flow`), what the templates need
//! resolved beforehand, then one template per reachable instruction. The
//! same emission serves a method inlined into another (`inline.rs`).

use super::asm::Label;
use super::frame::Gen;
use super::{Compiled, NONE, flow, inline, layout, mem, ops};
use crate::Vm;
use crate::class::{ClassKind, InitState, MethodRef, Resolved};
use classfile::{Code, Constant};

/// A method to emit: its code and stack shapes.
pub(super) struct Unit<'a> {
    pub vm: &'a Vm,
    pub m: MethodRef,
    pub code: &'a Code,
    pub shapes: flow::Shapes,
}

/// The method `m` if it can be compiled: a class the verifier checked, code,
/// and stack shapes `flow` is sure of.
pub(super) fn unit(vm: &Vm, m: MethodRef) -> Option<Unit<'_>> {
    let class = vm.class(m.class);
    if !class.checked {
        return None;
    }
    let code = vm.method(m).code.as_deref()?;
    let shapes = flow::shapes(code, &class.file.as_ref()?.pool)?;
    Some(Unit {
        vm,
        m,
        code,
        shapes,
    })
}

/// Emits `u`'s reachable instructions; `ret` is where an inlined method's
/// returns go (the value, if any, in xmm0). The labels of its pcs.
pub(super) fn emit_method(g: &mut Gen, u: &Unit, ret: Option<Label>) -> Vec<Option<Label>> {
    let vm = u.vm;
    let class = vm.class(u.m.class);
    let Some(file) = class.file.as_ref() else {
        return Vec::new();
    };
    let pool = &file.pool;
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
    let class_id = |i: u16| match cached(i)? {
        Resolved::Class(c) => Some(c.0),
        _ => None,
    };
    let new_class = |pc: usize| -> Option<u32> {
        let code = &u.code.bytecode;
        let index = || u16::from_be_bytes([code[pc + 1], code[pc + 2]]);
        let id = match code[pc] {
            0xbb => {
                let Resolved::Class(c) = cached(index())? else {
                    return None;
                };
                let k = vm.class(c);
                let plain = matches!(k.kind, ClassKind::Instance)
                    && !k.is_interface()
                    && k.access & classfile::ACC_ABSTRACT == 0;
                (plain && k.verified && k.state == InitState::Initialized).then_some(c)?
            }
            0xbc => {
                let elem = match code[pc + 1] {
                    4 => "[Z",
                    5 => "[C",
                    6 => "[F",
                    7 => "[D",
                    8 => "[B",
                    9 => "[S",
                    10 => "[I",
                    11 => "[J",
                    _ => return None,
                };
                vm.loaded_class(elem)?
            }
            _ => {
                let Resolved::Class(c) = cached(index())? else {
                    return None;
                };
                vm.loaded_class(&crate::interp::array_name(&vm.class(c).name))?
            }
        };
        Some(id.0)
    };
    let try_inline = |g: &mut Gen, pc: usize, sh: &[bool]| inline::try_inline(g, u, pc, sh);
    let labels: Vec<_> = u
        .shapes
        .iter()
        .map(|s| s.as_ref().map(|_| g.a.label()))
        .collect();
    let env = ops::Env {
        code: &u.code.bytecode,
        shapes: &u.shapes,
        labels: &labels,
        constant: &constant,
        field: &field,
        statics: &statics,
        class: &class_id,
        new_class: &new_class,
        inline: &try_inline,
        ret,
    };
    for (pc, shape) in u.shapes.iter().enumerate() {
        if let (Some(shape), Some(l)) = (shape, labels[pc]) {
            g.a.bind(l);
            ops::emit(g, &env, pc, shape);
        }
    }
    labels
}

/// Compiles `m`, inlining small calls if `inlining`.
pub(super) fn compile(vm: &Vm, m: MethodRef, inlining: bool) -> Option<Compiled> {
    let u = unit(vm, m)?;
    let mut g = Gen::new();
    g.inlining = inlining;
    let labels = emit_method(&mut g, &u, None);
    let entries: Vec<u32> = labels
        .iter()
        .map(|l| l.and_then(|l| g.a.pos(l)).map_or(NONE, |p| p as u32))
        .collect();
    let depth = u
        .shapes
        .iter()
        .map(|s| s.as_ref().map_or(u16::MAX, |s| s.len() as u16))
        .collect();
    let deopts = std::mem::take(&mut g.deopts);
    let bytes = g.finish()?;
    Some(Compiled {
        code: mem::Code::install(&bytes)?,
        entries,
        depth,
        deopts,
        max_stack: usize::from(u.code.max_stack),
        locals: usize::from(u.code.max_locals),
    })
}
