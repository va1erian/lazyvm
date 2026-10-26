//! `-jar`: the manifest's `Main-Class` and `Class-Path` (JAR File Specification).

use std::path::{Path, PathBuf};

use classfile::jar::{Jar, manifest_attributes};

/// What `-jar app.jar` runs: the main class and the application class path.
pub struct JarLaunch {
    pub main_class: String,
    pub classpath: Vec<PathBuf>,
}

/// Reads `jar`'s manifest. Errors carry `java`'s wording, ready to print.
pub fn resolve(jar: &Path) -> Result<JarLaunch, String> {
    let shown = jar.display();
    let corrupt = || format!("Error: Invalid or corrupt jarfile {shown}");
    let data = std::fs::read(jar).map_err(|_| format!("Error: Unable to access jarfile {shown}"))?;
    let archive = Jar::open(data).map_err(|_| corrupt())?;
    let manifest = archive.read("META-INF/MANIFEST.MF").map_err(|_| corrupt())?;
    let attrs = manifest
        .map(|m| manifest_attributes(&String::from_utf8_lossy(&m)))
        .unwrap_or_default();
    let get = |key: &str| attrs.iter().find(|(k, _)| k.eq_ignore_ascii_case(key)).map(|(_, v)| v.trim());
    let main_class = get("Main-Class")
        .filter(|m| !m.is_empty())
        .ok_or_else(|| format!("no main manifest attribute, in {shown}"))?
        .to_owned();
    let mut classpath = vec![jar.to_path_buf()];
    // Class-Path entries are relative URLs, resolved against the jar's folder.
    let base = jar.parent().unwrap_or(Path::new("."));
    for entry in get("Class-Path").unwrap_or("").split_whitespace() {
        if !entry.contains(':') {
            classpath.push(base.join(entry.replace("%20", " ")));
        }
    }
    Ok(JarLaunch { main_class, classpath })
}
