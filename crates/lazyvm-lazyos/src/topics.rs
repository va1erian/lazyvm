// SPDX-License-Identifier: GPL-3.0-or-later
//! Topics on LazyOS (docs/msg.md section 6), through `messengerd`'s central
//! broker as LazyOS's own subscribers reach it (`user::central`): the
//! generated `os.lazy.messenger.topics.v1` client, the payload envelope, and a
//! doorbell per subscription. The reactor parks on a bell only while Java
//! waits for that subscription's next event, and pulls one event per wait
//! (`NextEvent` with an expired deadline, which answers at once). Java asks
//! for the next once it has queued one for its handler, so what a slow
//! handler has not taken waits in `lazy.msg`'s queue (`Topics.java`).
//!
//! Every subscription is `buffered` at the broker, at its deepest queue:
//! `lazy.msg` keeps a `latest` subscription's newest event per topic itself
//! (`Topics.java`), and the broker's `latest` would keep one event across
//! every topic a filter matches.
//!
//! A subscribe, a publish and an unsubscribe are calls the reactor thread
//! makes and waits for, each bounded by [`BOUND`]; the broker answers them at
//! once.

use std::collections::HashMap;
use std::time::Duration;

use lazyos_sys::client::Std;
use lazyos_sys::msg;
use libmessenger::Parcel;
use libmessenger::client::{CallFailure, Connection, Deadline, Errno, Handle, Retry, Transport};
use libmessenger::envelope;
use libmessenger::errno::{EAGAIN, EINVAL, ENOSPC, EPIPE};
use messenger_generated::os_lazy_messenger_topics_v1 as wire;
use platform::messenger::{Answer, TopicEvent};

use crate::messenger::errno;

/// What one call to the broker may take, finding it included: the reactor
/// thread waits for it.
const BOUND: Deadline = Deadline::millis(500);
/// How soon the reactor pulls again from a broker that was busy, since that
/// pull re-arms nothing and no ring would come.
pub const BUSY_RETRY: Duration = Duration::from_millis(10);
/// The broker's deepest `buffered` queue.
const DEPTH: u32 = 64;
/// Most doorbell messages consumed at once, and what one needs of a buffer.
const MAX_RINGS: usize = 16;
const RING_BYTES: usize = 512;

/// `lazyos-sys`'s std transport, with the reactor's bound on finding the
/// broker instead of the runtime's ten seconds.
struct Bounded;

impl Transport for Bounded {
    fn connect_bound(&self) -> Deadline {
        BOUND
    }

    fn call_flags(&self) -> u16 {
        Std.call_flags()
    }

    fn resolve(&self, name: &str, until: Deadline) -> Result<Handle, Errno> {
        Std.resolve(name, until)
    }

    fn call(
        &self,
        handle: Handle,
        request: &Parcel,
        until: Deadline,
        retry: Retry,
    ) -> Result<Parcel, CallFailure> {
        Std.call(handle, request, until, retry)
    }

    fn send(&self, handle: Handle, request: &Parcel) -> Result<(), Errno> {
        Std.send(handle, request)
    }

    fn release(&self, handle: Handle) {
        Std.release(handle)
    }
}

type Broker = wire::Client<Bounded>;

/// One subscription, by its bell (our end of the doorbell).
struct Sub {
    /// The broker's id.
    id: u64,
    /// The `Next` waiting for its event.
    next: Option<u64>,
    /// Whether the broker may hold an event: the bell rang, or the last pull
    /// found one, or it is new (its retained values came before its bell).
    unread: bool,
    /// How many of its first events replay retained values (see [`replays`]).
    replays: u64,
}

#[derive(Default)]
pub struct Topics {
    broker: Option<Broker>,
    subs: HashMap<u64, Sub>,
    /// The last pull found the broker busy.
    busy: bool,
}

/// The positive errno of a failed broker call.
fn refused(error: &wire::ClientError) -> i32 {
    errno(error.code())
}

fn failed(errno: i32) -> Answer {
    Answer::Failed {
        errno,
        delivered: true,
    }
}

impl Topics {
    fn broker(&mut self) -> &Broker {
        self.broker.get_or_insert_with(|| {
            Broker::from_connection(Connection::new(Bounded, wire::SERVICE_NAME))
        })
    }

    pub fn len(&self) -> usize {
        self.subs.len()
    }

    /// Subscribes to `filter`: the bell's handle, or the errno. `taken` is how
    /// many other endpoints the reactor's one wait names, which holds at most
    /// [`msg::WAIT_MAX_ENDPOINTS`].
    pub fn subscribe(&mut self, filter: &str, taken: usize) -> Result<u64, i32> {
        if self.subs.len() + taken >= msg::WAIT_MAX_ENDPOINTS {
            return Err(ENOSPC as i32);
        }
        let broker = self.broker();
        let id = broker
            .subscribe_within(filter, wire::QOS_BUFFERED, DEPTH, BOUND)
            .map_err(|e| refused(&e))?;
        let replays = replays(broker, id);
        let bell = match doorbell(broker, id) {
            Ok(bell) => bell,
            Err(code) => {
                let _ = broker.unsubscribe_within(id, BOUND);
                return Err(code);
            }
        };
        let sub = Sub {
            id,
            next: None,
            unread: true,
            replays,
        };
        self.subs.insert(bell, sub);
        Ok(bell)
    }

    /// Asks for the next event of `bell` under `id`.
    pub fn next(&mut self, id: u64, bell: u64, done: &mut Vec<(u64, Answer)>) {
        match self.subs.get_mut(&bell) {
            Some(sub) => sub.next = Some(id),
            None => done.push((id, failed(EPIPE as i32))),
        }
    }

    pub fn cancel(&mut self, id: u64) {
        for sub in self.subs.values_mut().filter(|s| s.next == Some(id)) {
            sub.next = None;
        }
    }

    /// The bells to park on: those of a waiting `Next` with nothing to pull.
    pub fn waiting(&self) -> Vec<u64> {
        let quiet = self
            .subs
            .iter()
            .filter(|(_, s)| s.next.is_some() && !s.unread);
        quiet.map(|(&bell, _)| bell).collect()
    }

    /// How long the reactor may park: briefly after a busy broker, which a
    /// pull at once would find busy again; else not at all while a waiting
    /// `Next` has something to pull.
    pub fn bound(&self, timeout: Option<Duration>) -> Option<Duration> {
        if self.busy {
            return Some(timeout.map_or(BUSY_RETRY, |t| t.min(BUSY_RETRY)));
        }
        if self.subs.values().any(|s| s.next.is_some() && s.unread) {
            return Some(Duration::ZERO);
        }
        timeout
    }

    /// Consumes the rings of the bells in `rung`, then hands each waiting
    /// `Next` the event its subscription holds, if any.
    pub fn take(&mut self, rung: &[u64], done: &mut Vec<(u64, Answer)>) {
        for (&bell, sub) in self.subs.iter_mut().filter(|(b, _)| rung.contains(b)) {
            take_rings(bell);
            sub.unread = true;
        }
        self.busy = false;
        let ready: Vec<u64> = self
            .subs
            .iter()
            .filter(|(_, s)| s.next.is_some() && s.unread)
            .map(|(&bell, _)| bell)
            .collect();
        for bell in ready {
            self.pull(bell, done);
        }
    }

    /// One `NextEvent` for `bell`'s subscription. An empty answer re-arms its
    /// bell; a busy broker is tried again soon.
    fn pull(&mut self, bell: u64, done: &mut Vec<(u64, Answer)>) {
        // A subscription was made through the broker, so both are there.
        let (Some(broker), Some(sub)) = (&self.broker, self.subs.get_mut(&bell)) else {
            return;
        };
        let answer = match broker.next_event_within(sub.id, Deadline::Poll) {
            Ok(event) if event.topic.is_empty() => {
                sub.unread = false;
                return;
            }
            Ok(event) => Answer::Reply(
                TopicEvent {
                    payload: envelope::unwrap(&event.payload),
                    topic: event.topic,
                    retained: replay(&mut sub.replays) && event.retained,
                    publisher: event.publisher,
                }
                .encode(),
            ),
            Err(e) if e.is_timeout() || e.code() == -EAGAIN => {
                self.busy = true;
                return;
            }
            Err(e) => failed(refused(&e)),
        };
        if let Some(id) = sub.next.take() {
            done.push((id, answer));
        }
    }

    /// Ends `bell`'s subscription; its waiting `Next` fails with EPIPE.
    pub fn unsubscribe(&mut self, bell: u64, done: &mut Vec<(u64, Answer)>) {
        let Some(sub) = self.subs.remove(&bell) else {
            return;
        };
        let _ = msg::close(bell);
        if let Some(broker) = &self.broker {
            // A broker that is gone took the subscription with it.
            let _ = broker.unsubscribe_within(sub.id, BOUND);
        }
        if let Some(id) = sub.next {
            done.push((id, failed(EPIPE as i32)));
        }
    }

    /// Publishes `payload` on `topic`, in the platform's envelope.
    pub fn publish(&mut self, topic: &str, payload: &[u8], retained: bool) -> Answer {
        let Ok(wrapped) = envelope::wrap(payload) else {
            return failed(EINVAL as i32);
        };
        match self
            .broker()
            .publish_within(topic, &wrapped, retained, BOUND)
        {
            Ok(_) => Answer::Reply(Vec::new()),
            Err(e) => failed(refused(&e)),
        }
    }
}

/// How many events subscription `id` holds right after its subscribe: the
/// retained values the broker replayed into it then. The broker marks a live
/// event published as retained the same way as a replay, and `lazy.msg`'s
/// `retained()` means a replay, so the first this many are the replays. Asked
/// at once; a publish another task makes in between counts as one (and keeps
/// the broker's mark), and a failed ask counts none.
fn replays(broker: &Broker, id: u64) -> u64 {
    broker
        .stats_within(id, BOUND)
        .map_or(0, |stats| stats.queued)
}

/// Whether the next event is one of the `left` replays, counting it.
fn replay(left: &mut u64) -> bool {
    let is = *left > 0;
    *left = left.saturating_sub(1);
    is
}

/// Gives subscription `id` a doorbell: our end's handle, or the errno.
fn doorbell(broker: &Broker, id: u64) -> Result<u64, i32> {
    let (bell, theirs) = msg::create_pair().map_err(errno)?;
    if let Err(e) = broker.bell_within(id, theirs, BOUND) {
        // The far end may have moved already; closing it then is a harmless EBADF.
        let _ = msg::close(theirs);
        let _ = msg::close(bell);
        return Err(refused(&e));
    }
    Ok(bell)
}

/// Consumes the `Ready` messages `bell` holds; the wait said one is there.
fn take_rings(bell: u64) {
    let mut buf = [0u8; RING_BYTES];
    for _ in 0..MAX_RINGS {
        if !matches!(msg::queued(bell), Ok(n) if n > 0) {
            return;
        }
        let _ = msg::recv(bell, &mut buf, msg::EXPIRED_DEADLINE);
    }
}
