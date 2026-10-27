//! Real paths: `lazy.internal.FileSystem.realPath` and `lexical`
//! (`Path.toRealPath`), as docs/design/files.md and nio-file.md PW2 describe
//! them, on the helpers of `canonical`.

use platform::files;
use vm::{Value, Vm, VmError, VmResult};

use crate::canonical::{
    DEVICE_PATH_LIMIT, SEP, WINDOWS, dots_and_spaces, existing, is_device, is_dir, join, joined,
    names, path_units, resolved_names, root_length, trim_dots_spaces, unc, units, without_verbatim,
};
use crate::ret_ref;

/// `realPath(String)`: the OS's real path of an existing file. A missing
/// one is a bare `NoSuchFileException`; another failure an `IOException`
/// with the OS's reason as a FileSystemException shows it.
pub(crate) fn real_path(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let path = path_units(vm, args)?;
    let root = root_length(&path);
    let opened = if WINDOWS {
        let (names, trailing) = opened_names(&path, root);
        let parent = &names[..names.len().saturating_sub(1)];
        let short = path.len() < DEVICE_PATH_LIMIT;
        if !unc(&path)
            && short
            && names.last().is_some_and(|n| is_device(n))
            && is_dir(&join(&path[..root], parent))
        {
            // Observed: NUL in an existing directory of a drive is its own
            // real path; on a share, or in a longer path, it is a name.
            return ret_ref(Some(vm.new_string_utf16(&joined(
                &path[..root],
                &names,
                trailing,
            ))?));
        }
        joined(&path[..root], &names, trailing)
    } else {
        path
    };
    // A DOS device name is looked up as a name, which only the verbatim form
    // does (a long plain path is refused), so a real file of that name (made
    // through `\\?\`) is found. A share refuses even that (access denied);
    // there the name counts if its directory lists it.
    let device = WINDOWS && opened.split(|&c| c == SEP).any(dos_device);
    let target = if device {
        verbatim(&opened)
    } else {
        opened.clone()
    };
    match std::fs::canonicalize(vm::wtf::from_units(&target)) {
        Ok(real) => {
            let real = without_verbatim(vm::wtf::to_units(real.as_os_str()));
            ret_ref(Some(vm.new_string_utf16(&real)?))
        }
        Err(e) if device && unc(&opened) && e.raw_os_error() == Some(ERROR_ACCESS_DENIED) => {
            if listed(&opened) {
                return ret_ref(Some(vm.new_string_utf16(&opened)?));
            }
            Err(VmError::bare("java/nio/file/NoSuchFileException"))
        }
        Err(e) if missing(&e) && !below_file(&opened) => {
            Err(VmError::bare("java/nio/file/NoSuchFileException"))
        }
        Err(e) if missing(&e) => {
            // Observed on Windows: below a file it is the OS's "directory name is invalid".
            const ERROR_DIRECTORY: i32 = 267;
            let e = std::io::Error::from_raw_os_error(ERROR_DIRECTORY);
            Err(VmError::exception(
                "java/io/IOException",
                files::os_text(&e, false),
            ))
        }
        Err(e) => Err(VmError::exception(
            "java/io/IOException",
            files::os_text(&e, false),
        )),
    }
}

const ERROR_ACCESS_DENIED: i32 = 5;

/// Whether the directory of `path` lists its last name (ignoring case, as
/// Windows names do).
fn listed(path: &[u16]) -> bool {
    let Some(cut) = path.iter().rposition(|&c| c == SEP) else {
        return false;
    };
    let name = String::from_utf16_lossy(&path[cut + 1..]);
    let Ok(entries) = std::fs::read_dir(vm::wtf::from_units(&path[..cut])) else {
        return false;
    };
    entries.filter_map(Result::ok).any(|e| {
        String::from_utf16_lossy(&vm::wtf::to_units(&e.file_name())).eq_ignore_ascii_case(&name)
    })
}

/// Whether the longest existing part of a missing path is a file (Windows).
fn below_file(path: &[u16]) -> bool {
    let root = root_length(path);
    let (names, _) = resolved_names(path, root);
    let k = existing(&path[..root], &names);
    WINDOWS && k > 0 && !is_dir(&join(&path[..root], &names[..k]))
}

/// The `\\?\` form of an absolute path, which the OS takes name by name.
fn verbatim(path: &[u16]) -> Vec<u16> {
    let mut out = if unc(path) {
        units(r"\\?\UNC")
    } else {
        units(r"\\?\")
    };
    out.extend_from_slice(if unc(path) { &path[1..] } else { path });
    out
}

/// CON, PRN, AUX, NUL, COM1-9, LPT1-9, CONIN$, CONOUT$, before any
/// extension or trailing spaces, in any case.
fn dos_device(name: &[u16]) -> bool {
    let stem = String::from_utf16_lossy(name).to_ascii_uppercase();
    let stem = stem.split('.').next().unwrap_or("").trim_end();
    let numbered = |p: &str| {
        stem.len() == 4
            && stem.starts_with(p)
            && stem.as_bytes()[3].is_ascii_digit()
            && &stem[3..] != "0"
    };
    matches!(stem, "CON" | "PRN" | "AUX" | "NUL" | "CONIN$" | "CONOUT$")
        || numbered("COM")
        || numbered("LPT")
}

/// Whether the OS says the file is not there. On Windows only "file" and
/// "path not found" are: an unreachable share keeps its own reason.
fn missing(e: &std::io::Error) -> bool {
    if WINDOWS {
        matches!(e.raw_os_error(), Some(2 | 3))
    } else {
        e.kind() == std::io::ErrorKind::NotFound
    }
}

/// Observed: Windows opens a path whose names end in dots or spaces as if
/// they did not (`d2..\in` is `d2\in`), except names of only dots and spaces.
fn opened_names(path: &[u16], root: usize) -> (Vec<Vec<u16>>, bool) {
    let (mut names, trailing) = resolved_names(path, root);
    names
        .iter_mut()
        .filter(|n| !dots_and_spaces(n))
        .for_each(trim_dots_spaces);
    (names, trailing)
}

/// `lexical(String)`: an absolute path as `toRealPath` reports a file it
/// cannot resolve: `.` and `..` applied by name, on Windows the names
/// trimmed as for a canonical form (but a name ending in two dots kept).
pub(crate) fn lexical(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let path = path_units(vm, args)?;
    let root = root_length(&path);
    let out = match names(&path, root, false) {
        Ok((names, trailing)) => joined(&path[..root], &names, trailing),
        Err(_) => path,
    };
    ret_ref(Some(vm.new_string_utf16(&out)?))
}
