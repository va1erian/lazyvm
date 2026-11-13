//! The Messenger seam (docs/msg.md section 7, docs/async.md "Messenger joins
//! in S10"). LazyOS's Messenger handles belong to the thread that made them,
//! so the reactor thread owns them all: Java threads queue calls to it like
//! socket requests, and it parks in the transport's one `wait` over the calls
//! doorbell and `mio`'s descriptor instead of in `mio` alone. The transport
//! itself is GPL and lives in `crates/lazyvm-lazyos`, which [`install`]s it;
//! with none installed (Windows, the plain `lazyvm`) the reactor parks in
//! `mio` and `lazy.msg` uses its loopback broker.

use std::io;
use std::sync::{OnceLock, mpsc};
use std::time::Duration;

use mio::{Events, Poll};

use crate::reactor::{self, Completion, Outcome};

/// What Java asks of the transport. A [`Request::Call`] and a
/// [`Request::Receive`] each end in one completion with an [`Answer`]; a
/// [`Request::Serve`] answers on its channel; the others answer nothing.
#[derive(Debug)]
pub enum Request {
    Call(Call),
    /// Serves `name` as `interface` (its id and name, which LazyOS checks
    /// against the app's domain). The registration's handle, or the errno,
    /// goes straight back to the caller, which waits for it: `lazy.msg`
    /// serves synchronously, from any thread, the loop's included.
    Serve {
        name: String,
        interface: u64,
        interface_name: String,
        done: mpsc::Sender<Result<u64, i32>>,
    },
    /// Waits for the next request to registration `served`; answers with it
    /// as [`Incoming::encode`] writes it.
    Receive {
        id: u64,
        served: u64,
    },
    /// Answers the received transaction `txn` with `body`.
    Reply {
        txn: u64,
        body: Vec<u8>,
    },
    /// Withdraws registration `served`.
    Unserve {
        served: u64,
    },
}

/// A request a served name received, with the sender's kernel-stamped
/// credential (`lazy.msg.Caller`).
#[derive(Debug, PartialEq)]
pub struct Incoming {
    /// The transaction to reply to; 0 for a one-way request.
    pub txn: u64,
    pub interface: u64,
    pub method: u32,
    /// uid, gid, caps, label, session and sender.
    pub caller: [u64; 6],
    pub body: Vec<u8>,
}

impl Incoming {
    /// `txn`, `interface`, the six credential words (little-endian 64-bit),
    /// `method` (32-bit), then the body: what `NativeBroker` reads.
    pub fn encode(self) -> Vec<u8> {
        let mut out = Vec::with_capacity(68 + self.body.len());
        for word in [self.txn, self.interface].iter().chain(&self.caller) {
            out.extend(word.to_le_bytes());
        }
        out.extend(self.method.to_le_bytes());
        out.extend(self.body);
        out
    }
}

/// A call on its way to a served name: the registry name, the request
/// header's interface id and method, and the encoded body (docs/msg.md
/// section 3). Objects (channels, buffers) are not carried yet (S10-03).
#[derive(Debug, PartialEq)]
pub struct Call {
    pub id: u64,
    pub service: String,
    pub interface: u64,
    pub method: u32,
    pub body: Vec<u8>,
    pub oneway: bool,
    /// The call's bound in milliseconds, 0 for none. `lazy.msg` times the
    /// call out itself; the transport may give the kernel a later bound so a
    /// withdrawn call does not outlive it there.
    pub deadline_ms: u64,
}

/// How a call ended, as the reactor hands it to Java: the reply body, or an
/// errno and whether the request was queued (so a resend is safe when not).
#[derive(Debug, PartialEq)]
pub enum Answer {
    Reply(Vec<u8>),
    Failed { errno: i32, delivered: bool },
}

impl Answer {
    /// The payload `lazy.internal.msg.NativeBroker` reads: `0` then the body,
    /// or `1`, the errno (little-endian) and `delivered`.
    pub fn encode(self) -> Vec<u8> {
        match self {
            Answer::Reply(body) => {
                let mut out = Vec::with_capacity(body.len() + 1);
                out.push(0);
                out.extend(body);
                out
            }
            Answer::Failed { errno, delivered } => {
                let mut out = vec![1];
                out.extend(errno.to_le_bytes());
                out.push(u8::from(delivered));
                out
            }
        }
    }
}

/// A Messenger transport, made on the reactor thread and used only there.
pub trait Transport {
    /// Parks until `fd` (`mio`'s descriptor) is readable, a call finished, or
    /// `timeout` passed (`None`: no bound).
    fn park(&mut self, fd: i32, timeout: Option<Duration>);
    /// Starts `request`; an answer, if it has one, comes back from
    /// [`Transport::finished`].
    fn request(&mut self, request: Request);
    /// Withdraws call or receive `id`: no answer comes for it.
    fn cancel(&mut self, id: u64);
    /// The calls, registrations and receives that ended since the last look, by id.
    fn finished(&mut self) -> Vec<(u64, Answer)>;
}

/// Makes the transport on the reactor thread; `None` when Messenger cannot
/// be reached, and then `lazy.msg` calls fail with ENOSYS.
pub type Factory = fn() -> Option<Box<dyn Transport>>;

static FACTORY: OnceLock<Factory> = OnceLock::new();

/// Installs the platform's transport, before any program code runs; a
/// second install is ignored.
pub fn install(factory: Factory) {
    let _ = FACTORY.set(factory);
}

/// Whether a transport is installed, so `lazy.msg` picks it over loopback.
pub fn available() -> bool {
    FACTORY.get().is_some()
}

/// Called on the reactor thread when it starts.
pub(crate) fn make() -> Option<Box<dyn Transport>> {
    FACTORY.get().and_then(|make| make())
}

/// Serves `name` (see [`Request::Serve`]): the registration's handle, or
/// the errno the transport gave, ENOSYS with none.
pub fn serve(
    name: String,
    interface: u64,
    interface_name: String,
) -> std::io::Result<Result<u64, i32>> {
    let (done, answer) = mpsc::channel();
    request(Request::Serve {
        name,
        interface,
        interface_name,
        done,
    })?;
    answer
        .recv()
        .map_err(|_| std::io::Error::other("the reactor thread ended"))
}

/// Queues `request` for the reactor thread.
pub fn request(request: Request) -> std::io::Result<()> {
    reactor::request(reactor::Request::Msg(request))
}

/// The completion of call `id`.
pub(crate) fn completion(id: u64, answer: Answer) -> Completion {
    Completion {
        id,
        handle: 0,
        outcome: Outcome::Bytes(answer.encode()),
    }
}

/// With no transport, what expects an answer fails at once with ENOSYS.
pub(crate) fn begin(msg: &mut Option<Box<dyn Transport>>, r: Request) {
    use Request as R;
    let Some(msg) = msg else {
        let id = match r {
            R::Call(Call { id, .. }) | R::Receive { id, .. } => id,
            R::Serve { done, .. } => {
                // A caller that stopped waiting needs no answer.
                let _ = done.send(Err(ENOSYS));
                return;
            }
            R::Reply { .. } | R::Unserve { .. } => return,
        };
        let refused = Answer::Failed {
            errno: ENOSYS,
            delivered: false,
        };
        return reactor::post(completion(id, refused));
    };
    msg.request(r);
}

/// LazyOS's errno for "no such function", as `lazy.msg` reports it.
const ENOSYS: i32 = 38;

/// Parks in the transport's wait when there is one, then reads `mio`'s
/// events without waiting; otherwise in `mio` alone.
pub(crate) fn park(
    poll: &mut Poll,
    events: &mut Events,
    msg: &mut Option<Box<dyn Transport>>,
    timeout: Option<Duration>,
) -> io::Result<()> {
    #[cfg(unix)]
    if let Some(msg) = msg {
        use std::os::fd::AsRawFd;
        msg.park(poll.as_raw_fd(), timeout);
        return poll.poll(events, Some(Duration::ZERO));
    }
    #[cfg(not(unix))]
    let _ = msg;
    poll.poll(events, timeout)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn answers_encode_as_the_java_side_reads_them() {
        assert_eq!(Answer::Reply(vec![7, 8]).encode(), [0, 7, 8]);
        let failed = Answer::Failed {
            errno: 32,
            delivered: true,
        }
        .encode();
        assert_eq!(failed, [1, 32, 0, 0, 0, 1]);
    }

    #[test]
    fn an_incoming_request_encodes_its_header_then_its_body() {
        let incoming = Incoming {
            txn: 1,
            interface: 2,
            method: 3,
            caller: [4, 5, 6, 7, 8, 9],
            body: vec![0xab],
        };
        let bytes = incoming.encode();
        assert_eq!(bytes.len(), 8 * 8 + 4 + 1);
        assert_eq!(bytes[0], 1);
        assert_eq!(bytes[8], 2);
        assert_eq!(bytes[16], 4);
        assert_eq!(bytes[56], 9);
        assert_eq!(&bytes[64..], [3, 0, 0, 0, 0xab]);
    }
}
