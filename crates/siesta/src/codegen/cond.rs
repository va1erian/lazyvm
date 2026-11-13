//! Conditions compiled as jumps (JLS 15.20 to 15.24): comparisons, `&&`,
//! `||`, `!`, `instanceof` with its binding, and boolean values made from
//! them.

use super::asm::{Label, VType};
use super::body::Body;
use super::ops::{prim_of, promote};
use crate::ast::{Expr, Lit, Prim};
use crate::diag::Result;
use crate::types::Type;

/// The `if<cond>` offset from `ifeq` for a comparison, and its negation.
fn cmp_index(op: &str, jump_if: bool) -> u8 {
    let (pos, neg) = match op {
        "==" => (0, 1),
        "!=" => (1, 0),
        "<" => (2, 3),
        ">=" => (3, 2),
        ">" => (4, 5),
        _ => (5, 4),
    };
    if jump_if { pos } else { neg }
}

impl Body<'_> {
    /// Jumps to `to` when `e` is `jump_if`, else falls through.
    pub fn cond(&mut self, e: &Expr, jump_if: bool, to: Label) -> Result<()> {
        match e {
            Expr::Binary {
                op: op @ ("&&" | "||"),
                left,
                right,
            } => {
                let and = *op == "&&";
                if and != jump_if {
                    self.cond(left, jump_if, to)?;
                    self.cond(right, jump_if, to)
                } else {
                    let skip = self.asm.label();
                    self.cond(left, !jump_if, skip)?;
                    self.cond(right, jump_if, to)?;
                    self.asm.place(skip);
                    Ok(())
                }
            }
            Expr::Unary { op: "!", expr, .. } => self.cond(expr, !jump_if, to),
            Expr::Lit(Lit::Bool(b), _) => {
                if *b == jump_if {
                    self.asm.jump(0xa7, 0, to);
                }
                Ok(())
            }
            Expr::Binary {
                op: op @ ("==" | "!=" | "<" | ">" | "<=" | ">="),
                left,
                right,
            } => self.compare(op, left, right, jump_if, to),
            Expr::InstanceOf { expr, ty, bind } => {
                let t = self.resolve_type(ty)?;
                if t.is_prim() {
                    return self.err(ty.span(), "`instanceof` needs a class or interface type");
                }
                let vt = self.expr(expr, None)?;
                if !vt.is_reference() {
                    return self.err(expr.span(), format!("a {vt} is not an object"));
                }
                let slot = bind.as_ref().map(|_| {
                    let s = self.temp(&vt);
                    self.asm.dup_under(0);
                    self.store(s, &vt);
                    s
                });
                let c = self.pool.class(&t.internal());
                self.asm.emit_u2(0xc1, c, 1, Some(VType::Int));
                self.asm.jump(if jump_if { 0x9a } else { 0x99 }, 1, to);
                if let (Some(b), Some(s)) = (bind, slot) {
                    if jump_if {
                        return self.err(
                            b.span,
                            "a binding needs a positive test: write `if (x instanceof T t)`",
                        );
                    }
                    self.load(s, &vt);
                    self.checkcast(&t.erasure());
                    let l = self.declare(&b.name, t.clone(), b.span)?;
                    self.store(l.slot, &t);
                }
                Ok(())
            }
            other => {
                let t = self.expr(other, None)?;
                match prim_of(&t) {
                    Some(Prim::Boolean) => {
                        self.to_prim(&t);
                        self.asm.jump(if jump_if { 0x9a } else { 0x99 }, 1, to);
                        Ok(())
                    }
                    _ => self.err(other.span(), format!("a {t} is not a boolean")),
                }
            }
        }
    }

    fn compare(
        &mut self,
        op: &str,
        left: &Expr,
        right: &Expr,
        jump_if: bool,
        to: Label,
    ) -> Result<()> {
        let lt = self.type_of(left, None)?;
        let rt = self.type_of(right, None)?;
        let idx = cmp_index(op, jump_if);
        let equality = matches!(op, "==" | "!=");
        let (lp, rp) = (prim_of(&lt), prim_of(&rt));
        let numeric = matches!((lp, rp), (Some(a), Some(b)) if a != Prim::Boolean && b != Prim::Boolean)
            && (lt.is_prim() || rt.is_prim() || !equality);
        if numeric {
            let p = promote(
                promote(lp.unwrap_or(Prim::Int), rp.unwrap_or(Prim::Int)),
                Prim::Int,
            );
            // `x == 0`: javac's `ifeq` family, with no constant to push.
            let zero = p == Prim::Int && self.const_int(right) == Some(0);
            let operands = [left, right];
            for e in &operands[..if zero { 1 } else { 2 }] {
                if self.widened_const(e, p) {
                    continue;
                }
                let t = self.expr(e, None)?;
                let q = self.to_prim(&t).unwrap_or(p);
                self.prim_convert(q, p);
            }
            match p {
                Prim::Int if zero => self.asm.jump(0x99 + idx, 1, to),
                Prim::Int => self.asm.jump(0x9f + idx, 2, to),
                Prim::Long => {
                    self.asm.emit(&[0x94], 2, Some(VType::Int));
                    self.asm.jump(0x99 + idx, 1, to);
                }
                _ => {
                    let g = matches!(op, "<" | "<=");
                    let code = if p == Prim::Float { 0x95 } else { 0x97 } + u8::from(g);
                    self.asm.emit(&[code], 2, Some(VType::Int));
                    self.asm.jump(0x99 + idx, 1, to);
                }
            }
            return Ok(());
        }
        if !equality {
            return self.err(
                left.span().to(right.span()),
                format!("`{op}` does not apply to {lt} and {rt}"),
            );
        }
        let bools = lp == Some(Prim::Boolean)
            && rp == Some(Prim::Boolean)
            && (lt.is_prim() || rt.is_prim());
        if bools {
            for e in [left, right] {
                let t = self.expr(e, None)?;
                self.to_prim(&t);
            }
            self.asm.jump(0x9f + idx, 2, to);
            return Ok(());
        }
        if !(lt.is_reference() && rt.is_reference()) {
            return self.err(
                left.span().to(right.span()),
                format!("`{op}` cannot compare {lt} and {rt}"),
            );
        }
        let null_side = [left, right]
            .iter()
            .position(|e| matches!(e, Expr::Lit(Lit::Null, _)));
        if let Some(n) = null_side {
            let other = if n == 0 { right } else { left };
            self.expr(other, None)?;
            let eq_null = (op == "==") == jump_if;
            self.asm.jump(if eq_null { 0xc6 } else { 0xc7 }, 1, to);
            return Ok(());
        }
        if !self.subtype(&lt, &rt)
            && !self.subtype(&rt, &lt)
            && !self.is_interfaceish(&lt)
            && !self.is_interfaceish(&rt)
        {
            return self.err(
                left.span().to(right.span()),
                format!("a {lt} and a {rt} are never the same object"),
            );
        }
        self.expr(left, None)?;
        self.expr(right, None)?;
        self.asm.jump(0xa5 + idx, 2, to);
        Ok(())
    }

    fn is_interfaceish(&self, t: &Type) -> bool {
        match t.erasure() {
            Type::Class(n, _) => self.w.class(&n).is_some_and(|c| c.is_interface()),
            _ => false,
        }
    }

    /// A condition as a `boolean` value: 1 or 0 on the stack.
    pub fn bool_value(&mut self, e: &Expr) -> Result<Type> {
        let (no, end) = (self.asm.label(), self.asm.label());
        let mark = self.push_scope();
        self.cond(e, false, no)?;
        self.pop_scope(mark);
        self.int_const(1);
        self.asm.jump(0xa7, 0, end);
        self.asm.place(no);
        self.int_const(0);
        self.asm.place(end);
        Ok(Type::Prim(Prim::Boolean))
    }
}
