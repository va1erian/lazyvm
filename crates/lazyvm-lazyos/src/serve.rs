// SPDX-License-Identifier: GPL-3.0-or-later
//! The names a LazyVM program serves (docs/msg.md section 5), as LazyOS's
//! own servers hold them (`xui-app/src/server.rs`): a pair whose first end is
//! registered and whose second receives. Java asks for one request at a time
//! per name, and the reactor parks on the endpoints that have a receive
//! waiting, beside the calls doorbell.

use std::collections::HashMap;

use lazyos_sys::msg::{self, parcel};
use libmessenger::Parcel;
use libmessenger::errno::{EAGAIN, EEXIST, EINTR, EMSGSIZE, ENOSPC, EPIPE, ETIMEDOUT};
use platform::messenger::{Answer, Incoming};

/// One served name, by its receiving endpoint.
struct Name {
    name: String,
    published: u64,
    /// The receive waiting for the next request.
    receive: Option<u64>,
}

#[derive(Default)]
pub struct Served {
    names: HashMap<u64, Name>,
    /// Received calls not answered yet: the reply's header, and the endpoint.
    open: HashMap<u64, (u64, u32, u64)>,
}

/// The positive errno an `Answer` carries, from a syscall's negative result.
fn errno(code: i64) -> i32 {
    i32::try_from(-code).unwrap_or(i32::MAX)
}

/// A failure `Answer` for the positive errno `code`.
fn failed(code: i64, delivered: bool) -> Answer {
    Answer::Failed {
        errno: errno(-code),
        delivered,
    }
}

impl Served {
    /// Registers `name`: the receiving endpoint's handle, or the errno. One
    /// `wait` names at most [`msg::WAIT_MAX_ENDPOINTS`] endpoints, so a name
    /// past that is refused rather than left unwatched.
    pub fn serve(
        &mut self,
        name: String,
        interface: u64,
        interface_name: &str,
    ) -> Result<u64, i32> {
        // The kernel lets an owner register a name again, replacing it;
        // lazy.msg refuses a name it already serves, as its loopback does.
        if self.names.values().any(|n| n.name == name) {
            return Err(errno(-EEXIST));
        }
        if self.names.len() >= msg::WAIT_MAX_ENDPOINTS {
            return Err(errno(-ENOSPC));
        }
        let (published, endpoint) = msg::create_pair().map_err(errno)?;
        if let Err(code) = parcel::register(&name, published, &[interface], &[interface_name]) {
            let _ = msg::close(published);
            let _ = msg::close(endpoint);
            return Err(errno(code));
        }
        self.names.insert(
            endpoint,
            Name {
                name,
                published,
                receive: None,
            },
        );
        Ok(endpoint)
    }

    /// Asks for the next request to `served` under receive `id`.
    pub fn receive(&mut self, id: u64, served: u64, done: &mut Vec<(u64, Answer)>) {
        match self.names.get_mut(&served) {
            Some(name) => name.receive = Some(id),
            None => done.push((id, failed(EPIPE, false))),
        }
    }

    pub fn cancel(&mut self, id: u64) {
        for name in self.names.values_mut().filter(|n| n.receive == Some(id)) {
            name.receive = None;
        }
    }

    /// The endpoints to park on: those with a receive waiting.
    pub fn waiting(&self) -> Vec<u64> {
        let waiting = self.names.iter().filter(|(_, n)| n.receive.is_some());
        waiting.map(|(&endpoint, _)| endpoint).collect()
    }

    /// Hands the waiting receive of each of `endpoints` the request queued
    /// for it, if any. A transient failure (nothing queued, an interrupted
    /// syscall, a message too large that the kernel dropped) waits for the
    /// next request.
    pub fn take(&mut self, endpoints: &[u64], buf: &mut [u8], done: &mut Vec<(u64, Answer)>) {
        for &endpoint in endpoints {
            let Some(id) = self.names.get(&endpoint).and_then(|n| n.receive) else {
                continue;
            };
            let answer = match msg::recv_from(endpoint, buf, msg::EXPIRED_DEADLINE) {
                Err(code) if [ETIMEDOUT, EMSGSIZE, EAGAIN, EINTR].contains(&-code) => continue,
                Err(code) => failed(-code, true),
                Ok((result, origin)) => {
                    // Objects are not carried yet (S10-03): what came with a request is let go.
                    for &handle in result.delivered() {
                        let _ = msg::release(handle);
                    }
                    let Some(request) =
                        self.request(endpoint, result.value, &buf[..result.bytes as usize])
                    else {
                        continue;
                    };
                    let caller = [
                        u64::from(origin.uid),
                        u64::from(origin.gid),
                        u64::from(origin.caps),
                        u64::from(origin.label_id),
                        origin.session,
                        result.aux,
                    ];
                    Answer::Reply(Incoming { caller, ..request }.encode())
                }
            };
            if let Some(name) = self.names.get_mut(&endpoint) {
                name.receive = None;
            }
            done.push((id, answer));
        }
    }

    /// The request in `bytes`, remembered for its reply; a malformed call is
    /// answered at once, so its caller does not wait for ever.
    fn request(&mut self, endpoint: u64, txn: u64, bytes: &[u8]) -> Option<Incoming> {
        let Ok(parcel) = Parcel::decode(bytes) else {
            if txn != 0 {
                let _ = parcel::reply(txn, &libmessenger::server::bad_request(0, 0));
            }
            return None;
        };
        let (interface, method) = (parcel.header.interface_id, parcel.header.method);
        if txn != 0 {
            self.open.insert(txn, (interface, method, endpoint));
        }
        Some(Incoming {
            txn,
            interface,
            method,
            caller: [0; 6],
            body: parcel.body,
        })
    }

    /// Answers `txn`. A caller that gave up has no transaction left, which
    /// the kernel reports and nobody needs to hear.
    pub fn reply(&mut self, txn: u64, body: Vec<u8>) {
        if let Some((interface, method, _)) = self.open.remove(&txn) {
            let _ = parcel::reply(txn, &libmessenger::server::reply(interface, method, body));
        }
    }

    /// Withdraws `served`; its waiting receive fails, and the kernel fails
    /// the calls it held.
    pub fn unserve(&mut self, served: u64, done: &mut Vec<(u64, Answer)>) {
        let Some(name) = self.names.remove(&served) else {
            return;
        };
        let _ = parcel::unregister(&name.name);
        let _ = msg::close(name.published);
        let _ = msg::close(served);
        self.open
            .retain(|_, &mut (_, _, endpoint)| endpoint != served);
        if let Some(id) = name.receive {
            done.push((id, failed(EPIPE, true)));
        }
    }
}
