//! `lazy.internal.FileSystem`: files by handle and path operations
//! (docs/design/files.md).

use std::path::Path;

use platform::files::{self, Mode};
use vm::heap::Array;
use vm::{Ref, Value, Vm, VmError, VmResult};

use crate::{arg, int, long, obj, ret_bool, ret_int, ret_ref, ret_void};

const FS: &str = "lazy/internal/FileSystem";

pub fn register(vm: &mut Vm) {
    vm.register_native(FS, "open", "(Ljava/lang/String;I)J", open);
    vm.register_native(FS, "read", "(J[BII)I", read);
    vm.register_native(FS, "write", "(J[BII)V", write);
    vm.register_native(FS, "position", "(J)J", |_, a| {
        Ok(Some(Value::Long(
            files::position(long(a, 0)?).map_err(handle_error)? as i64,
        )))
    });
    vm.register_native(FS, "seek", "(JJ)V", |_, a| {
        let pos = u64::try_from(long(a, 1)?).map_err(|_| io_exception("Negative seek offset"))?;
        files::seek(long(a, 0)?, pos).map_err(handle_error)?;
        ret_void()
    });
    vm.register_native(FS, "size", "(J)J", |_, a| {
        Ok(Some(Value::Long(
            files::size(long(a, 0)?).map_err(handle_error)? as i64,
        )))
    });
    vm.register_native(FS, "truncate", "(JJ)V", |_, a| {
        let len = u64::try_from(long(a, 1)?).map_err(|_| io_exception("Negative length"))?;
        files::truncate(long(a, 0)?, len).map_err(handle_error)?;
        ret_void()
    });
    vm.register_native(FS, "available", "(J)I", |_, a| {
        let n = files::available(long(a, 0)?).map_err(handle_error)?;
        ret_int(i32::try_from(n).unwrap_or(i32::MAX))
    });
    vm.register_native(FS, "sync", "(J)V", |_, a| {
        files::sync(long(a, 0)?).map_err(handle_error)?;
        ret_void()
    });
    vm.register_native(FS, "close", "(J)V", |_, a| {
        files::close(long(a, 0)?);
        ret_void()
    });
    register_paths(vm);
}

fn io_exception(msg: impl Into<String>) -> VmError {
    VmError::exception("java/io/IOException", msg)
}

/// Errors on an open handle carry no path; a closed handle is "Stream Closed".
fn handle_error(e: std::io::Error) -> VmError {
    io_exception(files::reason(&e))
}

fn path_arg(vm: &Vm, args: &[Value], i: usize) -> VmResult<String> {
    vm.string_arg(arg(args, i)?)
}

fn open(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let path = path_arg(vm, args, 0)?;
    let mode =
        Mode::from_int(int(args, 1)?).ok_or_else(|| VmError::Internal("bad open mode".into()))?;
    match files::open(&path, mode) {
        Ok(h) => Ok(Some(Value::Long(h))),
        Err(e) => {
            let msg = format!("{path} ({})", files::reason(&e));
            Err(VmError::exception("java/io/FileNotFoundException", msg))
        }
    }
}

/// `off..off+len` within an array of `array_len`, or `IndexOutOfBoundsException`.
fn range(off: i32, len: i32, array_len: usize) -> VmResult<std::ops::Range<usize>> {
    let end = i64::from(off) + i64::from(len);
    if off < 0 || len < 0 || end > array_len as i64 {
        return Err(VmError::bare("java/lang/IndexOutOfBoundsException"));
    }
    Ok(off as usize..end as usize)
}

fn read(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let (h, array) = (long(args, 0)?, obj(args, 1)?);
    let r = range(int(args, 2)?, int(args, 3)?, vm.heap.array(array)?.len())?;
    if r.is_empty() {
        return ret_int(0);
    }
    let mut buf = vec![0u8; r.len()];
    let n = files::read(h, &mut buf).map_err(handle_error)?;
    if n == 0 {
        return ret_int(-1);
    }
    if let Array::Byte(a) = vm.heap.array_mut(array)? {
        for (d, s) in a[r.start..r.start + n].iter_mut().zip(&buf) {
            *d = *s as i8;
        }
    }
    ret_int(n as i32)
}

fn write(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let (h, array) = (long(args, 0)?, obj(args, 1)?);
    let Array::Byte(a) = vm.heap.array(array)? else {
        return Err(VmError::verify("write needs a byte[]"));
    };
    let r = range(int(args, 2)?, int(args, 3)?, a.len())?;
    let data: Vec<u8> = a[r].iter().map(|&b| b as u8).collect();
    files::write(h, &data).map_err(handle_error)?;
    ret_void()
}

fn register_paths(vm: &mut Vm) {
    vm.register_native(FS, "attributes", "(Ljava/lang/String;)I", |vm, a| {
        ret_int(attributes(&path_arg(vm, a, 0)?))
    });
    vm.register_native(FS, "length", "(Ljava/lang/String;)J", |vm, a| {
        let len = std::fs::metadata(path_arg(vm, a, 0)?)
            .map(|m| m.len())
            .unwrap_or(0);
        Ok(Some(Value::Long(len as i64)))
    });
    vm.register_native(FS, "lastModified", "(Ljava/lang/String;)J", |vm, a| {
        Ok(Some(Value::Long(modified_millis(&path_arg(vm, a, 0)?))))
    });
    vm.register_native(FS, "setLastModified", "(Ljava/lang/String;J)Z", |vm, a| {
        let path = path_arg(vm, a, 0)?;
        let millis = long(a, 1)?;
        ret_bool(set_modified(&path, millis))
    });
    vm.register_native(FS, "list", "(Ljava/lang/String;)[Ljava/lang/String;", list);
    vm.register_native(FS, "delete", "(Ljava/lang/String;)Z", |vm, a| {
        let path = path_arg(vm, a, 0)?;
        let p = Path::new(&path);
        let ok = if p.is_dir() {
            std::fs::remove_dir(p).is_ok()
        } else {
            std::fs::remove_file(p).is_ok()
        };
        ret_bool(ok)
    });
    vm.register_native(
        FS,
        "rename",
        "(Ljava/lang/String;Ljava/lang/String;)Z",
        |vm, a| {
            let (from, to) = (path_arg(vm, a, 0)?, path_arg(vm, a, 1)?);
            ret_bool(std::fs::rename(from, to).is_ok())
        },
    );
    vm.register_native(FS, "mkdir", "(Ljava/lang/String;)Z", |vm, a| {
        ret_bool(std::fs::create_dir(path_arg(vm, a, 0)?).is_ok())
    });
    vm.register_native(FS, "createNew", "(Ljava/lang/String;)Z", |vm, a| {
        let path = path_arg(vm, a, 0)?;
        match std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
        {
            Ok(_) => ret_bool(true),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => ret_bool(false),
            Err(e) => Err(io_exception(files::reason(&e))),
        }
    });
    vm.register_native(
        FS,
        "canonicalize",
        "(Ljava/lang/String;)Ljava/lang/String;",
        canonicalize,
    );
    vm.register_native(FS, "setReadOnly", "(Ljava/lang/String;)Z", |vm, a| {
        let path = path_arg(vm, a, 0)?;
        let ok = std::fs::metadata(&path).and_then(|m| {
            let mut perms = m.permissions();
            perms.set_readonly(true);
            std::fs::set_permissions(&path, perms)
        });
        ret_bool(ok.is_ok())
    });
    vm.register_native(FS, "tempDir", "()Ljava/lang/String;", |vm, _| {
        let dir = std::env::temp_dir().display().to_string();
        ret_ref(Some(vm.new_string(&dir)?))
    });
}

fn attributes(path: &str) -> i32 {
    let p = Path::new(path);
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

fn modified_millis(path: &str) -> i64 {
    std::fs::metadata(path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

fn set_modified(path: &str, millis: i64) -> bool {
    let Ok(ms) = u64::try_from(millis) else {
        return false;
    };
    let when = std::time::UNIX_EPOCH + std::time::Duration::from_millis(ms);
    std::fs::File::options()
        .write(true)
        .open(path)
        .or_else(|_| std::fs::File::open(path))
        .and_then(|f| f.set_modified(when))
        .is_ok()
}

fn list(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let path = path_arg(vm, args, 0)?;
    let Ok(entries) = std::fs::read_dir(&path) else {
        return ret_ref(None);
    };
    let mut names: Vec<String> = entries
        .filter_map(|e| e.ok())
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .collect();
    names.sort();
    let len = i32::try_from(names.len()).map_err(|_| VmError::OutOfMemory)?;
    let array = vm.new_array_of("Ljava/lang/String;", len)?;
    for (i, n) in names.iter().enumerate() {
        let s: Ref = vm.new_string(n)?;
        vm.heap
            .array_mut(array)?
            .store(i, Value::Ref(Some(s)), false)?;
    }
    ret_ref(Some(array))
}

/// Absolute, with `.` and `..` resolved and links followed for the part
/// that exists; the rest is appended by name, as `File.getCanonicalPath`.
fn canonicalize(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let path = path_arg(vm, args, 0)?;
    let absolute = std::path::absolute(&path).map_err(|e| io_exception(files::reason(&e)))?;
    let mut existing = absolute.clone();
    let mut rest = Vec::new();
    while !existing.exists() {
        match (
            existing.file_name().map(|n| n.to_owned()),
            existing.parent().map(Path::to_path_buf),
        ) {
            (Some(name), Some(parent)) => {
                rest.push(name);
                existing = parent;
            }
            _ => break,
        }
    }
    let mut out = std::fs::canonicalize(&existing).unwrap_or(existing);
    for name in rest.into_iter().rev() {
        out.push(name);
    }
    let mut text = out.display().to_string();
    // Windows' canonical form has a `\\?\` prefix Java does not show.
    if let Some(stripped) = text.strip_prefix(r"\\?\") {
        text = stripped.to_owned();
    }
    ret_ref(Some(vm.new_string(&text)?))
}
