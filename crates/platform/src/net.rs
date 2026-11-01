//! TCP on the reactor thread (docs/async.md, "Inside"): the requests of
//! `lazy.net`, and connecting. Listening is in [`listen`], open connections in
//! [`conn`]. Every socket system call is made on that thread; name lookups,
//! which block, are in [`crate::resolver`].

mod conn;
mod listen;

use std::collections::{HashMap, VecDeque};
use std::io::{self, ErrorKind};
use std::net::{IpAddr, SocketAddr};
use std::time::{Duration, Instant};

use mio::net::TcpStream;
use mio::{Interest, Registry, Token};

use crate::reactor::{self, Completion, Due, Outcome, Timers, post};
use crate::resolver;
use conn::Conn;
use listen::Listener;

const ATTEMPT_LIMIT: Duration = Duration::from_secs(30);

const CLOSED: &str = "closed";
const TOO_MANY: &str = "too many sockets";

pub(crate) enum Request {
    Connect {
        id: u64,
        host: String,
        port: u16,
    },
    Listen {
        id: u64,
        host: String,
        port: u16,
    },
    Resolved {
        id: u64,
        addrs: Option<Vec<SocketAddr>>,
    },
    Accept {
        id: u64,
        handle: u64,
    },
    Read {
        id: u64,
        handle: u64,
        all: bool,
    },
    Write {
        id: u64,
        handle: u64,
        bytes: Vec<u8>,
    },
    Close {
        id: u64,
        handle: u64,
    },
    CloseListener {
        id: u64,
        handle: u64,
    },
    Abort {
        handle: u64,
    },
}

macro_rules! requests {
    ($($name:ident($($arg:ident: $ty:ty),*) => $variant:ident),* $(,)?) => {$(
        pub fn $name($($arg: $ty),*) -> io::Result<()> {
            reactor::request(reactor::Request::Net(Request::$variant { $($arg),* }))
        }
    )*};
}

requests! {
    connect(id: u64, host: String, port: u16) => Connect,
    listen(id: u64, host: String, port: u16) => Listen,
    accept(id: u64, handle: u64) => Accept,
    read(id: u64, handle: u64, all: bool) => Read,
    write(id: u64, handle: u64, bytes: Vec<u8>) => Write,
    close(id: u64, handle: u64) => Close,
    close_listener(id: u64, handle: u64) => CloseListener,
    abort(handle: u64) => Abort,
}

#[derive(Default)]
pub(crate) struct Net {
    next_token: u64,
    resolving: HashMap<u64, Lookup>,
    connecting: HashMap<u64, Connecting>,
    conns: HashMap<u64, Conn>,
    listeners: HashMap<u64, Listener>,
    /// The tokens of a listener's sockets: it has one per address.
    listener_tokens: HashMap<u64, u64>,
    /// What each operation in flight works on, for a cancel to find it.
    operations: HashMap<u64, Target>,
    read_buffer: Vec<u8>,
}

#[derive(Clone, Copy)]
enum Target {
    Resolving,
    Connecting(u64),
    Accept(u64),
    Conn(u64),
}

struct Lookup {
    listen: bool,
    host: String,
    port: u16,
}

/// A connect still looking for an address that answers.
struct Attempts {
    id: u64,
    token: u64,
    /// The host and port as the caller gave them, for error messages.
    what: String,
    host: String,
    addrs: VecDeque<SocketAddr>,
    failure: Option<String>,
}

struct Connecting {
    attempts: Attempts,
    stream: TcpStream,
}

fn raw_is(e: &io::Error, unix: i32, windows: i32) -> bool {
    e.raw_os_error() == Some(if cfg!(windows) { windows } else { unix })
}

fn out_of_descriptors(e: &io::Error) -> bool {
    raw_is(e, 24, 10024) || (!cfg!(windows) && raw_is(e, 23, 0))
}

/// The platform has no such address: no IPv6 on LazyOS, or no `::1` on a machine.
/// Any other address not being available is an error like any other.
fn no_such_family(e: &io::Error, addr: &SocketAddr) -> bool {
    raw_is(e, 97, 10047) || (e.kind() == ErrorKind::AddrNotAvailable && addr.ip().is_loopback())
}

/// A connect's failure, in the words of [`describe`].
fn describe_connect(e: &io::Error, what: &str) -> String {
    if e.kind() == ErrorKind::TimedOut {
        return format!("connect timed out: {what}");
    }
    describe(e, what)
}

/// Our own text, the same on both platforms, so goldens can print it.
fn describe(e: &io::Error, what: &str) -> String {
    if out_of_descriptors(e) {
        return TOO_MANY.into();
    }
    let reason = match e.kind() {
        ErrorKind::ConnectionRefused => "connection refused",
        ErrorKind::ConnectionReset | ErrorKind::ConnectionAborted | ErrorKind::BrokenPipe => {
            "connection reset"
        }
        ErrorKind::TimedOut => "timed out",
        ErrorKind::AddrInUse => "address in use",
        ErrorKind::PermissionDenied => "permission denied",
        ErrorKind::HostUnreachable | ErrorKind::NetworkUnreachable => "unreachable",
        _ => "network error",
    };
    format!("{reason}: {what}")
}

fn named(host: &str, port: u16) -> String {
    if host.contains(':') {
        format!("[{host}]:{port}")
    } else {
        format!("{host}:{port}")
    }
}

fn failed(id: u64, message: impl Into<String>) {
    post(Completion {
        id,
        handle: 0,
        outcome: Outcome::Failed(message.into()),
    });
}

impl Net {
    fn token(&mut self) -> u64 {
        self.next_token += 1;
        self.next_token
    }

    pub(crate) fn request(&mut self, r: Request, registry: &Registry, timers: &mut Timers) {
        match r {
            Request::Connect { id, host, port } => {
                self.lookup(
                    id,
                    Lookup {
                        listen: false,
                        host,
                        port,
                    },
                    registry,
                    timers,
                );
            }
            Request::Listen { id, host, port } => {
                self.lookup(
                    id,
                    Lookup {
                        listen: true,
                        host,
                        port,
                    },
                    registry,
                    timers,
                );
            }
            Request::Resolved { id, addrs } => {
                if let Some(lookup) = self.resolving.remove(&id) {
                    self.operations.remove(&id);
                    self.resolved(id, lookup, addrs, registry, timers);
                }
            }
            Request::Accept { id, handle } => self.begin_accept(id, handle, registry, timers),
            Request::Read { id, handle, all } => self.begin_read(id, handle, all, registry, timers),
            Request::Write { id, handle, bytes } => {
                self.begin_write(id, handle, bytes, registry, timers);
            }
            Request::Close { id, handle } => self.begin_close(id, handle, registry, timers),
            Request::CloseListener { id, handle } => {
                self.close_listener(handle, registry, timers);
                post(Completion {
                    id,
                    handle: 0,
                    outcome: Outcome::Done,
                });
            }
            Request::Abort { handle } => {
                self.abort_conn(handle, registry, timers);
                self.close_listener(handle, registry, timers);
            }
        }
    }

    /// A socket has news. Which kind does not matter: every state tries its
    /// next step and goes back to waiting if the socket would block.
    pub(crate) fn ready(&mut self, token: Token, registry: &Registry, timers: &mut Timers) {
        let token = token.0 as u64;
        if self.connecting.contains_key(&token) {
            self.connect_ready(token, registry, timers);
        } else if let Some(&handle) = self.listener_tokens.get(&token) {
            self.try_accept(handle, registry, timers);
        } else {
            self.conn_ready(token, registry, timers);
        }
    }

    pub(crate) fn timer(&mut self, due: Due, registry: &Registry, timers: &mut Timers) {
        match due {
            Due::Attempt(token) => {
                let what = self.connecting.get(&token).map(|c| c.attempts.what.clone());
                if let Some(what) = what {
                    self.attempt_ended(
                        token,
                        format!("connect timed out: {what}"),
                        registry,
                        timers,
                    );
                }
            }
            Due::Closing(handle) => self.abort_conn(handle, registry, timers),
            Due::AcceptRetry(handle) => self.try_accept(handle, registry, timers),
            Due::Operation(_) => {}
        }
    }

    /// The operation is withdrawn. Its id may still come through `post`, as
    /// `abort_conn` fails what a connection had pending; Java ignores that.
    pub(crate) fn cancel(&mut self, id: u64, registry: &Registry, timers: &mut Timers) {
        match self.operations.remove(&id) {
            Some(Target::Resolving) => {
                self.resolving.remove(&id);
            }
            Some(Target::Connecting(token)) => {
                timers.cancel(Due::Attempt(token));
                if let Some(mut c) = self.connecting.remove(&token) {
                    let _ = registry.deregister(&mut c.stream);
                }
            }
            Some(Target::Accept(handle)) => {
                timers.cancel(Due::AcceptRetry(handle));
                if let Some(listener) = self.listeners.get_mut(&handle) {
                    listener.accept = None;
                }
            }
            Some(Target::Conn(handle)) => self.abort_conn(handle, registry, timers),
            None => {}
        }
    }

    fn lookup(&mut self, id: u64, lookup: Lookup, registry: &Registry, timers: &mut Timers) {
        if let Ok(ip) = lookup.host.parse::<IpAddr>() {
            let addrs = Some(vec![SocketAddr::new(ip, lookup.port)]);
            return self.resolved(id, lookup, addrs, registry, timers);
        }
        resolver::lookup(id, lookup.host.clone(), lookup.port);
        self.resolving.insert(id, lookup);
        self.operations.insert(id, Target::Resolving);
    }

    fn resolved(
        &mut self,
        id: u64,
        lookup: Lookup,
        addrs: Option<Vec<SocketAddr>>,
        registry: &Registry,
        timers: &mut Timers,
    ) {
        let Some(addrs) = addrs else {
            return failed(id, format!("unknown host: {}", lookup.host));
        };
        let what = named(&lookup.host, lookup.port);
        if lookup.listen {
            return self.bind(id, &addrs, &lookup, &what, registry);
        }
        let token = self.token();
        let attempts = Attempts {
            id,
            token,
            what,
            host: lookup.host,
            addrs: addrs.into(),
            failure: None,
        };
        self.attempt(attempts, registry, timers);
    }

    /// Tries addresses until one is under way, or fails the operation with
    /// the last attempt's error. Addresses of a missing family do not count.
    fn attempt(&mut self, mut attempts: Attempts, registry: &Registry, timers: &mut Timers) {
        while let Some(addr) = attempts.addrs.pop_front() {
            let mut stream = match TcpStream::connect(addr) {
                Ok(stream) => stream,
                Err(e) if no_such_family(&e, &addr) => continue,
                Err(e) => {
                    attempts.failure = Some(describe_connect(&e, &attempts.what));
                    continue;
                }
            };
            let interest = Interest::READABLE | Interest::WRITABLE;
            let token = attempts.token;
            if let Err(e) = registry.register(&mut stream, Token(token as usize), interest) {
                attempts.failure = Some(describe_connect(&e, &attempts.what));
                continue;
            }
            self.operations
                .insert(attempts.id, Target::Connecting(token));
            self.connecting
                .insert(token, Connecting { attempts, stream });
            timers.add(Due::Attempt(token), Instant::now() + ATTEMPT_LIMIT);
            return;
        }
        self.operations.remove(&attempts.id);
        let message = attempts
            .failure
            .unwrap_or_else(|| format!("no usable address: {}", attempts.host));
        failed(attempts.id, message);
    }

    fn attempt_ended(
        &mut self,
        token: u64,
        reason: String,
        registry: &Registry,
        timers: &mut Timers,
    ) {
        timers.cancel(Due::Attempt(token));
        if let Some(mut c) = self.connecting.remove(&token) {
            let _ = registry.deregister(&mut c.stream);
            c.attempts.failure = Some(reason);
            self.attempt(c.attempts, registry, timers);
        }
    }

    fn connect_ready(&mut self, token: u64, registry: &Registry, timers: &mut Timers) {
        let Some(c) = self.connecting.get(&token) else {
            return;
        };
        let what = c.attempts.what.clone();
        if let Ok(Some(e)) | Err(e) = c.stream.take_error() {
            return self.attempt_ended(token, describe_connect(&e, &what), registry, timers);
        }
        match c.stream.peer_addr() {
            Ok(peer) => {
                timers.cancel(Due::Attempt(token));
                if let Some(c) = self.connecting.remove(&token) {
                    self.operations.remove(&c.attempts.id);
                    self.add_conn(c.attempts.id, token, c.stream, peer);
                }
            }
            Err(e) if matches!(e.kind(), ErrorKind::NotConnected | ErrorKind::WouldBlock) => {}
            Err(e) => self.attempt_ended(token, describe_connect(&e, &what), registry, timers),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn net_names_a_host_as_the_caller_gave_it() {
        assert_eq!(named("example.com", 80), "example.com:80");
        assert_eq!(named("::1", 7), "[::1]:7");
    }

    #[test]
    fn net_describes_errors_in_our_own_words() {
        let refused = io::Error::from(ErrorKind::ConnectionRefused);
        assert_eq!(describe(&refused, "h:1"), "connection refused: h:1");
        let reset = io::Error::from(ErrorKind::BrokenPipe);
        assert_eq!(describe(&reset, "h:1"), "connection reset: h:1");
        let other = io::Error::from(ErrorKind::InvalidData);
        assert_eq!(describe(&other, "h:1"), "network error: h:1");
    }
}
