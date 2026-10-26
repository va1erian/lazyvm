//! `-jar`: the manifest's `Main-Class` and `Class-Path` (JAR File Specification).

use std::path::{Path, PathBuf};

use classfile::jar::{Jar, manifest_attributes};

/// What `-jar app.jar` runs: the main class and the application class path.
pub struct JarLaunch {
    pub main_class: String,
    /// The jar itself; its `Class-Path` is expanded by `vm::ClassPath`.
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
    // The jar's Class-Path is followed by the class path itself
    // (`vm::ClassPath::open`), as for any jar given with -cp.
    let classpath = vec![jar.to_path_buf()];
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
