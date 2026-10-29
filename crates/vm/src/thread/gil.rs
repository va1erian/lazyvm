//! The VM lock (docs/decisions.md D5, docs/design/threads.md).
//!
//! The whole `Vm` value is owned by one OS thread at a time: the thread that
//! runs bytecode. To let another thread run, the owner moves the `Vm` into
//! this slot and waits for its turn to take it back; a waiting thread takes
//! it out. Handing the value over, rather than sharing a reference, keeps
//! the VM core free of `unsafe` and of interior mutability: whoever holds a
//! `&mut Vm` really is alone with it.
//!
//! Turns are first come, first served (a ticket each), so a thread that
//! yields at a safepoint goes behind the threads already waiting. A thread
//! running bytecode keeps the VM for at least `SLICE`: a waiter asks it to
//! yield only once it has waited that long, as handing the VM over at every
//! safepoint would spend all the time switching.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use crate::Vm;

pub(crate) struct Gil {
    slot: Mutex<Slot>,
    turn: Condvar,
    /// Set by a thread that has waited a whole slice: the owner should
    /// yield at its next safepoint. Cleared by each new owner.
    yield_request: AtomicBool,
}

/// How long the owner may run bytecode while others wait.
const SLICE: Duration = Duration::from_millis(5);

struct Slot {
    vm: Option<Vm>,
    next_ticket: u64,
    serving: u64,
}

impl Gil {
    /// A lock whose VM is held by its creator.
    pub(crate) fn new() -> Arc<Gil> {
        Arc::new(Gil {
            slot: Mutex::new(Slot {
                vm: None,
                next_ticket: 0,
                serving: 0,
            }),
            turn: Condvar::new(),
            yield_request: AtomicBool::new(false),
        })
    }

    /// Whether a waiting thread asked for the VM. A relaxed read is
    /// enough: a late answer only delays a yield to the next safepoint.
    pub(crate) fn wanted(&self) -> bool {
        self.yield_request.load(Ordering::Relaxed)
    }

    fn lock(&self) -> MutexGuard<'_, Slot> {
        // The slot holds no invariant a panic elsewhere could break.
        self.slot.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Gives the VM up; the next thread in line takes it.
    pub(crate) fn release(&self, vm: Vm) {
        let mut slot = self.lock();
        slot.vm = Some(vm);
        drop(slot);
        self.turn.notify_all();
    }

    /// Waits for this thread's turn and takes the VM.
    pub(crate) fn acquire(&self) -> Vm {
        let mut slot = self.lock();
        let ticket = slot.next_ticket;
        slot.next_ticket += 1;
        loop {
            if slot.serving == ticket
                && let Some(vm) = slot.vm.take()
            {
                slot.serving += 1;
                self.yield_request.store(false, Ordering::Relaxed);
                return vm;
            }
            let (s, wait) = self
                .turn
                .wait_timeout(slot, SLICE)
                .unwrap_or_else(PoisonError::into_inner);
            slot = s;
            if wait.timed_out() {
                self.yield_request.store(true, Ordering::Relaxed);
            }
        }
    }
}

impl Vm {
    /// Runs `f` with the VM released, so other threads can run bytecode
    /// meanwhile, then waits to take the VM back. `f` gets no access to the
    /// VM, and the caller must not rely on any `Ref` it has not rooted: a
    /// collection may run while this thread is outside.
    pub fn blocking<T>(&mut self, f: impl FnOnce() -> T) -> T {
        let me = self.current;
        self.park_state(me);
        let gil = Arc::clone(&self.gil);
        let vm = std::mem::replace(self, Vm::placeholder(Arc::clone(&gil)));
        gil.release(vm);
        let out = f();
        *self = gil.acquire();
        self.current = me;
        self.resume_state(me);
        out
    }

    /// A safepoint: lets a waiting thread run. The interpreter calls this at
    /// backward branches and calls (docs/design/threads.md).
    pub(crate) fn safepoint(&mut self) {
        if self.gil.wanted() {
            self.blocking(|| ());
        }
    }
}
