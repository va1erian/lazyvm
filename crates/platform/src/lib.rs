//! The platform layer: console, clocks and system facts, through Rust `std`.
//!
//! Windows and LazyOS (a static-musl `std` program through LazyOS's Linux
//! ABI) share this code. Windows- or LazyOS-only parts (windows, Messenger)
//! get their own crates in S9 (docs/decisions.md D10).

pub mod files;

use std::io::{self, Read, Write};
use std::sync::OnceLock;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

/// The three standard streams by Java's descriptor numbers.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Stream {
    In,
    Out,
    Err,
}

impl Stream {
    pub fn from_fd(fd: i32) -> Option<Stream> {
        match fd {
            0 => Some(Stream::In),
            1 => Some(Stream::Out),
            2 => Some(Stream::Err),
            _ => None,
        }
    }
}

/// Writes all of `bytes` to stdout or stderr. Stdout is line-buffered by
/// `std`; stderr is unbuffered.
pub fn write(stream: Stream, bytes: &[u8]) -> io::Result<()> {
    match stream {
        Stream::Out => io::stdout().lock().write_all(bytes),
        Stream::Err => io::stderr().lock().write_all(bytes),
        Stream::In => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "stdin is not writable",
        )),
    }
}

pub fn flush(stream: Stream) -> io::Result<()> {
    match stream {
        Stream::Out => io::stdout().lock().flush(),
        Stream::Err => io::stderr().lock().flush(),
        Stream::In => Ok(()),
    }
}

/// Reads from stdin, flushing stdout first so prompts appear.
pub fn read(stream: Stream, buf: &mut [u8]) -> io::Result<usize> {
    if stream != Stream::In {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "only stdin is readable",
        ));
    }
    flush(Stream::Out)?;
    io::stdin().lock().read(buf)
}

/// Milliseconds since the Unix epoch.
pub fn current_time_millis() -> i64 {
    match SystemTime::now().duration_since(UNIX_EPOCH) {
        Ok(d) => i64::try_from(d.as_millis()).unwrap_or(i64::MAX),
        Err(e) => -i64::try_from(e.duration().as_millis()).unwrap_or(i64::MAX),
    }
}

/// A monotonic clock in nanoseconds from an arbitrary origin.
pub fn nano_time() -> i64 {
    static ORIGIN: OnceLock<Instant> = OnceLock::new();
    let origin = ORIGIN.get_or_init(Instant::now);
    i64::try_from(origin.elapsed().as_nanos()).unwrap_or(i64::MAX)
}

pub fn available_processors() -> i32 {
    std::thread::available_parallelism()
        .map(|n| i32::try_from(n.get()).unwrap_or(i32::MAX))
        .unwrap_or(1)
}

/// The system properties LazyVM defines before `-D` overrides.
pub fn default_properties() -> Vec<(String, String)> {
    let windows = cfg!(windows);
    let dir = std::env::current_dir()
        .map(|d| d.display().to_string())
        .unwrap_or_default();
    let home = std::env::var(if windows { "USERPROFILE" } else { "HOME" }).unwrap_or_default();
    let tmp = std::env::temp_dir().display().to_string();
    let os = if windows { "Windows" } else { "Linux" };
    [
        ("java.version", "1.8.0"),
        ("java.specification.version", "1.8"),
        ("java.class.version", "52.0"),
        ("java.vendor", "LazyVM"),
        ("java.vm.name", "LazyVM"),
        ("java.vm.version", env!("CARGO_PKG_VERSION")),
        ("os.name", os),
        ("os.arch", "amd64"),
        ("file.separator", if windows { "\\" } else { "/" }),
        ("path.separator", if windows { ";" } else { ":" }),
        ("line.separator", if windows { "\r\n" } else { "\n" }),
        ("file.encoding", "UTF-8"),
        ("user.dir", &dir),
        ("user.home", &home),
        ("java.io.tmpdir", &tmp),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_owned(), v.to_owned()))
    .collect()
}
