//! Open files as numbered handles, like descriptors (docs/design/files.md).

use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{self, IsTerminal, Read, Seek, SeekFrom, Write};
use std::sync::{Mutex, MutexGuard, OnceLock};

/// How `open` opens a file (the `mode` argument of the native).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    Read,
    Write,
    Append,
    ReadWrite,
    ReadWriteExisting,
}

impl Mode {
    pub fn from_int(m: i32) -> Option<Mode> {
        Some(match m {
            0 => Mode::Read,
            1 => Mode::Write,
            2 => Mode::Append,
            3 => Mode::ReadWrite,
            4 => Mode::ReadWriteExisting,
            _ => return None,
        })
    }
}

#[derive(Default)]
struct Table {
    next: i64,
    open: HashMap<i64, File>,
}

fn table() -> MutexGuard<'static, Table> {
    static TABLE: OnceLock<Mutex<Table>> = OnceLock::new();
    let t = TABLE.get_or_init(|| {
        Mutex::new(Table {
            next: 1,
            open: HashMap::new(),
        })
    });
    // A panic while holding the lock cannot leave the map inconsistent
    // (every operation is a single insert, remove or call), so recover.
    t.lock().unwrap_or_else(|e| e.into_inner())
}

/// The error a handle that is not open gives, as Java 8 words it.
pub fn closed() -> io::Error {
    io::Error::other("Stream Closed")
}

/// Opens `path`; `shared_delete` lets others rename or delete it while it is
/// open (java.nio.file's streams; java.io's are opened without it).
pub fn open(path: &std::path::Path, mode: Mode, shared_delete: bool) -> io::Result<i64> {
    let mut o = OpenOptions::new();
    match mode {
        Mode::Read => o.read(true),
        Mode::Write => o.write(true).create(true).truncate(true),
        Mode::Append => o.append(true).create(true),
        Mode::ReadWrite => o.read(true).write(true).create(true),
        Mode::ReadWriteExisting => o.read(true).write(true),
    };
    if !shared_delete {
        share_like_java(&mut o);
    }
    let file = o.open(path)?;
    // A device such as NUL has no metadata; only a directory is refused.
    if file.metadata().is_ok_and(|m| m.is_dir()) {
        return Err(io::Error::new(
            io::ErrorKind::IsADirectory,
            if cfg!(windows) {
                "Access is denied"
            } else {
                "Is a directory"
            },
        ));
    }
    let mut t = table();
    let h = t.next;
    t.next += 1;
    t.open.insert(h, file);
    Ok(h)
}

/// Java 8's java.io opens files on Windows without delete sharing, so an
/// open file cannot be renamed or deleted; LazyVM does the same.
#[cfg(windows)]
fn share_like_java(o: &mut OpenOptions) {
    use std::os::windows::fs::OpenOptionsExt;
    const FILE_SHARE_READ: u32 = 1;
    const FILE_SHARE_WRITE: u32 = 2;
    o.share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE);
}

#[cfg(not(windows))]
fn share_like_java(_: &mut OpenOptions) {}

fn with<T>(h: i64, f: impl FnOnce(&mut File) -> io::Result<T>) -> io::Result<T> {
    let mut t = table();
    f(t.open.get_mut(&h).ok_or_else(closed)?)
}

pub fn read(h: i64, buf: &mut [u8]) -> io::Result<usize> {
    with(h, |f| f.read(buf))
}

pub fn write(h: i64, buf: &[u8]) -> io::Result<()> {
    with(h, |f| f.write_all(buf))
}

/// Position, seek and truncate are the OS's for every file, devices and
/// volumes included: Windows keeps a position for NUL and consoles too.
pub fn position(h: i64) -> io::Result<u64> {
    with(h, |f| f.stream_position())
}

pub fn seek(h: i64, pos: u64) -> io::Result<()> {
    with(h, |f| f.seek(SeekFrom::Start(pos)).map(|_| ()))
}

/// Observed on Windows: NUL, whose metadata the OS refuses ("Incorrect
/// function"), has length 0; a console, refused the same way, keeps the error.
pub fn size(h: i64) -> io::Result<u64> {
    with(h, |f| match f.metadata() {
        Ok(m) => Ok(m.len()),
        Err(e) if cfg!(windows) && e.raw_os_error() == Some(1) && !f.is_terminal() => Ok(0),
        Err(e) => Err(e),
    })
}

/// Observed (Windows): the pointer moves to `len` first, so it stays there
/// when the OS refuses the new length (NUL, a read-only handle); on success
/// it goes back unless it was past the new end.
pub fn truncate(h: i64, len: u64) -> io::Result<()> {
    with(h, |f| {
        let old = f.stream_position()?;
        f.seek(SeekFrom::Start(len))?;
        f.set_len(len)?;
        if old < len {
            f.seek(SeekFrom::Start(old))?;
        }
        Ok(())
    })
}

/// Bytes from the position to the end. A device the OS gives no metadata
/// for (NUL, a console on Windows) is the OS's error, as Java 8 reports it.
pub fn available(h: i64) -> io::Result<u64> {
    with(h, |f| {
        let pos = f.stream_position()?;
        Ok(f.metadata()?.len().saturating_sub(pos))
    })
}

pub fn sync(h: i64) -> io::Result<()> {
    with(h, |f| f.sync_all())
}

/// Closing an unknown or already closed handle does nothing, as in Java.
pub fn close(h: i64) {
    table().open.remove(&h);
}

/// The OS's text for an error without the "(os error N)" Rust appends.
/// `parenthesized`: as Java 8 shows it inside parentheses (`FileNotFound`
/// messages), without the final period; else whole, as a
/// FileSystemException's reason, which on Windows keeps the period and the
/// line break the OS message ends in.
pub fn os_text(e: &io::Error, parenthesized: bool) -> String {
    let text = e.to_string();
    let text = match text.rfind(" (os error ") {
        Some(i) => &text[..i],
        None => &text,
    };
    if parenthesized {
        return text.trim_end_matches('.').to_owned();
    }
    let mut text = text.to_owned();
    if cfg!(windows) && e.raw_os_error().is_some() {
        text.push_str("\r\n");
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_through_a_handle() {
        let path = std::env::temp_dir().join(format!("lazyvm-files-{}.txt", std::process::id()));
        let h = open(&path, Mode::Write, false).unwrap_or_else(|e| panic!("{e}"));
        assert!(write(h, b"hello").is_ok());
        close(h);
        let h = open(&path, Mode::Read, false).unwrap_or_else(|e| panic!("{e}"));
        let mut buf = [0u8; 16];
        assert_eq!(read(h, &mut buf).ok(), Some(5));
        assert_eq!(size(h).ok(), Some(5));
        close(h);
        close(h);
        assert!(read(h, &mut buf).is_err(), "a closed handle is an error");
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn reasons_lose_the_os_error_number() {
        let e = io::Error::from_raw_os_error(2);
        let r = os_text(&e, true);
        assert!(!r.contains("os error") && !r.ends_with('.'), "{r}");
    }
}
