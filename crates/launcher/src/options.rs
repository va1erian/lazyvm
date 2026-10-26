//! Command line parsing, compatible with what `java` and IntelliJ pass.

use std::path::PathBuf;

use vm::Options;

pub const USAGE: &str = "usage: lazyvm [options] <main class> [args...]
  -cp, -classpath <path>   application class path (directories)
  -Xbootclasspath:<path>   the class library (default: LAZYVM_BOOTCLASSPATH, or lazyrt next to lazyvm)
  -D<name>=<value>         set a system property
  -Xmx<size>               heap limit, e.g. 256m
  -Xss<size>               stack size, e.g. 1m (sets the frame limit)
  -verbose:class           print each class as it loads
  -Xtrace:interp           print every instruction (slow)
  -version                 print the version";

/// Options that only make sense for HotSpot or the IDE; accepted and ignored.
const IGNORED_PREFIXES: &[&str] = &[
    "-javaagent:",
    "-XX:",
    "-Xshare",
    "-ea",
    "-da",
    "-enableassertions",
    "-disableassertions",
    "-Didea.",
    "-Xverify",
    "-server",
    "-client",
    "-Xms",
    "-Dsun.",
    "-Dfile.encoding",
    "-Dstdout.encoding",
    "-Dstderr.encoding",
];

#[derive(Debug)]
pub enum Command {
    Run(Launch),
    Version,
    Help,
}

#[derive(Debug)]
pub struct Launch {
    pub classpath: Vec<PathBuf>,
    pub boot_classpath: Vec<PathBuf>,
    pub properties: Vec<(String, String)>,
    pub options: Options,
    pub main_class: String,
    pub args: Vec<String>,
}

pub fn parse(args: &[String]) -> Result<Command, String> {
    let mut classpath: Option<Vec<PathBuf>> = None;
    let mut boot: Option<Vec<PathBuf>> = None;
    let mut properties = Vec::new();
    let mut options = Options::default();
    let mut i = 0;
    while i < args.len() {
        let a = args[i].as_str();
        if !a.starts_with('-') {
            let cp = classpath.unwrap_or_else(default_classpath);
            let boot = boot
                .or_else(default_boot)
                .ok_or("cannot find lazyrt: pass -Xbootclasspath:<dir>")?;
            return Ok(Command::Run(Launch {
                classpath: cp,
                boot_classpath: boot,
                properties,
                options,
                main_class: a.to_owned(),
                args: args[i + 1..].to_vec(),
            }));
        }
        match a {
            "-cp" | "-classpath" | "--class-path" => {
                i += 1;
                let v = args.get(i).ok_or(format!("{a} needs a value"))?;
                classpath = Some(split_path(v));
            }
            "-version" | "--version" | "-showversion" => return Ok(Command::Version),
            "-help" | "-h" | "--help" | "-?" => return Ok(Command::Help),
            "-verbose:class" => options.verbose_class = true,
            "-Xtrace:interp" => options.trace_interp = true,
            "-jar" => return Err("-jar arrives in stage S3".into()),
            _ if a.starts_with("-Xbootclasspath:") => {
                boot = Some(split_path(&a["-Xbootclasspath:".len()..]))
            }
            _ if IGNORED_PREFIXES.iter().any(|p| a.starts_with(p)) => {}
            _ if a.starts_with("-D") => {
                let (k, v) = a[2..].split_once('=').unwrap_or((&a[2..], ""));
                properties.push((k.to_owned(), v.to_owned()));
            }
            _ if a.starts_with("-Xmx") => options.heap_limit = parse_size(&a[4..])?,
            _ if a.starts_with("-Xss") => {
                // About 128 bytes of native stack per interpreted frame in HotSpot terms.
                options.max_frames = (parse_size(&a[4..])? / 128).clamp(256, 1 << 20);
            }
            _ if a.starts_with("-verbose") => {}
            _ => return Err(format!("unknown option {a}")),
        }
        i += 1;
    }
    Err("no main class given".into())
}

fn split_path(s: &str) -> Vec<PathBuf> {
    std::env::split_paths(s)
        .filter(|p| !p.as_os_str().is_empty())
        .collect()
}

fn default_classpath() -> Vec<PathBuf> {
    match std::env::var_os("CLASSPATH") {
        Some(cp) => std::env::split_paths(&cp).collect(),
        None => vec![PathBuf::from(".")],
    }
}

/// `LAZYVM_BOOTCLASSPATH`, else `lib/lazyrt` beside or above the binary.
fn default_boot() -> Option<Vec<PathBuf>> {
    if let Some(p) = std::env::var_os("LAZYVM_BOOTCLASSPATH") {
        return Some(std::env::split_paths(&p).collect());
    }
    let exe = std::env::current_exe().ok()?;
    let dir = exe.parent()?;
    [dir.join("lazyrt"), dir.join("../lib/lazyrt")]
        .into_iter()
        .find(|p| p.is_dir())
        .map(|p| vec![p])
}

/// `256m`, `1g`, `65536`, `512k`.
fn parse_size(s: &str) -> Result<usize, String> {
    let (num, mult) = match s.chars().last() {
        Some('k' | 'K') => (&s[..s.len() - 1], 1usize << 10),
        Some('m' | 'M') => (&s[..s.len() - 1], 1 << 20),
        Some('g' | 'G') => (&s[..s.len() - 1], 1 << 30),
        _ => (s, 1),
    };
    num.parse::<usize>()
        .ok()
        .and_then(|n| n.checked_mul(mult))
        .filter(|&n| n > 0)
        .ok_or_else(|| format!("bad size {s:?}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strings(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn parses_an_intellij_command_line() {
        let args = strings(&[
            "-javaagent:C:\\idea\\idea_rt.jar=5000:C:\\idea\\bin",
            "-Dfile.encoding=UTF-8",
            "-Xbootclasspath:rt",
            "-classpath",
            "out",
            "-Dk=v",
            "-Xmx64m",
            "app.Main",
            "x",
            "-y",
        ]);
        let Ok(Command::Run(l)) = parse(&args) else {
            panic!("should parse")
        };
        assert_eq!(l.main_class, "app.Main");
        assert_eq!(l.args, ["x", "-y"]);
        assert_eq!(l.properties, [("k".to_string(), "v".to_string())]);
        assert_eq!(l.options.heap_limit, 64 << 20);
    }

    #[test]
    fn rejects_unknown_options_and_bad_sizes() {
        assert!(parse(&strings(&["-Xbootclasspath:rt", "-bogus", "Main"])).is_err());
        assert!(parse(&strings(&["-Xbootclasspath:rt", "-Xmx0", "Main"])).is_err());
        assert!(
            parse(&strings(&[
                "-Xbootclasspath:rt",
                "-Xmx99999999999999999g",
                "Main"
            ]))
            .is_err()
        );
    }
}
