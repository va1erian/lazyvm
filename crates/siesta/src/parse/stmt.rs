//! Statements (JLS 14), without the ones siesta.md section 3 cuts: every
//! local has an initialiser, `switch` has arrow cases only, and there are no
//! labels, `assert`, `synchronized` blocks or try-with-resources.

use super::Parser;
use crate::ast::{Case, Catch, Expr, Stmt, TypeRef};
use crate::diag::{Diag, Result};
use crate::lex::{Kw, Tok};

impl Parser {
    pub(super) fn block(&mut self) -> Result<Vec<Stmt>> {
        self.expect_op("{")?;
        let mut out = Vec::new();
        while !self.eat_op("}") {
            if *self.tok(0) == Tok::Eof {
                return self.fail("`}` expected");
            }
            out.push(self.stmt()?);
        }
        Ok(out)
    }

    pub(super) fn stmt(&mut self) -> Result<Stmt> {
        self.nested(|p| p.stmt_inner())
    }

    fn stmt_inner(&mut self) -> Result<Stmt> {
        let lo = self.span();
        match self.tok(0) {
            Tok::Op("{") => {
                let body = self.block()?;
                return Ok(Stmt::Block(body, lo.to(self.prev_span())));
            }
            Tok::Op(";") => return self.fail("an empty statement is not allowed; use `{}`"),
            Tok::Kw(Kw::If) => return self.if_stmt(),
            Tok::Kw(Kw::While) => {
                self.pos += 1;
                let cond = self.paren_expr()?;
                let body = Box::new(self.stmt()?);
                return Ok(Stmt::While { cond, body });
            }
            Tok::Kw(Kw::Do) => {
                self.pos += 1;
                let body = Box::new(self.stmt()?);
                self.expect_kw(Kw::While)?;
                let cond = self.paren_expr()?;
                self.expect_op(";")?;
                return Ok(Stmt::DoWhile { body, cond });
            }
            Tok::Kw(Kw::For) => return self.for_stmt(),
            Tok::Kw(Kw::Switch) => return self.switch_stmt(),
            Tok::Kw(Kw::Try) => return self.try_stmt(),
            Tok::Kw(Kw::Break) | Tok::Kw(Kw::Continue) => {
                let is_break = self.is_kw(Kw::Break);
                self.pos += 1;
                if matches!(self.tok(0), Tok::Ident(_)) {
                    return self.fail("labels are not supported");
                }
                self.expect_op(";")?;
                return Ok(if is_break {
                    Stmt::Break(lo)
                } else {
                    Stmt::Continue(lo)
                });
            }
            Tok::Kw(Kw::Return) => {
                self.pos += 1;
                let value = if self.is_op(";") {
                    None
                } else {
                    Some(self.expr()?)
                };
                self.expect_op(";")?;
                return Ok(Stmt::Return(value, lo.to(self.prev_span())));
            }
            Tok::Kw(Kw::Throw) => {
                self.pos += 1;
                let value = self.expr()?;
                self.expect_op(";")?;
                return Ok(Stmt::Throw(value, lo.to(self.prev_span())));
            }
            Tok::Kw(Kw::Synchronized) => {
                return self.fail("`synchronized` blocks are not supported");
            }
            Tok::Kw(Kw::This) | Tok::Kw(Kw::Super) if self.is_op_at(1, "(") => {
                let is_super = self.is_kw(Kw::Super);
                self.pos += 1;
                let args = self.args()?;
                self.expect_op(";")?;
                return Ok(Stmt::CtorCall {
                    is_super,
                    args,
                    span: lo.to(self.prev_span()),
                });
            }
            Tok::Kw(Kw::Class) | Tok::Kw(Kw::Interface) | Tok::Kw(Kw::Enum) => {
                return self.fail("local classes are not supported; declare it at the top level");
            }
            _ => {}
        }
        if self.at_declaration() {
            let s = self.local()?;
            self.expect_op(";")?;
            return Ok(s);
        }
        let e = self.expr()?;
        self.expect_op(";")?;
        Ok(Stmt::Expr(e))
    }

    /// `Type name = init`, without the `;`.
    fn local(&mut self) -> Result<Stmt> {
        let ty = self.ty()?;
        let name = self.ident()?;
        let ty = self.array_dims(ty)?;
        if matches!(ty, TypeRef::Void(_)) {
            return Err(Diag::new(name.span, "a variable cannot be `void`"));
        }
        if !self.eat_op("=") {
            return Err(Diag::new(
                name.span,
                "a local variable needs an initialiser",
            ));
        }
        let init = self.var_init()?;
        if self.is_op(",") {
            return self.fail("one variable per declaration");
        }
        Ok(Stmt::Local { ty, name, init })
    }

    fn paren_expr(&mut self) -> Result<Expr> {
        self.expect_op("(")?;
        let e = self.expr()?;
        self.expect_op(")")?;
        Ok(e)
    }

    fn if_stmt(&mut self) -> Result<Stmt> {
        self.expect_kw(Kw::If)?;
        let cond = self.paren_expr()?;
        let then = Box::new(self.stmt()?);
        let els = if self.eat_kw(Kw::Else) {
            Some(Box::new(self.stmt()?))
        } else {
            None
        };
        Ok(Stmt::If { cond, then, els })
    }

    fn for_stmt(&mut self) -> Result<Stmt> {
        self.expect_kw(Kw::For)?;
        self.expect_op("(")?;
        if self.at_declaration() {
            let start = self.pos;
            let ty = self.ty()?;
            let name = self.ident()?;
            if self.eat_op(":") {
                let iter = self.expr()?;
                self.expect_op(")")?;
                let body = Box::new(self.stmt()?);
                return Ok(Stmt::ForEach {
                    ty,
                    name,
                    iter,
                    body,
                });
            }
            self.pos = start;
        }
        let mut init = Vec::new();
        if !self.is_op(";") {
            if self.at_declaration() {
                init.push(self.local()?);
            } else {
                loop {
                    init.push(Stmt::Expr(self.expr()?));
                    if !self.eat_op(",") {
                        break;
                    }
                }
            }
        }
        self.expect_op(";")?;
        let cond = if self.is_op(";") {
            None
        } else {
            Some(self.expr()?)
        };
        self.expect_op(";")?;
        let mut update = Vec::new();
        if !self.is_op(")") {
            loop {
                update.push(self.expr()?);
                if !self.eat_op(",") {
                    break;
                }
            }
        }
        self.expect_op(")")?;
        let body = Box::new(self.stmt()?);
        Ok(Stmt::For {
            init,
            cond,
            update,
            body,
        })
    }

    fn switch_stmt(&mut self) -> Result<Stmt> {
        let lo = self.span();
        self.expect_kw(Kw::Switch)?;
        let scrut = self.paren_expr()?;
        self.expect_op("{")?;
        let mut cases = Vec::new();
        let mut seen_default = false;
        while !self.eat_op("}") {
            let mut labels = Vec::new();
            if self.eat_kw(Kw::Default) {
                if seen_default {
                    return self.fail("a second `default`");
                }
                seen_default = true;
            } else {
                self.expect_kw(Kw::Case)?;
                loop {
                    labels.push(self.ternary()?);
                    if !self.eat_op(",") {
                        break;
                    }
                }
            }
            if self.is_op(":") {
                return self.fail("use `->`: `switch` cases do not fall through");
            }
            self.expect_op("->")?;
            let body = if self.is_op("{") {
                let l = self.span();
                let b = self.block()?;
                Stmt::Block(b, l.to(self.prev_span()))
            } else if self.is_kw(Kw::Throw) {
                self.stmt()?
            } else {
                let e = self.expr()?;
                self.expect_op(";")?;
                Stmt::Expr(e)
            };
            cases.push(Case { labels, body });
        }
        Ok(Stmt::Switch {
            scrut,
            cases,
            span: lo.to(self.prev_span()),
        })
    }

    fn try_stmt(&mut self) -> Result<Stmt> {
        let lo = self.span();
        self.expect_kw(Kw::Try)?;
        if self.is_op("(") {
            return self.fail("try-with-resources is not supported; close in `finally`");
        }
        let body = self.block()?;
        let mut catches = Vec::new();
        while self.eat_kw(Kw::Catch) {
            self.expect_op("(")?;
            let mut types = vec![self.ty()?];
            while self.eat_op("|") {
                types.push(self.ty()?);
            }
            let name = self.ident()?;
            self.expect_op(")")?;
            catches.push(Catch {
                types,
                name,
                body: self.block()?,
            });
        }
        let finally = if self.eat_kw(Kw::Finally) {
            Some(self.block()?)
        } else {
            None
        };
        if catches.is_empty() && finally.is_none() {
            return self.fail("`catch` or `finally` expected");
        }
        Ok(Stmt::Try {
            body,
            catches,
            finally,
            span: lo.to(self.prev_span()),
        })
    }
}
