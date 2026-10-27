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

/// The OS's text for an error as Java 8 puts it in a FileSystemException
/// reason: without Rust's "(os error N)", keeping the final period.
pub(crate) fn os_text(e: &std::io::Error) -> String {
    let text = e.to_string();
    let mut text = match text.rfind(" (os error ") {
        Some(i) => text[..i].to_owned(),
        None => text,
    };
    // Windows' message texts end in a line break, which Java 8 keeps.
    if cfg!(windows) && e.raw_os_error().is_some() {
        text.push_str("\r\n");
    }
    text
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
    let m = int(args, 1)?;
    // A public class: a bad mode is the caller's error, not the VM's.
    // Bit 4 of the mode: let others delete the file while it is open.
    let shared_delete = m & 0x10 != 0;
    let mode = Mode::from_int(m & !0x10).ok_or_else(|| {
        VmError::exception(
            "java/lang/IllegalArgumentException",
            format!("bad open mode {m}"),
        )
    })?;
    match files::open(&path, mode, shared_delete) {
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
    vm.register_native(FS, "list", "(Ljava/lang/String;)[Ljava/lang/String;", list);
    vm.register_native(FS, "delete", "(Ljava/lang/String;)V", delete);
    vm.register_native(
        FS,
        "rename",
        "(Ljava/lang/String;Ljava/lang/String;)Z",
        |vm, a| {
            let (from, to) = (path_arg(vm, a, 0)?, path_arg(vm, a, 1)?);
            ret_bool(std::fs::rename(from, to).is_ok())
        },
    );
    vm.register_native(
        FS,
        "move",
        "(Ljava/lang/String;Ljava/lang/String;)Z",
        |vm, a| {
            let (from, to) = (path_arg(vm, a, 0)?, path_arg(vm, a, 1)?);
            match std::fs::rename(from, to) {
                Ok(()) => ret_bool(true),
                // Another volume: the caller copies instead.
                Err(e) if e.kind() == std::io::ErrorKind::CrossesDevices => ret_bool(false),
                Err(e) => Err(io_exception(os_text(&e))),
            }
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
            // Windows reports a directory in the way as access denied: it is "exists".
            Err(e)
                if e.kind() == std::io::ErrorKind::AlreadyExists || Path::new(&path).exists() =>
            {
                ret_bool(false)
            }
            Err(e) => Err(io_exception(files::reason(&e))),
        }
    });
    vm.register_native(
        FS,
        "canonicalize",
        "(Ljava/lang/String;)Ljava/lang/String;",
        canonicalize,
    );
    vm.register_native(FS, "tempDir", "()Ljava/lang/String;", |vm, _| {
        let dir = std::env::temp_dir().display().to_string();
        ret_ref(Some(vm.new_string(&dir)?))
    });
}

/// Deletes a file or an empty directory. Failures are the java.nio.file
/// exception Files.delete throws: no such file, access denied, not empty,
/// or an IOException with the OS's text.
fn delete(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let path = path_arg(vm, args, 0)?;
    let p = Path::new(&path);
    let result = std::fs::symlink_metadata(p).and_then(|m| {
        if m.is_dir() {
            std::fs::remove_dir(p)
        } else {
            std::fs::remove_file(p)
        }
    });
    let Err(e) = result else {
        return ret_void();
    };
    let class = match e.kind() {
        std::io::ErrorKind::NotFound => "java/nio/file/NoSuchFileException",
        std::io::ErrorKind::PermissionDenied => "java/nio/file/AccessDeniedException",
        std::io::ErrorKind::DirectoryNotEmpty => "java/nio/file/DirectoryNotEmptyException",
        _ => return Err(io_exception(os_text(&e))),
    };
    Err(VmError::exception(class, path))
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
    // Windows' canonical form has a `\\?\` prefix Java does not show, `\\?\UNC\` for a share.
    if let Some(share) = text.strip_prefix(r"\\?\UNC\") {
        text = format!(r"\\{share}");
    } else if let Some(stripped) = text.strip_prefix(r"\\?\") {
        text = stripped.to_owned();
    }
    // Observed: a separator the absolute form ends with stays (`dir\...` is `Dir\`).
    let sep = std::path::MAIN_SEPARATOR;
    if absolute.as_os_str().to_string_lossy().ends_with(sep) && !text.ends_with(sep) {
        text.push(sep);
    }
    ret_ref(Some(vm.new_string(&text)?))
}
