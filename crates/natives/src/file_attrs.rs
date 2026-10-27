//! `lazy.internal.FileSystem`: what a path's metadata says and how it
//! changes (docs/design/files.md): attribute bits, times and access.

use std::path::Path;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use vm::{Value, Vm, VmError};

use crate::{arg, int, long, ret_bool, ret_int, ret_void};

const FS: &str = "lazy/internal/FileSystem";

pub fn register(vm: &mut Vm) {
    vm.register_native(FS, "attributes", "(Ljava/lang/String;)I", |vm, a| {
        ret_int(attributes(&vm.path_arg(arg(a, 0)?)?))
    });
    vm.register_native(FS, "length", "(Ljava/lang/String;)J", |vm, a| {
        let len = std::fs::metadata(vm.path_arg(arg(a, 0)?)?).map_or(0, |m| m.len());
        Ok(Some(Value::Long(i64::try_from(len).unwrap_or(i64::MAX))))
    });
    vm.register_native(FS, "fileTime", "(Ljava/lang/String;I)J", |vm, a| {
        let path = vm.path_arg(arg(a, 0)?)?;
        Ok(Some(Value::Long(file_time(&path, int(a, 1)?))))
    });
    vm.register_native(FS, "setFileTime", "(Ljava/lang/String;IJ)V", |vm, a| {
        let path = vm.path_arg(arg(a, 0)?)?;
        set_file_time(&path, int(a, 1)?, long(a, 2)?).map_err(|e| {
            VmError::exception("java/io/IOException", platform::files::os_text(&e, false))
        })?;
        ret_void()
    });
    vm.register_native(FS, "setAccess", "(Ljava/lang/String;IZZ)Z", |vm, a| {
        let path = vm.path_arg(arg(a, 0)?)?;
        let (access, enable, owner_only) = (int(a, 1)?, int(a, 2)? != 0, int(a, 3)? != 0);
        ret_bool(set_access(&path, access, enable, owner_only))
    });
    vm.register_native(FS, "setReadOnly", "(Ljava/lang/String;)Z", |vm, a| {
        ret_bool(set_access(&vm.path_arg(arg(a, 0)?)?, 2, false, false))
    });
}

pub(crate) fn attributes(path: &Path) -> i32 {
    let p = path;
    let Ok(m) = std::fs::metadata(p) else {
        // A dangling link exists as a link.
        return if std::fs::symlink_metadata(p).is_ok() {
            1 | 8
        } else {
            0
        };
    };
    let link = std::fs::symlink_metadata(p).is_ok_and(|l| l.file_type().is_symlink());
    let name_hidden = p
        .file_name()
        .is_some_and(|n| n.to_string_lossy().starts_with('.'));
    let mut bits = 1;
    bits |= i32::from(m.is_file()) << 1;
    bits |= i32::from(m.is_dir()) << 2;
    bits |= i32::from(link) << 3;
    bits |= 1 << 4;
    bits |= i32::from(!m.permissions().readonly()) << 5;
    bits |= i32::from(executable(&m)) << 6;
    bits |= i32::from(hidden(&m) || (cfg!(unix) && name_hidden)) << 7;
    bits
}

#[cfg(unix)]
fn executable(m: &std::fs::Metadata) -> bool {
    use std::os::unix::fs::PermissionsExt;
    m.permissions().mode() & 0o111 != 0
}

#[cfg(not(unix))]
fn executable(m: &std::fs::Metadata) -> bool {
    // Windows has no execute bit; Java reports files and directories as executable.
    m.is_file() || m.is_dir()
}

#[cfg(windows)]
fn hidden(m: &std::fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    const FILE_ATTRIBUTE_HIDDEN: u32 = 0x2;
    m.file_attributes() & FILE_ATTRIBUTE_HIDDEN != 0
}

#[cfg(not(windows))]
fn hidden(_: &std::fs::Metadata) -> bool {
    false
}

/// `access` is 4 read, 2 write, 1 execute, as in a Unix mode digit.
#[cfg(unix)]
fn set_access(path: &Path, access: i32, enable: bool, owner_only: bool) -> bool {
    use std::os::unix::fs::PermissionsExt;
    let Ok(m) = std::fs::metadata(path) else {
        return false;
    };
    let digit = (access & 7) as u32;
    let bits = if owner_only {
        digit << 6
    } else {
        (digit << 6) | (digit << 3) | digit
    };
    let mut perms = m.permissions();
    let mode = perms.mode();
    perms.set_mode(if enable { mode | bits } else { mode & !bits });
    std::fs::set_permissions(path, perms).is_ok()
}

/// Windows has only a read-only flag, for files: write access maps to it and
/// a directory's cannot be changed either way; read and execute access cannot
/// be taken away, so granting them succeeds, even for a missing file, and
/// taking them fails (as Java 8 reports).
#[cfg(not(unix))]
fn set_access(path: &Path, access: i32, enable: bool, _owner_only: bool) -> bool {
    if access & 2 == 0 {
        return enable;
    }
    let Ok(m) = std::fs::metadata(path) else {
        return false;
    };
    if m.is_dir() {
        return false;
    }
    let mut perms = m.permissions();
    perms.set_readonly(!enable);
    std::fs::set_permissions(path, perms).is_ok()
}

/// Microseconds from the epoch to `t`, negative before it.
fn micros(t: SystemTime) -> i64 {
    match t.duration_since(UNIX_EPOCH) {
        Ok(d) => i64::try_from(d.as_micros()).unwrap_or(i64::MAX),
        Err(e) => -i64::try_from(e.duration().as_micros()).unwrap_or(i64::MAX),
    }
}

/// `which` is 0 modified, 1 accessed, 2 created; `i64::MIN` when the file is
/// missing or the platform does not keep that time.
fn file_time(path: &Path, which: i32) -> i64 {
    let Ok(m) = std::fs::metadata(path) else {
        return i64::MIN;
    };
    let t = match which {
        0 => m.modified(),
        1 => m.accessed(),
        _ => m.created(),
    };
    t.map_or(i64::MIN, micros)
}

fn at_micros(us: i64) -> Option<SystemTime> {
    let d = Duration::from_micros(us.unsigned_abs());
    if us >= 0 {
        UNIX_EPOCH.checked_add(d)
    } else {
        UNIX_EPOCH.checked_sub(d)
    }
}

/// Sets one time stamp; a time the platform cannot hold is an error.
fn set_file_time(path: &Path, which: i32, us: i64) -> std::io::Result<()> {
    // Observed on Windows: the first instant of 1601 is the "leave it" time stamp.
    const WINDOWS_EPOCH_US: i64 = -11_644_473_600_000_000;
    if cfg!(windows) && us == WINDOWS_EPOCH_US {
        return open_for_times(path).map(|_| ());
    }
    // A time the system cannot hold is the OS's own error for a bad parameter.
    let invalid = if cfg!(windows) { 87 } else { 22 };
    let when = at_micros(us).ok_or_else(|| std::io::Error::from_raw_os_error(invalid))?;
    let mut times = std::fs::FileTimes::new();
    times = match which {
        0 => times.set_modified(when),
        1 => times.set_accessed(when),
        _ => created(times, when)?,
    };
    open_for_times(path)?.set_times(times)
}

#[cfg(windows)]
fn created(times: std::fs::FileTimes, when: SystemTime) -> std::io::Result<std::fs::FileTimes> {
    use std::os::windows::fs::FileTimesExt;
    Ok(times.set_created(when))
}

#[cfg(not(windows))]
fn created(_: std::fs::FileTimes, _: SystemTime) -> std::io::Result<std::fs::FileTimes> {
    Err(std::io::Error::new(
        std::io::ErrorKind::Unsupported,
        "Operation not supported",
    ))
}

/// Windows opens directories only with backup semantics, and attribute
/// access works on read-only files too.
#[cfg(windows)]
fn open_for_times(path: &Path) -> std::io::Result<std::fs::File> {
    use std::os::windows::fs::OpenOptionsExt;
    const FILE_WRITE_ATTRIBUTES: u32 = 0x100;
    const FILE_FLAG_BACKUP_SEMANTICS: u32 = 0x0200_0000;
    std::fs::File::options()
        .access_mode(FILE_WRITE_ATTRIBUTES)
        .custom_flags(FILE_FLAG_BACKUP_SEMANTICS)
        .open(path)
}

#[cfg(not(windows))]
fn open_for_times(path: &Path) -> std::io::Result<std::fs::File> {
    std::fs::File::open(path)
}
