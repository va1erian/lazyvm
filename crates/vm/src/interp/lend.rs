//! Lending the VM to callbacks that cannot borrow it.
//!
//! A native that hands control to a toolkit's event loop (xui's `run`, for
//! `lazy.ui`) holds `&mut Vm`, but the toolkit calls back through `'static`
//! closures that cannot capture that borrow. `Vm::lend` parks the borrow in a
//! thread-local for the duration of a call, and `with_lent` takes it out
//! again for one callback. The slot is emptied while a callback holds the
//! VM, so two `&mut Vm` never exist at once, and a nested `lend` (a native
//! that runs a nested loop from inside a callback) stacks on top.
#![allow(unsafe_code)]

use std::cell::Cell;
use std::ptr;

use crate::Vm;

thread_local! {
    static LENT: Cell<*mut Vm> = const { Cell::new(ptr::null_mut()) };
}

/// Puts the previous slot value back, even if the callback panics.
struct Restore(*mut Vm);

impl Drop for Restore {
    fn drop(&mut self) {
        LENT.with(|slot| slot.set(self.0));
    }
}

impl Vm {
    /// Runs `f` with this VM available to `with_lent` on this thread.
    pub fn lend<R>(&mut self, f: impl FnOnce() -> R) -> R {
        let previous = LENT.with(|slot| slot.replace(self as *mut Vm));
        let _restore = Restore(previous);
        f()
    }
}

/// Runs `f` on the VM lent by the innermost `Vm::lend` on this thread, or
/// returns `None` if none is lent or a callback already holds it.
pub fn with_lent<R>(f: impl FnOnce(&mut Vm) -> R) -> Option<R> {
    let vm = LENT.with(|slot| slot.replace(ptr::null_mut()));
    if vm.is_null() {
        return None;
    }
    let _restore = Restore(vm);
    // Why this holds: `vm` came from the `&mut Vm` that `lend` received. That
    // borrow lives until `lend` returns, and `lend` is still on this thread's
    // stack (it restores the slot before returning), so the pointer is
    // valid. The lender does not touch the VM while `f` in `lend` runs, since
    // `lend` borrows it mutably, and the slot is null while this reference
    // exists, so no other `with_lent` can create a second one. The reference
    // does not escape `f`.
    // SAFETY: see above; `vm` is valid and unaliased for the call.
    Some(f(unsafe { &mut *vm }))
}
