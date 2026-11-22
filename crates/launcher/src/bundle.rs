//! Self-contained executables (docs/bundle.md): `tools/bundle.py` appends a
//! payload to a copy of the launcher, and the launcher runs it instead of
//! reading its command line for a class to run.
//!
//! The file ends with the packed payload, then a trailer: six u64,
//! little-endian (packed, class library, application, files and arguments
//! lengths, then the CRC-32 of the unpacked payload), then `MAGIC`.
//! Unpacked, the payload is the class library jar, the application jar, a
//! jar of files to unpack (`extract.rs`; length 0 for none), then the launch
//! arguments (UTF-8, each ended by a NUL). It is packed as one raw deflate
//! stream so that class files share their redundancy: the jars inside store
//! their entries uncompressed.
//! A file without the trailer is a plain launcher.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::PathBuf;

use classfile::jar::{Jar, crc32, inflate};
use vm::ClassPath;

use crate::extract;
use crate::options::Arg;

/// The trailer's last bytes; the digit is the payload layout's version.
const MAGIC: &[u8; 8] = b"LZVMBND1";
const TRAILER: u64 = 56;
/// Far above any real application; it bounds what a damaged trailer allocates.
const MAX_UNPACKED: u64 = 1 << 30;

/// What a self-contained executable carries.
pub struct Bundle {
    /// The executable itself: the class path the application sees.
    pub exe: PathBuf,
    pub boot: Jar,
    pub app: Jar,
    /// Where the bundle's files were unpacked, if it has any.
    pub dir: Option<PathBuf>,
    /// Options and the main class, then fixed program arguments.
    args: Vec<Arg>,
}

/// The trailer: lengths in bytes, and the unpacked payload's CRC-32.
struct Lens {
    packed: u64,
    boot: u64,
    app: u64,
    files: u64,
    args: u64,
    crc: u64,
}

impl Bundle {
    /// The command line to parse: the stored arguments, then the user's,
    /// which follow the main class and so reach `main`.
    pub fn command_line(&self, user: Vec<Arg>) -> Vec<Arg> {
        let exe = vm::wtf::to_units(self.exe.as_os_str());
        // `-cp` is what `java.class.path` shows; `classpath` is what loads.
        let mut boot: Arg = "-Xbootclasspath:".encode_utf16().collect();
        boot.extend(&exe);
        let cp = match &self.dir {
            // `unpack` checked that the two paths join.
            Some(dir) => class_path(&self.exe, dir).map_or(exe, |p| vm::wtf::to_units(&p)),
            None => exe,
        };
        let mut out = vec![boot, "-cp".encode_utf16().collect(), cp];
        out.extend(self.args.iter().cloned());
        out.extend(user);
        out
    }

    pub fn classpath(self) -> ClassPath {
        ClassPath::from_jars(self.boot, self.app, self.dir.into_iter().collect())
    }
}

/// The running executable's payload: `None` for a plain launcher (or when
/// the executable cannot be found or opened), an error for a damaged one.
pub fn load(exe: Option<PathBuf>) -> Result<Option<Bundle>, String> {
    let Some(exe) = exe else { return Ok(None) };
    let Ok(mut file) = File::open(&exe) else {
        return Ok(None);
    };
    let damaged = |what: &str| format!("{}: damaged bundle: {what}", exe.display());
    let len = file.metadata().map_err(|e| damaged(&e.to_string()))?.len();
    let Some(lens) = read_trailer(&mut file, len).map_err(|e| damaged(&e))? else {
        return Ok(None);
    };
    if lens.packed > len - TRAILER {
        return Err(damaged("payload larger than the file"));
    }
    let mut packed = vec![0; usize::try_from(lens.packed).map_err(|_| damaged("too large"))?];
    file.seek(SeekFrom::Start(len - TRAILER - lens.packed))
        .and_then(|_| file.read_exact(&mut packed))
        .map_err(|e| damaged(&e.to_string()))?;
    unpack(exe.clone(), &packed, &lens)
        .map(Some)
        .map_err(|e| damaged(&e))
}

/// The lengths, or `None` when the file has no trailer.
fn read_trailer(file: &mut File, len: u64) -> Result<Option<Lens>, String> {
    if len < TRAILER {
        return Ok(None);
    }
    let mut t = [0u8; TRAILER as usize];
    file.seek(SeekFrom::Start(len - TRAILER))
        .and_then(|_| file.read_exact(&mut t))
        .map_err(|e| e.to_string())?;
    if &t[48..] != MAGIC {
        return Ok(None);
    }
    let le = |i: usize| u64::from_le_bytes(t[i * 8..i * 8 + 8].try_into().unwrap_or_default());
    Ok(Some(Lens {
        packed: le(0),
        boot: le(1),
        app: le(2),
        files: le(3),
        args: le(4),
        crc: le(5),
    }))
}

/// Inflates the payload, splits it into its parts and unpacks its files.
fn unpack(exe: PathBuf, packed: &[u8], lens: &Lens) -> Result<Bundle, String> {
    let total = [lens.boot, lens.app, lens.files, lens.args]
        .iter()
        .try_fold(0u64, |acc, &n| acc.checked_add(n))
        .filter(|&t| t <= MAX_UNPACKED)
        .ok_or("unpacked size out of range")?;
    // At most MAX_UNPACKED, so every length fits a usize.
    let payload = inflate(packed, total as usize)
        .filter(|p| p.len() as u64 == total && u64::from(crc32(p)) == lens.crc)
        .ok_or("corrupt payload")?;
    let (boot, rest) = payload.split_at(lens.boot as usize);
    let (app, rest) = rest.split_at(lens.app as usize);
    let (files, args) = rest.split_at(lens.files as usize);
    let boot = Jar::open(boot.to_vec()).map_err(|e| format!("class library: {e}"))?;
    let app = Jar::open(app.to_vec()).map_err(|e| format!("application: {e}"))?;
    let args = split_args(args)?;
    let dir = if files.is_empty() {
        None
    } else {
        let files = Jar::open(files.to_vec()).map_err(|e| format!("files: {e}"))?;
        let dir = extract::unpack_dir(&files, extract::fnv64(packed), &exe)?;
        // A path holding the separator cannot be listed in `java.class.path`.
        class_path(&exe, &dir).ok_or_else(|| {
            format!(
                "cannot list {} and {} as a class path",
                exe.display(),
                dir.display()
            )
        })?;
        Some(dir)
    };
    Ok(Bundle {
        exe,
        boot,
        app,
        dir,
        args,
    })
}

/// `java.class.path` for a bundle with a directory.
fn class_path(exe: &std::path::Path, dir: &std::path::Path) -> Option<std::ffi::OsString> {
    std::env::join_paths([exe, dir]).ok()
}

fn split_args(bytes: &[u8]) -> Result<Vec<Arg>, String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "arguments are not UTF-8")?;
    let body = text
        .strip_suffix('\0')
        .ok_or("arguments are not NUL-terminated")?;
    Ok(body
        .split('\0')
        .map(|a| a.encode_utf16().collect())
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// An empty zip: only its end-of-central-directory record.
    const EMPTY_JAR: [u8; 22] = [
        b'P', b'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ];

    fn tmp(name: &str, bytes: &[u8]) -> Option<PathBuf> {
        let p = std::env::temp_dir().join(format!("lazyvm-bundle-{}-{name}", std::process::id()));
        std::fs::write(&p, bytes).expect("write a test file");
        Some(p)
    }

    /// `data` as one stored deflate block (RFC 1951 3.2.4).
    fn stored(data: &[u8]) -> Vec<u8> {
        let n = u16::try_from(data.len()).expect("one stored block");
        let mut out = vec![1];
        out.extend(n.to_le_bytes());
        out.extend((!n).to_le_bytes());
        out.extend(data);
        out
    }

    /// A launcher stand-in followed by a payload of `boot`, `app` and `args`.
    fn bundle(boot: &[u8], app: &[u8], args: &[u8]) -> Vec<u8> {
        let packed = stored(&[boot, app, args].concat());
        let all = [boot, app, args].concat();
        let lens = [
            packed.len(),
            boot.len(),
            app.len(),
            0,
            args.len(),
            crc32(&all) as usize,
        ];
        let mut file = b"MZ program".to_vec();
        file.extend(&packed);
        file.extend(lens.iter().flat_map(|&n| (n as u64).to_le_bytes()));
        file.extend(MAGIC);
        file
    }

    #[test]
    fn a_bundle_round_trips() {
        let file = bundle(&EMPTY_JAR, &EMPTY_JAR, "-Xmx1g\0a.Main\0".as_bytes());
        let b = load(tmp("ok", &file)).expect("loads").expect("a bundle");
        let line = b.command_line(vec!["x".encode_utf16().collect()]);
        let text: Vec<String> = line[3..]
            .iter()
            .map(|a| String::from_utf16_lossy(a))
            .collect();
        assert_eq!(text, ["-Xmx1g", "a.Main", "x"]);
    }

    #[test]
    fn a_file_without_trailer_is_no_bundle() {
        assert!(
            load(tmp("plain", b"MZ just a program"))
                .expect("no error")
                .is_none()
        );
        assert!(load(tmp("short", b"MZ")).expect("no error").is_none());
        assert!(load(None).expect("no error").is_none());
    }

    #[test]
    fn damaged_lengths_are_errors() {
        let mut file = bundle(&EMPTY_JAR, &EMPTY_JAR, b"a\0");
        let at = file.len() - 56;
        file[at..at + 8].copy_from_slice(&u64::MAX.to_le_bytes());
        assert!(load(tmp("packed", &file)).is_err());
        let mut file = bundle(&EMPTY_JAR, &EMPTY_JAR, b"a\0");
        file[at + 8..at + 16].copy_from_slice(&u64::MAX.to_le_bytes());
        assert!(load(tmp("overflow", &file)).is_err());
        let mut file = bundle(&EMPTY_JAR, &EMPTY_JAR, b"a\0");
        file[at + 32..at + 40].copy_from_slice(&3u64.to_le_bytes());
        assert!(load(tmp("short", &file)).is_err());
    }

    #[test]
    fn a_changed_argument_fails_the_crc() {
        let mut file = bundle(&EMPTY_JAR, &EMPTY_JAR, b"a.Main ");
        let last = file.len() - 57; // the arguments' final byte, stored as is
        file[last - 1] = b'X';
        let Err(err) = load(tmp("crc", &file)) else {
            panic!("a changed payload was accepted");
        };
        assert!(err.contains("corrupt payload"), "{err}");
    }

    #[test]
    fn a_damaged_jar_is_an_error() {
        let file = bundle(b"nota", &EMPTY_JAR, b"a\0");
        let Err(err) = load(tmp("jar", &file)) else {
            panic!("a damaged jar was accepted");
        };
        assert!(err.contains("class library"), "{err}");
    }

    #[test]
    fn arguments_split_on_nul() {
        let args = split_args("-Xmx1g\0a.Main\0x y\0".as_bytes()).expect("splits");
        let text: Vec<String> = args.iter().map(|a| String::from_utf16_lossy(a)).collect();
        assert_eq!(text, ["-Xmx1g", "a.Main", "x y"]);
        assert!(split_args(b"a.Main").is_err());
        assert!(split_args(&[0xff, 0]).is_err());
    }
}
