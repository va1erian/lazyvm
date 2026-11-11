//! Source positions and error messages (siesta.md section 9).
//!
//! Every problem in a program, whether malformed text or a type error, is a
//! [`Diag`] with the span it is about; the compiler never panics on input.

use std::fmt;

/// A byte range in one source file. `file` indexes the compilation's files.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Span {
    pub file: u32,
    pub lo: u32,
    pub hi: u32,
}

impl Span {
    pub fn new(file: u32, lo: usize, hi: usize) -> Span {
        Span {
            file,
            lo: clamp(lo),
            hi: clamp(hi),
        }
    }

    /// The smallest span covering both.
    pub fn to(self, other: Span) -> Span {
        Span {
            file: self.file,
            lo: self.lo.min(other.lo),
            hi: self.hi.max(other.hi),
        }
    }
}

fn clamp(v: usize) -> u32 {
    u32::try_from(v).unwrap_or(u32::MAX)
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Diag {
    pub span: Span,
    pub message: String,
}

impl Diag {
    pub fn new(span: Span, message: impl Into<String>) -> Diag {
        Diag {
            span,
            message: message.into(),
        }
    }
}

pub type Result<T> = std::result::Result<T, Diag>;

/// One source file of a compilation.
pub struct Source {
    pub name: String,
    pub text: String,
}

impl Source {
    /// The 1-based line and column (in characters) of byte offset `at`.
    pub fn line_col(&self, at: u32) -> (usize, usize) {
        let at = (at as usize).min(self.text.len());
        let before = self.text.get(..at).unwrap_or("");
        let line = before.matches('\n').count() + 1;
        let col = before.rsplit('\n').next().unwrap_or("").chars().count() + 1;
        (line, col)
    }

    fn line_text(&self, line: usize) -> &str {
        self.text.lines().nth(line - 1).unwrap_or("")
    }
}

/// Renders `diag` as `file:line:col: message`, the line, and a caret.
pub struct Rendered<'a> {
    pub diag: &'a Diag,
    pub sources: &'a [Source],
}

impl fmt::Display for Rendered<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let Some(src) = self.sources.get(self.diag.span.file as usize) else {
            return write!(f, "error: {}", self.diag.message);
        };
        let (line, col) = src.line_col(self.diag.span.lo);
        writeln!(f, "{}:{line}:{col}: {}", src.name, self.diag.message)?;
        let text = src.line_text(line);
        writeln!(f, "    {text}")?;
        let pad: String = text
            .chars()
            .take(col - 1)
            .map(|c| if c == '\t' { '\t' } else { ' ' })
            .collect();
        write!(f, "    {pad}^")
    }
}
