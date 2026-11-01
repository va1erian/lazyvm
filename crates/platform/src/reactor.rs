//! The reactor: one native thread that parks in `mio` and never runs bytecode
//! (docs/async.md, "Inside"). Java threads queue requests to it and wake it;
//! it posts the ids of finished operations to an inbox, which the dispatcher,
//! a Java thread, empties through [`take`].

use std::collections::{BTreeSet, HashMap, VecDeque};
use std::io;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Condvar, Mutex, MutexGuard, OnceLock, mpsc};
use std::time::{Duration, Instant};

use mio::{Events, Poll, Token, Waker};

use crate::timer;

const WAKE: Token = Token(0);
/// It runs no bytecode and recurses nowhere, so a small stack will do.
const STACK_BYTES: usize = 256 * 1024;
/// How long to wait before polling again after `poll` itself fails, so that
/// a failure that persists does not become a busy loop.
const POLL_RETRY: Duration = Duration::from_millis(100);
/// The last stretch of a wait is left to the sleeper thread. `poll`'s timeout
/// ends on the system tick on Windows (measured: `delay` of 1 ms took 15.5 ms,
/// of 16 ms 29 ms); a longer stretch starts on `poll`, which ends before the
/// deadline since it is late by at most about one tick (16.6 ms).
const SLEEPER_RANGE: Duration = Duration::from_millis(20);

enum Request {
    Timer { id: u64, nanos: u64 },
    Cancel(u64),
}

#[derive(Default)]
struct Inbox {
    done: VecDeque<u64>,
    woken: bool,
}

static REQUESTS: Mutex<Vec<Request>> = Mutex::new(Vec::new());
static INBOX: OnceLock<(Mutex<Inbox>, Condvar)> = OnceLock::new();
static WAKER: OnceLock<Result<Waker, String>> = OnceLock::new();
static WAKES: AtomicU64 = AtomicU64::new(0);

fn locked<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn inbox() -> &'static (Mutex<Inbox>, Condvar) {
    INBOX.get_or_init(Default::default)
}

/// Starts an operation that finishes `nanos` from now. Its id comes to
/// [`take`] once, unless it is cancelled first.
pub fn timer(id: u64, nanos: u64) -> io::Result<()> {
    request(Request::Timer { id, nanos })
}

/// Withdraws an operation. An id that has already finished is ignored, and
/// with no reactor running there is nothing to withdraw.
pub fn cancel(id: u64) {
    if WAKER.get().is_some() {
        // A failed wake leaves the request queued for the reactor's next turn.
        let _ = request(Request::Cancel(id));
    }
}

/// Up to `max` finished ids. Blocks until there is one or [`wake_taker`] was
/// called since the last `take`, so it may return none.
pub fn take(max: usize) -> Vec<u64> {
    let (lock, ready) = inbox();
    let mut inbox = locked(lock);
    while inbox.done.is_empty() && !inbox.woken {
        inbox = ready.wait(inbox).unwrap_or_else(|e| e.into_inner());
    }
    inbox.woken = false;
    let n = inbox.done.len().min(max);
    inbox.done.drain(..n).collect()
}

/// Makes the current or next [`take`] return.
pub fn wake_taker() {
    let (lock, ready) = inbox();
    locked(lock).woken = true;
    ready.notify_one();
}

/// How many times the reactor has come back from `poll`, for tests.
pub fn wakes() -> u64 {
    WAKES.load(Ordering::Relaxed)
}

/// The reactor's waker, starting the thread on first use. The thread makes
/// its own `Poll` and `Waker`, so a platform whose threads do not share
/// descriptors fails `Timers` instead of passing by accident.
fn waker() -> io::Result<&'static Waker> {
    WAKER
        .get_or_init(start)
        .as_ref()
        .map_err(|e| io::Error::other(e.clone()))
}

fn start() -> Result<Waker, String> {
    let (sent, received) = mpsc::channel();
    std::thread::Builder::new()
        .name("lazy-reactor".into())
        .stack_size(STACK_BYTES)
        .spawn(move || {
            let setup = Poll::new().and_then(|poll| {
                let waker = Waker::new(poll.registry(), WAKE)?;
                Ok((poll, waker))
            });
            match setup {
                Ok((poll, waker)) => {
                    let _ = sent.send(Ok(waker));
                    run(poll);
                }
                Err(e) => {
                    let _ = sent.send(Err(e.to_string()));
                }
            }
        })
        .map_err(|e| e.to_string())?;
    received
        .recv()
        .map_err(|_| "the reactor thread ended".to_string())?
}

/// Only the first request into an empty queue wakes the reactor: it empties
/// the queue after every wake, so the later ones are covered by that one.
fn request(r: Request) -> io::Result<()> {
    let waker = waker()?;
    let was_empty = {
        let mut queue = locked(&REQUESTS);
        let was_empty = queue.is_empty();
        queue.push(r);
        was_empty
    };
    if was_empty {
        waker.wake()?;
    }
    Ok(())
}

fn run(mut poll: Poll) {
    let mut events = Events::with_capacity(64);
    let mut timers = Timers::default();
    loop {
        for r in std::mem::take(&mut *locked(&REQUESTS)) {
            match r {
                Request::Timer { id, nanos } => timers.add(id, Instant::now(), nanos),
                Request::Cancel(id) => timers.cancel(id),
            }
        }
        let now = Instant::now();
        let due = timers.take_due(now);
        if !due.is_empty() {
            post(due);
        }
        match poll.poll(&mut events, park_for(timers.next_deadline(), now)) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
            Err(_) => std::thread::sleep(POLL_RETRY),
        }
        WAKES.fetch_add(1, Ordering::Relaxed);
    }
}

/// How long `poll` may park before the next deadline, arming the sleeper if
/// that is close. Without a sleeper, `poll` times it all.
fn park_for(next: Option<Instant>, now: Instant) -> Option<Duration> {
    let left = next.map(|at| at.saturating_duration_since(now));
    match left {
        Some(left) if left <= SLEEPER_RANGE => (!sleeper::arm(next)).then_some(left),
        _ => {
            sleeper::arm(None);
            left.map(|left| left - SLEEPER_RANGE)
        }
    }
}

/// Times the last stretch before a deadline and then wakes the reactor. It
/// starts at the first deadline that close, and parks while none is.
mod sleeper {
    use super::{Instant, Mutex, OnceLock, WAKER, locked, timer};
    use std::thread::Thread;

    const STACK_BYTES: usize = 64 * 1024;

    static ARMED: Mutex<Option<Instant>> = Mutex::new(None);
    static THREAD: OnceLock<Option<Thread>> = OnceLock::new();

    /// Sets the deadline to wake at; false if the sleeper cannot run.
    pub(super) fn arm(at: Option<Instant>) -> bool {
        // Only a deadline needs the thread: disarming one never started is a no-op.
        let thread = match (at, THREAD.get()) {
            (None, None) => return true,
            (_, Some(thread)) => thread,
            (Some(_), None) => THREAD.get_or_init(start),
        };
        let Some(thread) = thread else { return false };
        let mut armed = locked(&ARMED);
        if *armed != at {
            *armed = at;
            drop(armed);
            thread.unpark();
        }
        true
    }

    fn start() -> Option<Thread> {
        std::thread::Builder::new()
            .name("lazy-reactor-sleeper".into())
            .stack_size(STACK_BYTES)
            .spawn(run)
            .ok()
            .map(|handle| handle.thread().clone())
    }

    fn run() {
        loop {
            let at = *locked(&ARMED);
            let Some(at) = at else {
                std::thread::park();
                continue;
            };
            if at > Instant::now() {
                timer::park_until(at);
                continue;
            }
            let mut armed = locked(&ARMED);
            if *armed == Some(at) {
                *armed = None;
            }
            drop(armed);
            // A deadline is armed only after a request, so the waker is there.
            if let Some(Ok(waker)) = WAKER.get() {
                let _ = waker.wake();
            }
        }
    }
}

fn post(ids: Vec<u64>) {
    let (lock, ready) = inbox();
    locked(lock).done.extend(ids);
    ready.notify_one();
}

/// Timers by deadline, with the deadline by id so that a cancel is cheap: a
/// timeout cancelled on every read must not pile up until its deadline.
#[derive(Default)]
struct Timers {
    by_deadline: BTreeSet<(Instant, u64)>,
    by_id: HashMap<u64, Instant>,
}

impl Timers {
    /// A deadline past what `Instant` can hold is never reached.
    fn add(&mut self, id: u64, now: Instant, nanos: u64) {
        if let Some(deadline) = now.checked_add(Duration::from_nanos(nanos)) {
            self.by_deadline.insert((deadline, id));
            self.by_id.insert(id, deadline);
        }
    }

    fn cancel(&mut self, id: u64) {
        if let Some(deadline) = self.by_id.remove(&id) {
            self.by_deadline.remove(&(deadline, id));
        }
    }

    /// The ids due at `now`, soonest first.
    fn take_due(&mut self, now: Instant) -> Vec<u64> {
        let mut due = Vec::new();
        while let Some(&(deadline, id)) = self.by_deadline.first() {
            if deadline > now {
                break;
            }
            self.by_deadline.pop_first();
            self.by_id.remove(&id);
            due.push(id);
        }
        due
    }

    fn next_deadline(&self) -> Option<Instant> {
        self.by_deadline.first().map(|&(deadline, _)| deadline)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MS: u64 = 1_000_000;

    #[test]
    fn timers_come_due_in_deadline_order() {
        let now = Instant::now();
        let mut t = Timers::default();
        t.add(1, now, 30 * MS);
        t.add(2, now, 10 * MS);
        t.add(3, now, 20 * MS);
        assert_eq!(t.next_deadline(), Some(now + Duration::from_millis(10)));
        assert_eq!(t.take_due(now), Vec::<u64>::new());
        assert_eq!(t.take_due(now + Duration::from_millis(20)), [2, 3]);
        assert_eq!(t.take_due(now + Duration::from_millis(30)), [1]);
        assert_eq!(t.next_deadline(), None);
    }

    #[test]
    fn a_cancelled_timer_leaves_nothing_behind() {
        let now = Instant::now();
        let mut t = Timers::default();
        t.add(1, now, MS);
        t.cancel(1);
        t.cancel(1);
        assert_eq!(t.next_deadline(), None);
        assert!(t.by_id.is_empty());
    }

    /// The median, as in `timer`'s own test: a busy machine does not count, but
    /// waits of a whole system tick would. Also shows the reactor end to end.
    #[test]
    fn a_short_timer_does_not_last_a_system_tick() {
        let mut waits: Vec<Duration> = (0..9)
            .map(|i| {
                let start = Instant::now();
                timer(i, MS).expect("the reactor starts");
                while take(8).is_empty() {}
                start.elapsed()
            })
            .collect();
        waits.sort();
        assert!(waits[4] < Duration::from_millis(8), "{waits:?}");
    }
}
