//! The platform layer: console, clocks and system facts, through Rust `std`.
//!
//! Windows and LazyOS (a static-musl `std` program through LazyOS's Linux
//! ABI) share this code. Windows- or LazyOS-only parts (windows, Messenger)
//! get their own crates in S9 (docs/decisions.md D10).

pub mod files;
pub mod wtf;

use std::ffi::OsString;
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

/// The system properties LazyVM defines before `-D` overrides. Values are
/// OS strings, so a directory name reaches Java exactly (`vm::wtf`).
pub fn default_properties() -> Vec<(String, OsString)> {
    let windows = cfg!(windows);
    let dir = std::env::current_dir()
        .map(|d| d.into_os_string())
        .unwrap_or_default();
    let home = std::env::var_os(if windows { "USERPROFILE" } else { "HOME" }).unwrap_or_default();
    let tmp = std::env::temp_dir().into_os_string();
    let user = std::env::var_os(if windows { "USERNAME" } else { "USER" }).unwrap_or_default();
    // Where the VM's own files are: its binary's directory.
    let vm_home = std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(|d| d.as_os_str().to_owned()))
        .unwrap_or_default();
    let os = if windows { "Windows" } else { "Linux" };
    [
        ("java.version", "1.8.0"),
        ("java.specification.version", "1.8"),
        ("java.class.version", "52.0"),
        ("java.specification.name", "SE 8 API Specification"),
        ("java.specification.vendor", "LazyVM"),
        ("java.vendor", "LazyVM"),
        ("java.vendor.url", "https://github.com/va1erian/lazyvm"),
        ("java.vm.name", "LazyVM"),
        ("java.vm.vendor", "LazyVM"),
        ("java.vm.version", env!("CARGO_PKG_VERSION")),
        (
            "java.vm.specification.name",
            "SE 8 Virtual Machine Specification",
        ),
        ("java.vm.specification.vendor", "LazyVM"),
        ("java.vm.specification.version", "1.8"),
        // Set because the Javadoc of System.getProperties says they are; nothing here
        // uses them: extensions and native libraries are not loaded from directories.
        ("java.ext.dirs", ""),
        ("java.library.path", ""),
        // std has no call for it, and no program should branch on it.
        ("os.version", ""),
        ("os.name", os),
        ("os.arch", "amd64"),
        ("file.separator", if windows { "\\" } else { "/" }),
        ("path.separator", if windows { ";" } else { ":" }),
        ("line.separator", if windows { "\r\n" } else { "\n" }),
        ("file.encoding", "UTF-8"),
        // The default locale is en-US whatever the OS is set to, so output is the same on every
        // machine; -D overrides these (Locale.getDefault).
        ("user.language", "en"),
        ("user.country", "US"),
        ("user.variant", ""),
        ("user.script", ""),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_owned(), OsString::from(v)))
    .chain([
        ("user.dir".to_owned(), dir),
        ("user.home".to_owned(), home),
        ("user.name".to_owned(), user),
        ("java.home".to_owned(), vm_home),
        ("java.io.tmpdir".to_owned(), tmp),
    ])
    .collect()
}

/// The process environment as (name, value) pairs in UTF-16, for `System.getenv`.
/// LazyOS passes whatever environment its launcher gave the process (possibly
/// none), through the same `std` call. Windows drive variables such as `=C:`
/// are ordinary entries whose names start with '='.
pub fn environment() -> Vec<(Vec<u16>, Vec<u16>)> {
    std::env::vars_os()
        .map(|(name, value)| (wtf::to_units(&name), wtf::to_units(&value)))
        .collect()
}

/// Whether environment variable names compare ignoring case (Windows).
pub fn environment_ignores_case() -> bool {
    cfg!(windows)
}

/// 64 bits that differ on every call and in every run, for `new Random()`
/// and `UUID.randomUUID()` (docs/design/util.md R3, util-data.md U8).
///
/// `RandomState` carries keys the standard library draws from the operating
/// system's random source once per process; hashing the wall clock at full
/// resolution, the monotonic clock and a call counter with them gives a fresh
/// value per call. Callers must not rely on it for cryptographic strength.
pub fn entropy() -> i64 {
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    use std::sync::atomic::{AtomicU64, Ordering};
    static KEYS: OnceLock<RandomState> = OnceLock::new();
    static CALLS: AtomicU64 = AtomicU64::new(0);
    let mut hasher = KEYS.get_or_init(RandomState::new).build_hasher();
    let wall = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_nanos());
    hasher.write_u128(wall);
    hasher.write_i64(nano_time());
    hasher.write_u64(CALLS.fetch_add(1, Ordering::Relaxed));
    hasher.finish() as i64
}

#[cfg(test)]
mod tests {
    #[test]
    fn entropy_differs_per_call() {
        let values: std::collections::HashSet<i64> = (0..1000).map(|_| super::entropy()).collect();
        assert_eq!(values.len(), 1000);
    }
}
