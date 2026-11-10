//! Native helper threads that take turns with the Java threads without being
//! one (the debugger agent), and what the agent needs to see of the others.

use std::sync::Arc;

use super::{Blocked, Gil, Line, ThreadEntry, ThreadState};
use crate::Vm;
use crate::interp::Frame;

impl Vm {
    /// Makes room in the thread table for a helper thread that will run
    /// `with_vm`. It is a daemon, so the VM does not wait for it at exit, and
    /// has no `java.lang.Thread`. Its OS handle comes from `set_helper_os`.
    pub(crate) fn add_helper_thread(&mut self) -> usize {
        let mut entry = ThreadEntry::new(None, true);
        entry.state = Some(ThreadState::default());
        self.threads.insert(entry)
    }

    pub(crate) fn set_helper_os(&mut self, index: usize, os: std::thread::Thread) {
        if let Some(e) = self.threads.get_mut(index) {
            e.os = os;
        }
    }

    pub(crate) fn remove_helper_thread(&mut self, index: usize) {
        if let Some(slot) = self.threads.entries.get_mut(index) {
            *slot = None;
        }
    }

    /// Thread indices of every thread that has a `java.lang.Thread`, in table order.
    pub(crate) fn java_thread_indices(&self) -> Vec<usize> {
        let entries = self.threads.entries.iter().enumerate();
        entries
            .filter(|(_, e)| e.as_ref().is_some_and(|e| e.java.is_some()))
            .map(|(i, _)| i)
            .collect()
    }

    /// The frames of thread `index`, bottom first. Whoever asks holds the VM,
    /// so the frames of every other thread are in its entry and stand still.
    pub(crate) fn frames_of_thread(&self, index: usize) -> Option<&[Frame]> {
        if index == self.current {
            return Some(&self.frames);
        }
        let entry = self.threads.get(index)?;
        entry.state.as_ref().map(|s| &s.frames[..])
    }

    /// What thread `index` is blocked on, for its status.
    pub(crate) fn thread_blocked(&self, index: usize) -> Option<Blocked> {
        self.threads.get(index).map(|e| e.blocked)
    }

    /// Waits, outside the VM, until another thread unparks this one. A
    /// suspended thread does this; callers loop on whether it is still held.
    pub(crate) fn park_suspended(&mut self) {
        let me = self.current;
        if let Some(e) = self.threads.get_mut(me) {
            e.parked = true;
        }
        self.blocking_unchecked(std::thread::park);
        if let Some(e) = self.threads.get_mut(me) {
            e.parked = false;
        }
    }

    /// Wakes thread `index` if it waits in `park_suspended` or anywhere else
    /// it parks: all of them recheck their condition.
    pub(crate) fn wake_thread(&self, index: usize) {
        self.threads.unpark(&self.gil, index);
    }
}

/// Runs `f` as thread `me`, holding the VM for as long as it takes. The thread
/// waits its turn like any other, so the Java threads are not stopped for it.
pub(crate) fn with_vm<T>(gil: &Arc<Gil>, me: usize, f: impl FnOnce(&mut Vm) -> T) -> T {
    let mut vm = gil.acquire(me, Line::Woken);
    vm.current = me;
    vm.resume_state(me);
    let out = f(&mut vm);
    vm.park_state(me);
    gil.release(vm);
    out
}
