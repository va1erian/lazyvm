//! `siestac`: the Siesta compiler's command line (docs/siesta.md section 9).
//!
//! ```text
//! siestac [-cp path] [-d out] files...      compile to a directory
//! siestac run [-cp path] file... [-- args]  compile to a temporary directory, then run lazyvm
//! siestac check [-cp path] files...         diagnostics only
//! ```

use std::path::{Path, PathBuf};
use std::process::ExitCode;

use siesta::classpath::ClassPath;
use siesta::compile::{ClassOut, compile};
use siesta::{Rendered, Source};

/// Nesting is bounded in the parser; this stack keeps the checker's walk
/// over the deepest allowed tree well inside it.
const STACK: usize = 64 << 20;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match std::thread::Builder::new()
        .stack_size(STACK)
        .spawn(move || run(args))
    {
        Ok(h) => h.join().unwrap_or(ExitCode::from(3)),
        Err(e) => {
            eprintln!("siestac: {e}");
            ExitCode::from(3)
        }
    }
}

struct Opts {
    mode: Mode,
    classpath: Vec<PathBuf>,
    out: Option<PathBuf>,
    files: Vec<String>,
    program_args: Vec<String>,
}

#[derive(PartialEq)]
enum Mode {
    Build,
    Run,
    Check,
}

fn usage() -> ExitCode {
    eprintln!("usage: siestac [run | check] [-cp path] [-d dir] files... [-- program arguments]");
    ExitCode::from(2)
}

fn parse_opts(args: Vec<String>) -> Option<Opts> {
    let mut o = Opts {
        mode: Mode::Build,
        classpath: Vec::new(),
        out: None,
        files: Vec::new(),
        program_args: Vec::new(),
    };
    let mut it = args.into_iter().peekable();
    match it.peek().map(String::as_str) {
        Some("run") => {
            o.mode = Mode::Run;
            it.next();
        }
        Some("check") => {
            o.mode = Mode::Check;
            it.next();
        }
        _ => {}
    }
    while let Some(a) = it.next() {
        match a.as_str() {
            "-cp" | "-classpath" => o.classpath.extend(std::env::split_paths(&it.next()?)),
            "-d" => o.out = Some(PathBuf::from(it.next()?)),
            "--" => {
                o.program_args = it.by_ref().collect();
            }
            f if f.starts_with('-') => return None,
            f => o.files.push(f.to_string()),
        }
    }
    (!o.files.is_empty()).then_some(o)
}

fn run(args: Vec<String>) -> ExitCode {
    let Some(mut o) = parse_opts(args) else {
        return usage();
    };
    if o.classpath.is_empty() {
        o.classpath = default_classpath();
    }
    let mut sources = Vec::new();
    for path in &o.files {
        match std::fs::read_to_string(path) {
            Ok(text) => sources.push(Source {
                name: path.clone(),
                text,
            }),
            Err(e) => {
                eprintln!("{path}: {e}");
                return ExitCode::from(2);
            }
        }
    }
    let cp = match ClassPath::from_paths(&o.classpath) {
        Ok(cp) => cp,
        Err(e) => {
            eprintln!("siestac: {e}");
            return ExitCode::from(2);
        }
    };
    let classes = match compile(&sources, cp) {
        Ok(c) => c,
        Err(errors) => {
            for d in &errors {
                eprintln!(
                    "{}",
                    Rendered {
                        diag: d,
                        sources: &sources
                    }
                );
            }
            eprintln!(
                "{} error{}",
                errors.len(),
                if errors.len() == 1 { "" } else { "s" }
            );
            return ExitCode::from(1);
        }
    };
    match o.mode {
        Mode::Check => ExitCode::SUCCESS,
        Mode::Build => write_or_fail(&classes, o.out.as_deref().unwrap_or(Path::new("."))),
        Mode::Run => run_program(&o, &classes, &sources),
    }
}

fn write_or_fail(classes: &[ClassOut], dir: &Path) -> ExitCode {
    for c in classes {
        let path = dir.join(format!("{}.class", c.name));
        let written = path
            .parent()
            .map_or(Ok(()), std::fs::create_dir_all)
            .and_then(|_| std::fs::write(&path, &c.bytes));
        if let Err(e) = written {
            eprintln!("{}: {e}", path.display());
            return ExitCode::from(2);
        }
    }
    ExitCode::SUCCESS
}

fn run_program(o: &Opts, classes: &[ClassOut], sources: &[Source]) -> ExitCode {
    let dir = std::env::temp_dir().join(format!("siesta-{}", std::process::id()));
    let code = write_or_fail(classes, &dir);
    if code != ExitCode::SUCCESS {
        return code;
    }
    let main_class = main_class(sources, classes);
    let vm = sibling("lazyvm");
    let mut cp = std::env::join_paths(
        std::iter::once(dir.clone()).chain(
            o.classpath
                .iter()
                .filter(|p| !p.ends_with("lazyrt.jar"))
                .cloned(),
        ),
    )
    .unwrap_or_default();
    if cp.is_empty() {
        cp = dir.clone().into_os_string();
    }
    let status = std::process::Command::new(&vm)
        .arg("-cp")
        .arg(&cp)
        .arg(&main_class)
        .args(&o.program_args)
        .status();
    let _ = std::fs::remove_dir_all(&dir);
    match status {
        Ok(s) => ExitCode::from(s.code().and_then(|c| u8::try_from(c).ok()).unwrap_or(1)),
        Err(e) => {
            eprintln!("siestac: cannot run {}: {e}", vm.display());
            ExitCode::from(2)
        }
    }
}

/// The first file's top-level class, which holds `main`.
fn main_class(sources: &[Source], classes: &[ClassOut]) -> String {
    let stem = siesta::declare::file_class_name(&sources[0].name);
    classes
        .iter()
        .find(|c| c.name == stem || c.name.ends_with(&format!("/{stem}")))
        .map_or(stem, |c| c.name.replace('/', "."))
}

fn sibling(name: &str) -> PathBuf {
    let exe = format!("{name}{}", std::env::consts::EXE_SUFFIX);
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.join(&exe)))
        .filter(|p| p.is_file())
        .unwrap_or_else(|| PathBuf::from(exe))
}

/// `lazyrt.jar` beside `siestac`, or the repository's `dist/lazyrt.jar` when
/// run from `target/`.
fn default_classpath() -> Vec<PathBuf> {
    let Some(dir) = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(Path::to_path_buf))
    else {
        return Vec::new();
    };
    [
        dir.join("lazyrt.jar"),
        dir.join("../../dist/lazyrt.jar"),
        dir.join("../../../dist/lazyrt.jar"),
    ]
    .into_iter()
    .filter(|p| p.is_file())
    .take(1)
    .collect()
}
