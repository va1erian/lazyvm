//! The VM lock (docs/decisions.md D5, docs/design/threads.md).
//!
//! The whole `Vm` value is owned by one OS thread at a time: the thread that
//! runs bytecode. To let another thread run, the owner moves the `Vm` into
//! this slot and waits for its turn to take it back; a waiting thread takes
//! it out. Handing the value over, rather than sharing a reference, keeps
//! the VM core free of `unsafe` and of interior mutability: whoever holds a
//! `&mut Vm` really is alone with it.
//!
//! Who goes next is the point of this file, because a switch through the line
//! must not cost a wait behind every busy thread (threads.md, "Turns"):
//!
//! - A slice is `SLICE` of VM time, and a thread running bytecode is asked to
//!   yield only when its slice is spent and someone waits.
//! - A thread that gave the VM up to block leaves the rest of its slice to a
//!   thread that was woken: threads handing a lock around share one slice.
//!   A running thread names who it wakes, so that one is expected before it
//!   has even got out of its OS wait, and busy threads cannot slip in first.
//! - A spent slice goes to the oldest preempted thread if woken threads had
//!   the last one, and the other way round, so neither side starves the other.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

use crate::Vm;

pub(crate) struct Gil {
    slot: Mutex<Slot>,
    turn: Condvar,
    /// Set by a waiting thread once the slice is spent: the owner should
    /// yield at its next safepoint. Cleared by each new owner.
    yield_request: AtomicBool,
}

/// How long threads may run bytecode while others wait.
const SLICE: Duration = Duration::from_millis(5);

/// Which part of the line a thread joins.
#[derive(Clone, Copy, PartialEq, Eq)]
pub(super) enum Line {
    /// It could have gone on running: its slice ended, or it yielded.
    Preempted,
    /// It was blocked, and is back.
    Woken,
}

/// A thread in the woken line.
struct Woken {
    thread: usize,
    /// False while it is named by its waker but still leaving its OS wait.
    arrived: bool,
}

struct Slot {
    vm: Option<Vm>,
    /// Oldest first.
    preempted: VecDeque<usize>,
    /// In the order they were woken.
    woken: VecDeque<Woken>,
    slice_start: Instant,
    /// Whether the running slice went to the woken line.
    slice_was_woken: bool,
}

impl Slot {
    fn slice_left(&self) -> Duration {
        SLICE.saturating_sub(self.slice_start.elapsed())
    }

    /// The thread that takes the VM now, if any may.
    fn next_up(&self) -> Option<usize> {
        let woken = self.woken.iter().find(|w| w.arrived).map(|w| w.thread);
        let preempted = self.preempted.front().copied();
        if !self.slice_left().is_zero() {
            // A woken thread on its way is worth waiting for: it uses this
            // slice, and at worst the slice runs out with the VM idle.
            return if self.woken.is_empty() {
                preempted
            } else {
                woken
            };
        }
        if self.slice_was_woken {
            preempted.or(woken)
        } else {
            woken.or(preempted)
        }
    }

    /// Takes `me` off the line and starts or continues a slice.
    fn serve(&mut self, me: usize) {
        let was_woken = self.woken.iter().position(|w| w.thread == me);
        match was_woken {
            Some(at) => {
                self.woken.remove(at);
            }
            None => {
                self.preempted.pop_front();
            }
        }
        if was_woken.is_none() || self.slice_left().is_zero() {
            self.slice_start = Instant::now();
        }
        self.slice_was_woken = was_woken.is_some();
    }
}

impl Gil {
    /// A lock whose VM is held by its creator.
    pub(crate) fn new() -> Arc<Gil> {
        Arc::new(Gil {
            slot: Mutex::new(Slot {
                vm: None,
                preempted: VecDeque::new(),
                woken: VecDeque::new(),
                slice_start: Instant::now(),
                slice_was_woken: false,
            }),
            turn: Condvar::new(),
            yield_request: AtomicBool::new(false),
        })
    }

    /// Whether a waiting thread asked for the VM. A relaxed read is
    /// enough: a late answer only delays a yield to the next safepoint.
    #[inline]
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

    /// Puts `thread`, about to be woken by the VM's holder, in the woken
    /// line. It must be parked (`ThreadEntry::parked`), which promises that
    /// it will come to `acquire`, so the place does not stay taken for ever.
    pub(super) fn expect(&self, thread: usize) {
        let mut slot = self.lock();
        if !slot.woken.iter().any(|w| w.thread == thread) {
            slot.woken.push_back(Woken {
                thread,
                arrived: false,
            });
        }
    }

    /// Joins `line`, waits for this thread's turn and takes the VM.
    pub(super) fn acquire(&self, me: usize, line: Line) -> Vm {
        let mut slot = self.lock();
        match line {
            Line::Preempted => slot.preempted.push_back(me),
            Line::Woken => match slot.woken.iter_mut().find(|w| w.thread == me) {
                Some(w) => w.arrived = true,
                None => slot.woken.push_back(Woken {
                    thread: me,
                    arrived: true,
                }),
            },
        }
        loop {
            if slot.vm.is_some() {
                if slot.next_up() == Some(me)
                    && let Some(vm) = slot.vm.take()
                {
                    slot.serve(me);
                    self.yield_request.store(false, Ordering::Relaxed);
                    return vm;
                }
            } else if slot.slice_left().is_zero() {
                self.yield_request.store(true, Ordering::Relaxed);
            }
            let wait = match slot.slice_left() {
                Duration::ZERO => SLICE,
                left => left,
            };
            let (s, _) = self
                .turn
                .wait_timeout(slot, wait)
                .unwrap_or_else(PoisonError::into_inner);
            slot = s;
        }
    }
}

impl Vm {
    /// Runs `f` with the VM released, so other threads can run bytecode
    /// meanwhile, then waits to take the VM back. `f` gets no access to the
    /// VM, and the caller must not rely on any `Ref` it has not rooted: a
    /// collection may run while this thread is outside.
    pub fn blocking<T>(&mut self, f: impl FnOnce() -> T) -> T {
        self.step_out(Line::Woken, f)
    }

    /// `Thread.yield`: goes behind the threads waiting for the VM. Such a
    /// thread is ready to run, so it gets no place among the woken ones.
    pub fn yield_thread(&mut self) {
        self.step_out(Line::Preempted, std::thread::yield_now);
    }

    fn step_out<T>(&mut self, line: Line, f: impl FnOnce() -> T) -> T {
        let me = self.current;
        self.park_state(me);
        let gil = Arc::clone(&self.gil);
        let vm = std::mem::replace(self, Vm::placeholder(Arc::clone(&gil)));
        gil.release(vm);
        let out = f();
        *self = gil.acquire(me, line);
        self.current = me;
        self.resume_state(me);
        out
    }

    /// A safepoint: lets a waiting thread run. The interpreter calls this at
    /// backward branches and calls (docs/design/threads.md).
    #[inline]
    pub(crate) fn safepoint(&mut self) {
        if self.gil.wanted() {
            self.yield_vm();
        }
    }

    #[cold]
    fn yield_vm(&mut self) {
        self.step_out(Line::Preempted, || ());
    }
}
