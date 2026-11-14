// SPDX-License-Identifier: GPL-3.0-or-later
//! The LazyOS Messenger transport of `platform::messenger` (docs/msg.md
//! section 7): calls begun with `CALL_BEGIN` on the reactor thread, which
//! parks in one `wait` over the calls doorbell and `mio`'s epoll descriptor
//! (LazyOS `docs/architecture/call-completion.md`), and reads each finished
//! call with `CALL_CHECK`. Served names are registered and received on here
//! too ([`crate::serve`]), and topics followed by their doorbells
//! ([`crate::topics`]). Deadlines, resends and errors are `lazy.msg`'s
//! (`Client.java`); this moves bytes.

use std::collections::HashMap;
use std::time::Duration;

use lazyos_sys::msg::{self, parcel};
use libmessenger::errno::{EAGAIN, ENOENT, EPIPE, ETIMEDOUT};
use libmessenger::{MAX_PARCEL_BYTES, flags};
use platform::messenger::{Answer, Call, Request, Transport};

use crate::serve::Served;
use crate::topics::Topics;

/// How many finished transaction ids one `CALL_DRAIN` takes.
const DRAIN: usize = 64;
/// How long after the program's own deadline the kernel ends a call, so the
/// program sees its ETIMEDOUT first and a withdrawn call does not outlive it.
const KERNEL_GRACE_MS: u64 = 1000;

/// The transport, owned by the reactor thread.
pub struct Kernel {
    /// The resolved handle of each name called, released when its peer dies.
    handles: HashMap<String, u64>,
    /// Calls in flight: the transaction and its call's id, with the name it went to.
    pending: HashMap<u64, (u64, String)>,
    /// Transactions of withdrawn calls, consumed when they end so the kernel
    /// does not keep their replies.
    dropped: Vec<u64>,
    /// The names this process serves.
    pub(crate) served: Served,
    /// The topics it follows.
    topics: Topics,
    pub(crate) reply: Vec<u8>,
    pub(crate) done: Vec<(u64, Answer)>,
    /// What the last park reported: the endpoints it named and the ready
    /// mask, or `None` when it failed and everything must be looked at.
    rung: Option<(Vec<u64>, u64)>,
}

/// `platform::messenger::Factory`.
pub fn factory() -> Option<Box<dyn Transport>> {
    Some(Box::new(Kernel {
        handles: HashMap::new(),
        pending: HashMap::new(),
        dropped: Vec::new(),
        served: Served::default(),
        topics: Topics::default(),
        reply: vec![0; MAX_PARCEL_BYTES],
        done: Vec::new(),
        rung: None,
    }))
}

impl Kernel {
    fn handle(&mut self, name: &str) -> Result<u64, i64> {
        if let Some(&handle) = self.handles.get(name) {
            return Ok(handle);
        }
        let handle = parcel::resolve(name)?;
        self.handles.insert(name.to_owned(), handle);
        Ok(handle)
    }

    /// A dead peer's handle is let go, so the next call (`lazy.msg`'s
    /// resend) resolves the name again and reaches a restarted service.
    fn forget(&mut self, name: &str) {
        if let Some(handle) = self.handles.remove(name) {
            // A handle already gone has nothing left to release.
            let _ = msg::release(handle);
        }
    }

    fn fail(&mut self, id: u64, name: &str, code: i64, delivered: bool) {
        if code == -EPIPE {
            self.forget(name);
        }
        let errno = i32::try_from(-code).unwrap_or(i32::MAX);
        self.done.push((id, Answer::Failed { errno, delivered }));
    }

    /// Looks at every call in flight: the doorbell's queue drops entries
    /// when it overflows, so the transactions themselves are the record.
    fn check_all(&mut self) {
        let mut ids = [0u64; DRAIN];
        while matches!(msg::drain_calls(&mut ids), Ok(n) if n == DRAIN) {}
        // Checked into the real buffer, so a finished one is consumed whatever its size.
        let reply = &mut self.reply;
        self.dropped
            .retain(|&txn| msg::call_check(txn, reply) == Err(-EAGAIN));
        let txns: Vec<u64> = self.pending.keys().copied().collect();
        for txn in txns {
            let result = msg::call_check(txn, &mut self.reply);
            if result == Err(-EAGAIN) {
                continue;
            }
            let Some((id, name)) = self.pending.remove(&txn) else {
                continue;
            };
            match result.and_then(|len| parcel::decode(&self.reply[..len])) {
                Ok(reply) => self.done.push((id, Answer::Reply(reply.body))),
                // A transaction the kernel no longer knows went with its
                // channel, when the server closed both ends: its peer died.
                Err(code) if code == -ENOENT => self.fail(id, &name, -EPIPE, true),
                Err(code) => self.fail(id, &name, code, true),
            }
        }
    }
}

impl Transport for Kernel {
    fn park(&mut self, fd: i32, timeout: Option<Duration>) {
        let flags = msg::WAIT_CALLS | msg::wait_fd(fd as u32);
        let mut words = self.served.waiting();
        words.extend(self.topics.waiting());
        // An interrupted or failed wait only costs a turn of the loop, which
        // then looks at everything.
        let ready = match self.topics.bound(timeout) {
            Some(t) => {
                let nanos = u64::try_from(t.as_nanos()).unwrap_or(u64::MAX);
                msg::wait_any_ns(
                    &words,
                    flags,
                    lazyos_sys::time::monotonic_ns().saturating_add(nanos),
                )
            }
            None => msg::wait_any(&words, flags, 0),
        };
        self.rung = match ready {
            Ok(mask) => Some((words, mask)),
            Err(code) if code == -ETIMEDOUT => Some((words, 0)),
            Err(_) => None,
        };
    }

    fn request(&mut self, request: Request) {
        match request {
            Request::Call(call) => self.begin(call),
            Request::Serve {
                name,
                interface,
                interface_name,
                done,
            } => {
                let taken = self.topics.len();
                // A caller that stopped waiting needs no answer.
                let _ = done.send(self.served.serve(name, interface, &interface_name, taken));
            }
            Request::Subscribe { filter, done } => {
                let _ = done.send(self.topics.subscribe(&filter, self.served.len()));
            }
            Request::Next { id, subscription } => {
                self.topics.next(id, subscription, &mut self.done)
            }
            Request::Unsubscribe { subscription } => {
                self.topics.unsubscribe(subscription, &mut self.done)
            }
            Request::Publish {
                id,
                topic,
                payload,
                retained,
            } => {
                let answer = self.topics.publish(&topic, &payload, retained);
                self.done.push((id, answer));
            }
            Request::Receive { id, served } => self.served.receive(id, served, &mut self.done),
            Request::Reply { txn, body } => self.served.reply(txn, body),
            Request::Unserve { served } => self.served.unserve(served, &mut self.done),
        }
    }

    fn cancel(&mut self, id: u64) {
        let txn = self
            .pending
            .iter()
            .find(|(_, (call, _))| *call == id)
            .map(|(&txn, _)| txn);
        if let Some(txn) = txn {
            self.pending.remove(&txn);
            self.dropped.push(txn);
        }
        self.served.cancel(id);
        self.topics.cancel(id);
    }

    /// Looks only where the park says something happened: the calls when
    /// the doorbell rang, the endpoints whose bit is set.
    fn finished(&mut self) -> Vec<(u64, Answer)> {
        let rung = self.rung.take();
        let calls = rung
            .as_ref()
            .is_none_or(|(_, mask)| mask & msg::CALLS_READY != 0);
        if calls && (!self.pending.is_empty() || !self.dropped.is_empty()) {
            self.check_all();
        }
        let endpoints = match &rung {
            None => {
                let mut all = self.served.waiting();
                all.extend(self.topics.waiting());
                all
            }
            Some((words, mask)) => {
                let ready = words
                    .iter()
                    .enumerate()
                    .filter(|(i, _)| mask & (1 << i) != 0);
                ready.map(|(_, &endpoint)| endpoint).collect()
            }
        };
        self.served
            .take(&endpoints, &mut self.reply, &mut self.done);
        self.topics.take(&endpoints, &mut self.done);
        std::mem::take(&mut self.done)
    }
}

impl Kernel {
    fn begin(&mut self, call: Call) {
        let cached = self.handles.contains_key(&call.service);
        let handle = match self.handle(&call.service) {
            Ok(handle) => handle,
            Err(code) => return self.fail(call.id, &call.service, code, false),
        };
        let mut bits = flags::ALLOW_NESTED;
        if call.oneway {
            bits |= flags::ONE_WAY;
        }
        let request = parcel::request(call.interface, call.method, bits, call.body);
        let sent = if call.oneway {
            parcel::send(handle, &request).map(|()| None)
        } else {
            parcel::encode(&request)
                .and_then(|bytes| {
                    msg::call_begin(handle, &bytes, kernel_deadline(call.deadline_ms))
                })
                .map(Some)
        };
        match sent {
            Ok(None) => self.done.push((call.id, Answer::Reply(Vec::new()))),
            Ok(Some(txn)) => {
                self.pending.insert(txn, (call.id, call.service));
            }
            // A cached handle whose channel is gone (the service exited and
            // was reclaimed) is unknown to the kernel: its peer died and
            // nothing was queued, so the call is sent again to a fresh resolve.
            Err(code) if cached && code == -ENOENT => {
                self.fail(call.id, &call.service, -EPIPE, false)
            }
            Err(code) => self.fail(call.id, &call.service, code, false),
        }
    }
}

/// The absolute tick the kernel ends a call at, a little after the
/// program's own deadline; 0 (never) for a call with none.
fn kernel_deadline(ms: u64) -> u64 {
    if ms == 0 {
        return 0;
    }
    lazyos_sys::time::deadline_after_ms(ms.saturating_add(KERNEL_GRACE_MS))
}
