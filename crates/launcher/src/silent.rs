//! A windowed bundle (`tools/bundle.py --gui`) started from Explorer has no
//! stderr, so its errors would vanish. Once `arm`ed, the launcher keeps what
//! stderr is sent (`platform::capture_err`) and, when the program ends with a
//! failure, by returning from `main` or through `platform::exit`, shows it in
//! a message box (docs/bundle.md, "Errors").

use std::path::Path;
use std::sync::OnceLock;

/// The most lines of stderr the box shows; the first ones say what failed.
const MAX_LINES: usize = 25;
const MAX_CHARS: usize = 3000;

/// Whether stderr goes nowhere, as for a windowed program started without a
/// console. A redirected stderr, even to `NUL`, is somewhere.
pub fn no_stderr() -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::io::AsRawHandle;
        std::io::stderr().as_raw_handle().is_null()
    }
    #[cfg(not(windows))]
    {
        false
    }
}

/// The box's title, once armed.
static TITLE: OnceLock<String> = OnceLock::new();

/// From now on keep stderr, and show it if the process ends with a failure.
pub fn arm(exe: Option<&Path>) {
    let title = exe
        .and_then(Path::file_stem)
        .map_or("LazyVM".into(), |s| s.to_string_lossy().into_owned());
    if TITLE.set(title).is_ok() {
        platform::capture_err();
        platform::set_exit_hook(on_exit);
    }
}

fn on_exit(code: i32) {
    if code != 0 {
        report();
    }
}

/// Shows what stderr was sent, if armed, at most once.
pub fn report() {
    static SHOWN: OnceLock<()> = OnceLock::new();
    if let Some(title) = TITLE.get()
        && SHOWN.set(()).is_ok()
    {
        error_box(title, &box_text(&platform::captured_err()));
    }
}

/// The start of `captured`, or a sentence when the program printed nothing.
fn box_text(captured: &[u8]) -> String {
    let text = String::from_utf8_lossy(captured);
    let lines: Vec<&str> = text.lines().collect();
    if lines.iter().all(|l| l.trim().is_empty()) {
        return "The program stopped with an error, without saying why.".into();
    }
    let mut out = lines[..lines.len().min(MAX_LINES)].join("\n");
    let mut cut = lines.len() > MAX_LINES;
    if out.chars().count() > MAX_CHARS {
        out = out.chars().take(MAX_CHARS).collect();
        cut = true;
    }
    if cut {
        out.push_str("\n...");
    }
    out
}

#[cfg(windows)]
fn error_box(title: &str, text: &str) {
    lazyvm_win::error_box(title, text);
}

/// Only Windows programs start without a stderr (`no_stderr`).
#[cfg(not(windows))]
fn error_box(_title: &str, _text: &str) {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_box_shows_the_start_of_stderr() {
        assert_eq!(box_text(b"Error: no main\nmore\n"), "Error: no main\nmore");
        let many: String = (0..40).map(|i| format!("line {i}\n")).collect();
        let text = box_text(many.as_bytes());
        assert!(
            text.starts_with("line 0\n") && text.ends_with("line 24\n..."),
            "{text}"
        );
        let long = "x".repeat(5000);
        assert_eq!(box_text(long.as_bytes()).chars().count(), MAX_CHARS + 4);
    }

    #[test]
    fn silence_gets_a_sentence() {
        assert!(box_text(b"").contains("without saying why"));
        assert!(box_text(b"\n  \n").contains("without saying why"));
    }
}
