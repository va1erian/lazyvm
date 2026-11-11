//! Operators (JLS 15.15 to 15.26): arithmetic with numeric promotion,
//! string concatenation, assignment, `++` and `--`, and `?:`.

use super::asm::VType;
use super::body::Body;
use super::names::Place;
use super::ops::{family, prim_of, promote};
use crate::ast::{Expr, Lit, Prim};
use crate::diag::{Result, Span};
use crate::types::{STRING, Type};

fn arith_base(op: &str) -> Option<u8> {
    Some(match op {
        "+" => 0x60,
        "-" => 0x64,
        "*" => 0x68,
        "/" => 0x6c,
        "%" => 0x70,
        "<<" => 0x78,
        ">>" => 0x7a,
        ">>>" => 0x7c,
        "&" => 0x7e,
        "|" => 0x80,
        "^" => 0x82,
        _ => return None,
    })
}

fn is_shift(op: &str) -> bool {
    matches!(op, "<<" | ">>" | ">>>")
}

fn is_bitwise(op: &str) -> bool {
    matches!(op, "&" | "|" | "^")
}

impl Body<'_> {
    /// The result type of `left op right` without emitting, or `None` when
    /// the operands do not fit the operator.
    fn binary_result(&self, op: &str, lt: &Type, rt: &Type) -> Option<Prim> {
        let (a, b) = (prim_of(lt)?, prim_of(rt)?);
        if is_bitwise(op) && a == Prim::Boolean && b == Prim::Boolean {
            return Some(Prim::Boolean);
        }
        if a == Prim::Boolean || b == Prim::Boolean {
            return None;
        }
        if is_shift(op) {
            return Some(promote(a, Prim::Int));
        }
        if is_bitwise(op) && matches!(promote(a, b), Prim::Float | Prim::Double) {
            return None;
        }
        Some(promote(a, b))
    }

    pub fn binary(
        &mut self,
        op: &'static str,
        left: &Expr,
        right: &Expr,
        whole: &Expr,
    ) -> Result<Type> {
        let lt = self.type_of(left, None)?;
        let rt = self.type_of(right, None)?;
        if op == "+" && (lt.is_class(STRING) || rt.is_class(STRING)) {
            let mut parts = Vec::new();
            self.concat_parts(whole, &mut parts)?;
            self.builder_start();
            for p in parts {
                let t = self.expr(p, None)?;
                if t == Type::Void {
                    return self.err(p.span(), "this expression has no value");
                }
                self.append(&t);
            }
            self.builder_finish();
            return Ok(Type::string());
        }
        let Some(p) = self.binary_result(op, &lt, &rt) else {
            return self.err(
                whole.span(),
                format!("`{op}` does not apply to {lt} and {rt}"),
            );
        };
        let operand = if is_shift(op) {
            p
        } else if p == Prim::Boolean {
            Prim::Int
        } else {
            p
        };
        self.operand(left, operand)?;
        let right_to = if is_shift(op) { Prim::Int } else { operand };
        self.operand(right, right_to)?;
        self.arith_op(op, operand);
        Ok(Type::Prim(p))
    }

    /// Emits `e` converted to `p` for an operator.
    fn operand(&mut self, e: &Expr, p: Prim) -> Result<()> {
        let t = self.expr(e, None)?;
        let Some(q) = self.to_prim(&t) else {
            return self.err(e.span(), format!("a {t} is not a number"));
        };
        if q == Prim::Boolean && p == Prim::Int {
            return Ok(());
        }
        self.prim_convert(q, p);
        Ok(())
    }

    fn arith_op(&mut self, op: &str, p: Prim) {
        let Some(base) = arith_base(op) else { return };
        let f = family(&Type::Prim(p));
        let code = if is_shift(op) || is_bitwise(op) {
            base + u8::from(f == 1)
        } else {
            base + f
        };
        let t = if p == Prim::Boolean {
            VType::Int
        } else {
            VType::of(&Type::Prim(p))
        };
        self.asm.emit(&[code], 2, Some(t));
    }

    /// The operands of a chain of `+` that builds a string, left to right.
    fn concat_parts<'e>(&mut self, e: &'e Expr, out: &mut Vec<&'e Expr>) -> Result<()> {
        if let Expr::Binary {
            op: "+",
            left,
            right,
        } = e
        {
            let lt = self.type_of(left, None)?;
            let rt = self.type_of(right, None)?;
            if lt.is_class(STRING) || rt.is_class(STRING) {
                self.concat_parts(left, out)?;
                out.push(right);
                return Ok(());
            }
        }
        out.push(e);
        Ok(())
    }

    pub fn unary(&mut self, op: &str, e: &Expr, span: Span, want: Option<&Type>) -> Result<Type> {
        if op == "!" {
            return self.bool_value(&Expr::Unary {
                op: "!",
                expr: Box::new(e.clone()),
                span,
            });
        }
        if op == "-"
            && let Expr::Lit(l @ Lit::Num(_), s) = e
        {
            return self.literal(l, *s, want, true);
        }
        let t = self.type_of(e, None)?;
        let Some(p) = prim_of(&t).filter(|p| *p != Prim::Boolean) else {
            return self.err(span, format!("`{op}` does not apply to {t}"));
        };
        let p = promote(p, Prim::Int);
        self.operand(e, p)?;
        match op {
            "-" => self.asm.emit(
                &[0x74 + family(&Type::Prim(p))],
                1,
                Some(VType::of(&Type::Prim(p))),
            ),
            "~" => {
                if matches!(p, Prim::Float | Prim::Double) {
                    return self.err(span, "`~` needs an integer");
                }
                if p == Prim::Long {
                    self.long_const(-1);
                } else {
                    self.int_const(-1);
                }
                self.arith_op("^", p);
            }
            _ => {}
        }
        Ok(Type::Prim(p))
    }

    pub fn assign(
        &mut self,
        op: Option<&'static str>,
        target: &Expr,
        value: &Expr,
        keep: bool,
    ) -> Result<Type> {
        let p = self.place(target)?;
        let ty = p.ty();
        match op {
            None => {
                let vt = self.expr(value, Some(&ty))?;
                self.coerce(&vt, &ty, value.span())?;
            }
            Some("+") if ty.is_class(STRING) => {
                self.place_dup_operands(&p);
                self.place_load(&p);
                self.builder_start();
                self.asm.swap();
                self.append(&ty);
                let vt = self.expr(value, None)?;
                self.append(&vt);
                self.builder_finish();
            }
            Some(op) => {
                self.place_dup_operands(&p);
                self.place_load(&p);
                let rt = self.type_of(value, None)?;
                let Some(res) = self.binary_result(op, &ty, &rt) else {
                    return self.err(
                        target.span().to(value.span()),
                        format!("`{op}=` does not apply to {ty} and {rt}"),
                    );
                };
                let operand = if res == Prim::Boolean { Prim::Int } else { res };
                let cur = self.to_prim(&ty).unwrap_or(Prim::Int);
                if cur != Prim::Boolean {
                    self.prim_convert(cur, operand);
                }
                self.operand(value, if is_shift(op) { Prim::Int } else { operand })?;
                self.arith_op(op, operand);
                self.store_converted(operand, &ty);
            }
        }
        if keep {
            self.asm.dup_under(p.below());
        }
        self.place_store(&p);
        Ok(if keep { ty } else { Type::Void })
    }

    /// The primitive result on the stack, cast back to a variable's type
    /// (JLS 15.26.2's implicit cast), boxed if the variable is a wrapper.
    fn store_converted(&mut self, from: Prim, to: &Type) {
        match to {
            Type::Prim(q) => self.prim_convert(from, *q),
            other => {
                if let Some(q) = prim_of(other) {
                    self.prim_convert(from, q);
                    self.box_prim(q);
                }
            }
        }
    }

    pub fn step(
        &mut self,
        op: &str,
        post: bool,
        target: &Expr,
        span: Span,
        keep: bool,
    ) -> Result<Type> {
        let delta: i8 = if op == "++" { 1 } else { -1 };
        if let Expr::Name(n) = target
            && let Some(Place::Local(l)) = self.place_of_name(n)?
            && !l.boxed
            && l.ty == Type::Prim(Prim::Int)
            && let Ok(slot) = u8::try_from(l.slot)
        {
            if keep && post {
                self.load(l.slot, &l.ty);
            }
            self.asm.emit(&[0x84, slot, delta as u8], 0, None);
            if keep && !post {
                self.load(l.slot, &l.ty);
            }
            return Ok(if keep { l.ty } else { Type::Void });
        }
        let p = self.place(target)?;
        let ty = p.ty();
        let Some(q) = prim_of(&ty).filter(|q| *q != Prim::Boolean) else {
            return self.err(span, format!("`{op}` does not apply to {ty}"));
        };
        self.place_dup_operands(&p);
        self.place_load(&p);
        if keep && post {
            self.asm.dup_under(p.below());
        }
        self.to_prim(&ty);
        let w = promote(q, Prim::Int);
        self.prim_convert(q, w);
        match w {
            Prim::Long => self.long_const(1),
            Prim::Float => self.float_const(1.0),
            Prim::Double => self.double_const(1.0),
            _ => self.int_const(1),
        }
        self.arith_op(if delta > 0 { "+" } else { "-" }, w);
        self.store_converted(w, &ty);
        if keep && !post {
            self.asm.dup_under(p.below());
        }
        self.place_store(&p);
        Ok(if keep { ty } else { Type::Void })
    }

    pub fn conditional(
        &mut self,
        cond: &Expr,
        then: &Expr,
        els: &Expr,
        want: Option<&Type>,
    ) -> Result<Type> {
        let t = match want {
            Some(w) if *w != Type::Void => w.clone(),
            _ => {
                let a = self.type_of(then, None)?;
                let b = self.type_of(els, None)?;
                self.join(&a, &b)
            }
        };
        let (no, end) = (self.asm.label(), self.asm.label());
        self.cond(cond, false, no)?;
        let a = self.expr(then, Some(&t))?;
        self.coerce(&a, &t, then.span())?;
        self.asm.retype_top(VType::of(&t));
        self.asm.jump(0xa7, 0, end);
        self.asm.place(no);
        let b = self.expr(els, Some(&t))?;
        self.coerce(&b, &t, els.span())?;
        self.asm.retype_top(VType::of(&t));
        self.asm.place(end);
        Ok(t)
    }

    /// The type of `c ? a : b` (JLS 15.25, simplified).
    pub fn join(&self, a: &Type, b: &Type) -> Type {
        if a == b {
            return a.clone();
        }
        match (a, b) {
            (Type::Null, t) | (t, Type::Null) if t.is_reference() => return t.clone(),
            (Type::Null, Type::Prim(p)) | (Type::Prim(p), Type::Null) => {
                return Type::class(crate::types::boxed(*p));
            }
            _ => {}
        }
        if let (Some(p), Some(q)) = (prim_of(a), prim_of(b))
            && (a.is_prim() || b.is_prim())
        {
            return if p == q {
                Type::Prim(p)
            } else {
                Type::Prim(promote(p, q))
            };
        }
        if self.subtype(a, b) {
            return b.clone();
        }
        if self.subtype(b, a) {
            return a.clone();
        }
        let mut cur = a.erasure();
        while let Some(sup) = self.w.supertypes(&cur).into_iter().next() {
            if self.subtype(b, &sup) {
                return sup;
            }
            cur = sup;
        }
        Type::object()
    }
}
