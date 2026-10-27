//! `lazy.internal.FileSystem.canonicalize`: the canonical form of an
//! absolute path, as `File.getCanonicalPath` shows it (docs/design/files.md).
//!
//! The path is worked on as Java's UTF-16 units: `.` and `..` are resolved
//! by name, the longest part that exists is resolved by the OS (links,
//! on-disk case), and the rest is appended as written. Observed on Windows:
//! the last name loses its trailing dots and spaces (and goes if nothing is
//! left, the separator staying), and a name ending in a dot or a space is
//! not looked up, since the OS would read it as another name.

use std::path::MAIN_SEPARATOR;

use vm::{Value, Vm, VmError, VmResult};

use crate::{arg, ret_ref};

const SEP: u16 = MAIN_SEPARATOR as u16;
const WINDOWS: bool = cfg!(windows);

pub(crate) fn canonicalize(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let r = arg(args, 0)?.reference()?.ok_or_else(VmError::npe)?;
    let path = vm.string_utf16(r)?;
    let out = canonical(&path);
    ret_ref(Some(vm.new_string_utf16(&out)?))
}

fn canonical(path: &[u16]) -> Vec<u16> {
    let root_len = root_length(path);
    let root = &path[..root_len];
    let mut names: Vec<&[u16]> = Vec::new();
    for n in path[root_len..]
        .split(|&c| c == SEP)
        .filter(|n| !n.is_empty())
    {
        if n == units(".").as_slice() {
            continue;
        }
        if n == units("..").as_slice() {
            names.pop();
            continue;
        }
        names.push(n);
    }
    let mut trailing = path.len() > root_len && path.last() == Some(&SEP);
    let trimmed;
    if let Some(last) = names.last().copied().filter(|_| WINDOWS) {
        names.pop();
        let keep = last.len()
            - last
                .iter()
                .rev()
                .take_while(|&&c| c == u16::from(b'.') || c == u16::from(b' '))
                .count();
        trimmed = last[..keep].to_vec();
        if trimmed.is_empty() {
            trailing = true;
        } else {
            names.push(&trimmed);
        }
    }
    if let Some(device) = names.last().filter(|n| WINDOWS && is_device(n)) {
        // Observed: NUL stands for the device wherever it is.
        let mut out = units(r"\\.\");
        out.extend_from_slice(device);
        return out;
    }
    let mut k = names.len();
    while k > 0 && !(lookable(names[k - 1]) && exists(&join(root, &names[..k]))) {
        k -= 1;
    }
    let base = join(root, &names[..k]);
    let mut out = match std::fs::canonicalize(vm::wtf::from_units(&base)) {
        Ok(c) => without_verbatim(vm::wtf::to_units(c.as_os_str())),
        Err(_) => base,
    };
    for n in &names[k..] {
        if out.last() != Some(&SEP) {
            out.push(SEP);
        }
        out.extend_from_slice(n);
    }
    if trailing && out.last() != Some(&SEP) {
        out.push(SEP);
    }
    out
}

/// `C:\`, `\\host\share\` (Windows) or `/`: the part `..` cannot leave.
fn root_length(path: &[u16]) -> usize {
    if !WINDOWS {
        return usize::from(path.first() == Some(&SEP));
    }
    if path.starts_with(&[SEP, SEP]) {
        // Past the separator after the share, or the whole path.
        let mut seps = 0;
        for (i, &c) in path.iter().enumerate().skip(2) {
            if c == SEP {
                seps += 1;
                if seps == 2 {
                    return i + 1;
                }
            }
        }
        return path.len();
    }
    path.len().min(3)
}

/// Observed on Windows 11: of the DOS device names only NUL (any case)
/// still names its device inside a directory (`dir\con` is a file name).
fn is_device(name: &[u16]) -> bool {
    String::from_utf16_lossy(name).eq_ignore_ascii_case("NUL")
}

fn lookable(name: &[u16]) -> bool {
    !WINDOWS || !matches!(name.last(), Some(&c) if c == u16::from(b'.') || c == u16::from(b' '))
}

fn exists(units: &[u16]) -> bool {
    std::path::Path::new(&vm::wtf::from_units(units)).exists()
}

fn join(root: &[u16], names: &[&[u16]]) -> Vec<u16> {
    let mut out = root.to_vec();
    for n in names {
        if out.last().is_some_and(|&c| c != SEP) {
            out.push(SEP);
        }
        out.extend_from_slice(n);
    }
    out
}

/// Windows' canonical form has a `\\?\` prefix Java does not show, `\\?\UNC\` for a share.
fn without_verbatim(mut text: Vec<u16>) -> Vec<u16> {
    if text.starts_with(&units(r"\\?\UNC\")) {
        text.splice(..8, units(r"\\"));
    } else if text.starts_with(&units(r"\\?\")) {
        text.drain(..4);
    }
    text
}

fn units(s: &str) -> Vec<u16> {
    s.encode_utf16().collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text(u: &[u16]) -> String {
        String::from_utf16_lossy(u)
    }

    #[test]
    fn names_resolve_and_missing_rest_is_kept() {
        let root = std::env::temp_dir();
        let base = text(&without_verbatim(vm::wtf::to_units(
            std::fs::canonicalize(&root).expect("temp dir").as_os_str(),
        )));
        let sep = MAIN_SEPARATOR;
        let input = format!("{base}{sep}nope{sep}.{sep}x{sep}..{sep}y");
        assert_eq!(
            text(&canonical(&units(&input))),
            format!("{base}{sep}nope{sep}y")
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_trims_the_last_name_only() {
        assert_eq!(text(&canonical(&units(r"C:\nope\ \x. "))), r"C:\nope\ \x");
        assert_eq!(text(&canonical(&units(r"C:\nope\..."))), r"C:\nope\");
        assert_eq!(text(&canonical(&units(r"C:\nope\b:x"))), r"C:\nope\b:x");
    }
}
