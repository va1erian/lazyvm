//! The lexer: Java's tokens (JLS 3) plus string templates (`"a ${x}"`).
//!
//! `>` is always one token; the parser joins adjacent ones into `>>`, `>=`
//! and so on where an expression needs them, so `List<List<String>>` closes
//! two type argument lists without special cases.

use crate::diag::{Diag, Result, Span};

#[derive(Clone, Debug, PartialEq)]
pub enum Tok {
    Ident(String),
    Kw(Kw),
    /// A number as written, without underscores; checked when typed.
    Num(Num),
    Char(u16),
    Str(String),
    /// A string with `${...}` parts.
    Template(Vec<Part>),
    Op(&'static str),
    Eof,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Num {
    pub text: String,
    pub radix: u32,
    pub kind: NumKind,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NumKind {
    Int,
    Long,
    Float,
    Double,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Part {
    Text(String),
    Expr(Vec<Token>),
}

#[derive(Clone, Debug, PartialEq)]
pub struct Token {
    pub tok: Tok,
    pub span: Span,
}

macro_rules! keywords {
    ($($name:ident = $text:literal),* $(,)?) => {
        #[derive(Clone, Copy, Debug, PartialEq, Eq)]
        pub enum Kw { $($name),* }
        impl Kw {
            fn from(s: &str) -> Option<Kw> {
                match s { $($text => Some(Kw::$name),)* _ => None }
            }
            pub fn text(self) -> &'static str {
                match self { $(Kw::$name => $text),* }
            }
        }
    };
}

keywords! {
    Abstract = "abstract", Async = "async", Await = "await", Boolean = "boolean",
    Break = "break", Byte = "byte", Case = "case", Catch = "catch", Char = "char",
    Class = "class", Continue = "continue", Default = "default", Do = "do",
    Double = "double", Else = "else", Enum = "enum", Extends = "extends",
    False = "false", Final = "final", Finally = "finally", Float = "float",
    For = "for", If = "if", Implements = "implements", Import = "import",
    Instanceof = "instanceof", Int = "int", Interface = "interface", Long = "long",
    New = "new", Null = "null", Package = "package", Private = "private",
    Protected = "protected", Public = "public", Return = "return", Short = "short",
    Static = "static", Super = "super", Switch = "switch", Synchronized = "synchronized",
    This = "this", Throw = "throw", Throws = "throws", True = "true", Try = "try",
    Var = "var", Void = "void", While = "while",
}

/// Longest first, so a prefix never wins over a longer operator.
const OPS: &[&str] = &[
    "<<=", "...", "->", "::", "..", "++", "--", "&&", "||", "==", "!=", "<=", "+=", "-=", "*=",
    "/=", "%=", "&=", "|=", "^=", "<<", "(", ")", "{", "}", "[", "]", ";", ",", ".", "@", "=", ">",
    "<", "!", "~", "?", ":", "+", "-", "*", "/", "&", "|", "^", "%",
];

/// The largest source file accepted, so offsets fit a `u32` with room.
pub const MAX_SOURCE: usize = 16 << 20;

pub fn lex(file: u32, text: &str) -> Result<Vec<Token>> {
    if text.len() > MAX_SOURCE {
        return Err(Diag::new(
            Span::new(file, 0, 0),
            "source file larger than 16 MiB",
        ));
    }
    let mut lx = Lexer {
        file,
        src: text.as_bytes(),
        text,
        pos: 0,
    };
    let mut out = Vec::new();
    loop {
        let t = lx.next()?;
        let end = t.tok == Tok::Eof;
        out.push(t);
        if end {
            return Ok(out);
        }
    }
}

struct Lexer<'a> {
    file: u32,
    src: &'a [u8],
    text: &'a str,
    pos: usize,
}

impl Lexer<'_> {
    fn span(&self, lo: usize) -> Span {
        Span::new(self.file, lo, self.pos)
    }

    fn err<T>(&self, lo: usize, msg: &str) -> Result<T> {
        Err(Diag::new(
            Span::new(self.file, lo, self.pos.max(lo + 1)),
            msg,
        ))
    }

    fn peek(&self, ahead: usize) -> u8 {
        self.src.get(self.pos + ahead).copied().unwrap_or(0)
    }

    fn next(&mut self) -> Result<Token> {
        self.skip_space()?;
        let lo = self.pos;
        let c = self.peek(0);
        let tok = if self.pos >= self.src.len() {
            Tok::Eof
        } else if c.is_ascii_alphabetic() || c == b'_' || c == b'$' || c >= 0x80 {
            self.word()?
        } else if c.is_ascii_digit() || (c == b'.' && self.peek(1).is_ascii_digit()) {
            Tok::Num(self.number(lo)?)
        } else if c == b'"' {
            self.string(lo)?
        } else if c == b'\'' {
            self.char_lit(lo)?
        } else {
            self.op(lo)?
        };
        Ok(Token {
            tok,
            span: self.span(lo),
        })
    }

    fn skip_space(&mut self) -> Result<()> {
        loop {
            let c = self.peek(0);
            if c == b' ' || c == b'\t' || c == b'\n' || c == b'\r' || c == 0x0c {
                self.pos += 1;
            } else if c == b'/' && self.peek(1) == b'/' {
                while self.pos < self.src.len() && self.peek(0) != b'\n' {
                    self.pos += 1;
                }
            } else if c == b'/' && self.peek(1) == b'*' {
                let lo = self.pos;
                self.pos += 2;
                loop {
                    if self.pos >= self.src.len() {
                        return self.err(lo, "comment not closed");
                    }
                    if self.peek(0) == b'*' && self.peek(1) == b'/' {
                        self.pos += 2;
                        break;
                    }
                    self.pos += 1;
                }
            } else {
                return Ok(());
            }
        }
    }

    fn word(&mut self) -> Result<Tok> {
        let lo = self.pos;
        let rest = self.text.get(lo..).unwrap_or("");
        let len = rest
            .char_indices()
            .find(|&(_, ch)| !(ch.is_alphanumeric() || ch == '_' || ch == '$'))
            .map_or(rest.len(), |(i, _)| i);
        if len == 0 {
            return self.err(lo, "unexpected character");
        }
        self.pos += len;
        let w = &rest[..len];
        Ok(Kw::from(w).map_or_else(|| Tok::Ident(w.to_string()), Tok::Kw))
    }

    fn op(&mut self, lo: usize) -> Result<Tok> {
        let rest = self.src.get(lo..).unwrap_or(&[]);
        match OPS.iter().find(|op| rest.starts_with(op.as_bytes())) {
            Some(op) => {
                self.pos += op.len();
                Ok(Tok::Op(op))
            }
            None => self.err(lo, "unexpected character"),
        }
    }

    fn number(&mut self, lo: usize) -> Result<Num> {
        let (c0, c1) = (self.peek(0), self.peek(1) | 0x20);
        if c0 == b'0' && (c1 == b'x' || c1 == b'b') {
            self.pos += 2;
            let radix = if c1 == b'x' { 16 } else { 2 };
            let text = self.digits(|c| (c as char).is_digit(radix));
            if text.is_empty() {
                return self.err(lo, "digits expected");
            }
            return self.int_suffix(lo, text, radix);
        }
        let mut text = self.digits(|c| c.is_ascii_digit());
        let mut float = false;
        if self.peek(0) == b'.' && self.peek(1).is_ascii_digit() {
            self.pos += 1;
            text.push('.');
            text += &self.digits(|c| c.is_ascii_digit());
            float = true;
        } else if self.peek(0) == b'.' && self.peek(1) != b'.' && !is_ident_start(self.peek(1)) {
            self.pos += 1;
            float = true;
        }
        if self.peek(0) | 0x20 == b'e' {
            self.pos += 1;
            text.push('e');
            if matches!(self.peek(0), b'+' | b'-') {
                text.push(self.peek(0) as char);
                self.pos += 1;
            }
            let exp = self.digits(|c| c.is_ascii_digit());
            if exp.is_empty() {
                return self.err(lo, "exponent digits expected");
            }
            text += &exp;
            float = true;
        }
        match self.peek(0) | 0x20 {
            b'f' => self.suffixed(Num {
                text,
                radix: 10,
                kind: NumKind::Float,
            }),
            b'd' => self.suffixed(Num {
                text,
                radix: 10,
                kind: NumKind::Double,
            }),
            _ if float => Ok(Num {
                text,
                radix: 10,
                kind: NumKind::Double,
            }),
            _ => {
                // A leading zero means octal (JLS 3.10.1).
                let radix = if text.len() > 1 && text.starts_with('0') {
                    8
                } else {
                    10
                };
                if radix == 8 && text.bytes().any(|c| c > b'7') {
                    return self.err(lo, "digit out of range for an octal number");
                }
                self.int_suffix(lo, text, radix)
            }
        }
    }

    fn suffixed(&mut self, n: Num) -> Result<Num> {
        self.pos += 1;
        Ok(n)
    }

    fn int_suffix(&mut self, lo: usize, text: String, radix: u32) -> Result<Num> {
        let kind = if self.peek(0) | 0x20 == b'l' {
            self.pos += 1;
            NumKind::Long
        } else {
            NumKind::Int
        };
        if is_ident_start(self.peek(0)) || self.peek(0).is_ascii_digit() {
            return self.err(lo, "malformed number");
        }
        Ok(Num { text, radix, kind })
    }

    fn digits(&mut self, ok: impl Fn(u8) -> bool) -> String {
        let mut s = String::new();
        while ok(self.peek(0)) || (self.peek(0) == b'_' && ok(self.peek(1))) {
            if self.peek(0) != b'_' {
                s.push(self.peek(0) as char);
            }
            self.pos += 1;
        }
        s
    }

    fn char_lit(&mut self, lo: usize) -> Result<Tok> {
        self.pos += 1;
        let units = match self.peek(0) {
            b'\'' | b'\n' | 0 => return self.err(lo, "empty character literal"),
            b'\\' => self.escape(lo)?,
            _ => self.plain_char(),
        };
        if self.peek(0) != b'\'' || units.len() != 1 {
            return self.err(lo, "character literal not closed");
        }
        self.pos += 1;
        Ok(Tok::Char(units[0]))
    }

    fn plain_char(&mut self) -> Vec<u16> {
        let ch = self
            .text
            .get(self.pos..)
            .and_then(|r| r.chars().next())
            .unwrap_or('\0');
        self.pos += ch.len_utf8();
        ch.encode_utf16(&mut [0; 2]).to_vec()
    }

    /// One escape (JLS 3.10.6, plus `\u` and Siesta's `\$`), as UTF-16.
    fn escape(&mut self, lo: usize) -> Result<Vec<u16>> {
        self.pos += 1;
        let c = self.peek(0);
        self.pos += 1;
        let unit = match c {
            b'b' => 8,
            b't' => 9,
            b'n' => 10,
            b'f' => 12,
            b'r' => 13,
            b's' => 32,
            b'"' | b'\'' | b'\\' | b'$' => u16::from(c),
            b'0'..=b'7' => {
                let max = if c <= b'3' { 2 } else { 1 };
                let mut v = u16::from(c - b'0');
                for _ in 0..max {
                    if !(b'0'..=b'7').contains(&self.peek(0)) {
                        break;
                    }
                    v = v * 8 + u16::from(self.peek(0) - b'0');
                    self.pos += 1;
                }
                v
            }
            b'u' => {
                while self.peek(0) == b'u' {
                    self.pos += 1;
                }
                let hex = self.text.get(self.pos..self.pos + 4).unwrap_or("");
                let v = u16::from_str_radix(hex, 16)
                    .or_else(|_| self.err(lo, "malformed \\u escape"))?;
                self.pos += 4;
                v
            }
            _ => return self.err(lo, "unknown escape"),
        };
        Ok(vec![unit])
    }

    fn string(&mut self, lo: usize) -> Result<Tok> {
        self.pos += 1;
        let mut parts = Vec::new();
        let mut text: Vec<u16> = Vec::new();
        loop {
            match self.peek(0) {
                b'"' => {
                    self.pos += 1;
                    break;
                }
                b'\n' => return self.err(lo, "string not closed"),
                _ if self.pos >= self.src.len() => return self.err(lo, "string not closed"),
                b'\\' => text.extend(self.escape(lo)?),
                b'$' if self.peek(1) == b'{' => {
                    parts.push(Part::Text(String::from_utf16_lossy(&std::mem::take(
                        &mut text,
                    ))));
                    self.pos += 2;
                    parts.push(Part::Expr(self.template_expr(lo)?));
                }
                _ => text.extend(self.plain_char()),
            }
        }
        let last = String::from_utf16_lossy(&text);
        if parts.is_empty() {
            return Ok(Tok::Str(last));
        }
        parts.push(Part::Text(last));
        Ok(Tok::Template(parts))
    }

    /// The tokens of `${ ... }` up to its closing brace, which is consumed.
    fn template_expr(&mut self, lo: usize) -> Result<Vec<Token>> {
        let mut depth = 0usize;
        let mut toks = Vec::new();
        loop {
            let t = self.next()?;
            match &t.tok {
                Tok::Eof => return self.err(lo, "`${` not closed"),
                Tok::Op("{") => depth += 1,
                Tok::Op("}") if depth == 0 => {
                    let span = t.span;
                    toks.push(Token {
                        tok: Tok::Eof,
                        span,
                    });
                    return Ok(toks);
                }
                Tok::Op("}") => depth -= 1,
                _ => {}
            }
            toks.push(t);
        }
    }
}

fn is_ident_start(c: u8) -> bool {
    c.is_ascii_alphabetic() || c == b'_' || c == b'$' || c >= 0x80
}

#[cfg(test)]
#[path = "lex_tests.rs"]
mod tests;
