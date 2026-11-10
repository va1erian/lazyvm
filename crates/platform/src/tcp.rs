//! Blocking TCP for the debugger connection (JDWP, `vm::debug`).
//!
//! The reactor (`net`) hands finished operations to one shared inbox that
//! `lazy.async.Loop` empties, so a second reader there would take the Loop's
//! completions, and it would also need the VM to be running a Loop at all. The
//! debugger agent instead keeps one OS thread of its own that blocks on its
//! socket, never touches the VM while it waits, and so needs no inbox. These
//! are the few `std::net` calls it makes, kept here with the other sockets.

use std::io::{self, ErrorKind};
use std::net::{TcpListener, TcpStream, ToSocketAddrs};

/// A bound listening socket.
pub struct Listener(TcpListener);

/// A connection: read and written like any stream, cloned for a second owner.
pub type Stream = TcpStream;

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
    pub fn accept(&self) -> io::Result<Stream> {
        let (stream, _) = self.0.accept()?;
        stream.set_nodelay(true)?;
        Ok(stream)
    }
}

/// Connects to `host:port`, trying each address the name has.
pub fn connect(host: &str, port: u16) -> io::Result<Stream> {
    let mut last = io::Error::new(ErrorKind::NotFound, "no address");
    for addr in (host, port).to_socket_addrs()? {
        match TcpStream::connect(addr) {
            Ok(stream) => {
                stream.set_nodelay(true)?;
                return Ok(stream);
            }
            Err(e) => last = e,
        }
    }
    Err(last)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};

    #[test]
    fn a_connection_carries_bytes_both_ways() {
        let listener = listen("127.0.0.1", 0).expect("test input");
        let port = listener.port().expect("test input");
        let client = std::thread::spawn(move || {
            let mut s = connect("127.0.0.1", port).expect("test input");
            s.write_all(b"ping").expect("test input");
            let mut back = [0u8; 4];
            s.read_exact(&mut back).expect("test input");
            back
        });
        let mut server = listener.accept().expect("test input");
        let mut got = [0u8; 4];
        server.read_exact(&mut got).expect("test input");
        assert_eq!(&got, b"ping");
        server.write_all(b"pong").expect("test input");
        assert_eq!(&client.join().expect("test input"), b"pong");
    }
}
