//! Timed parking, close to the requested time on every platform.
//!
//! On Windows a timed wait on a lock or a thread's token ends on the system
//! tick, 15.6 ms apart, so `Thread.sleep(1)` took 16 ms. `std::thread::sleep`
//! is much finer there (measured: to the next 0.5 ms), but cannot be woken. So
//! the last stretch of a wait is timed by one helper thread, which sleeps and
//! unparks the threads whose time has come. It needs no VM, costs nothing until
//! the first short wait, and sleeps on a condition variable when none is left.
//! Elsewhere `park_timeout` is exact enough (docs/design/threads.md).

use std::time::Instant;

/// Parks until `deadline`, or until the thread is unparked. Wakeups can be
/// spurious, or early: callers loop on their condition and on the clock.
pub fn park_until(deadline: Instant) {
    #[cfg(windows)]
    windows::park_until(deadline);
    #[cfg(not(windows))]
    std::thread::park_timeout(deadline.saturating_duration_since(Instant::now()));
}

#[cfg(windows)]
mod windows {
    use std::sync::{Condvar, Mutex, MutexGuard, OnceLock, PoisonError};
    use std::thread::Thread;
    use std::time::{Duration, Instant};

    /// A wait longer than this starts on the OS timer: late by at most about
    /// one tick (measured: 16.6 ms), it still ends before the deadline.
    const OS_TIMER_MARGIN: Duration = Duration::from_millis(20);
    /// A sleep ends up to this much later than asked (measured).
    const SLEEP_GRAIN: Duration = Duration::from_micros(500);
    /// Closer than this a sleep would overshoot by more than it is worth, so
    /// the helper yields until the time comes, at the price of this much CPU
    /// time at worst for each wait.
    const SPIN: Duration = Duration::from_micros(100);
    /// The least the helper asks of a sleep.
    const SHORTEST_SLEEP: Duration = Duration::from_micros(1);

    static PENDING: Mutex<Vec<(Instant, Thread)>> = Mutex::new(Vec::new());
    static MORE: Condvar = Condvar::new();
    static HELPER: OnceLock<bool> = OnceLock::new();

    fn helper_started() -> bool {
        *HELPER.get_or_init(|| {
            std::thread::Builder::new()
                .name("timer".into())
                .stack_size(64 << 10)
                .spawn(run_helper)
                .is_ok()
        })
    }

    fn pending() -> MutexGuard<'static, Vec<(Instant, Thread)>> {
        PENDING.lock().unwrap_or_else(PoisonError::into_inner)
    }

    pub(super) fn park_until(deadline: Instant) {
        let left = deadline.saturating_duration_since(Instant::now());
        if left > OS_TIMER_MARGIN {
            std::thread::park_timeout(left - OS_TIMER_MARGIN);
        } else if helper_started() {
            let me = std::thread::current();
            let id = me.id();
            pending().push((deadline, me));
            MORE.notify_one();
            std::thread::park();
            // Woken before its time, it takes its entry back. Left there, the
            // entries of a thread hopping on short waits pile up, keep the
            // helper yielding at their deadlines and wake it for nothing.
            let mut list = pending();
            if let Some(at) = list.iter().rposition(|(_, t)| t.id() == id) {
                list.swap_remove(at);
            }
        } else {
            std::thread::park_timeout(left);
        }
    }

    fn run_helper() {
        loop {
            let mut list = pending();
            let now = Instant::now();
            list.retain(|(at, thread)| {
                let due = *at <= now;
                if due {
                    thread.unpark();
                }
                !due
            });
            let Some(next) = list.iter().map(|(at, _)| *at).min() else {
                drop(MORE.wait(list).unwrap_or_else(PoisonError::into_inner));
                continue;
            };
            drop(list);
            let left = next.saturating_duration_since(now);
            if left <= SPIN {
                std::thread::yield_now();
            } else {
                // Newcomers may be due sooner, so it never sleeps for long.
                let sleep = left.saturating_sub(SLEEP_GRAIN).min(SLEEP_GRAIN);
                std::thread::sleep(sleep.max(SHORTEST_SLEEP));
            }
        }
    }
}

#[cfg(all(test, windows))]
mod tests {
    use std::time::{Duration, Instant};

    /// The median, so that a busy machine and the first wait, which starts at
    /// any point of a tick, do not count: waits of one system tick (15.6 ms)
    /// that start right after the last take over 14 ms.
    #[test]
    fn a_short_wait_does_not_last_a_system_tick() {
        let mut waits: Vec<Duration> = (0..9)
            .map(|_| {
                let start = Instant::now();
                let deadline = start + Duration::from_millis(1);
                while Instant::now() < deadline {
                    super::park_until(deadline);
                }
                start.elapsed()
            })
            .collect();
        waits.sort();
        assert!(waits[4] < Duration::from_millis(8), "{waits:?}");
    }

    #[test]
    fn a_wait_ends_early_when_unparked() {
        let waiter = std::thread::current();
        let start = Instant::now();
        std::thread::scope(|s| {
            s.spawn(|| waiter.unpark());
            super::park_until(start + Duration::from_secs(60));
        });
        assert!(start.elapsed() < Duration::from_secs(30));
    }
}
