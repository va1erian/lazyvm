//! Listening sockets (docs/async.md, "`Listener`"): one per address of a host,
//! on one port, with one accept pending at a time.

use std::io::{self, ErrorKind};
use std::net::SocketAddr;
use std::time::{Duration, Instant};

use mio::net::{TcpListener, TcpStream};
use mio::{Interest, Registry, Token};

use super::{CLOSED, Lookup, Net, Target, describe, failed, no_such_family};
use crate::reactor::{Completion, Due, Outcome, Timers, post};

/// An accept that failed gets no new edge, so it tries again: out of descriptors,
/// say. A server must outlive what happens to one connection.
const ACCEPT_RETRY: Duration = Duration::from_millis(100);
/// How many times port 0 is tried when a free port turns out taken on another address.
const BIND_TRIES: u32 = 8;

pub(super) struct Listener {
    sockets: Vec<(u64, TcpListener)>,
    pub(super) accept: Option<u64>,
}

/// Binds every address on one port: the first chooses it if `port` is 0.
/// A family the platform lacks is skipped; any other error undoes it all.
fn bind_all(addrs: &[SocketAddr], port: u16) -> io::Result<Vec<TcpListener>> {
    let mut chosen = port;
    let mut seen: Vec<SocketAddr> = Vec::new();
    let mut sockets = Vec::new();
    for addr in addrs {
        if seen.contains(addr) {
            continue;
        }
        seen.push(*addr);
        let mut at = *addr;
        at.set_port(chosen);
        match TcpListener::bind(at) {
            Ok(socket) => {
                chosen = socket.local_addr()?.port();
                sockets.push(socket);
            }
            Err(e) if no_such_family(&e, addr) => {}
            Err(e) => return Err(e),
        }
    }
    Ok(sockets)
}

impl Net {
    pub(super) fn bind(
        &mut self,
        id: u64,
        addrs: &[SocketAddr],
        lookup: &Lookup,
        what: &str,
        registry: &Registry,
    ) {
        for _ in 0..if lookup.port == 0 { BIND_TRIES } else { 1 } {
            match bind_all(addrs, lookup.port) {
                Ok(sockets) if sockets.is_empty() => {
                    return failed(id, format!("no usable address: {}", lookup.host));
                }
                Ok(sockets) => return self.listening(id, sockets, what, registry),
                Err(e) if lookup.port == 0 && e.kind() == ErrorKind::AddrInUse => {}
                Err(e) => return failed(id, describe(&e, what)),
            }
        }
        failed(id, format!("address in use: {what}"));
    }

    pub(super) fn listening(
        &mut self,
        id: u64,
        sockets: Vec<TcpListener>,
        what: &str,
        registry: &Registry,
    ) {
        let handle = self.token();
        let port = sockets
            .first()
            .and_then(|s| s.local_addr().ok())
            .map_or(0, |a| a.port());
        let mut tokened = Vec::new();
        for mut socket in sockets {
            let token = self.token();
            if let Err(e) =
                registry.register(&mut socket, Token(token as usize), Interest::READABLE)
            {
                for (token, mut socket) in tokened {
                    let _ = registry.deregister(&mut socket);
                    self.listener_tokens.remove(&token);
                }
                return failed(id, describe(&e, what));
            }
            self.listener_tokens.insert(token, handle);
            tokened.push((token, socket));
        }
        self.listeners.insert(
            handle,
            Listener {
                sockets: tokened,
                accept: None,
            },
        );
        let outcome = Outcome::Bytes(port.to_string().into_bytes());
        post(Completion {
            id,
            handle,
            outcome,
        });
    }

    pub(super) fn begin_accept(
        &mut self,
        id: u64,
        handle: u64,
        registry: &Registry,
        timers: &mut Timers,
    ) {
        let Some(listener) = self.listeners.get_mut(&handle) else {
            return failed(id, CLOSED);
        };
        listener.accept = Some(id);
        self.operations.insert(id, Target::Accept(handle));
        self.try_accept(handle, registry, timers);
    }

    pub(super) fn try_accept(&mut self, handle: u64, registry: &Registry, timers: &mut Timers) {
        let Some(listener) = self.listeners.get(&handle) else {
            return;
        };
        let Some(id) = listener.accept else { return };
        match next_connection(listener) {
            Accepted::Nothing => return,
            Accepted::Retry => {
                timers.add(Due::AcceptRetry(handle), Instant::now() + ACCEPT_RETRY);
                return;
            }
            Accepted::Connection(mut stream, peer) => {
                let token = self.token();
                let interest = Interest::READABLE | Interest::WRITABLE;
                if registry
                    .register(&mut stream, Token(token as usize), interest)
                    .is_err()
                {
                    timers.add(Due::AcceptRetry(handle), Instant::now() + ACCEPT_RETRY);
                    return;
                }
                self.add_conn(id, token, stream, peer);
            }
        }
        self.operations.remove(&id);
        if let Some(listener) = self.listeners.get_mut(&handle) {
            listener.accept = None;
        }
    }

    /// Closes a listener, if `handle` is one, failing the accept it has pending.
    pub(super) fn close_listener(&mut self, handle: u64, registry: &Registry, timers: &mut Timers) {
        let Some(listener) = self.listeners.remove(&handle) else {
            return;
        };
        timers.cancel(Due::AcceptRetry(handle));
        for (token, mut socket) in listener.sockets {
            let _ = registry.deregister(&mut socket);
            self.listener_tokens.remove(&token);
        }
        if let Some(id) = listener.accept {
            self.operations.remove(&id);
            failed(id, CLOSED);
        }
    }
}

enum Accepted {
    Nothing,
    Retry,
    Connection(TcpStream, SocketAddr),
}

/// One connection from any of a listener's sockets. Reaching `WouldBlock` on
/// each is what re-arms its edge, and the next accept comes back to it.
fn next_connection(listener: &Listener) -> Accepted {
    for (_, socket) in &listener.sockets {
        loop {
            match socket.accept() {
                Ok((stream, peer)) => return Accepted::Connection(stream, peer),
                Err(e) if e.kind() == ErrorKind::WouldBlock => break,
                // What a client did to its own connection before we got to it.
                Err(e)
                    if matches!(
                        e.kind(),
                        ErrorKind::Interrupted
                            | ErrorKind::ConnectionAborted
                            | ErrorKind::ConnectionReset
                    ) => {}
                Err(_) => return Accepted::Retry,
            }
        }
    }
    Accepted::Nothing
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn net_binds_every_address_on_the_port_the_first_chose() {
        let loopback: SocketAddr = "127.0.0.1:0".parse().expect("an address");
        let sockets = bind_all(&[loopback, loopback], 0).expect("a free port");
        assert_eq!(sockets.len(), 1, "a repeated address binds once");
        assert_ne!(sockets[0].local_addr().expect("bound").port(), 0);
    }
}
