//! Blocking TCP for the debugger connection (JDWP, `vm::debug`).
//!
//! The reactor (`net`) hands finished operations to one shared inbox that
//! `lazy.async.Loop` empties, so a second reader there would take the Loop's
//! completions, and it would also need the VM to be running a Loop at all. The
//! debugger agent instead keeps one OS thread of its own that blocks on its
//! socket, never touches the VM while it waits, and so needs no inbox. These
//! are the few `std::net` calls it makes, kept here with the other sockets.
//!
//! One thread reads and any thread may write, so a connection is shared by
//! reference (`&TcpStream` reads and writes) and never duplicated, which not
//! every platform's socket layer supports.

use std::io::{self, ErrorKind, Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream, ToSocketAddrs};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

/// A bound listening socket.
pub struct Listener(TcpListener);

/// A connection. Reads are for one thread; writes take turns.
pub struct Conn {
    stream: TcpStream,
    writing: Mutex<()>,
}

/// How long to pause between attempts to connect.
const RETRY_PAUSE: Duration = Duration::from_millis(250);

impl Conn {
    fn new(stream: TcpStream) -> Arc<Conn> {
        // Small packets should leave at once; where the option is missing,
        // that is only slower.
        let _ = stream.set_nodelay(true);
        Arc::new(Conn {
            stream,
            writing: Mutex::new(()),
        })
    }

    /// Writes all of `bytes`, never mixed with another thread's.
    pub fn write_all(&self, bytes: &[u8]) -> io::Result<()> {
        let _turn = self.writing.lock().unwrap_or_else(PoisonError::into_inner);
        (&self.stream).write_all(bytes)
    }

    /// Ends both directions, which also ends a read blocked on another thread.
    pub fn shutdown(&self) {
        let _ = self.stream.shutdown(Shutdown::Both);
    }

    /// Ends the sending direction only: the peer reads what was sent, then EOF.
    pub fn finish_sending(&self) {
        let _ = self.stream.shutdown(Shutdown::Write);
    }
}

impl Read for &Conn {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        (&self.stream).read(buf)
    }
}

/// Listens on `host:port` (`*` is every interface). Port 0 asks for a free one.
pub fn listen(host: &str, port: u16) -> io::Result<Listener> {
    let host = if host == "*" { "0.0.0.0" } else { host };
    TcpListener::bind((host, port)).map(Listener)
}

impl Listener {
    /// The port bound, for `address=0`.
    pub fn port(&self) -> io::Result<u16> {
        self.0.local_addr().map(|a| a.port())
    }

    /// Waits for one connection.
    pub fn accept(&self) -> io::Result<Arc<Conn>> {
        let (stream, _) = self.0.accept()?;
        Ok(Conn::new(stream))
    }
}

/// Connects to `host:port`, trying each address the name has, and trying again
/// until `patience` has passed (a debugger that is not listening yet, or a
/// network that is not up yet).
pub fn connect(host: &str, port: u16, patience: Duration) -> io::Result<Arc<Conn>> {
    let end = Instant::now() + patience;
    loop {
        let err = match connect_once(host, port) {
            Ok(conn) => return Ok(conn),
            Err(e) => e,
        };
        if Instant::now() >= end {
            return Err(err);
        }
        std::thread::sleep(RETRY_PAUSE);
    }
}

fn connect_once(host: &str, port: u16) -> io::Result<Arc<Conn>> {
    let mut last = io::Error::new(ErrorKind::NotFound, "no address");
    for addr in (host, port).to_socket_addrs()? {
        match TcpStream::connect(addr) {
            Ok(stream) => return Ok(Conn::new(stream)),
            Err(e) => last = e,
        }
    }
    Err(last)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_connection_carries_bytes_both_ways() {
        let listener = listen("127.0.0.1", 0).expect("a free port");
        let port = listener.port().expect("a bound port");
        let client = std::thread::spawn(move || {
            let c = connect("127.0.0.1", port, Duration::from_secs(5)).expect("connect");
            c.write_all(b"ping").expect("write");
            let mut back = [0u8; 4];
            let mut from = &*c;
            from.read_exact(&mut back).expect("read");
            back
        });
        let server = listener.accept().expect("accept");
        let mut got = [0u8; 4];
        let mut from = &*server;
        from.read_exact(&mut got).expect("read");
        assert_eq!(&got, b"ping");
        server.write_all(b"pong").expect("write");
        assert_eq!(&client.join().expect("client thread"), b"pong");
    }

    #[test]
    fn connect_gives_up_after_its_patience() {
        let port = {
            let l = listen("127.0.0.1", 0).expect("a free port");
            l.port().expect("a bound port")
        };
        let started = Instant::now();
        assert!(connect("127.0.0.1", port, Duration::from_millis(300)).is_err());
        assert!(started.elapsed() >= Duration::from_millis(300));
    }
}
