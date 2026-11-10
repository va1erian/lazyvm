//! The agent thread: the connection, the handshake and the command loop.

use std::io::Read;
use std::sync::Arc;
use std::time::Duration;

use platform::tcp::{self, Conn, Listener};

use super::requests::SUSPEND_ALL;
use super::wire::{self, Command, HANDSHAKE, PacketError};
use crate::Vm;
use crate::thread::Gil;
use crate::thread::outside::with_vm;

/// Exit status for a VM bug, as in `sysexits.h` (the launcher's too).
const EXIT_INTERNAL: i32 = 70;
/// The agent's stack: a command runs a shallow call tree and no bytecode.
const STACK_BYTES: usize = 1 << 20;

/// The options of `-agentlib:jdwp=...` that this agent acts on. Others (`quiet`,
/// `timeout`, `onthrow`, ...) are accepted and ignored.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Config {
    /// `server=y`: wait for the debugger. `server=n`: connect to it.
    pub server: bool,
    /// `suspend=y` (the default): no program code runs until the debugger resumes the VM.
    pub suspend: bool,
    pub host: String,
    pub port: u16,
    /// `timeout=`: with `server=n`, how long to keep trying to reach the debugger.
    pub timeout: Duration,
}

impl Config {
    /// Parses what follows `jdwp=` (or `-Xrunjdwp:`).
    pub fn parse(text: &str) -> Result<Config, String> {
        let mut transport = None;
        let mut server = false;
        let mut suspend = true;
        let mut address = None;
        let mut timeout = Duration::ZERO;
        for item in text.split(',').filter(|i| !i.is_empty()) {
            let (key, value) = item.split_once('=').unwrap_or((item, ""));
            match key {
                "transport" => transport = Some(value),
                "server" => server = yes_no(key, value)?,
                "suspend" => suspend = yes_no(key, value)?,
                "address" => address = Some(value),
                "timeout" => {
                    let ms = value
                        .parse()
                        .map_err(|_| format!("bad timeout {value:?}"))?;
                    timeout = Duration::from_millis(ms);
                }
                _ => {}
            }
        }
        match transport {
            Some("dt_socket") => {}
            Some(other) => return Err(format!("transport library not found: {other}")),
            None => return Err("no transport specified".into()),
        }
        let (host, port) = match address {
            Some(a) => split_address(a)?,
            None if server => ("127.0.0.1".into(), 0),
            None => return Err("address is missing".into()),
        };
        Ok(Config {
            server,
            suspend,
            host,
            port,
            timeout,
        })
    }
}

fn yes_no(key: &str, value: &str) -> Result<bool, String> {
    match value {
        "y" => Ok(true),
        "n" => Ok(false),
        _ => Err(format!("{key} must be y or n, not {value:?}")),
    }
}

/// `port`, `host:port` or `*:port`. A bare port is the loopback interface.
fn split_address(address: &str) -> Result<(String, u16), String> {
    let (host, port) = address.rsplit_once(':').unwrap_or(("127.0.0.1", address));
    let port = port
        .parse()
        .map_err(|_| format!("bad port in address {address:?}"))?;
    let host = host.trim_start_matches('[').trim_end_matches(']');
    Ok((
        if host.is_empty() { "127.0.0.1" } else { host }.to_string(),
        port,
    ))
}

/// Where the first debugger connection comes from.
pub(super) enum Source {
    Listen(Listener),
    Connected(Arc<Conn>),
}

/// Binds (`server=y`, printing the line an IDE waits for) or connects
/// (`server=n`). Errors come back as the text of the failure.
pub(super) fn open(config: &Config) -> Result<Source, String> {
    if config.server {
        let listener = tcp::listen(&config.host, config.port)
            .map_err(|e| format!("cannot listen on {}:{}: {e}", config.host, config.port))?;
        let port = listener.port().map_err(|e| e.to_string())?;
        let line = format!("Listening for transport dt_socket at address: {port}\n");
        let _ = platform::write(platform::Stream::Out, line.as_bytes());
        let _ = platform::flush(platform::Stream::Out);
        Ok(Source::Listen(listener))
    } else {
        let stream = tcp::connect(&config.host, config.port, config.timeout)
            .map_err(|e| format!("cannot connect to {}:{}: {e}", config.host, config.port))?;
        Ok(Source::Connected(stream))
    }
}

pub(super) fn spawn(
    gil: Arc<Gil>,
    me: usize,
    config: Config,
    source: Source,
) -> std::io::Result<std::thread::JoinHandle<()>> {
    std::thread::Builder::new()
        .name("jdwp-agent".into())
        .stack_size(STACK_BYTES)
        .spawn(move || {
            let body = std::panic::AssertUnwindSafe(|| run(&gil, me, &config, source));
            if std::panic::catch_unwind(body).is_err() {
                eprintln!("lazyvm: internal error: the debugger thread panicked");
                std::process::exit(EXIT_INTERNAL);
            }
        })
}

fn run(gil: &Arc<Gil>, me: usize, config: &Config, source: Source) {
    let (listener, mut waiting) = match source {
        Source::Listen(l) => (Some(l), None),
        Source::Connected(s) => (None, Some(s)),
    };
    let mut first = true;
    loop {
        let stream = match (waiting.take(), &listener) {
            (Some(s), _) => s,
            // A debugger that left may be followed by another.
            (None, Some(l)) => match l.accept() {
                Ok(s) => s,
                Err(_) => break,
            },
            (None, None) => break,
        };
        serve(gil, me, config, stream, first);
        first = false;
    }
    with_vm(gil, me, |vm| vm.remove_helper_thread(me));
}

/// One debugger session: handshake, then commands until the connection ends.
fn serve(gil: &Arc<Gil>, me: usize, config: &Config, conn: Arc<Conn>, first: bool) {
    if handshake(&conn).is_err() {
        return;
    }
    with_vm(gil, me, |vm| {
        vm.attach_debugger(Arc::clone(&conn));
        // With suspend=y the VM stands still until the debugger resumes it,
        // and says so in the event it greets the debugger with.
        if first && config.suspend {
            vm.send_vm_start(SUSPEND_ALL);
        }
    });
    loop {
        let cmd = match wire::read_command(&mut &*conn) {
            Ok(c) => c,
            Err(e) => {
                report_bad_packet(&e);
                break;
            }
        };
        if with_vm(gil, me, |vm| vm.answer(cmd)) {
            break;
        }
    }
    conn.shutdown();
    with_vm(gil, me, Vm::detach_debugger);
}

/// A debugger that just hung up is not worth a word; one that sent nonsense is.
fn report_bad_packet(e: &PacketError) {
    match e {
        PacketError::Io(e) => {
            use std::io::ErrorKind::{ConnectionAborted, ConnectionReset, UnexpectedEof};
            if !matches!(
                e.kind(),
                UnexpectedEof | ConnectionReset | ConnectionAborted
            ) {
                eprintln!("lazyvm: the debugger connection failed: {e}");
            }
        }
        PacketError::BadLength(n) => {
            eprintln!("lazyvm: the debugger sent a packet of {n} bytes; closing the connection");
        }
        PacketError::NotACommand => {
            eprintln!("lazyvm: the debugger sent a reply, not a command; closing the connection");
        }
    }
}

fn handshake(conn: &Conn) -> std::io::Result<()> {
    let mut got = [0u8; HANDSHAKE.len()];
    let mut from = conn;
    from.read_exact(&mut got)?;
    if got != *HANDSHAKE {
        return Err(std::io::ErrorKind::InvalidData.into());
    }
    conn.write_all(HANDSHAKE)
}

impl Vm {
    /// Runs one command and writes its reply. True when the session is over.
    fn answer(&mut self, cmd: Command) -> bool {
        let Command {
            id,
            set,
            cmd: number,
            data,
        } = cmd;
        let mut reader = wire::Reader::new(&data);
        let mut out = wire::Writer::default();
        let code = match self.dispatch(set, number, &mut reader, &mut out) {
            Ok(()) => 0,
            Err(code) => {
                out.buf.clear();
                code
            }
        };
        let packet = wire::reply_packet(id, code, &out.buf);
        self.write_packet(&packet);
        if let Some(status) = self.debug.exit {
            self.exit_process(status);
        }
        self.debug.disconnect
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_options_an_ide_passes_parse() {
        let c = Config::parse("transport=dt_socket,address=127.0.0.1:5005,suspend=y,server=n")
            .expect("test input");
        assert_eq!(
            c,
            Config {
                server: false,
                suspend: true,
                host: "127.0.0.1".into(),
                port: 5005,
                timeout: Duration::ZERO,
            }
        );
        let c = Config::parse("transport=dt_socket,server=y,suspend=n,address=*:0")
            .expect("test input");
        assert_eq!(
            (c.server, c.suspend, c.host.as_str(), c.port),
            (true, false, "*", 0)
        );
        let c = Config::parse("transport=dt_socket,server=y,address=6000,quiet=y,timeout=5")
            .expect("test input");
        assert_eq!(
            (c.host.as_str(), c.port, c.suspend),
            ("127.0.0.1", 6000, true)
        );
        assert_eq!(c.timeout, Duration::from_millis(5));
    }

    #[test]
    fn bad_options_are_errors_not_panics() {
        for bad in [
            "",
            "transport=dt_shmem,address=1",
            "transport=dt_socket",
            "transport=dt_socket,address=x",
            "transport=dt_socket,address=70000",
            "transport=dt_socket,address=1,suspend=maybe",
            "transport=dt_socket,address=:",
            "transport=dt_socket,address=1,timeout=soon",
        ] {
            assert!(Config::parse(bad).is_err(), "{bad:?}");
        }
    }
}
