//! Lending the VM to callbacks that cannot borrow it.
//!
//! A native that hands control to a toolkit's event loop (xui's `run`, for
//! `lazy.ui`) holds `&mut Vm`, but the toolkit calls back through `'static`
//! closures that cannot capture that borrow, and while the loop waits for
//! events other Java threads must run (`Ui.executor()`, docs/ui.md,
//! "Threads"). So `Vm::lend` gives the VM up for the length of the loop, as
//! `blocking` does, and `with_lent` takes it back, in turn with the other
//! threads, for one callback, then gives it up again. The VM is never
//! reached through a pointer: it moves through the lock like any thread's.

use std::cell::RefCell;
use std::sync::Arc;

use super::Gil;
use super::outside::with_vm;
use crate::Vm;

thread_local! {
    /// The lends on this thread, innermost last: the lock and this thread's
    /// index. Empty while a callback holds the VM.
    static LENT: RefCell<Vec<(Arc<Gil>, usize)>> = const { RefCell::new(Vec::new()) };
}

/// Takes the innermost lend off the stack until dropped, so a callback's own
/// `with_lent` finds none while it holds the VM, and a nested `lend` stacks
/// above it.
struct Taken(Option<(Arc<Gil>, usize)>);

impl Drop for Taken {
    fn drop(&mut self) {
        if let Some(lent) = self.0.take() {
            LENT.with(|l| l.borrow_mut().push(lent));
        }
    }
}

/// Pops the lend pushed by `lend` when it returns, even on a panic.
struct Pushed;

impl Drop for Pushed {
    fn drop(&mut self) {
        LENT.with(|l| l.borrow_mut().pop());
    }
}

impl Vm {
    /// Runs `f` with the VM given up, so that other threads run meanwhile,
    /// and available to `with_lent` on this thread. As with `blocking`, a
    /// `Ref` the caller has not rooted may be collected while `f` runs.
    pub fn lend<R>(&mut self, f: impl FnOnce() -> R) -> R {
        let gil = Arc::clone(&self.gil);
        let me = self.current;
        self.blocking(move || {
            LENT.with(|l| l.borrow_mut().push((gil, me)));
            let _pop = Pushed;
            f()
        })
    }
}

/// Runs `f` with the VM taken back from the innermost `Vm::lend` on this
/// thread, or returns `None` if none is lent or a callback already holds it.
pub fn with_lent<R>(f: impl FnOnce(&mut Vm) -> R) -> Option<R> {
    let taken = Taken(LENT.with(|l| l.borrow_mut().pop()));
    let (gil, me) = taken.0.clone()?;
    Some(with_vm(&gil, me, |vm| {
        vm.hold_if_suspended();
        f(vm)
    }))
}

#[cfg(test)]
#[path = "lend_tests.rs"]
mod tests;
