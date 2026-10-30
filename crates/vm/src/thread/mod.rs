//! Java threads on OS threads (docs/design/threads.md).
//!
//! Every `java.lang.Thread` that has started runs on its own OS thread, and
//! they share one `Vm` through the VM lock (`gil`). The `Vm` fields that
//! belong to one thread (its frames, roots and nesting counters) describe the
//! thread that holds the VM; a thread that gives the VM up moves them into
//! its `ThreadEntry` first, where the collector still sees them.

mod gil;
mod monitor;
mod spawn;

use std::sync::Arc;
use std::time::{Duration, Instant};

pub(crate) use gil::Gil;
use gil::Line;
pub(crate) use monitor::Monitors;

use crate::interp::Frame;
use crate::value::Ref;
use crate::{Vm, VmError, VmResult};

/// What a thread is blocked on, for `Thread.getState`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Blocked {
    No,
    /// Entering a monitor another thread owns.
    Monitor,
    /// `wait()`, or `join`.
    Waiting,
    /// `sleep` or a timed `wait`.
    TimedWaiting,
}

/// The per-thread part of the `Vm`, kept here while the thread is outside.
#[derive(Default)]
pub(crate) struct ThreadState {
    frames: Vec<Frame>,
    roots: Vec<Ref>,
    frame_slots: usize,
    nesting: u32,
    creating_exception: u32,
    past_limit: u32,
    loading: Vec<Arc<str>>,
}

pub(crate) struct ThreadEntry {
    /// The `java.lang.Thread`; the main thread's is made on first use.
    pub(crate) java: Option<Ref>,
    /// For waking the thread when it is parked.
    os: std::thread::Thread,
    /// In `Vm::park`: it holds no VM, and comes back for it once woken.
    parked: bool,
    pub(crate) daemon: bool,
    pub(crate) interrupted: bool,
    /// Set by `notify` for a thread in a wait set.
    pub(crate) notified: bool,
    /// `LockSupport`'s permit: one, not a count.
    park_permit: bool,
    pub(crate) blocked: Blocked,
    /// `Some` while the thread does not hold the VM.
    pub(crate) state: Option<ThreadState>,
}

/// Every live thread, by index. The main thread is index 0.
#[derive(Default)]
pub(crate) struct Threads {
    pub(crate) entries: Vec<Option<ThreadEntry>>,
    /// Threads waiting for a class another thread is initializing (JVMS 5.5).
    pub(crate) init_waiters: Vec<usize>,
    /// Threads waiting for the live thread count to change (VM exit).
    pub(crate) exit_waiters: Vec<usize>,
}

impl Threads {
    /// A table holding the calling OS thread as the main thread.
    pub(crate) fn with_main() -> Threads {
        let main = ThreadEntry::new(None, false);
        Threads {
            entries: vec![Some(main)],
            ..Threads::default()
        }
    }

    fn insert(&mut self, entry: ThreadEntry) -> usize {
        if let Some(i) = self.entries.iter().position(Option::is_none) {
            self.entries[i] = Some(entry);
            return i;
        }
        self.entries.push(Some(entry));
        self.entries.len() - 1
    }

    pub(crate) fn get(&self, i: usize) -> Option<&ThreadEntry> {
        self.entries.get(i).and_then(Option::as_ref)
    }

    pub(crate) fn get_mut(&mut self, i: usize) -> Option<&mut ThreadEntry> {
        self.entries.get_mut(i).and_then(Option::as_mut)
    }

    /// The index of the live thread whose `java.lang.Thread` is `t`.
    pub(crate) fn find(&self, t: Ref) -> Option<usize> {
        self.entries
            .iter()
            .position(|e| e.as_ref().is_some_and(|e| e.java == Some(t)))
    }

    /// Wakes thread `i` if it is parked. Its waker holds the VM, so the
    /// thread gets its place in the line now (`Gil::expect`).
    pub(crate) fn unpark(&self, gil: &Gil, i: usize) {
        if let Some(e) = self.get(i) {
            if e.parked {
                gil.expect(i);
            }
            e.os.unpark();
        }
    }

    /// Wakes every thread in `list` and empties it.
    pub(crate) fn unpark_all(&self, gil: &Gil, list: &mut Vec<usize>) {
        for i in list.drain(..) {
            self.unpark(gil, i);
        }
    }

    /// References the collector must keep: thread objects and the frames
    /// and roots of threads outside the VM.
    pub(crate) fn roots(&self, out: &mut Vec<Ref>) {
        for e in self.entries.iter().flatten() {
            out.extend(e.java);
            if let Some(s) = &e.state {
                for f in &s.frames {
                    f.roots(out);
                }
                out.extend(s.roots.iter().copied());
            }
        }
    }
}

impl ThreadEntry {
    fn new(java: Option<Ref>, daemon: bool) -> ThreadEntry {
        ThreadEntry {
            java,
            os: std::thread::current(),
            parked: false,
            daemon,
            interrupted: false,
            notified: false,
            park_permit: false,
            blocked: Blocked::No,
            state: None,
        }
    }
}

impl Vm {
    /// Moves the running thread's state into its entry (it is leaving).
    fn park_state(&mut self, me: usize) {
        let state = ThreadState {
            frames: std::mem::take(&mut self.frames),
            roots: std::mem::take(&mut self.roots),
            frame_slots: self.frame_slots,
            nesting: self.nesting,
            creating_exception: self.creating_exception,
            past_limit: self.heap.past_limit,
            loading: std::mem::take(&mut self.loading),
        };
        if let Some(e) = self.threads.get_mut(me) {
            e.state = Some(state);
        }
    }

    /// Takes the state of thread `me` back (it holds the VM again).
    fn resume_state(&mut self, me: usize) {
        let Some(s) = self.threads.get_mut(me).and_then(|e| e.state.take()) else {
            return;
        };
        self.frames = s.frames;
        self.roots = s.roots;
        self.frame_slots = s.frame_slots;
        self.nesting = s.nesting;
        self.creating_exception = s.creating_exception;
        self.heap.past_limit = s.past_limit;
        self.loading = s.loading;
    }

    /// Parks outside the VM until another thread unparks this one or
    /// `deadline` passes. Wakeups can be spurious: callers loop on their
    /// condition, which they check while holding the VM.
    pub(crate) fn park(&mut self, blocked: Blocked, deadline: Option<Instant>) {
        self.set_blocked(blocked, true);
        self.blocking(|| match deadline {
            None => std::thread::park(),
            Some(d) => std::thread::park_timeout(d.saturating_duration_since(Instant::now())),
        });
        self.set_blocked(Blocked::No, false);
    }

    fn set_blocked(&mut self, b: Blocked, parked: bool) {
        let me = self.current;
        if let Some(e) = self.threads.get_mut(me) {
            e.blocked = b;
            e.parked = parked;
        }
    }

    /// The running thread's `java.lang.Thread`, `None` for a main thread
    /// that has not asked for one yet.
    pub fn current_thread(&self) -> Option<Ref> {
        self.threads.get(self.current).and_then(|e| e.java)
    }

    /// Records the main thread's `java.lang.Thread`, made lazily by Java.
    pub fn attach_current_thread(&mut self, t: Ref) {
        let me = self.current;
        if let Some(e) = self.threads.get_mut(me) {
            e.java.get_or_insert(t);
        }
    }

    /// The live thread running `t`, if `t` has started and not ended.
    fn thread_index(&self, t: Ref) -> Option<usize> {
        self.threads.find(t)
    }

    /// `Thread.interrupt`: sets the flag and wakes the thread if it is
    /// parked. A thread that is not alive is left alone.
    pub fn interrupt(&mut self, t: Ref) {
        if let Some(i) = self.thread_index(t)
            && let Some(e) = self.threads.get_mut(i)
        {
            e.interrupted = true;
            self.threads.unpark(&self.gil, i);
        }
    }

    /// `Thread.isInterrupted`, clearing the flag when `clear` is set.
    pub fn is_interrupted(&mut self, t: Ref, clear: bool) -> bool {
        let i = self.thread_index(t).unwrap_or(usize::MAX);
        let Some(e) = self.threads.get_mut(i) else {
            return false;
        };
        let was = e.interrupted;
        if clear {
            e.interrupted = false;
        }
        was
    }

    /// Clears the running thread's interrupt flag, returning what it was.
    pub fn take_interrupt(&mut self) -> bool {
        let me = self.current;
        self.threads
            .get_mut(me)
            .is_some_and(|e| std::mem::take(&mut e.interrupted))
    }

    /// What the live thread `t` is blocked on; `None` if it is not alive.
    pub fn blocked_state(&self, t: Ref) -> Option<Blocked> {
        self.thread_index(t)
            .and_then(|i| self.threads.get(i))
            .map(|e| e.blocked)
    }

    /// `LockSupport.unpark`: a thread that is not alive is left alone.
    pub fn unpark_thread(&mut self, t: Ref) {
        if let Some(i) = self.thread_index(t)
            && let Some(e) = self.threads.get_mut(i)
        {
            e.park_permit = true;
            self.threads.unpark(&self.gil, i);
        }
    }

    /// `LockSupport.park`: returns when the permit is there (using it up), when the
    /// interrupt flag is set (leaving it set), or at the end of `nanos`, if given.
    /// The OS token can be stale, so only those three end the wait.
    pub fn park_thread(&mut self, nanos: Option<u64>) {
        let deadline = nanos.and_then(|n| Instant::now().checked_add(Duration::from_nanos(n)));
        let blocked = match nanos {
            Some(_) => Blocked::TimedWaiting,
            None => Blocked::Waiting,
        };
        let me = self.current;
        loop {
            let Some(e) = self.threads.get_mut(me) else {
                return;
            };
            if std::mem::take(&mut e.park_permit) || e.interrupted {
                return;
            }
            if deadline.is_some_and(|d| Instant::now() >= d) {
                return;
            }
            self.park(blocked, deadline);
        }
    }

    /// `Thread.sleep`: interruptible, the flag cleared when it fires.
    pub fn sleep(&mut self, millis: i64) -> VmResult<()> {
        let deadline = deadline_after(millis);
        loop {
            if self.take_interrupt() {
                return Err(VmError::exception(
                    "java/lang/InterruptedException",
                    "sleep interrupted",
                ));
            }
            if deadline.is_some_and(|d| Instant::now() >= d) {
                return Ok(());
            }
            self.park(Blocked::TimedWaiting, deadline);
        }
    }
}

/// The instant `millis` from now, or `None` when that is too far to name.
/// Callers treat `None` as "no deadline".
pub(crate) fn deadline_after(millis: i64) -> Option<Instant> {
    let d = Duration::from_millis(u64::try_from(millis).unwrap_or(0));
    Instant::now().checked_add(d)
}
