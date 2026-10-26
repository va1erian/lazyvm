//! `-jar`: the manifest's `Main-Class` and `Class-Path` (JAR File Specification).

use std::path::{Path, PathBuf};

use classfile::jar::{Jar, manifest_attributes};

/// What `-jar app.jar` runs: the main class and the application class path.
pub struct JarLaunch {
    pub main_class: String,
    /// The jar, then every jar its `Class-Path` reaches, transitively.
    pub classpath: Vec<PathBuf>,
}

/// Reads `jar`'s manifest. Errors carry `java`'s wording, ready to print.
pub fn resolve(jar: &Path) -> Result<JarLaunch, String> {
    let shown = jar.display();
    let corrupt = || format!("Error: Invalid or corrupt jarfile {shown}");
    let data =
        std::fs::read(jar).map_err(|_| format!("Error: Unable to access jarfile {shown}"))?;
    let archive = Jar::open(data).map_err(|_| corrupt())?;
    let attrs = attributes(&archive).ok_or_else(corrupt)?;
    let main_class = get(&attrs, "Main-Class")
        .filter(|m| !m.is_empty())
        .ok_or_else(|| format!("no main manifest attribute, in {shown}"))?
        .to_owned();
    let mut classpath = vec![jar.to_path_buf()];
    let mut i = 0;
    // Breadth-first over Class-Path, each jar once; a library that cannot
    // be read is skipped, as `java` skips bad class path entries.
    while let Some(current) = classpath.get(i).cloned() {
        i += 1;
        let current_attrs = if i == 1 {
            Some(attrs.clone())
        } else {
            std::fs::read(&current)
                .ok()
                .and_then(|d| Jar::open(d).ok())
                .and_then(|j| attributes(&j))
        };
        let base = current.parent().unwrap_or(Path::new(".")).to_path_buf();
        let entries = current_attrs
            .as_deref()
            .and_then(|a| get(a, "Class-Path"))
            .unwrap_or("")
            .to_owned();
        for entry in entries.split_whitespace() {
            // Relative URLs only; `file:` and other schemes are not followed.
            if entry.contains(':') {
                continue;
            }
            let path = base.join(entry.replace("%20", " "));
            if !classpath.contains(&path) {
                classpath.push(path);
            }
        }
    }
    Ok(JarLaunch {
        main_class,
        classpath,
    })
}

/// The manifest's main attributes; an empty list without a manifest, `None`
/// when the manifest is unreadable or malformed.
fn attributes(jar: &Jar) -> Option<Vec<(String, String)>> {
    match jar.read("META-INF/MANIFEST.MF").ok()? {
        Some(m) => manifest_attributes(&String::from_utf8_lossy(&m)).ok(),
        None => Some(Vec::new()),
    }
}

fn get<'a>(attrs: &'a [(String, String)], key: &str) -> Option<&'a str> {
    attrs
        .iter()
        .find(|(k, _)| k.eq_ignore_ascii_case(key))
        .map(|(_, v)| v.trim())
}
