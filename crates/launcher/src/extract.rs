//! A bundle's directories (`tools/bundle.py --dir`): files an application
//! opens by path, not only as resources. They are unpacked once per build of
//! the executable into a cache directory, which then joins the class path
//! (docs/bundle.md, "Directories").

use std::path::{Path, PathBuf};

use classfile::jar::Jar;

/// The cache directory for `exe`'s files, unpacking them if no earlier run
/// did. `key` names this build of the executable: a new build unpacks anew.
pub fn unpack_dir(files: &Jar, key: u64, exe: &Path) -> Result<PathBuf, String> {
    let stem = exe
        .file_stem()
        .map_or("app".into(), |s| s.to_string_lossy());
    let root = cache_root()
        .ok_or("no per-user cache directory: set LOCALAPPDATA, XDG_CACHE_HOME or HOME")?;
    let dir = root.join(format!("{stem}-{key:016x}"));
    if dir.is_dir() {
        return Ok(dir);
    }
    // Unpacked beside its final place, then renamed: a run never sees half a
    // directory, and two first runs at once both end with a whole one.
    let tmp = root.join(format!(".{stem}-{key:016x}.{}.tmp", std::process::id()));
    let fail = |e: String| format!("cannot unpack into {}: {e}", root.display());
    write_all(files, &tmp).map_err(|e| {
        let _ = std::fs::remove_dir_all(&tmp);
        fail(e)
    })?;
    if let Err(e) = std::fs::rename(&tmp, &dir) {
        let _ = std::fs::remove_dir_all(&tmp);
        if !dir.is_dir() {
            return Err(fail(e.to_string()));
        }
    }
    Ok(dir)
}

/// Per user, where programs keep caches. Never a directory users share, such
/// as the system's temp directory: what is found there goes on the class
/// path, so another user could plant classes in it.
fn cache_root() -> Option<PathBuf> {
    let env = |k: &str| {
        std::env::var_os(k)
            .filter(|v| !v.is_empty())
            .map(PathBuf::from)
    };
    let base = env("LOCALAPPDATA")
        .map(|p| p.join("LazyVM"))
        .or_else(|| env("XDG_CACHE_HOME").map(|p| p.join("lazyvm")))
        .or_else(|| env("HOME").map(|p| p.join(".cache").join("lazyvm")))?;
    Some(base.join("bundles"))
}

fn write_all(files: &Jar, dir: &Path) -> Result<(), String> {
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    for name in files.names() {
        let target = dir.join(safe_path(name).ok_or_else(|| format!("bad entry name {name:?}"))?);
        if name.ends_with('/') {
            std::fs::create_dir_all(&target).map_err(|e| e.to_string())?;
            continue;
        }
        let data = files
            .read(name)
            .map_err(|e| e.to_string())?
            .ok_or_else(|| format!("{name} is missing"))?;
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        std::fs::write(&target, data).map_err(|e| format!("{name}: {e}"))?;
    }
    Ok(())
}

/// `name` as a relative path that stays inside the directory, or `None`.
fn safe_path(name: &str) -> Option<PathBuf> {
    let mut out = PathBuf::new();
    for part in name.trim_end_matches('/').split('/') {
        // Windows drops trailing dots and spaces, and opens devices for
        // their names: such a part would not name the file it says.
        let bad = part.is_empty()
            || part == "."
            || part == ".."
            || part.contains(['\\', ':', '\0'])
            || part.ends_with(['.', ' '])
            || is_device_name(part);
        if bad {
            return None;
        }
        out.push(part);
    }
    Some(out)
}

/// A Windows device name, with or without an extension (`nul.txt`).
fn is_device_name(part: &str) -> bool {
    let stem = part.split('.').next().unwrap_or(part).to_ascii_uppercase();
    let numbered = |prefix: &str| {
        stem.strip_prefix(prefix)
            .is_some_and(|n| n.len() == 1 && n.as_bytes()[0].is_ascii_digit())
    };
    matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL") || numbered("COM") || numbered("LPT")
}

/// FNV-1a: a cheap name for one build's payload, not a security check.
pub fn fnv64(bytes: &[u8]) -> u64 {
    bytes.iter().fold(0xcbf2_9ce4_8422_2325, |h, &b| {
        (h ^ u64::from(b)).wrapping_mul(0x0100_0000_01b3)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_stay_inside() {
        assert_eq!(safe_path("a/b.png"), Some(PathBuf::from("a").join("b.png")));
        assert_eq!(safe_path("a/"), Some(PathBuf::from("a")));
        for bad in [
            "../x",
            "a/../../x",
            "/abs",
            "C:/x",
            "a\\..\\x",
            "a//b",
            "./a",
            "nul.txt",
            "a/CON",
            "com1",
            "dots./x",
            "",
        ] {
            assert_eq!(safe_path(bad), None, "{bad}");
        }
    }
}
