//! Where class files and resources come from: the boot and application
//! class paths, each a list of directories and jars.

use std::collections::HashSet;
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;

use classfile::jar::{Jar, manifest_attributes};

/// The most entries the application path may grow to through `Class-Path`.
const MAX_ENTRIES: usize = 4096;

#[derive(Clone, Debug)]
pub enum Entry {
    Dir(PathBuf),
    Jar(Arc<Jar>),
}

/// The boot class path (`lazyrt`) is searched before the application path,
/// so an application can never replace a `java.*` class.
#[derive(Clone, Debug, Default)]
pub struct ClassPath {
    pub boot: Vec<Entry>,
    pub app: Vec<Entry>,
}

/// Bytes found on the class path, and whether they came from the boot path.
pub struct Found {
    pub bytes: Vec<u8>,
    pub boot: bool,
}

impl ClassPath {
    /// Builds both paths. Missing, unreadable and corrupt entries are
    /// skipped, as `java` skips them. Application jars bring in the jars
    /// their manifest's `Class-Path` names, transitively, each once.
    pub fn open(boot: &[PathBuf], app: &[PathBuf]) -> ClassPath {
        ClassPath {
            boot: open_all(boot, false),
            app: open_all(app, true),
        }
    }

    /// The class file of `name` (internal form, no `.class`).
    pub fn find_class(&self, name: &str) -> Option<Found> {
        is_safe_name(name)
            .then(|| self.find(&format!("{name}.class")))
            .flatten()
    }

    /// Every class file's internal name on both paths, sorted and once each.
    pub fn class_names(&self) -> Vec<String> {
        let mut names = Vec::new();
        for entry in self.boot.iter().chain(&self.app) {
            match entry {
                Entry::Dir(dir) => collect_class_names(dir, "", &mut names),
                Entry::Jar(jar) => names.extend(
                    jar.names()
                        .iter()
                        .filter(|n| !n.starts_with("META-INF/"))
                        .filter_map(|n| n.strip_suffix(".class").map(str::to_string)),
                ),
            }
        }
        names.sort_unstable();
        names.dedup();
        names
    }

    /// A resource by its `/`-separated name, without a leading slash, as
    /// Java finds it: in a directory, `.` and `..` are resolved (never above
    /// the root) and a directory reads as empty; in a jar the name must match
    /// an entry exactly, `name/` included for a directory entry.
    pub fn find_resource(&self, name: &str) -> Option<Vec<u8>> {
        let in_dir = normalize(name.trim_end_matches('/')).filter(|n| is_safe_name(n));
        let lookup = |e: &Entry| match e {
            Entry::Dir(dir) => in_dir.as_deref().and_then(|n| read_resource(dir, n)),
            Entry::Jar(jar) => jar
                .read(name)
                .ok()
                .flatten()
                .or_else(|| jar.read(&format!("{name}/")).ok().flatten()),
        };
        self.boot.iter().chain(&self.app).find_map(lookup)
    }

    fn find(&self, rel: &str) -> Option<Found> {
        let lookup = |e: &Entry| match e {
            Entry::Dir(dir) => read_in(dir, rel),
            // A corrupt entry is treated as absent.
            Entry::Jar(jar) => jar.read(rel).ok().flatten(),
        };
        if let Some(bytes) = self.boot.iter().find_map(lookup) {
            return Some(Found { bytes, boot: true });
        }
        self.app
            .iter()
            .find_map(lookup)
            .map(|bytes| Found { bytes, boot: false })
    }
}

fn collect_class_names(dir: &Path, prefix: &str, out: &mut Vec<String>) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        let path = entry.path();
        if path.is_dir() {
            collect_class_names(&path, &format!("{prefix}{name}/"), out);
        } else if let Some(stem) = name.strip_suffix(".class") {
            out.push(format!("{prefix}{stem}"));
        }
    }
}

/// Resolves `.` and `..`; `None` if `..` would climb above the root.
fn normalize(name: &str) -> Option<String> {
    let mut parts: Vec<&str> = Vec::new();
    for seg in name.split('/') {
        match seg {
            // `a//b` is `a/b`, as Java resolves it in a directory.
            "" | "." => {}
            ".." => {
                parts.pop()?;
            }
            s => parts.push(s),
        }
    }
    Some(parts.join("/"))
}

/// Opens the entries in search order. A jar's `Class-Path` jars come right
/// after it, before the next entry (depth first, as Java 8 searches them).
fn open_all(paths: &[PathBuf], follow_manifests: bool) -> Vec<Entry> {
    let mut stack: Vec<PathBuf> = paths.iter().rev().map(|p| lexical(p)).collect();
    let mut seen = HashSet::new();
    let mut pushed = stack.len();
    let mut out = Vec::new();
    while let Some(p) = stack.pop() {
        if !seen.insert(dedup_key(&p)) {
            continue;
        }
        if p.is_dir() {
            out.push(Entry::Dir(p));
            continue;
        }
        let Some(jar) = std::fs::read(&p).ok().and_then(|d| Jar::open(d).ok()) else {
            continue;
        };
        if follow_manifests {
            let base = p.parent().unwrap_or(Path::new("."));
            let mut children: Vec<PathBuf> = manifest_class_path(&jar)
                .iter()
                .map(|e| lexical(&base.join(e)))
                .collect();
            // Past the cap, the leading entries are the ones kept.
            children.truncate(MAX_ENTRIES.saturating_sub(pushed));
            pushed += children.len();
            stack.extend(children.into_iter().rev());
        }
        out.push(Entry::Jar(Arc::new(jar)));
    }
    out
}

/// Windows file names are case-insensitive, so `A.jar` and `a.jar` are one jar.
fn dedup_key(p: &Path) -> String {
    let s = p.to_string_lossy();
    if cfg!(windows) {
        s.to_lowercase()
    } else {
        s.into_owned()
    }
}

/// A jar's `Class-Path` entries: relative URLs only (no scheme), `%20`
/// decoded. A missing or malformed manifest contributes nothing.
fn manifest_class_path(jar: &Jar) -> Vec<String> {
    let Some(text) = jar.read("META-INF/MANIFEST.MF").ok().flatten() else {
        return Vec::new();
    };
    let Ok(attrs) = manifest_attributes(&String::from_utf8_lossy(&text)) else {
        return Vec::new();
    };
    attrs
        .iter()
        .filter(|(k, _)| k.eq_ignore_ascii_case("Class-Path"))
        .flat_map(|(_, v)| v.split_whitespace())
        .filter(|e| !e.contains(':'))
        .map(|e| e.replace("%20", " "))
        .collect()
}

/// `p` with `.` and `..` resolved by name, so one jar reached by two
/// spellings is opened once.
fn lexical(p: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            Component::CurDir => {}
            Component::ParentDir
                if matches!(out.components().next_back(), Some(Component::Normal(_))) =>
            {
                out.pop();
            }
            // `..` at the root stays at the root (`C:..` is not the root:
            // it is the parent of drive C's current directory, so it stays).
            Component::ParentDir
                if matches!(out.components().next_back(), Some(Component::RootDir)) => {}
            other => out.push(other.as_os_str()),
        }
    }
    // Nothing left means the current directory ("." or "a/.."), not an empty path.
    if out.as_os_str().is_empty() {
        out.push(".");
    }
    out
}

/// A file's bytes, or for a directory its entry names, one per line, as
/// Java's directory resources read (sorted here, so the output is stable).
fn read_resource(dir: &Path, rel: &str) -> Option<Vec<u8>> {
    let path = dir.join(rel);
    if path.is_dir() {
        let mut names: Vec<String> = std::fs::read_dir(&path)
            .ok()?
            .filter_map(|e| e.ok())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .collect();
        names.sort();
        return Some(
            names
                .iter()
                .flat_map(|n| format!("{n}\n").into_bytes())
                .collect(),
        );
    }
    read_in(dir, rel)
}

fn read_in(dir: &Path, rel: &str) -> Option<Vec<u8>> {
    let path = dir.join(rel);
    // A directory with the name of the file is not the file.
    path.is_file().then(|| std::fs::read(path).ok()).flatten()
}

/// Rejects names that could escape a class path directory or are not class
/// or resource names at all: empty segments, `.`/`..`, backslashes, drive
/// letters, NUL.
fn is_safe_name(name: &str) -> bool {
    !name.is_empty()
        && name.split('/').all(|seg| {
            !seg.is_empty() && seg != "." && seg != ".." && !seg.contains(['\\', ':', '\0'])
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unsafe_names_are_refused() {
        for bad in [
            "",
            "../etc/passwd",
            "a//b",
            "a/./b",
            "C:/x",
            "a\\b",
            "a\0b",
            "/abs",
        ] {
            assert!(!is_safe_name(bad), "{bad:?}");
        }
        assert!(is_safe_name("java/lang/Object"));
        assert!(is_safe_name("Outer$Inner"));
        assert!(is_safe_name("META-INF/MANIFEST.MF"));
    }

    #[test]
    fn resource_names_resolve_dots_inside_the_root() {
        assert_eq!(normalize("a/../b/./c").as_deref(), Some("b/c"));
        assert_eq!(normalize("../x"), None);
        assert_eq!(normalize("a/../../x"), None);
    }
}
