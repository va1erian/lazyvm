//! Inlining: a call to a small, resolved method becomes that method's body,
//! compiled in place. The body runs on the scratch area of the context
//! (`Ctx::scratch`): r12 and r13 are pointed at its locals and stack there,
//! so the templates are the same, and set back after. A body never calls,
//! loops or allocates; anything it cannot do inline (a nested call, an
//! instruction that throws, a failed helper) exits through `run::materialize`,
//! which builds the frames the inlined calls would have had from the scratch
//! area and lets the interpreter run on from that instruction. So inlined
//! code is as exact as the rest: the interpreter sees the same frames, with
//! the same values, as if nothing had been inlined.
//!
//! Calls inlined: `invokestatic` of an initialised class, `invokespecial` of
//! a constructor or a method of the same class, and `invokevirtual` or
//! `invokeinterface` at a site that has seen one receiver class (guarded by
//! a class check, the call made as usual when it fails).

use super::asm::{Alu, Cc, R12, R13, RBX};
use super::compile::{Unit, emit_method};
use super::frame::{CTX_SCRATCH, Gen, Helper, Inlined, MAX_INLINE, SCRATCH, s};
use super::helpers;
use super::layout::{LOW, SIZE, TAG, TOP};
use crate::Vm;
use crate::class::{ClassId, InitState, MethodRef, Resolved};

/// The largest body inlined, in bytes of bytecode.
const MAX_CODE: usize = 64;

enum Guard {
    None,
    NonNull,
    Class(ClassId),
}

fn u2(code: &[u8], at: usize) -> u16 {
    u16::from_be_bytes([code[at], code[at + 1]])
}

/// The method an invoke at `index` runs, if it can be inlined, and what to
/// check first.
fn candidate(vm: &Vm, from: ClassId, op: u8, index: u16) -> Option<(MethodRef, Guard)> {
    let class = vm.class(from);
    let Some(Resolved::Method(resolved)) =
        class.cp_cache.get(usize::from(index)).copied().flatten()
    else {
        return None;
    };
    let interface = match class.file.as_ref()?.pool.get(index)? {
        classfile::Constant::MethodRef { .. } => false,
        classfile::Constant::InterfaceMethodRef { .. } => true,
        _ => return None,
    };
    let m = vm.method(resolved);
    let (target, guard) = match op {
        0xb8 if m.is_static() && vm.class(resolved.class).state == InitState::Initialized => {
            (resolved, Guard::None)
        }
        0xb7 if !m.is_static() && !interface => {
            let plain = resolved.class == from || &*m.name == "<init>";
            plain.then_some((resolved, Guard::NonNull))?
        }
        0xb6 | 0xb9 if !m.is_static() && interface == (op == 0xb9) => {
            match class.call_cache.get(usize::from(index))? {
                [Some((rc, target)), None] => (*target, Guard::Class(*rc)),
                _ => return None,
            }
        }
        _ => return None,
    };
    let t = vm.method(target);
    let sync = t.access & classfile::ACC_SYNCHRONIZED != 0;
    if t.is_native() || t.is_abstract() || sync {
        return None;
    }
    Some((target, guard))
}

/// Calls `f(pc, op)` for each instruction of a body if it is small, has no
/// handlers and no backward branch; false if it is not one, or `f` said no.
fn walk(u: &Unit, mut f: impl FnMut(usize, u8) -> bool) -> bool {
    let code = &u.code.bytecode;
    if code.len() > MAX_CODE || !u.code.handlers.is_empty() {
        return false;
    }
    let mut pc = 0;
    while pc < code.len() {
        let op = code[pc];
        if matches!(op, 0x99..=0xa7 | 0xc6 | 0xc7) && (u2(code, pc + 1) as i16) <= 0 {
            return false;
        }
        if matches!(op, 0xaa | 0xab | 0xc8 | 0xc9) || !f(pc, op) {
            return false;
        }
        match super::flow::length(code, pc) {
            Some(n) => pc += n,
            None => return false,
        }
    }
    true
}

/// The body `u` would run at inlining level `level`, and the calls it made
/// so far could all be inlined there too: inlined, it would not leave for
/// the interpreter at a call each time it runs (`run::materialize` is dear).
/// A call not yet made (its site unresolved, or no receiver seen) is fine.
fn fits(u: &Unit, level: usize, outer: &[MethodRef]) -> bool {
    let vm = u.vm;
    let class = vm.class(u.m.class);
    let mut chain = outer.to_vec();
    chain.push(u.m);
    walk(u, |pc, op| {
        // Instructions compiled code always leaves for: invokedynamic,
        // monitors, multianewarray.
        if matches!(op, 0xba | 0xc2 | 0xc3 | 0xc5) {
            return false;
        }
        if !matches!(op, 0xb6..=0xb9) {
            return true;
        }
        let index = u2(&u.code.bytecode, pc + 1);
        let made = match class.cp_cache.get(usize::from(index)).copied().flatten() {
            Some(Resolved::Method(_)) if matches!(op, 0xb6 | 0xb9) => class
                .call_cache
                .get(usize::from(index))
                .is_some_and(|w| w[0].is_some()),
            Some(_) => true,
            None => false,
        };
        if !made {
            return true;
        }
        let Some((target, _)) = candidate(vm, u.m.class, op, index) else {
            return false;
        };
        if level + 1 >= MAX_INLINE || chain.contains(&target) {
            return false;
        }
        super::compile::unit(vm, target).is_some_and(|t| fits(&t, level + 1, &chain))
    })
}

/// Inlines the invoke at `pc` (stack shape `sh`) of `caller` if it can;
/// false leaves the call to be made as usual.
pub(super) fn try_inline(g: &mut Gen, caller: &Unit, pc: usize, sh: &[bool]) -> bool {
    if !g.inlining || g.inline.len() >= MAX_INLINE {
        return false;
    }
    let code = &caller.code.bytecode;
    let op = code[pc];
    let vm = caller.vm;
    let Some((target, guard)) = candidate(vm, caller.m.class, op, u2(code, pc + 1)) else {
        return false;
    };
    if target == caller.m || g.inline.iter().any(|l| l.method == target) {
        return false;
    }
    let Some(callee) = super::compile::unit(vm, target) else {
        return false;
    };
    let m = vm.method(target);
    let nlocals = usize::from(callee.code.max_locals.max(m.arg_slots));
    let nstack = usize::from(callee.code.max_stack);
    let n = usize::from(m.arg_values);
    let d = sh.len();
    if d < n || g.scratch_used + nlocals + nstack > SCRATCH {
        return false;
    }
    let outer: Vec<MethodRef> = g
        .inline
        .iter()
        .map(|l| l.method)
        .chain([caller.m])
        .collect();
    if !fits(&callee, g.inline.len(), &outer) {
        return false;
    }
    let (slow, end, done) = (g.a.label(), g.a.label(), g.a.label());
    match guard {
        Guard::None => {}
        Guard::NonNull | Guard::Class(_) => {
            g.a.alu_mem_imm(Alu::Cmp, false, R13, s(d - n) + LOW, 0);
            g.a.jcc(Cc::E, slow);
        }
    }
    if let Guard::Class(c) = guard {
        g.check(helpers::is_class as Helper, d - n, c.0, slow);
    }
    let level = g.inline.len();
    g.save_bases(level);
    let locals_off = g.scratch_used;
    let stack_off = locals_off + nlocals;
    let at = |slot: usize| CTX_SCRATCH + ((locals_off + slot) as i32) * SIZE;
    let mut slot = 0;
    for k in 0..n {
        g.a.movups_load(0, R13, s(d - n + k));
        g.a.movups_store(RBX, at(slot), 0);
        slot += if sh[d - n + k] { 2 } else { 1 };
        if sh[d - n + k] {
            g.a.store_imm(false, RBX, at(slot - 1) + TAG, TOP as i32);
        }
    }
    for i in slot..nlocals {
        g.a.store_imm(false, RBX, at(i) + TAG, TOP as i32);
    }
    g.a.lea(R12, RBX, at(0));
    g.a.lea(R13, RBX, CTX_SCRATCH + (stack_off as i32) * SIZE);
    g.inline.push(Inlined {
        method: target,
        invoke_pc: pc,
        invoke_len: if op == 0xb9 { 5 } else { 3 },
        parent_depth: d,
        nargs: n,
        locals_off,
        nlocals,
        stack_off,
    });
    g.scratch_used += nlocals + nstack;
    emit_method(g, &callee, Some(end));
    g.inline.pop();
    g.scratch_used = locals_off;
    g.a.bind(end);
    g.restore_bases(level);
    if m.ret != b'V' {
        g.a.movups_store(R13, s(d - n), 0);
    }
    g.a.jmp(done);
    g.a.bind(slow);
    g.transfer(pc, d);
    g.a.bind(done);
    true
}
