//! Where class files and resources come from: the boot and application
//! class paths, each a list of directories and jars.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use classfile::jar::Jar;

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
    /// skipped, as `java` skips them.
    pub fn open(boot: &[PathBuf], app: &[PathBuf]) -> ClassPath {
        ClassPath {
            boot: open_all(boot),
            app: open_all(app),
        }
    }

    /// The class file of `name` (internal form, no `.class`).
    pub fn find_class(&self, name: &str) -> Option<Found> {
        is_safe_name(name)
            .then(|| self.find(&format!("{name}.class")))
            .flatten()
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

/// Resolves `.` and `..`; `None` if `..` would climb above the root.
fn normalize(name: &str) -> Option<String> {
    let mut parts: Vec<&str> = Vec::new();
    for seg in name.split('/') {
        match seg {
            "." => {}
            ".." => {
                parts.pop()?;
            }
            s => parts.push(s),
        }
    }
    Some(parts.join("/"))
}

fn open_all(paths: &[PathBuf]) -> Vec<Entry> {
    let mut out = Vec::new();
    for p in paths {
        if p.is_dir() {
            out.push(Entry::Dir(p.clone()));
        } else if let Some(jar) = std::fs::read(p).ok().and_then(|d| Jar::open(d).ok()) {
            out.push(Entry::Jar(Arc::new(jar)));
        }
    }
    out
}

/// A file's bytes, or nothing for a directory (Java gives an empty stream).
fn read_resource(dir: &Path, rel: &str) -> Option<Vec<u8>> {
    let path = dir.join(rel);
    if path.is_dir() {
        return Some(Vec::new());
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
