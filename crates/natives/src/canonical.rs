//! Canonical paths: `lazy.internal.FileSystem.canonicalize`
//! (`File.getCanonicalPath`), as docs/design/files.md and nio-file.md PW2
//! describe them, and the path helpers `real_path` shares.
//!
//! Paths are worked on as Java's UTF-16 units. The part of the path that
//! exists is resolved by the OS (links, on-disk case) and the rest is
//! appended as written. On Windows `.` and `..` are applied by name first,
//! as the OS itself does, and names lose trailing dots (and spaces) by the
//! observed rules below. On Unix the OS resolves the existing part as
//! written, so a `..` after a link leaves the link's target, a dangling link
//! is followed to the target it names, and only the missing rest is resolved
//! by name: a path the OS resolves gets the form of the file it reaches, while
//! a `..` after a missing name is applied by name (files.md).

use std::path::MAIN_SEPARATOR;

use vm::{Value, Vm, VmError, VmResult};

use crate::{arg, ret_ref};

pub(crate) const SEP: u16 = MAIN_SEPARATOR as u16;
pub(crate) const WINDOWS: bool = cfg!(windows);
pub(crate) const DOT: u16 = b'.' as u16;
pub(crate) const SPACE: u16 = b' ' as u16;
pub(crate) const COLON: u16 = b':' as u16;
/// Observed: a NUL in an existing directory of a drive is the device in a
/// path of at most 247 units; from 248 on it is an ordinary name.
pub(crate) const DEVICE_PATH_LIMIT: usize = 248;
const SYNTAX: &str = "The filename, directory name, or volume label syntax is incorrect";

pub(crate) fn path_units(vm: &Vm, args: &[Value]) -> VmResult<Vec<u16>> {
    let r = arg(args, 0)?.reference()?.ok_or_else(VmError::npe)?;
    vm.string_utf16(r)
}

/// `canonicalize(String)`: the canonical form of an absolute path.
pub(crate) fn canonicalize(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let path = path_units(vm, args)?;
    let out = if WINDOWS {
        windows_canonical(&path)
    } else {
        Ok(unix_canonical(&path))
    };
    match out {
        Ok(out) => ret_ref(Some(vm.new_string_utf16(&out)?)),
        Err(msg) => Err(VmError::exception("java/io/IOException", msg)),
    }
}

/// Removes a name's trailing dots and spaces, as Windows reads a last name.
pub(crate) fn trim_dots_spaces(name: &mut Vec<u16>) {
    while matches!(name.last(), Some(&DOT | &SPACE)) {
        name.pop();
    }
}

pub(crate) fn dots_and_spaces(name: &[u16]) -> bool {
    name.iter().all(|&c| c == DOT || c == SPACE)
}

/// Whether a path is on a share (`\\host\share\`) rather than a drive.
pub(crate) fn unc(path: &[u16]) -> bool {
    path.starts_with(&[SEP, SEP])
}

/// The names after `root`, `.` and `..` applied, and whether a separator
/// ends the result. On Windows (observed): a name before another loses one
/// final dot, and one ending in two dots is "Bad pathname" when `strict`;
/// the last name loses its trailing dots and spaces, and goes if nothing is
/// left, the separator staying.
pub(crate) fn names(
    path: &[u16],
    root: usize,
    strict: bool,
) -> Result<(Vec<Vec<u16>>, bool), &'static str> {
    let (mut names, mut trailing) = resolved_names(path, root);
    if !WINDOWS {
        return Ok((names, trailing));
    }
    let last = names.len().saturating_sub(1);
    for n in names.iter_mut().take(last) {
        if n.ends_with(&[DOT, DOT]) {
            if strict {
                return Err("Bad pathname");
            }
        } else if n.last() == Some(&DOT) {
            n.pop();
        }
    }
    if let Some(n) = names.last_mut() {
        trim_dots_spaces(n);
        if n.is_empty() {
            names.pop();
            trailing = true;
        }
    }
    Ok((names, trailing))
}

/// The names after `root` with `.` and `..` applied by name, and whether a
/// separator ends the path: the one place either is resolved by name (Unix
/// resolves only the missing rest, after the OS's real base).
pub(crate) fn resolved_names(path: &[u16], root: usize) -> (Vec<Vec<u16>>, bool) {
    let mut names: Vec<Vec<u16>> = Vec::new();
    for n in path[root..].split(|&c| c == SEP).filter(|n| !n.is_empty()) {
        match n {
            [DOT] => {}
            [DOT, DOT] => {
                names.pop();
            }
            _ => names.push(n.to_vec()),
        }
    }
    (names, path.len() > root && path.last() == Some(&SEP))
}

fn windows_canonical(path: &[u16]) -> Result<Vec<u16>, &'static str> {
    let root_len = root_length(path);
    let root = &path[..root_len];
    // Observed: on a drive, a last name NUL as written (trailing dots and
    // spaces aside, before `.` and `..` apply and before any other check)
    // stands for the device wherever it is; on a share it is a name.
    let written = path[root_len..]
        .split(|&c| c == SEP)
        .rfind(|n| !n.is_empty());
    if let Some(mut device) = written.map(<[u16]>::to_vec).filter(|_| !unc(path)) {
        trim_dots_spaces(&mut device);
        if is_device(&device) {
            let mut out = units(r"\\.\");
            out.extend_from_slice(&device[..3]);
            return Ok(out);
        }
    }
    let (names, trailing) = names(path, root_len, true)?;
    // Observed: a name of only dots and spaces is not looked up (the OS
    // would read `. ` as the directory itself), so it and the rest stay.
    let lookable = names
        .iter()
        .position(|n| dots_and_spaces(n))
        .unwrap_or(names.len());
    let k = existing(root, &names[..lookable]);
    // Observed: a ':' in a name the OS looks up (one whose parent is a
    // directory under the parent's name as written, so `d2 \a:b` is not) is
    // a syntax error; below a missing parent it stays as written.
    let colon = |n: &Vec<u16>| n.contains(&COLON);
    if names[..k].iter().any(colon)
        || names
            .get(k)
            .is_some_and(|n| colon(n) && is_dir(&joined(root, &names[..k], true)))
    {
        return Err(SYNTAX);
    }
    let out = resolved(root, &names, k, trailing);
    // Observed: a `\\.\` path keeps the prefix, which reaches the file by name
    // (`\\.\C:\d\NUL` is a file NUL, `C:\d\NUL` the device).
    let dotted = units(r"\\.\");
    if path.starts_with(&dotted) && !unc(&out) {
        return Ok([dotted, out].concat());
    }
    Ok(out)
}

fn unix_canonical(path: &[u16]) -> Vec<u16> {
    unix_canonical_hops(path, 40)
}

/// The existing part resolved by the OS, a dangling link at its end followed
/// (at most `hops` times, as the OS bounds link chains), then the rest by name.
fn unix_canonical_hops(path: &[u16], hops: u32) -> Vec<u16> {
    let root_len = root_length(path);
    let root = &path[..root_len];
    let raw: Vec<Vec<u16>> = path[root_len..]
        .split(|&c| c == SEP)
        .filter(|n| !n.is_empty())
        .map(<[u16]>::to_vec)
        .collect();
    let k = existing(root, &raw);
    let base = os_real(join(root, &raw[..k]));
    let link = (k < raw.len() && hops > 0)
        .then(|| dangling_target(&join(root, &raw[..=k])))
        .flatten();
    if let Some(target) = link {
        let start = if target.first() == Some(&SEP) {
            Vec::new()
        } else {
            base
        };
        let mut next = join(&start, &[target]);
        for n in &raw[k + 1..] {
            next.push(SEP);
            next.extend_from_slice(n);
        }
        return unix_canonical_hops(&next, hops - 1);
    }
    let rest = join(&base, &raw[k..]);
    let (names, _) = resolved_names(&rest, root_len);
    join(root, &names)
}

/// The target of `path` when it is a symbolic link (whose target is missing).
fn dangling_target(path: &[u16]) -> Option<Vec<u16>> {
    let os = vm::wtf::from_units(path);
    let link = std::fs::read_link(&os).ok()?;
    Some(vm::wtf::to_units(link.as_os_str()))
}

/// The OS's resolved form of the first `k` names, then the rest as written.
fn resolved(root: &[u16], names: &[Vec<u16>], k: usize, trailing: bool) -> Vec<u16> {
    let mut out = os_real(join(root, &names[..k]));
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

/// What the OS resolves an existing path to, or the path itself.
fn os_real(base: Vec<u16>) -> Vec<u16> {
    match std::fs::canonicalize(vm::wtf::from_units(&base)) {
        Ok(c) => without_verbatim(vm::wtf::to_units(c.as_os_str())),
        Err(_) => base,
    }
}

/// How many leading names exist. The whole path is tried first (the usual
/// case); else, since a missing directory has no existing descendants, a
/// binary search keeps a deep missing path to a few lookups.
pub(crate) fn existing(root: &[u16], names: &[Vec<u16>]) -> usize {
    if exists(&join(root, names)) {
        return names.len();
    }
    let (mut lo, mut hi) = (0, names.len());
    while lo + 1 < hi {
        let mid = (lo + hi) / 2;
        if exists(&join(root, &names[..mid])) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    lo
}

pub(crate) fn joined(root: &[u16], names: &[Vec<u16>], trailing: bool) -> Vec<u16> {
    let mut out = join(root, names);
    if trailing && out.last() != Some(&SEP) {
        out.push(SEP);
    }
    out
}

/// `C:\`, `\\host\share\` (Windows) or `/`: the part `..` cannot leave.
pub(crate) fn root_length(path: &[u16]) -> usize {
    if !WINDOWS {
        return usize::from(path.first() == Some(&SEP));
    }
    if unc(path) {
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

/// Observed on Windows 11: of the DOS device names only NUL (any case, a
/// ':' after it too) still names its device inside a directory.
pub(crate) fn is_device(name: &[u16]) -> bool {
    let nul = |n: &[u16]| String::from_utf16_lossy(n).eq_ignore_ascii_case("NUL");
    nul(name) || (name.len() == 4 && name[3] == COLON && nul(&name[..3]))
}

fn exists(units: &[u16]) -> bool {
    std::path::Path::new(&vm::wtf::from_units(units)).exists()
}

pub(crate) fn is_dir(units: &[u16]) -> bool {
    std::path::Path::new(&vm::wtf::from_units(units)).is_dir()
}

pub(crate) fn join(root: &[u16], names: &[Vec<u16>]) -> Vec<u16> {
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
pub(crate) fn without_verbatim(mut text: Vec<u16>) -> Vec<u16> {
    if text.starts_with(&units(r"\\?\UNC\")) {
        text.splice(..8, units(r"\\"));
    } else if text.starts_with(&units(r"\\?\")) {
        text.drain(..4);
    }
    text
}

pub(crate) fn units(s: &str) -> Vec<u16> {
    s.encode_utf16().collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text(u: &[u16]) -> String {
        String::from_utf16_lossy(u)
    }

    fn canonical(path: &str) -> Result<String, &'static str> {
        let p = units(path);
        if WINDOWS {
            windows_canonical(&p).map(|u| text(&u))
        } else {
            Ok(text(&unix_canonical(&p)))
        }
    }

    fn temp_base() -> String {
        let real = std::fs::canonicalize(std::env::temp_dir()).expect("temp dir");
        text(&without_verbatim(vm::wtf::to_units(real.as_os_str())))
    }

    #[test]
    fn names_resolve_and_missing_rest_is_kept() {
        let base = temp_base();
        let sep = MAIN_SEPARATOR;
        let input = format!("{base}{sep}nope{sep}.{sep}x{sep}..{sep}y");
        assert_eq!(canonical(&input), Ok(format!("{base}{sep}nope{sep}y")));
    }

    #[cfg(windows)]
    #[test]
    fn windows_trims_every_name() {
        assert_eq!(canonical(r"C:\nope\ \x. "), Ok(r"C:\nope\ \x".into()));
        assert_eq!(canonical(r"C:\nope\..."), Ok(r"C:\nope\".into()));
        assert_eq!(canonical(r"C:\nope.\x"), Ok(r"C:\nope\x".into()));
        assert_eq!(canonical(r"C:\nope .\x"), Ok(r"C:\nope \x".into()));
        assert_eq!(canonical(r"C:\nope. \x"), Ok(r"C:\nope. \x".into()));
        assert_eq!(canonical(r"C:\. .\x"), Ok(r"C:\. \x".into()));
        assert_eq!(canonical(r"C:\nope..\x"), Err("Bad pathname"));
        assert_eq!(canonical(r"C:\nope..\..\x"), Ok(r"C:\x".into()));
    }

    #[cfg(windows)]
    #[test]
    fn windows_colon_needs_a_directory_parent() {
        assert_eq!(canonical(r"C:\nope\b:x"), Ok(r"C:\nope\b:x".into()));
        assert_eq!(canonical(r"C:\b:x"), Err(SYNTAX));
        assert_eq!(canonical(r"C:\Windows\NUL:"), Ok(r"\\.\NUL".into()));
    }

    #[cfg(unix)]
    #[test]
    fn unix_dot_dot_after_a_link_leaves_its_target() {
        let dir = format!("{}/lazyvm-canon-{}", temp_base(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(format!("{dir}/a/b")).expect("dirs");
        std::fs::create_dir_all(format!("{dir}/t")).expect("dirs");
        std::os::unix::fs::symlink(format!("{dir}/a/b"), format!("{dir}/t/link")).expect("link");
        assert_eq!(
            canonical(&format!("{dir}/t/link/../x")),
            Ok(format!("{dir}/a/x"))
        );
        assert_eq!(
            canonical(&format!("{dir}/t/nope/../x")),
            Ok(format!("{dir}/t/x"))
        );
        let _ = std::fs::remove_dir_all(&dir);
    }
}
