//! Open files as numbered handles, like descriptors (docs/design/files.md).

use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
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

pub fn open(path: &str, mode: Mode) -> io::Result<i64> {
    let mut o = OpenOptions::new();
    match mode {
        Mode::Read => o.read(true),
        Mode::Write => o.write(true).create(true).truncate(true),
        Mode::Append => o.append(true).create(true),
        Mode::ReadWrite => o.read(true).write(true).create(true),
        Mode::ReadWriteExisting => o.read(true).write(true),
    };
    let file = o.open(path)?;
    if file.metadata()?.is_dir() {
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

fn with<T>(h: i64, f: impl FnOnce(&mut File) -> io::Result<T>) -> io::Result<T> {
    let mut t = table();
    let file = t.open.get_mut(&h).ok_or_else(closed)?;
    f(file)
}

pub fn read(h: i64, buf: &mut [u8]) -> io::Result<usize> {
    with(h, |f| f.read(buf))
}

pub fn write(h: i64, buf: &[u8]) -> io::Result<()> {
    with(h, |f| f.write_all(buf))
}

pub fn position(h: i64) -> io::Result<u64> {
    with(h, |f| f.stream_position())
}

pub fn seek(h: i64, pos: u64) -> io::Result<()> {
    with(h, |f| f.seek(SeekFrom::Start(pos)).map(|_| ()))
}

pub fn size(h: i64) -> io::Result<u64> {
    with(h, |f| f.metadata().map(|m| m.len()))
}

pub fn truncate(h: i64, len: u64) -> io::Result<()> {
    with(h, |f| f.set_len(len))
}

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

/// The OS's text for an error, as Java 8 shows it inside parentheses:
/// without the trailing period and the "(os error N)" Rust appends.
pub fn reason(e: &io::Error) -> String {
    let text = e.to_string();
    let text = match text.rfind(" (os error ") {
        Some(i) => &text[..i],
        None => &text,
    };
    text.trim_end_matches('.').to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_through_a_handle() {
        let path = std::env::temp_dir().join(format!("lazyvm-files-{}.txt", std::process::id()));
        let p = path.to_string_lossy().into_owned();
        let h = open(&p, Mode::Write).unwrap_or_else(|e| panic!("{e}"));
        assert!(write(h, b"hello").is_ok());
        close(h);
        let h = open(&p, Mode::Read).unwrap_or_else(|e| panic!("{e}"));
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
        let r = reason(&e);
        assert!(!r.contains("os error") && !r.ends_with('.'), "{r}");
    }
}
