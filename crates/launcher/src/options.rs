//! Command line parsing, compatible with what `java` and IntelliJ pass.

use std::ffi::{OsStr, OsString};
use std::path::PathBuf;

use platform::wtf;

use vm::Options;
use vm::verify::VerifyMode;

pub const USAGE: &str = "usage: lazyvm [options] <main class> [args...]
       lazyvm [options] -jar <file> [args...]
  -cp, -classpath <path>   application class path (directories and jars)
  -jar <file>              run the jar's Main-Class; it is the class path
  -Xbootclasspath:<path>   the class library (default: LAZYVM_BOOTCLASSPATH, or lazyrt next to lazyvm)
  -D<name>=<value>         set a system property
  -Xmx<size>               heap limit, e.g. 256m
  -Xss<size>               stack size, e.g. 1m, the default (16384 frames per MiB)
  -Xverify:<mode>          all (the default), remote (not the class library) or none
  -noverify                the same as -Xverify:none
  -Xverify:check           verify every class on the class paths, then exit; needs no main class
  -Xmaxclasses:<n>         most classes that may be loaded
  -Xmaxcode:<bytes>        most bytes of code one method may have, up to 65535
  -verbose:class           print each class as it loads
  -Xtrace:interp           print every instruction (slow)
  -version                 print the version";

/// Options that only make sense for HotSpot or the IDE; accepted and ignored.
const IGNORED_PREFIXES: &[&str] = &[
    "-javaagent:",
    "-XX:",
    "-Xshare",
    "-da",
    "-esa",
    "-dsa",
    "-disableassertions",
    "-Didea.",
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
    Run(Box<Launch>),
    Version,
    Help,
}

/// A command-line argument as UTF-16 units, so a Windows argument with an unpaired
/// surrogate (or a Unix one with odd bytes, read as WTF-8) reaches Java unchanged.
pub type Arg = Vec<u16>;

#[derive(Debug)]
pub struct Launch {
    /// Where classes are loaded from, one entry per element of `classpath_text`.
    pub classpath: Vec<PathBuf>,
    /// The `java.class.path` value as the user gave it, empty entries included.
    pub classpath_text: Arg,
    pub boot_classpath: Vec<PathBuf>,
    /// `-D` properties, name and value, in order.
    pub properties: Vec<(Arg, Arg)>,
    pub options: Options,
    /// The class to run, as given, or `None` with `-jar` (the manifest names it).
    pub main_class: Option<Arg>,
    /// `-jar <file>`: the jar is the whole application class path.
    pub jar: Option<PathBuf>,
    pub args: Vec<Arg>,
    /// `-showversion`: print the version, then run.
    pub show_version: bool,
    /// `-Xverify:check`: verify the class paths instead of running anything.
    pub verify_check: bool,
}

fn text(a: &[u16]) -> String {
    String::from_utf16_lossy(a)
}

fn os(a: &[u16]) -> OsString {
    wtf::from_units(a)
}

pub fn parse(args: &[Arg]) -> Result<Command, String> {
    // `-cp` and `-Djava.class.path` set the same thing; the last one given wins.
    let mut classpath: Option<Arg> = None;
    let mut boot: Option<Vec<PathBuf>> = None;
    let mut properties = Vec::new();
    let mut options = Options::default();
    let mut show_version = false;
    let mut verify_check = false;
    let mut i = 0;
    while i < args.len() {
        let a = text(&args[i]);
        let a = a.as_str();
        if !a.starts_with('-') {
            let classpath_text = classpath.unwrap_or_else(default_classpath);
            let boot = boot
                .or_else(default_boot)
                .ok_or("cannot find lazyrt: pass -Xbootclasspath:<dir>")?;
            return Ok(Command::Run(Box::new(Launch {
                classpath: split_classpath(&os(&classpath_text)),
                classpath_text,
                boot_classpath: boot,
                properties,
                options,
                main_class: Some(args[i].clone()),
                jar: None,
                args: args[i + 1..].to_vec(),
                show_version,
                verify_check,
            })));
        }
        match a {
            "-cp" | "-classpath" | "--class-path" => {
                i += 1;
                classpath = Some(args.get(i).ok_or(format!("{a} needs a value"))?.clone());
            }
            "-version" | "--version" => return Ok(Command::Version),
            "-showversion" => show_version = true,
            // IDEs probe a JDK with this; LazyVM's properties need a VM, so
            // only the version is reported.
            _ if a.starts_with("-XshowSettings") => {}
            "-help" | "-h" | "-?" | "--help" => return Ok(Command::Help),
            "-verbose:class" => options.verbose_class = true,
            // Package and class filters (`-ea:pkg...`) enable all application assertions.
            _ if a == "-ea" || a.starts_with("-ea:") || a.starts_with("-enableassertions") => {
                options.assertions = true
            }
            "-Xtrace:interp" => options.trace_interp = true,
            "-Xverify" | "-Xverify:all" => options.verify = VerifyMode::All,
            "-Xverify:remote" => options.verify = VerifyMode::Remote,
            "-Xverify:none" | "-noverify" => options.verify = VerifyMode::None,
            "-Xverify:check" => verify_check = true,
            _ if a.starts_with("-Xmaxclasses:") => {
                options.max_classes = parse_size(&a[13..])?;
            }
            _ if a.starts_with("-Xmaxcode:") => {
                options.max_method_code = parse_size(&a[10..])?.min(65535);
            }
            "-jar" => {
                let jar = args.get(i + 1).ok_or("-jar needs a jar file")?;
                let boot = boot
                    .or_else(default_boot)
                    .ok_or("cannot find lazyrt: pass -Xbootclasspath:<path>")?;
                return Ok(Command::Run(Box::new(Launch {
                    classpath: Vec::new(),
                    classpath_text: Vec::new(),
                    boot_classpath: boot,
                    properties,
                    options,
                    main_class: None,
                    jar: Some(PathBuf::from(os(jar))),
                    args: args[i + 2..].to_vec(),
                    show_version,
                    verify_check,
                })));
            }
            _ if a.starts_with("-Xbootclasspath:") => {
                boot = Some(split_path(&os(&args[i][16..])));
            }
            _ if IGNORED_PREFIXES.iter().any(|p| a.starts_with(p)) => {}
            _ if a.starts_with("-D") => {
                let rest = &args[i][2..];
                let eq = rest.iter().position(|&u| u == u16::from(b'='));
                let (k, v) = eq.map_or((rest, &[][..]), |e| (&rest[..e], &rest[e + 1..]));
                if k == "java.class.path".encode_utf16().collect::<Vec<_>>() {
                    classpath = Some(v.to_vec());
                }
                properties.push((k.to_vec(), v.to_vec()));
            }
            _ if a.starts_with("-Xmx") => options.heap_limit = parse_size(&a[4..])?,
            _ if a.starts_with("-Xss") => {
                // 1m is the default, as it is on the oracle (decisions.md D15).
                options.max_frames = (parse_size(&a[4..])? / 64).clamp(256, 1 << 20);
            }
            _ if a.starts_with("-verbose") => {}
            _ => return Err(format!("unknown option {a}")),
        }
        i += 1;
    }
    if verify_check {
        let classpath_text = classpath.unwrap_or_else(default_classpath);
        let boot = boot
            .or_else(default_boot)
            .ok_or("cannot find lazyrt: pass -Xbootclasspath:<path>")?;
        return Ok(Command::Run(Box::new(Launch {
            classpath: split_classpath(&os(&classpath_text)),
            classpath_text,
            boot_classpath: boot,
            properties,
            options,
            main_class: None,
            jar: None,
            args: Vec::new(),
            show_version,
            verify_check,
        })));
    }
    Err("no main class given".into())
}

/// The boot class path: empty entries are dropped.
fn split_path(s: &OsStr) -> Vec<PathBuf> {
    std::env::split_paths(s)
        .filter(|p| !p.as_os_str().is_empty())
        .collect()
}

/// The application class path: an empty entry (or an empty value) is the current directory.
fn split_classpath(s: &OsStr) -> Vec<PathBuf> {
    // An empty value splits into one empty entry, so it needs no case of its own.
    std::env::split_paths(s)
        .map(|p| {
            if p.as_os_str().is_empty() {
                PathBuf::from(".")
            } else {
                p
            }
        })
        .collect()
}

fn default_classpath() -> Arg {
    match std::env::var_os("CLASSPATH") {
        Some(cp) => wtf::to_units(&cp),
        None => vec![u16::from(b'.')],
    }
}

/// `LAZYVM_BOOTCLASSPATH`, else the class library next to the binary.
fn default_boot() -> Option<Vec<PathBuf>> {
    if let Some(p) = std::env::var_os("LAZYVM_BOOTCLASSPATH") {
        return Some(std::env::split_paths(&p).collect());
    }
    let exe = std::env::current_exe().ok()?;
    let dir = exe.parent()?;
    // Beside the binary, an install's lib/, or the fake JDK home's jre/lib.
    let candidates = [
        dir.join("lazyrt.jar"),
        dir.join("../lib/lazyrt.jar"),
        dir.join("../jre/lib/rt.jar"),
        dir.join("lazyrt"),
        dir.join("../lib/lazyrt"),
    ];
    candidates.into_iter().find(|p| p.exists()).map(|p| vec![p])
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

    fn strings(a: &[&str]) -> Vec<Arg> {
        a.iter().map(|s| s.encode_utf16().collect()).collect()
    }

    fn units(s: &str) -> Arg {
        s.encode_utf16().collect()
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
        assert_eq!(l.main_class, Some(units("app.Main")));
        assert_eq!(l.args, [units("x"), units("-y")]);
        assert_eq!(l.properties, [(units("k"), units("v"))]);
        assert_eq!(l.options.heap_limit, 64 << 20);
    }

    #[test]
    fn parses_verifier_and_limit_options() {
        let run = |a: &[&str]| match parse(&strings(a)) {
            Ok(Command::Run(l)) => *l,
            other => panic!("{other:?}"),
        };
        let boot = "-Xbootclasspath:rt";
        assert_eq!(run(&[boot, "Main"]).options.verify, VerifyMode::All);
        assert_eq!(
            run(&[boot, "-Xverify:none", "Main"]).options.verify,
            VerifyMode::None
        );
        assert_eq!(
            run(&[boot, "-noverify", "Main"]).options.verify,
            VerifyMode::None
        );
        assert_eq!(
            run(&[boot, "-Xverify:remote", "Main"]).options.verify,
            VerifyMode::Remote
        );
        assert!(run(&[boot, "-Xverify:check"]).verify_check);
        assert_eq!(
            run(&[boot, "-Xmaxclasses:99", "Main"]).options.max_classes,
            99
        );
        let code = run(&[boot, "-Xmaxcode:1m", "Main"]).options.max_method_code;
        assert_eq!(code, 65535);
        assert!(parse(&strings(&[boot, "-Xverify:bogus", "Main"])).is_err());
        assert!(parse(&strings(&[boot, "-Xmaxclasses:0", "Main"])).is_err());
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
