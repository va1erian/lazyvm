//! Object monitors: `synchronized`, `wait` and `notify` (JVMS 2.11.10,
//! 6.5 monitorenter/monitorexit; the `Object.wait` Javadoc).
//!
//! The owner and entry count live in the object's heap slot (`LockWord`),
//! so an uncontended `synchronized` is a few field writes. Only a monitor
//! that threads block on or wait in gets an entry in `Vm::monitors`, whose
//! keys the collector keeps alive; an owned monitor's object is kept alive
//! by the owning frame (`Frame::monitors`) or by the call's arguments.
//!
//! All of it is guarded by the VM lock: a blocked thread parks outside the
//! VM, and whoever changes what it waits for unparks it.

use std::collections::{HashMap, VecDeque};

use super::{Blocked, deadline_after};
use crate::value::Ref;
use crate::{Vm, VmError, VmResult};

/// The threads blocked on one monitor.
#[derive(Default)]
pub(crate) struct Waits {
    /// Threads blocked entering.
    entrants: Vec<usize>,
    /// Threads in `wait`, oldest first.
    waiters: VecDeque<usize>,
}

pub(crate) type Monitors = HashMap<Ref, Waits>;

/// No message, as on Java 8.
fn not_owner() -> VmError {
    VmError::bare("java/lang/IllegalMonitorStateException")
}

impl Vm {
    /// The running thread as a `LockWord` owner.
    fn lock_owner(&self) -> u32 {
        u32::try_from(self.current).map_or(u32::MAX, |c| c.saturating_add(1))
    }

    /// Enters `obj`'s monitor, blocking while another thread owns it.
    pub fn monitor_enter(&mut self, obj: Ref) -> VmResult<()> {
        self.monitor_acquire(obj, 1)
    }

    /// Takes the monitor with `count` entries (more than 1 when a `wait`
    /// takes back what it released).
    fn monitor_acquire(&mut self, obj: Ref, count: u32) -> VmResult<()> {
        let me = self.lock_owner();
        loop {
            let lock = self.heap.lock_word(obj)?;
            if lock.owner == 0 {
                lock.owner = me;
                lock.count = count;
                self.leave_entrants(obj);
                return Ok(());
            }
            if lock.owner == me {
                lock.count = lock
                    .count
                    .checked_add(count)
                    .ok_or_else(|| VmError::Internal("monitor entered 2^32 times".into()))?;
                return Ok(());
            }
            let current = self.current;
            let waits = self.monitors.entry(obj).or_default();
            if !waits.entrants.contains(&current) {
                waits.entrants.push(current);
            }
            self.park(Blocked::Monitor, None);
        }
    }

    /// Takes the running thread off `obj`'s entrants, if it was waiting.
    fn leave_entrants(&mut self, obj: Ref) {
        if self.monitors.is_empty() {
            return;
        }
        let me = self.current;
        if let Some(w) = self.monitors.get_mut(&obj) {
            w.entrants.retain(|&t| t != me);
            if w.entrants.is_empty() && w.waiters.is_empty() {
                self.monitors.remove(&obj);
            }
        }
    }

    /// Leaves `obj`'s monitor once; `IllegalMonitorStateException` if this
    /// thread does not own it.
    pub fn monitor_exit(&mut self, obj: Ref) -> VmResult<()> {
        let me = self.lock_owner();
        let lock = self.heap.lock_word(obj)?;
        if lock.owner != me {
            return Err(not_owner());
        }
        lock.count -= 1;
        if lock.count == 0 {
            lock.owner = 0;
            self.wake_entrants(obj);
        }
        Ok(())
    }

    fn wake_entrants(&self, obj: Ref) {
        if self.monitors.is_empty() {
            return;
        }
        if let Some(w) = self.monitors.get(&obj) {
            for &t in &w.entrants {
                self.threads.unpark(t);
            }
        }
    }

    /// The entry count if the running thread owns `obj`'s monitor.
    fn owned_count(&mut self, obj: Ref) -> VmResult<u32> {
        let me = self.lock_owner();
        let lock = self.heap.lock_word(obj)?;
        if lock.owner == me {
            Ok(lock.count)
        } else {
            Err(not_owner())
        }
    }

    /// Whether the running thread owns `obj`'s monitor (`Thread.holdsLock`).
    pub fn holds_lock(&mut self, obj: Ref) -> bool {
        self.owned_count(obj).is_ok()
    }

    /// `Object.wait(millis)`; 0 waits until notified or interrupted.
    pub fn monitor_wait(&mut self, obj: Ref, millis: i64) -> VmResult<()> {
        let me = self.current;
        let count = self.owned_count(obj)?;
        if self.take_interrupt() {
            return Err(VmError::bare("java/lang/InterruptedException"));
        }
        if let Some(e) = self.threads.get_mut(me) {
            e.notified = false;
        }
        self.monitors.entry(obj).or_default().waiters.push_back(me);
        let lock = self.heap.lock_word(obj)?;
        lock.owner = 0;
        lock.count = 0;
        self.wake_entrants(obj);
        let deadline = (millis != 0).then(|| deadline_after(millis)).flatten();
        let blocked = if millis == 0 {
            Blocked::Waiting
        } else {
            Blocked::TimedWaiting
        };
        loop {
            let woken = self
                .threads
                .get(me)
                .is_some_and(|e| e.notified || e.interrupted);
            if woken || deadline.is_some_and(|d| std::time::Instant::now() >= d) {
                break;
            }
            self.park(blocked, deadline);
        }
        if let Some(w) = self.monitors.get_mut(&obj) {
            w.waiters.retain(|&t| t != me);
        }
        let notified = self.threads.get(me).is_some_and(|e| e.notified);
        self.monitor_acquire(obj, count)?;
        // JLS 17.2.4: a notification is never lost to an interrupt, so a
        // notified thread returns normally and keeps its interrupt pending.
        if !notified && self.take_interrupt() {
            return Err(VmError::bare("java/lang/InterruptedException"));
        }
        Ok(())
    }

    /// `Object.notify` (`all` false) or `notifyAll`.
    pub fn monitor_notify(&mut self, obj: Ref, all: bool) -> VmResult<()> {
        self.owned_count(obj)?;
        let Some(w) = self.monitors.get_mut(&obj) else {
            return Ok(());
        };
        let n = if all { w.waiters.len() } else { 1 };
        let woken: Vec<usize> = (0..n).filter_map(|_| w.waiters.pop_front()).collect();
        for t in woken {
            if let Some(e) = self.threads.get_mut(t) {
                e.notified = true;
            }
            self.threads.unpark(t);
        }
        Ok(())
    }

    /// Objects with blocked or waiting threads: the collector keeps them.
    pub(crate) fn monitor_roots(&self, out: &mut Vec<Ref>) {
        out.extend(self.monitors.keys().copied());
    }
}
