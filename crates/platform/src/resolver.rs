//! Name lookups, which block, on threads of their own (docs/async.md,
//! "Inside"). Up to four, so one dead DNS server does not stall every other
//! lookup. They start on demand and wait for more work; there is no cache.

use std::collections::VecDeque;
use std::net::{SocketAddr, ToSocketAddrs};
use std::sync::{Condvar, Mutex, MutexGuard};

use crate::net::Request;
use crate::reactor;

const MOST_THREADS: usize = 4;
/// The platform's resolver goes deep on Windows, so this is not the reactor's small stack.
const STACK_BYTES: usize = 1024 * 1024;

#[derive(Default)]
struct Queue {
    waiting: VecDeque<(u64, String, u16)>,
    idle: usize,
    threads: usize,
}

static QUEUE: Mutex<Option<Queue>> = Mutex::new(None);
static MORE: Condvar = Condvar::new();

fn queue() -> MutexGuard<'static, Option<Queue>> {
    QUEUE.lock().unwrap_or_else(|e| e.into_inner())
}

/// Looks `host` up; the answer comes to the reactor as `Request::Resolved`.
pub(crate) fn lookup(id: u64, host: String, port: u16) {
    let mut guard = queue();
    let q = guard.get_or_insert_with(Queue::default);
    q.waiting.push_back((id, host, port));
    if q.idle == 0 && q.threads < MOST_THREADS {
        let started = std::thread::Builder::new()
            .name("lazy-resolver".into())
            .stack_size(STACK_BYTES)
            .spawn(run);
        if started.is_ok() {
            q.threads += 1;
        } else if q.threads == 0 {
            // No thread will ever take these, so they fail as lookups.
            let lost = std::mem::take(&mut q.waiting);
            drop(guard);
            for (id, _, _) in lost {
                answer(id, None);
            }
            return;
        }
    }
    MORE.notify_one();
}

fn answer(id: u64, addrs: Option<Vec<SocketAddr>>) {
    // A failed wake leaves it queued for the reactor's next turn.
    let _ = reactor::request(reactor::Request::Net(Request::Resolved { id, addrs }));
}

fn run() {
    loop {
        let (id, host, port) = next();
        let addrs = (host.as_str(), port).to_socket_addrs().ok();
        answer(id, addrs.map(Iterator::collect));
    }
}

fn next() -> (u64, String, u16) {
    let mut guard = queue();
    loop {
        if let Some(q) = guard.as_mut() {
            if let Some(item) = q.waiting.pop_front() {
                return item;
            }
            q.idle += 1;
        }
        guard = MORE.wait(guard).unwrap_or_else(|e| e.into_inner());
        if let Some(q) = guard.as_mut() {
            q.idle -= 1;
        }
    }
}
