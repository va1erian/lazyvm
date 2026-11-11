//! Invokes from compiled code through a record of the call site made when
//! the caller was compiled (`CallSite`): the method for `invokestatic` and
//! `invokespecial`, the call cache's slot for a virtual call. A call that
//! the record cannot settle (a receiver class not in the cache, a null
//! receiver) goes the general way (`run::transfer`'s), so the outcome is
//! the same; only the lookups are saved.

use super::frame::Ctx;
use super::run;
use crate::Vm;
use crate::class::{ClassId, MethodRef};
use crate::value::Value;

/// An invoke in compiled code, as far as compiling it could tell.
pub(super) struct CallSite {
    pub pc: u32,
    pub op: u8,
    /// The pool index, in `class`, whose call cache a virtual call uses.
    pub index: u16,
    pub class: ClassId,
    /// The method an `invokestatic` (of an initialised class) or a plain
    /// `invokespecial` runs; None for a virtual call.
    pub target: Option<MethodRef>,
    /// The call's arguments, the receiver included, in stack entries.
    pub nargs: u16,
}

/// The invoke of `site` with `sp` stack entries, from compiled code; the
/// result is `run::transfer`'s.
pub(super) extern "C" fn call(ctx: *mut Ctx, site: *const CallSite, sp: u32) -> usize {
    // SAFETY: as in `run::transfer`; `site` is a record of the calling
    // code, which lives as long as that code (a recompile retires both).
    let (ctx, vm, site) = unsafe { (&mut *ctx, &mut *(*ctx).vm, &*site) };
    let Some(f) = vm.frames.last_mut() else {
        return 0;
    };
    if sp as usize > f.stack.capacity() {
        return 0;
    }
    f.pc = site.pc as usize;
    // SAFETY: compiled code wrote every entry below `sp` (as at an exit).
    unsafe { f.stack.set_len(sp as usize) };
    // A waiting thread gets its turn through the interpreter's safepoint.
    if vm.gil.wanted() {
        return 0;
    }
    let made = match select(vm, site) {
        Some(target) => vm.fast_call(target, if site.op == 0xb9 { 5 } else { 3 }),
        None => vm.fast_transfer(site.op, ctx.base as usize),
    };
    if !made {
        return 0;
    }
    run::entered(vm, ctx, site.op)
}

/// The method the call runs, if the record settles it: a known target
/// (with a receiver, but for `invokestatic`), or the call cache's entry for
/// the receiver's class.
fn select(vm: &Vm, site: &CallSite) -> Option<MethodRef> {
    if site.op == 0xb8 {
        return site.target;
    }
    let f = vm.frames.last()?;
    let at = f.stack.len().checked_sub(usize::from(site.nargs))?;
    let Value::Ref(Some(r)) = *f.stack.get(at)? else {
        return None;
    };
    let rc = vm.heap.class_of(r).ok()?;
    if site.target.is_some() {
        return site.target;
    }
    let ways = vm
        .class(site.class)
        .call_cache
        .get(usize::from(site.index))?;
    ways.iter().flatten().find(|w| w.0 == rc).map(|w| w.1)
}
