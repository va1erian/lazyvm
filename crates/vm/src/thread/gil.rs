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
//! - A slice is `SLICE` of VM time. A thread running bytecode times its own
//!   slice, and gives up the VM once it is spent and someone can take it.
//! - A thread that gave the VM up to block leaves the rest of its slice to a
//!   thread that was woken: threads handing a lock around share one slice.
//!   A running thread names who it wakes, so that one is expected before it
//!   has even got out of its OS wait, and busy threads cannot slip in first.
//! - A spent slice goes to the oldest preempted thread if woken threads had
//!   the last one, and the other way round, so neither side starves the other.
//!
//! A switch wakes the one thread that goes next, on its own condition variable,
//! and every step here takes constant time, however many threads wait.
//!
//! Counting polls times a slice well only while polls come quickly, and a poll
//! can come after a copy of megabytes or a collection. So a helper thread, the
//! watch, tells the holder when a slice with someone waiting has run out
//! (threads.md, "Safepoints and time slices").

use std::cmp::Reverse;
use std::collections::{BinaryHeap, VecDeque};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, PoisonError, Weak};
use std::time::{Duration, Instant};

use crate::Vm;

pub(crate) struct Gil {
    slot: Mutex<Slot>,
    /// Set while a thread waits in line, so the holder has its slice to time.
    /// With none, a safepoint costs only the read of this flag.
    waiting: AtomicBool,
    /// The watch saw the slice run out: the holder looks at the clock at its
    /// next poll, whatever its count says.
    look_now: AtomicBool,
    /// What the watch sleeps on with the line empty (`Watch::Asleep`).
    rouse: Condvar,
    /// This lock, for the thread that keeps the watch.
    this: Weak<Gil>,
}

/// How long threads may run bytecode while others wait.
const SLICE: Duration = Duration::from_millis(5);

/// How many safepoints a holder with threads in line passes between looks at
/// the clock: a look is about 30 ns on Windows, and a safepoint usually comes every
/// few nanoseconds to tens of them, so the slice is exact to some 50 us. All
/// that sharing the VM costs is 2% to 4% of the time (threads.md).
const POLLS_PER_LOOK: u32 = 1024;

/// With no one in line for this many looks in a row, the watch stops timing
/// and sleeps until someone joins the line. Rousing it takes a system call, so
/// threads that only hand a lock back and forth must not do it at each hop.
const IDLE_LOOKS: u32 = 8;

/// What the thread keeping the watch is doing.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Watch {
    /// Not there: no thread has waited yet.
    Absent,
    /// The OS would not start it, and a switch should not ask again each time.
    Refused,
    Timing,
    Asleep,
}

/// Which part of the line a thread joins.
#[derive(Clone, Copy, PartialEq, Eq)]
pub(super) enum Line {
    /// It could have gone on running: its slice ended, or it yielded.
    Preempted,
    /// It was blocked, and is back.
    Woken,
}

/// A thread's place by the slot, kept by thread index.
#[derive(Default)]
struct Seat {
    /// What it waits on while in line.
    turn: Arc<Condvar>,
    /// Its place in the woken line, once named or arrived.
    place: Option<u64>,
    /// VM time it had since it last blocked, up to its last switch.
    ran: Duration,
}

struct Slot {
    vm: Option<Vm>,
    seats: Vec<Seat>,
    /// Oldest first.
    preempted: VecDeque<usize>,
    /// Woken threads that have reached the slot, first place first. Places
    /// are given in the order threads were woken, not in the order the OS got
    /// them here.
    arrived: BinaryHeap<Reverse<(u64, usize)>>,
    /// Woken threads named by their waker, still leaving their OS wait.
    expected: usize,
    next_place: u64,
    slice_start: Instant,
    /// Whether the running slice went to the woken line.
    slice_was_woken: bool,
    /// When the holder got the VM.
    served_at: Instant,
    watch: Watch,
}

impl Slot {
    fn slice_left(&self) -> Duration {
        SLICE.saturating_sub(self.slice_start.elapsed())
    }

    fn seat(&mut self, thread: usize) -> &mut Seat {
        if self.seats.len() <= thread {
            self.seats.resize_with(thread + 1, Seat::default);
        }
        &mut self.seats[thread]
    }

    /// The thread that takes the VM now, if any may.
    fn next_up(&self) -> Option<usize> {
        let woken = self.arrived.peek().map(|&Reverse((_, thread))| thread);
        let preempted = self.preempted.front().copied();
        if !self.slice_left().is_zero() {
            // A woken thread on its way is worth waiting for: it uses this
            // slice, and at worst the slice runs out with the VM idle.
            return if woken.is_none() && self.expected == 0 {
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

    /// Who should look at the slot now that the VM is free: the next thread,
    /// or, if that is one still on its way, the first preempted thread, which
    /// watches the clock in case the slice runs out first.
    fn to_notify(&self, me: Option<usize>) -> Option<Arc<Condvar>> {
        self.vm.as_ref()?;
        let next = self.next_up().or(self.preempted.front().copied())?;
        (Some(next) != me).then(|| Arc::clone(&self.seats[next].turn))
    }

    fn join(&mut self, me: usize, line: Line) {
        match line {
            Line::Preempted => {
                self.seat(me);
                self.preempted.push_back(me);
            }
            Line::Woken => {
                let place = match self.seat(me).place {
                    Some(place) => {
                        self.expected -= 1;
                        place
                    }
                    None => self.new_place(me),
                };
                self.seat(me).ran = Duration::ZERO;
                self.arrived.push(Reverse((place, me)));
            }
        }
    }

    /// `me`'s slice is spent. Having shared it with other woken threads, and had
    /// less than a slice since it blocked, it stays in the woken line: threads
    /// handing a lock around would otherwise stop, whenever the slice ends in
    /// the middle of one's turn, until it has come round all the busy threads.
    /// Otherwise it is a busy thread, and goes behind them. The next thread is
    /// chosen among those that waited while the slice ran, which `me` did not:
    /// alone in the preempted line, it must not count as the side whose turn
    /// it is.
    fn preempt_holder(&mut self, me: usize) {
        let served_at = self.served_at;
        let seat = self.seat(me);
        seat.ran += served_at.elapsed();
        if self.slice_was_woken && self.seats[me].ran < SLICE {
            let place = self.new_place(me);
            self.arrived.push(Reverse((place, me)));
            return;
        }
        if self.preempted.is_empty() {
            self.slice_was_woken = false;
        }
        self.preempted.push_back(me);
    }

    fn new_place(&mut self, thread: usize) -> u64 {
        let place = self.next_place;
        self.next_place += 1;
        self.seat(thread).place = Some(place);
        place
    }

    /// Takes `me`, which `next_up` named, off the line and starts or continues
    /// a slice.
    fn serve(&mut self, me: usize) {
        let was_woken = self.seats[me].place.take().is_some();
        if was_woken {
            self.arrived.pop();
        } else {
            self.preempted.pop_front();
        }
        if !was_woken || self.slice_left().is_zero() {
            self.slice_start = Instant::now();
        }
        self.slice_was_woken = was_woken;
        self.served_at = Instant::now();
    }

    fn anyone_in_line(&self) -> bool {
        !self.preempted.is_empty() || !self.arrived.is_empty()
    }

    fn slice_is_over(&self) -> bool {
        self.slice_left().is_zero() && self.next_up().is_some()
    }
}

impl Gil {
    /// A lock whose VM is held by its creator.
    pub(crate) fn new() -> Arc<Gil> {
        Arc::new_cyclic(|this| Gil {
            slot: Mutex::new(Slot {
                vm: None,
                seats: Vec::new(),
                preempted: VecDeque::new(),
                arrived: BinaryHeap::new(),
                expected: 0,
                next_place: 0,
                slice_start: Instant::now(),
                slice_was_woken: false,
                served_at: Instant::now(),
                watch: Watch::Absent,
            }),
            waiting: AtomicBool::new(false),
            look_now: AtomicBool::new(false),
            rouse: Condvar::new(),
            this: Weak::clone(this),
        })
    }

    /// Whether a thread is in line. A relaxed read is enough: a late answer
    /// only delays a yield to a later safepoint.
    #[inline]
    pub(crate) fn wanted(&self) -> bool {
        self.waiting.load(Ordering::Relaxed)
    }

    fn lock(&self) -> MutexGuard<'_, Slot> {
        // The slot holds no invariant a panic elsewhere could break.
        self.slot.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Gives the VM up; the next thread in line takes it.
    pub(crate) fn release(&self, vm: Vm) {
        let mut slot = self.lock();
        slot.vm = Some(vm);
        let next = slot.to_notify(None);
        // Woken outside the lock, so it does not at once wait for the lock.
        drop(slot);
        if let Some(turn) = next {
            turn.notify_one();
        }
    }

    /// Puts `thread`, about to be woken by the VM's holder, in the woken
    /// line. It must be parked (`ThreadEntry::parked`), which promises that
    /// it will come to `acquire`, so the place does not stay taken for ever.
    pub(super) fn expect(&self, thread: usize) {
        let mut slot = self.lock();
        if slot.seat(thread).place.is_none() {
            slot.new_place(thread);
            slot.expected += 1;
        }
    }

    /// Joins `line`, waits for this thread's turn and takes the VM.
    pub(super) fn acquire(&self, me: usize, line: Line) -> Vm {
        let mut slot = self.lock();
        slot.join(me, line);
        self.wait_turn(slot, me)
    }

    /// Whether the holder's slice is spent with a thread there to take over.
    fn slice_is_over(&self) -> bool {
        self.lock().slice_is_over()
    }

    /// Counts a poll by the holder: whether it should now see if the slice is
    /// over. A stale `look_now` only costs one look.
    fn time_to_look(&self, polls_to_look: &mut u32) -> bool {
        *polls_to_look = polls_to_look.saturating_sub(1);
        let look = *polls_to_look == 0 || self.look_now.load(Ordering::Relaxed);
        if look {
            *polls_to_look = POLLS_PER_LOOK;
            self.look_now.store(false, Ordering::Relaxed);
        }
        look
    }

    /// A thread is joining the line, so someone must keep the watch. If the
    /// thread cannot be started, slices are as long as the polls make them.
    fn call_watch(&self, slot: &mut Slot) {
        match slot.watch {
            Watch::Timing | Watch::Refused => {}
            Watch::Asleep => {
                slot.watch = Watch::Timing;
                self.rouse.notify_one();
            }
            Watch::Absent => {
                let Some(gil) = self.this.upgrade() else {
                    return;
                };
                let started = std::thread::Builder::new()
                    .name("vm-watch".into())
                    .stack_size(64 << 10)
                    .spawn(move || gil.keep_watch());
                slot.watch = match started {
                    Ok(_) => Watch::Timing,
                    Err(_) => Watch::Refused,
                };
            }
        }
    }

    /// The watch's body. It holds the lock for good: a process has one VM, and
    /// the thread ends with it. Unlike a look by the holder, this is on time
    /// however long a poll takes, up to the lateness of a sleep (0.5 ms on
    /// Windows, `timer.rs`), and it costs a wake-up per slice, in contention only.
    fn keep_watch(&self) {
        let mut idle_looks = 0;
        loop {
            let pause = self.look_for_holder(&mut idle_looks);
            std::thread::sleep(pause);
        }
    }

    /// Tells the holder if its slice is over, and says how long to sleep.
    fn look_for_holder(&self, idle_looks: &mut u32) -> Duration {
        let mut slot = self.lock();
        *idle_looks = if slot.anyone_in_line() {
            0
        } else {
            *idle_looks + 1
        };
        if *idle_looks >= IDLE_LOOKS {
            *idle_looks = 0;
            slot.watch = Watch::Asleep;
            while slot.watch == Watch::Asleep {
                slot = self
                    .rouse
                    .wait(slot)
                    .unwrap_or_else(PoisonError::into_inner);
            }
        }
        if slot.slice_is_over() {
            self.look_now.store(true, Ordering::Relaxed);
        }
        match slot.slice_left() {
            left if left.is_zero() => SLICE,
            left => left,
        }
    }

    /// The holder's slice is spent: gives the VM up and joins the preempted
    /// line, in one step (`Slot::preempt_holder`).
    fn preempt(&self, vm: Vm, me: usize) -> Vm {
        let mut slot = self.lock();
        slot.vm = Some(vm);
        slot.preempt_holder(me);
        self.wait_turn(slot, me)
    }

    fn wait_turn<'a>(&'a self, mut slot: MutexGuard<'a, Slot>, me: usize) -> Vm {
        self.waiting.store(true, Ordering::Relaxed);
        let turn = Arc::clone(&slot.seats[me].turn);
        loop {
            if slot.vm.is_some() && slot.next_up() == Some(me) {
                let Some(vm) = slot.vm.take() else { continue };
                slot.serve(me);
                self.waiting.store(slot.anyone_in_line(), Ordering::Relaxed);
                return vm;
            }
            // Only now: a lone thread back from a sleep needs no one to time it.
            self.call_watch(&mut slot);
            // What this thread's arrival, or the time that passed, changed.
            if let Some(next) = slot.to_notify(Some(me)) {
                next.notify_one();
            }
            // The only wait with no one to end it: a free VM kept for a thread
            // on its way. So the first preempted thread times the slice.
            let watch = slot.vm.is_some() && slot.preempted.front() == Some(&me);
            slot = match slot.slice_left() {
                left if watch && !left.is_zero() => {
                    turn.wait_timeout(slot, left)
                        .unwrap_or_else(PoisonError::into_inner)
                        .0
                }
                _ => turn.wait(slot).unwrap_or_else(PoisonError::into_inner),
            };
        }
    }
}

impl Vm {
    /// Runs `f` with the VM released, so other threads can run bytecode
    /// meanwhile, then waits to take the VM back. `f` gets no access to the
    /// VM, and the caller must not rely on any `Ref` it has not rooted: a
    /// collection may run while this thread is outside.
    pub fn blocking<T>(&mut self, f: impl FnOnce() -> T) -> T {
        self.hand_over(|gil, vm, me| {
            gil.release(vm);
            let out = f();
            (gil.acquire(me, Line::Woken), out)
        })
    }

    /// `Thread.yield`: goes behind the threads waiting for the VM. Such a
    /// thread is ready to run, so it gets no place among the woken ones.
    pub fn yield_thread(&mut self) {
        self.hand_over(|gil, vm, me| {
            gil.release(vm);
            std::thread::yield_now();
            (gil.acquire(me, Line::Preempted), ())
        });
    }

    /// Moves the running thread's state and the VM out, for `exchange` to
    /// give up and get back.
    fn hand_over<T>(&mut self, exchange: impl FnOnce(&Gil, Vm, usize) -> (Vm, T)) -> T {
        let me = self.current;
        self.park_state(me);
        let gil = Arc::clone(&self.gil);
        let vm = std::mem::replace(self, Vm::placeholder(Arc::clone(&gil)));
        let (vm, out) = exchange(&gil, vm, me);
        *self = vm;
        self.current = me;
        self.resume_state(me);
        out
    }

    /// A safepoint: lets a waiting thread run. The interpreter calls this at
    /// backward branches and calls (docs/design/threads.md).
    #[inline]
    pub(crate) fn safepoint(&mut self) {
        if self.gil.wanted() {
            self.poll_slice();
        }
    }

    /// Threads are in line: every so often, or when the watch says so, see
    /// whether the slice is spent. The holder times it itself, as a waiting
    /// thread's OS timer can be late by a lot (15.6 ms on Windows).
    #[cold]
    fn poll_slice(&mut self) {
        if self.gil.time_to_look(&mut self.polls_to_look) && self.gil.slice_is_over() {
            self.hand_over(|gil, vm, me| (gil.preempt(vm, me), ()));
        }
    }
}

#[cfg(test)]
#[path = "gil_tests.rs"]
mod tests;
