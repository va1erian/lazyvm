//! The parser: recursive descent over Java's grammar (JLS chapters 7 to 10,
//! 14 and 15) minus the features siesta.md section 3 cuts, plus Siesta's.
//!
//! It works on a token vector and backtracks where Java needs lookahead
//! (casts, lambdas, local declarations), which a vector makes cheap.

mod decl;
mod expr;
mod primary;
mod stmt;
mod types;

use crate::ast::{File, Ident};
use crate::diag::{Diag, Result, Span};
use crate::lex::{self, Kw, Tok, Token};

/// How deeply expressions and statements may nest, so hostile input ends
/// in an error and never in a stack overflow.
const MAX_DEPTH: usize = 100;

/// Parses one file. `file` is its index in the compilation.
pub fn parse_file(file: u32, text: &str) -> Result<File> {
    let tokens = lex::lex(file, text)?;
    let mut p = Parser {
        toks: tokens,
        pos: 0,
        file,
        depth: 0,
    };
    p.file_unit()
}

pub(crate) struct Parser {
    toks: Vec<Token>,
    pos: usize,
    file: u32,
    depth: usize,
}

impl Parser {
    fn sub(&self, toks: Vec<Token>) -> Parser {
        Parser {
            toks,
            pos: 0,
            file: self.file,
            depth: self.depth,
        }
    }

    fn tok(&self, ahead: usize) -> &Tok {
        self.toks
            .get(self.pos + ahead)
            .or(self.toks.last())
            .map_or(&Tok::Eof, |t| &t.tok)
    }

    fn span(&self) -> Span {
        self.toks
            .get(self.pos)
            .or(self.toks.last())
            .map_or(Span::new(self.file, 0, 0), |t| t.span)
    }

    fn prev_span(&self) -> Span {
        self.pos
            .checked_sub(1)
            .and_then(|i| self.toks.get(i))
            .map_or(self.span(), |t| t.span)
    }

    fn bump(&mut self) -> Token {
        let t = self.toks.get(self.pos).cloned().unwrap_or(Token {
            tok: Tok::Eof,
            span: self.span(),
        });
        if self.pos < self.toks.len() {
            self.pos += 1;
        }
        t
    }

    fn is_op(&self, op: &str) -> bool {
        matches!(self.tok(0), Tok::Op(o) if *o == op)
    }

    fn is_op_at(&self, ahead: usize, op: &str) -> bool {
        matches!(self.tok(ahead), Tok::Op(o) if *o == op)
    }

    fn is_kw(&self, kw: Kw) -> bool {
        *self.tok(0) == Tok::Kw(kw)
    }

    fn eat_op(&mut self, op: &str) -> bool {
        let yes = self.is_op(op);
        if yes {
            self.pos += 1;
        }
        yes
    }

    fn eat_kw(&mut self, kw: Kw) -> bool {
        let yes = self.is_kw(kw);
        if yes {
            self.pos += 1;
        }
        yes
    }

    fn expect_op(&mut self, op: &str) -> Result<Span> {
        if self.is_op(op) {
            Ok(self.bump().span)
        } else {
            self.fail(&format!("`{op}` expected"))
        }
    }

    fn expect_kw(&mut self, kw: Kw) -> Result<()> {
        if self.eat_kw(kw) {
            Ok(())
        } else {
            self.fail(&format!("`{}` expected", kw.text()))
        }
    }

    fn ident(&mut self) -> Result<Ident> {
        match self.tok(0).clone() {
            Tok::Ident(name) => Ok(Ident {
                name,
                span: self.bump().span,
            }),
            Tok::Kw(k) => self.fail(&format!("`{}` is a reserved word", k.text())),
            _ => self.fail("a name expected"),
        }
    }

    fn fail<T>(&self, msg: &str) -> Result<T> {
        let found = match self.tok(0) {
            Tok::Eof => " (found the end of the file)".to_string(),
            _ => String::new(),
        };
        Err(Diag::new(self.span(), format!("{msg}{found}")))
    }

    /// Runs `f` one level deeper, failing past [`MAX_DEPTH`].
    fn nested<T>(&mut self, f: impl FnOnce(&mut Parser) -> Result<T>) -> Result<T> {
        if self.depth >= MAX_DEPTH {
            return self.fail("nested too deeply");
        }
        self.depth += 1;
        let r = f(self);
        self.depth -= 1;
        r
    }

    /// Runs `f`, and rewinds if it fails: for the places Java's grammar
    /// needs to look past a type to know what it is reading.
    fn attempt<T>(&mut self, f: impl FnOnce(&mut Parser) -> Result<T>) -> Option<T> {
        let start = self.pos;
        match f(self) {
            Ok(v) => Some(v),
            Err(_) => {
                self.pos = start;
                None
            }
        }
    }

    /// True when the next `n` tokens are `>` with nothing between them, so
    /// they can be read as one operator (`>>`, `>>>`).
    fn joined_gt(&self, n: usize) -> bool {
        (0..n).all(|i| self.is_op_at(i, ">"))
            && (1..n).all(|i| {
                let (a, b) = (self.toks.get(self.pos + i - 1), self.toks.get(self.pos + i));
                matches!((a, b), (Some(a), Some(b)) if a.span.hi == b.span.lo)
            })
    }

    /// True when the token after `n` joined `>`s is a `=` touching them.
    fn joined_gt_eq(&self, n: usize) -> bool {
        self.joined_gt(n)
            && self.is_op_at(n, "=")
            && matches!(
                (self.toks.get(self.pos + n - 1), self.toks.get(self.pos + n)),
                (Some(a), Some(b)) if a.span.hi == b.span.lo
            )
    }
}

#[cfg(test)]
#[path = "tests.rs"]
mod tests;
