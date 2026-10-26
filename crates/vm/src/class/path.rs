//! Where class files come from: the boot and application class paths.

use std::path::{Path, PathBuf};

/// One class path entry. Jars arrive in S3.
#[derive(Clone, Debug)]
pub enum Entry {
    Dir(PathBuf),
}

/// The boot class path (`lazyrt`) is searched before the application path,
/// so an application can never replace a `java.*` class.
#[derive(Clone, Debug, Default)]
pub struct ClassPath {
    pub boot: Vec<Entry>,
    pub app: Vec<Entry>,
}

impl ClassPath {
    pub fn from_strings(boot: &[PathBuf], app: &[PathBuf]) -> ClassPath {
        ClassPath {
            boot: boot.iter().map(|p| Entry::Dir(p.clone())).collect(),
            app: app.iter().map(|p| Entry::Dir(p.clone())).collect(),
        }
    }

    /// The bytes of `name` (internal form, no `.class`), or `None`.
    pub fn find(&self, name: &str) -> Option<Vec<u8>> {
        if !is_safe_name(name) {
            return None;
        }
        let rel = format!("{name}.class");
        self.boot.iter().chain(&self.app).find_map(|e| match e {
            Entry::Dir(dir) => read_in(dir, &rel),
        })
    }
}

fn read_in(dir: &Path, rel: &str) -> Option<Vec<u8>> {
    std::fs::read(dir.join(rel)).ok()
}

/// Rejects names that could escape a class path directory or are not class
/// names at all: empty segments, `.`/`..`, backslashes, drive letters, NUL.
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
    }
}
