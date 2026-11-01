//! An open connection: its pending read, queued writes and close sequence
//! (docs/async.md, "`Connection`"). `mio` reports edges, so each socket
//! remembers whether it may still be ready, and each step runs until the
//! socket would block.

use std::collections::VecDeque;
use std::io::{self, ErrorKind, Read, Write};
use std::net::{Shutdown, SocketAddr};
use std::time::{Duration, Instant};

use mio::Registry;
use mio::net::TcpStream;

use super::{CLOSED, Net, Target, describe, failed};
use crate::reactor::{Completion, Due, Outcome, Timers, post};

/// The most one read hands back.
const READ_CHUNK: usize = 64 * 1024;
/// What `readAll` holds at most, so a hostile peer cannot fill the heap.
const READ_ALL_LIMIT: usize = 16 * 1024 * 1024;
const CLOSE_LIMIT: Duration = Duration::from_secs(10);

pub(super) struct Conn {
    stream: TcpStream,
    peer: String,
    readable: bool,
    writable: bool,
    read: Option<PendingRead>,
    writes: VecDeque<PendingWrite>,
    ended: bool,
    broken: Option<String>,
    closing: Option<u64>,
    shut: bool,
}

struct PendingRead {
    id: u64,
    /// What `readAll` has so far; `None` for a plain read.
    all: Option<Vec<u8>>,
}

struct PendingWrite {
    id: u64,
    bytes: Vec<u8>,
    sent: usize,
}

impl Net {
    /// A connection is born: `id` completes with its handle and its peer's address.
    pub(super) fn add_conn(&mut self, id: u64, handle: u64, stream: TcpStream, peer: SocketAddr) {
        let _ = stream.set_nodelay(true);
        let peer = peer.to_string();
        let outcome = Outcome::Bytes(peer.clone().into_bytes());
        self.conns.insert(
            handle,
            Conn {
                stream,
                peer,
                readable: true,
                writable: true,
                read: None,
                writes: VecDeque::new(),
                ended: false,
                broken: None,
                closing: None,
                shut: false,
            },
        );
        post(Completion {
            id,
            handle,
            outcome,
        });
    }

    pub(super) fn begin_read(
        &mut self,
        id: u64,
        handle: u64,
        all: bool,
        registry: &Registry,
        timers: &mut Timers,
    ) {
        let Some(conn) = self.conns.get_mut(&handle) else {
            return failed(id, CLOSED);
        };
        if let Some(reason) = &conn.broken {
            return failed(id, reason.clone());
        }
        conn.read = Some(PendingRead {
            id,
            all: all.then(Vec::new),
        });
        self.operations.insert(id, Target::Conn(handle));
        self.pump_read(handle, registry, timers);
    }

    pub(super) fn begin_write(
        &mut self,
        id: u64,
        handle: u64,
        bytes: Vec<u8>,
        registry: &Registry,
        timers: &mut Timers,
    ) {
        let Some(conn) = self.conns.get_mut(&handle) else {
            return failed(id, CLOSED);
        };
        if let Some(reason) = &conn.broken {
            return failed(id, reason.clone());
        }
        conn.writes.push_back(PendingWrite { id, bytes, sent: 0 });
        self.operations.insert(id, Target::Conn(handle));
        self.pump_writes(handle, registry, timers);
    }

    /// Stops reads, then `advance_close` sends what is queued and waits for the peer.
    pub(super) fn begin_close(
        &mut self,
        id: u64,
        handle: u64,
        registry: &Registry,
        timers: &mut Timers,
    ) {
        let Some(conn) = self.conns.get_mut(&handle) else {
            return post(Completion {
                id,
                handle: 0,
                outcome: Outcome::Done,
            });
        };
        conn.closing = Some(id);
        let read = conn.read.take();
        if let Some(read) = read {
            self.operations.remove(&read.id);
            match read.all {
                None => post(Completion {
                    id: read.id,
                    handle,
                    outcome: Outcome::Bytes(Vec::new()),
                }),
                // Its body is cut short, so it must not pass for the whole.
                Some(_) => failed(read.id, CLOSED),
            }
        }
        self.operations.insert(id, Target::Conn(handle));
        timers.add(Due::Closing(handle), Instant::now() + CLOSE_LIMIT);
        self.pump_writes(handle, registry, timers);
    }

    pub(super) fn conn_ready(&mut self, handle: u64, registry: &Registry, timers: &mut Timers) {
        let Some(conn) = self.conns.get_mut(&handle) else {
            return;
        };
        conn.readable = true;
        conn.writable = true;
        self.pump_read(handle, registry, timers);
        self.pump_writes(handle, registry, timers);
    }

    fn pump_read(&mut self, handle: u64, registry: &Registry, timers: &mut Timers) {
        self.read_buffer.resize(READ_CHUNK, 0);
        loop {
            let Some(conn) = self.conns.get_mut(&handle) else {
                return;
            };
            if conn.read.is_none() {
                return;
            }
            if conn.ended {
                return self.finish_read(handle, Vec::new());
            }
            if !conn.readable {
                return;
            }
            match conn.stream.read(&mut self.read_buffer) {
                Ok(0) => conn.ended = true,
                Ok(n) => {
                    if !self.got(handle, n, registry, timers) {
                        return;
                    }
                }
                Err(e) if e.kind() == ErrorKind::WouldBlock => conn.readable = false,
                Err(e) if e.kind() == ErrorKind::Interrupted => {}
                Err(e) => return self.broke(handle, &e, registry, timers),
            }
        }
    }

    /// `n` bytes came in. False if the read is over.
    fn got(&mut self, handle: u64, n: usize, registry: &Registry, timers: &mut Timers) -> bool {
        let bytes = &self.read_buffer[..n];
        let Some(conn) = self.conns.get_mut(&handle) else {
            return false;
        };
        let Some(read) = &mut conn.read else {
            return false;
        };
        let Some(all) = &mut read.all else {
            let bytes = bytes.to_vec();
            self.finish_read(handle, bytes);
            return false;
        };
        if all.len() + n > READ_ALL_LIMIT {
            let (id, peer) = (read.id, conn.peer.clone());
            conn.read = None;
            self.operations.remove(&id);
            failed(id, format!("more than 16 MiB to read: {peer}"));
            self.abort_conn(handle, registry, timers);
            return false;
        }
        all.extend_from_slice(bytes);
        true
    }

    /// The pending read completes with what `readAll` holds, plus `bytes`.
    fn finish_read(&mut self, handle: u64, bytes: Vec<u8>) {
        let Some(conn) = self.conns.get_mut(&handle) else {
            return;
        };
        let Some(read) = conn.read.take() else { return };
        self.operations.remove(&read.id);
        let outcome = Outcome::Bytes(read.all.unwrap_or(bytes));
        post(Completion {
            id: read.id,
            handle,
            outcome,
        });
    }

    fn pump_writes(&mut self, handle: u64, registry: &Registry, timers: &mut Timers) {
        loop {
            let Some(conn) = self.conns.get_mut(&handle) else {
                return;
            };
            let Some(front) = conn.writes.front_mut() else {
                break;
            };
            if front.sent == front.bytes.len() {
                let id = front.id;
                conn.writes.pop_front();
                self.operations.remove(&id);
                post(Completion {
                    id,
                    handle,
                    outcome: Outcome::Done,
                });
                continue;
            }
            if !conn.writable {
                return;
            }
            match conn.stream.write(&front.bytes[front.sent..]) {
                Ok(0) => {
                    let e = io::Error::from(ErrorKind::WriteZero);
                    return self.broke(handle, &e, registry, timers);
                }
                Ok(n) => front.sent += n,
                Err(e) if e.kind() == ErrorKind::WouldBlock => conn.writable = false,
                Err(e) if e.kind() == ErrorKind::Interrupted => {}
                Err(e) => return self.broke(handle, &e, registry, timers),
            }
        }
        self.advance_close(handle, registry, timers);
    }

    /// With the writes gone: shuts the sending side, then discards what the
    /// peer still sends until it closes too, so our last bytes are not lost
    /// to the reset that closing over unread input causes.
    fn advance_close(&mut self, handle: u64, registry: &Registry, timers: &mut Timers) {
        let Some(conn) = self.conns.get_mut(&handle) else {
            return;
        };
        if conn.closing.is_none() {
            return;
        }
        if conn.broken.is_some() {
            return self.abort_conn(handle, registry, timers);
        }
        if !conn.shut {
            conn.shut = true;
            let _ = conn.stream.shutdown(Shutdown::Write);
        }
        // An empty buffer would read as the end of the stream.
        self.read_buffer.resize(READ_CHUNK, 0);
        while conn.readable {
            match conn.stream.read(&mut self.read_buffer) {
                Ok(n) if n > 0 => {}
                Err(e) if e.kind() == ErrorKind::WouldBlock => conn.readable = false,
                Err(e) if e.kind() == ErrorKind::Interrupted => {}
                _ => return self.abort_conn(handle, registry, timers),
            }
        }
    }

    /// The socket is broken: everything pending on it fails with the reason.
    fn broke(&mut self, handle: u64, e: &io::Error, registry: &Registry, timers: &mut Timers) {
        let Some(conn) = self.conns.get_mut(&handle) else {
            return;
        };
        let reason = describe(e, &conn.peer);
        conn.broken = Some(reason.clone());
        let read = conn.read.take();
        let writes = std::mem::take(&mut conn.writes);
        self.fail_pending(read, writes, &reason);
        self.advance_close(handle, registry, timers);
    }

    /// Closes `handle` as it stands, if it is a connection: writes not yet sent
    /// fail, and a close in progress is over.
    pub(super) fn abort_conn(&mut self, handle: u64, registry: &Registry, timers: &mut Timers) {
        let Some(mut conn) = self.conns.remove(&handle) else {
            return;
        };
        timers.cancel(Due::Closing(handle));
        let _ = registry.deregister(&mut conn.stream);
        self.fail_pending(conn.read.take(), std::mem::take(&mut conn.writes), CLOSED);
        if let Some(id) = conn.closing {
            self.operations.remove(&id);
            post(Completion {
                id,
                handle: 0,
                outcome: Outcome::Done,
            });
        }
    }

    fn fail_pending(
        &mut self,
        read: Option<PendingRead>,
        writes: VecDeque<PendingWrite>,
        reason: &str,
    ) {
        let ids = read
            .map(|r| r.id)
            .into_iter()
            .chain(writes.iter().map(|w| w.id));
        for id in ids {
            self.operations.remove(&id);
            failed(id, reason);
        }
    }
}
