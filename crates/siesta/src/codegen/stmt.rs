//! Statements as bytecode (JLS 14). `finally` blocks are copied onto every
//! way out of their `try` (JVMS 4.10.2.5: no `jsr` in version 52).

use super::asm::Label;
use super::body::{Body, LoopCx};
use crate::ast::{Expr, Stmt, TypeRef};
use crate::diag::Result;
use crate::types::Type;

const THROWABLE: &str = "java/lang/Throwable";

impl Body<'_> {
    pub fn stmts(&mut self, stmts: &[Stmt]) -> Result<()> {
        let mark = self.push_scope();
        for s in stmts {
            self.stmt(s)?;
        }
        self.pop_scope(mark);
        Ok(())
    }

    pub fn stmt(&mut self, s: &Stmt) -> Result<()> {
        if let Some(span) = stmt_span(s) {
            let line = self.line_of(span);
            self.asm.line(line);
        }
        match s {
            Stmt::Local { ty, name, init } => {
                let declared = match ty {
                    TypeRef::Var(_) => None,
                    t => Some(self.resolve_type(t)?),
                };
                self.local_from(&name.name, declared, init, name.span)?;
            }
            Stmt::Expr(e) => self.expr_stmt(e)?,
            Stmt::Block(b, _) => self.stmts(b)?,
            Stmt::If { cond, then, els } => {
                let (no, end) = (self.asm.label(), self.asm.label());
                // An `instanceof` binding is in scope in the `then` branch.
                let mark = self.push_scope();
                self.cond(cond, false, no)?;
                self.scoped(then)?;
                self.pop_scope(mark);
                if let Some(e) = els {
                    self.asm.jump(0xa7, 0, end);
                    self.asm.place(no);
                    self.scoped(e)?;
                } else {
                    self.asm.place(no);
                }
                self.asm.place(end);
            }
            Stmt::While { cond, body } => {
                let (top, end) = (self.asm.label(), self.asm.label());
                self.asm.place(top);
                self.cond(cond, false, end)?;
                self.loop_body(body, end, top)?;
                self.asm.jump(0xa7, 0, top);
                self.asm.place(end);
            }
            Stmt::DoWhile { body, cond } => {
                let (top, next, end) = (self.asm.label(), self.asm.label(), self.asm.label());
                self.asm.place(top);
                self.loop_body(body, end, next)?;
                self.asm.place(next);
                self.cond(cond, true, top)?;
                self.asm.place(end);
            }
            Stmt::For {
                init,
                cond,
                update,
                body,
            } => {
                let mark = self.push_scope();
                for s in init {
                    self.stmt(s)?;
                }
                let (top, next, end) = (self.asm.label(), self.asm.label(), self.asm.label());
                self.asm.place(top);
                if let Some(c) = cond {
                    self.cond(c, false, end)?;
                }
                self.loop_body(body, end, next)?;
                self.asm.place(next);
                for u in update {
                    self.expr_stmt(u)?;
                }
                self.asm.jump(0xa7, 0, top);
                self.asm.place(end);
                self.pop_scope(mark);
            }
            Stmt::ForEach {
                ty,
                name,
                iter,
                body,
            } => self.for_each(ty, name, iter, body)?,
            Stmt::Switch { scrut, cases, span } => self.switch(scrut, cases, *span)?,
            Stmt::Break(span) | Stmt::Continue(span) => {
                let is_break = matches!(s, Stmt::Break(_));
                let Some(l) = self.loops.last() else {
                    return self.err(
                        *span,
                        if is_break {
                            "`break` outside a loop or `switch`"
                        } else {
                            "`continue` outside a loop"
                        },
                    );
                };
                let depth = l.finally_depth;
                let target = if is_break {
                    l.brk
                } else {
                    match self.loops.iter().rev().find_map(|l| l.cont) {
                        Some(c) => c,
                        None => return self.err(*span, "`continue` outside a loop"),
                    }
                };
                self.unwind(depth)?;
                self.asm.jump(0xa7, 0, target);
            }
            Stmt::Return(value, span) => self.ret_stmt(value.as_ref(), *span)?,
            Stmt::Throw(e, _) => {
                let t = self.expr(e, None)?;
                if !self.subtype(&t, &Type::class(THROWABLE)) {
                    return self.err(e.span(), format!("a {t} cannot be thrown"));
                }
                self.asm.emit(&[0xbf], 1, None);
                self.asm.end_flow();
            }
            Stmt::Try {
                body,
                catches,
                finally,
                ..
            } => self.try_stmt(body, catches, finally.as_deref())?,
            Stmt::CtorCall { span, .. } => {
                return self.err(
                    *span,
                    "`this(...)` or `super(...)` must come first in a constructor",
                );
            }
        }
        Ok(())
    }

    /// A statement in its own scope, as a branch or a loop body is.
    pub(super) fn scoped(&mut self, s: &Stmt) -> Result<()> {
        let mark = self.push_scope();
        self.stmt(s)?;
        self.pop_scope(mark);
        Ok(())
    }

    pub(super) fn loop_body(&mut self, body: &Stmt, brk: Label, cont: Label) -> Result<()> {
        self.loops.push(LoopCx {
            brk,
            cont: Some(cont),
            finally_depth: self.finallies.len(),
        });
        let r = self.scoped(body);
        self.loops.pop();
        r
    }

    fn ret_stmt(&mut self, value: Option<&Expr>, span: crate::diag::Span) -> Result<()> {
        let ret = self.ret.clone();
        if ret == Type::Infer(u32::MAX) {
            let t = match value {
                Some(e) => self.expr(e, None)?,
                None => Type::Void,
            };
            self.ret_seen.get_or_insert(t);
            self.asm.end_flow();
            return Ok(());
        }
        match (value, &ret) {
            (None, Type::Void) => {
                self.unwind(0)?;
                self.ret(&Type::Void);
            }
            (None, t) => return self.err(span, format!("this method returns a {t}")),
            (Some(e), Type::Void) => return self.err(e.span(), "this method returns nothing"),
            (Some(e), t) => {
                let got = self.expr(e, Some(t))?;
                self.coerce(&got, t, e.span())?;
                if self.finallies.is_empty() {
                    self.ret(t);
                } else {
                    let slot = self.temp(t);
                    self.store(slot, t);
                    self.unwind(0)?;
                    self.load(slot, t);
                    self.ret(t);
                }
            }
        }
        Ok(())
    }
}

fn stmt_span(s: &Stmt) -> Option<crate::diag::Span> {
    Some(match s {
        Stmt::Local { name, .. } => name.span,
        Stmt::Expr(e) | Stmt::Throw(e, _) => e.span(),
        Stmt::If { cond, .. } | Stmt::While { cond, .. } => cond.span(),
        Stmt::Return(_, s) | Stmt::Break(s) | Stmt::Continue(s) | Stmt::Switch { span: s, .. } => {
            *s
        }
        Stmt::Try { span, .. } | Stmt::CtorCall { span, .. } => *span,
        Stmt::ForEach { name, .. } => name.span,
        _ => return None,
    })
}
