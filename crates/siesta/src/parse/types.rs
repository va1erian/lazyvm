//! Types (JLS 4.3, 8.1.2): primitives, qualified names with type arguments,
//! arrays and type parameters.

use super::Parser;
use crate::ast::{Ident, Prim, TypeArg, TypeParam, TypeRef};
use crate::diag::Result;
use crate::lex::{Kw, Tok};

pub(super) fn prim_of(kw: Kw) -> Option<Prim> {
    Some(match kw {
        Kw::Boolean => Prim::Boolean,
        Kw::Byte => Prim::Byte,
        Kw::Char => Prim::Char,
        Kw::Short => Prim::Short,
        Kw::Int => Prim::Int,
        Kw::Long => Prim::Long,
        Kw::Float => Prim::Float,
        Kw::Double => Prim::Double,
        _ => return None,
    })
}

impl Parser {
    /// A type, with any `[]` after it.
    pub(super) fn ty(&mut self) -> Result<TypeRef> {
        let base = self.base_type()?;
        self.array_dims(base)
    }

    pub(super) fn array_dims(&mut self, mut t: TypeRef) -> Result<TypeRef> {
        while self.is_op("[") && self.is_op_at(1, "]") {
            self.pos += 2;
            let span = t.span().to(self.prev_span());
            t = TypeRef::Array(Box::new(t), span);
        }
        Ok(t)
    }

    pub(super) fn base_type(&mut self) -> Result<TypeRef> {
        let lo = self.span();
        if let Tok::Kw(k) = *self.tok(0) {
            if let Some(p) = prim_of(k) {
                self.pos += 1;
                return Ok(TypeRef::Prim(p, lo));
            }
            if k == Kw::Var {
                self.pos += 1;
                return Ok(TypeRef::Var(lo));
            }
            if k == Kw::Void {
                self.pos += 1;
                return Ok(TypeRef::Void(lo));
            }
        }
        if self.is_kw(Kw::Async) || self.is_kw(Kw::Await) {
            return self.fail("a type expected");
        }
        let path = self.qualified()?;
        let args = if self.is_op("<") {
            self.type_args()?
        } else {
            Vec::new()
        };
        Ok(TypeRef::Named {
            path,
            args,
            span: lo.to(self.prev_span()),
        })
    }

    /// `a.b.c`, stopping before a `.` that is not followed by a name.
    pub(super) fn qualified(&mut self) -> Result<Vec<Ident>> {
        let mut path = vec![self.ident()?];
        while self.is_op(".")
            && matches!(self.tok(1), Tok::Ident(_) | Tok::Kw(Kw::Async | Kw::Await))
        {
            self.pos += 1;
            path.push(self.ident()?);
        }
        Ok(path)
    }

    /// `<A, ? extends B>`; `<>` (diamond) is an empty list.
    pub(super) fn type_args(&mut self) -> Result<Vec<TypeArg>> {
        self.expect_op("<")?;
        let mut args = Vec::new();
        if self.eat_op(">") {
            return Ok(args);
        }
        loop {
            args.push(self.nested(|p| p.type_arg())?);
            if !self.eat_op(",") {
                break;
            }
        }
        self.expect_op(">")?;
        Ok(args)
    }

    fn type_arg(&mut self) -> Result<TypeArg> {
        if self.is_op("?") {
            let span = self.bump().span;
            let upper = self.is_kw(Kw::Extends);
            if upper || self.is_kw(Kw::Super) {
                self.pos += 1;
                let bound = self.ty()?;
                return Ok(TypeArg::Wild {
                    span: span.to(bound.span()),
                    bound: Some(Box::new(bound)),
                    upper,
                });
            }
            return Ok(TypeArg::Wild {
                bound: None,
                upper: true,
                span,
            });
        }
        let t = self.ty()?;
        if matches!(t, TypeRef::Var(_) | TypeRef::Void(_)) {
            return Err(crate::diag::Diag::new(t.span(), "not a type argument"));
        }
        Ok(TypeArg::Type(t))
    }

    /// `<T, U extends Comparable<U>>` before a class or method.
    pub(super) fn type_params(&mut self) -> Result<Vec<TypeParam>> {
        let mut out = Vec::new();
        if !self.eat_op("<") {
            return Ok(out);
        }
        loop {
            let name = self.ident()?;
            let mut bounds = Vec::new();
            if self.eat_kw(Kw::Extends) {
                bounds.push(self.ty()?);
                while self.eat_op("&") {
                    bounds.push(self.ty()?);
                }
            }
            out.push(TypeParam { name, bounds });
            if !self.eat_op(",") {
                break;
            }
        }
        self.expect_op(">")?;
        Ok(out)
    }

    /// True when the tokens ahead read as a type followed by a name, as at
    /// the start of a declaration: `List<String> x`, `int[] a`, `var v`.
    pub(super) fn at_declaration(&mut self) -> bool {
        if matches!(self.tok(0), Tok::Ident(n) if n == "assert") {
            return false; // Java's `assert x;`, refused as a statement
        }
        let start = self.pos;
        let ok = self.ty().is_ok() && matches!(self.tok(0), Tok::Ident(_));
        self.pos = start;
        ok
    }
}
