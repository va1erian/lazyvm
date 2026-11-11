//! `try`, `catch` and `finally` (JLS 14.20). `finally` is copied onto every
//! way out of its `try` (no `jsr` in version 52), and its copies are left
//! out of the ranges its own handlers protect.

use super::body::{Body, FinallyCx};
use crate::ast::{Catch, Stmt};
use crate::diag::Result;
use crate::types::Type;

const THROWABLE: &str = "java/lang/Throwable";

impl Body<'_> {
    /// Runs the `finally` blocks above `depth`, innermost first, before a
    /// jump out of them; their copies are left out of their own handlers.
    pub fn unwind(&mut self, depth: usize) -> Result<()> {
        let mut i = self.finallies.len();
        while i > depth {
            i -= 1;
            let end = self.asm.label();
            self.asm.place(end);
            let start = self.finallies[i].open;
            self.finallies[i].ranges.push((start, end));
            let saved: Vec<FinallyCx> = self.finallies.drain(i..).collect();
            let body = saved[0].body.clone();
            let r = self.stmts(&body);
            self.finallies.extend(saved);
            r?;
            let open = self.asm.label();
            self.asm.place(open);
            self.finallies[i].open = open;
        }
        Ok(())
    }

    pub(super) fn try_stmt(
        &mut self,
        body: &[Stmt],
        catches: &[Catch],
        finally: Option<&[Stmt]>,
    ) -> Result<()> {
        let after = self.asm.label();
        let fin_handler = self.asm.label();
        if let Some(f) = finally {
            let open = self.asm.label();
            self.asm.place(open);
            self.finallies.push(FinallyCx {
                body: f.to_vec(),
                ranges: Vec::new(),
                open,
            });
        }
        let (start, end) = (self.asm.label(), self.asm.label());
        let handlers: Vec<_> = catches.iter().map(|_| self.asm.label()).collect();
        self.asm.place(start);
        self.stmts(body)?;
        self.asm.place(end);
        // The catches cover the block, but not the copies of `finally`
        // that a `return` or `break` inside it made.
        let body_ranges = match self.finallies.last().filter(|_| finally.is_some()) {
            Some(cx) => {
                let mut r = cx.ranges.clone();
                r.push((cx.open, end));
                r
            }
            None => vec![(start, end)],
        };
        let exit = |b: &mut Self| -> Result<()> {
            if b.asm.reachable() {
                let depth = b.finallies.len() - usize::from(finally.is_some());
                b.unwind(depth)?;
                b.asm.jump(0xa7, 0, after);
            }
            Ok(())
        };
        exit(self)?;
        for (c, h) in catches.iter().zip(&handlers) {
            let types = c
                .types
                .iter()
                .map(|t| self.resolve_type(t))
                .collect::<Result<Vec<_>>>()?;
            for (t, tr) in types.iter().zip(&c.types) {
                if !self.subtype(t, &Type::class(THROWABLE)) {
                    return self.err(tr.span(), format!("a {t} cannot be caught"));
                }
                let ci = self.pool.class(&t.internal());
                for &(s, e) in &body_ranges {
                    self.asm.handlers.push((s, e, *h, ci));
                }
            }
            let caught = if types.len() == 1 {
                types[0].clone()
            } else {
                Type::class(THROWABLE)
            };
            self.asm.place_handler(*h, &caught.internal());
            let mark = self.push_scope();
            let l = self.declare(&c.name.name, caught.clone(), c.name.span)?;
            self.store(l.slot, &caught);
            self.stmts(&c.body)?;
            self.pop_scope(mark);
            exit(self)?;
        }
        if let Some(f) = finally {
            let Some(mut cx) = self.finallies.pop() else {
                return Ok(());
            };
            let close = self.asm.label();
            self.asm.place(close);
            cx.ranges.push((cx.open, close));
            for (s, e) in &cx.ranges {
                self.asm.handlers.push((*s, *e, fin_handler, 0));
            }
            self.asm.place_handler(fin_handler, THROWABLE);
            let mark = self.push_scope();
            let slot = self.temp(&Type::class(THROWABLE));
            self.store(slot, &Type::class(THROWABLE));
            self.stmts(f)?;
            self.load(slot, &Type::class(THROWABLE));
            self.asm.emit(&[0xbf], 1, None);
            self.asm.end_flow();
            self.pop_scope(mark);
        }
        self.asm.place(after);
        Ok(())
    }
}
