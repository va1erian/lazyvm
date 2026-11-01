//! The reactor: one native thread that parks in `mio` and never runs bytecode
//! (docs/async.md, "Inside"). Java threads queue requests to it and wake it;
//! it posts finished operations to an inbox, which the dispatcher, a Java
//! thread, empties through [`take`]. The sockets are in [`crate::net`].

use std::collections::{BTreeSet, HashMap, VecDeque};
use std::hash::Hash;
use std::io;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Condvar, Mutex, MutexGuard, OnceLock, mpsc};
use std::time::{Duration, Instant};

use mio::{Events, Poll, Token, Waker};

use crate::net::{self, Net};
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
/// The longest `poll` parks with a deadline ahead. On Windows a timeout of about
/// 49.7 days or more reaches the OS as "no timeout", and the deadline would be missed.
const LONGEST_PARK: Duration = Duration::from_secs(24 * 60 * 60);

pub(crate) enum Request {
    Timer { id: u64, deadline: Instant },
    Cancel(u64),
    Net(net::Request),
}

/// What a finished operation left.
#[derive(Debug, PartialEq)]
pub enum Outcome {
    Done,
    Bytes(Vec<u8>),
    Failed(String),
}

/// A finished operation. `handle` is what must be released if nobody wants
/// the result any more: the socket it made or worked on, or 0.
#[derive(Debug, PartialEq)]
pub struct Completion {
    pub id: u64,
    pub handle: u64,
    pub outcome: Outcome,
}

#[derive(Default)]
struct Inbox {
    done: VecDeque<Completion>,
    woken: bool,
}

/// What a timer is for. Operations are timers of their own; the others time
/// a stage of a socket's life.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub(crate) enum Due {
    Operation(u64),
    Attempt(u64),
    Closing(u64),
    AcceptRetry(u64),
}

static REQUESTS: Mutex<Vec<Request>> = Mutex::new(Vec::new());
static INBOX: OnceLock<(Mutex<Inbox>, Condvar)> = OnceLock::new();
static WAKER: OnceLock<Result<Waker, String>> = OnceLock::new();
static WAKES: AtomicU64 = AtomicU64::new(0);
/// A wake failed, so the requests queued behind it still need one.
static WAKE_OWED: AtomicBool = AtomicBool::new(false);

fn locked<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn inbox() -> &'static (Mutex<Inbox>, Condvar) {
    INBOX.get_or_init(Default::default)
}

/// Starts an operation that finishes `nanos` from now. It comes to [`take`]
/// once, unless it is cancelled first. A deadline past what `Instant` can hold
/// is never reached.
pub fn timer(id: u64, nanos: u64) -> io::Result<()> {
    match Instant::now().checked_add(Duration::from_nanos(nanos)) {
        Some(deadline) => request(Request::Timer { id, deadline }),
        None => waker().map(|_| ()),
    }
}

/// Withdraws an operation. An id that has already finished is ignored, and
/// with no reactor running there is nothing to withdraw.
pub fn cancel(id: u64) {
    if WAKER.get().is_some() {
        // A failed wake leaves the request queued for the reactor's next turn.
        let _ = request(Request::Cancel(id));
    }
}

/// Up to `max` finished operations. Blocks until there is one or
/// [`wake_taker`] was called since the last `take`, so it may return none.
pub fn take(max: usize) -> Vec<Completion> {
    let (lock, ready) = inbox();
    let mut inbox = locked(lock);
    while inbox.done.is_empty() && !inbox.woken {
        inbox = ready.wait(inbox).unwrap_or_else(|e| e.into_inner());
    }
    inbox.woken = false;
    let n = inbox.done.len().min(max);
    inbox.done.drain(..n).collect()
}

/// Puts back what a [`take`] could not hand on, ahead of what came since.
pub fn untake(rest: Vec<Completion>) {
    let (lock, ready) = inbox();
    let mut inbox = locked(lock);
    for completion in rest.into_iter().rev() {
        inbox.done.push_front(completion);
    }
    ready.notify_one();
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
pub(crate) fn request(r: Request) -> io::Result<()> {
    let waker = waker()?;
    let was_empty = {
        let mut queue = locked(&REQUESTS);
        let was_empty = queue.is_empty();
        queue.push(r);
        was_empty
    };
    if was_empty || WAKE_OWED.swap(false, Ordering::Relaxed) {
        waker
            .wake()
            .inspect_err(|_| WAKE_OWED.store(true, Ordering::Relaxed))?;
    }
    Ok(())
}

/// Hands a finished operation to the dispatcher.
pub(crate) fn post(completion: Completion) {
    let (lock, ready) = inbox();
    locked(lock).done.push_back(completion);
    ready.notify_one();
}

fn run(mut poll: Poll) {
    let mut events = Events::with_capacity(256);
    let mut timers = Timers::default();
    let mut net = Net::default();
    loop {
        // Out of the loop's head: a guard there would hold the queue through the handling.
        let batch = std::mem::take(&mut *locked(&REQUESTS));
        for r in batch {
            match r {
                Request::Timer { id, deadline } => timers.add(Due::Operation(id), deadline),
                Request::Cancel(id) => {
                    timers.cancel(Due::Operation(id));
                    net.cancel(id, poll.registry(), &mut timers);
                }
                Request::Net(r) => net.request(r, poll.registry(), &mut timers),
            }
        }
        let now = Instant::now();
        for due in timers.take_due(now) {
            match due {
                Due::Operation(id) => post(Completion {
                    id,
                    handle: 0,
                    outcome: Outcome::Done,
                }),
                other => net.timer(other, poll.registry(), &mut timers),
            }
        }
        match poll.poll(&mut events, park_for(timers.next_deadline(), now)) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
            Err(_) => std::thread::sleep(POLL_RETRY),
        }
        WAKES.fetch_add(1, Ordering::Relaxed);
        for event in events.iter().filter(|e| e.token() != WAKE) {
            net.ready(event.token(), poll.registry(), &mut timers);
        }
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
            left.map(far_park)
        }
    }
}

/// How long `poll` may park with `left` (over [`SLEEPER_RANGE`]) to go.
fn far_park(left: Duration) -> Duration {
    (left - SLEEPER_RANGE).min(LONGEST_PARK)
}

/// Times the last stretch before a deadline and then wakes the reactor. It
/// starts at the first deadline that close, and parks while none is.
mod sleeper {
    use super::{Instant, Mutex, OnceLock, POLL_RETRY, WAKER, locked, timer};
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
            // A deadline is armed only after a request, so the waker is there.
            // The reactor may be parked with no timeout, so a failed wake is tried again.
            if let Some(Ok(waker)) = WAKER.get()
                && waker.wake().is_err()
            {
                std::thread::sleep(POLL_RETRY);
                continue;
            }
            let mut armed = locked(&ARMED);
            if *armed == Some(at) {
                *armed = None;
            }
        }
    }
}

/// Timers by deadline, with the deadline by key so that a cancel is cheap: a
/// timeout cancelled on every read must not pile up until its deadline.
pub(crate) struct Timers<K = Due> {
    by_deadline: BTreeSet<(Instant, K)>,
    by_key: HashMap<K, Instant>,
}

impl<K> Default for Timers<K> {
    fn default() -> Self {
        Timers {
            by_deadline: BTreeSet::new(),
            by_key: HashMap::new(),
        }
    }
}

impl<K: Copy + Ord + Hash> Timers<K> {
    pub(crate) fn add(&mut self, key: K, deadline: Instant) {
        self.cancel(key);
        self.by_deadline.insert((deadline, key));
        self.by_key.insert(key, deadline);
    }

    pub(crate) fn cancel(&mut self, key: K) {
        if let Some(deadline) = self.by_key.remove(&key) {
            self.by_deadline.remove(&(deadline, key));
        }
    }

    /// The keys due at `now`, soonest first.
    fn take_due(&mut self, now: Instant) -> Vec<K> {
        let mut due = Vec::new();
        while let Some(&(deadline, key)) = self.by_deadline.first() {
            if deadline > now {
                break;
            }
            self.by_deadline.pop_first();
            self.by_key.remove(&key);
            due.push(key);
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

    fn ms(n: u64) -> Duration {
        Duration::from_millis(n)
    }

    #[test]
    fn timers_come_due_in_deadline_order() {
        let now = Instant::now();
        let mut t = Timers::<u64>::default();
        t.add(1, now + ms(30));
        t.add(2, now + ms(10));
        t.add(3, now + ms(20));
        assert_eq!(t.next_deadline(), Some(now + ms(10)));
        assert_eq!(t.take_due(now), Vec::<u64>::new());
        assert_eq!(t.take_due(now + ms(20)), [2, 3]);
        assert_eq!(t.take_due(now + ms(30)), [1]);
        assert_eq!(t.next_deadline(), None);
    }

    #[test]
    fn a_cancelled_timer_leaves_nothing_behind() {
        let now = Instant::now();
        let mut t = Timers::<u64>::default();
        t.add(1, now + ms(1));
        t.cancel(1);
        t.cancel(1);
        assert_eq!(t.next_deadline(), None);
        assert!(t.by_key.is_empty());
    }

    #[test]
    fn a_far_deadline_still_ends_the_park_in_time() {
        let sixty_days = Duration::from_secs(60 * 24 * 60 * 60);
        assert_eq!(far_park(sixty_days), LONGEST_PARK);
        assert_eq!(far_park(ms(50)), ms(30));
    }

    /// The median, as in `timer`'s own test: a busy machine does not count, but
    /// waits of a whole system tick would. Also shows the reactor end to end.
    #[test]
    fn a_short_timer_does_not_last_a_system_tick() {
        let mut waits: Vec<Duration> = (0..9)
            .map(|i| {
                let id = u64::MAX - i;
                let start = Instant::now();
                timer(id, MS).expect("the reactor starts");
                while !take(8).iter().any(|c| c.id == id) {}
                start.elapsed()
            })
            .collect();
        waits.sort();
        assert!(waits[4] < Duration::from_millis(8), "{waits:?}");
    }
}
