//! Primaries and postfix operators (JLS 15.8 to 15.15): literals, names,
//! calls, `new`, indexing, method references, and Siesta's templates,
//! collection literals, cascades and trailing lambdas.

use super::Parser;
use super::types::prim_of;
use crate::ast::{Expr, Ident, LambdaBody, LambdaParam, Lit, TemplatePart, TypeRef};
use crate::diag::{Diag, Result};
use crate::lex::{Kw, Part, Tok};

impl Parser {
    pub(super) fn postfix(&mut self) -> Result<Expr> {
        let mut e = self.primary()?;
        loop {
            e = match self.tok(0) {
                Tok::Op(".") => self.dot(e)?,
                Tok::Op("[") if self.is_op_at(1, "]") => {
                    let ty = self.array_dims(expr_type(&e)?)?;
                    self.type_suffix(ty)?
                }
                Tok::Op("[") => {
                    self.pos += 1;
                    let index = Box::new(self.expr()?);
                    self.expect_op("]")?;
                    Expr::Index {
                        array: Box::new(e),
                        index,
                    }
                }
                Tok::Op("::") => self.method_ref(e)?,
                Tok::Op("..") => self.cascade(e)?,
                Tok::Op(op @ ("++" | "--")) => {
                    let op = *op;
                    let span = e.span().to(self.bump().span);
                    Expr::Step {
                        op,
                        post: true,
                        target: Box::new(e),
                        span,
                    }
                }
                Tok::Op("{") | Tok::Kw(Kw::Async) if self.at_trailing_lambda() => {
                    self.trailing(e)?
                }
                _ => return Ok(e),
            };
        }
    }

    fn dot(&mut self, target: Expr) -> Result<Expr> {
        self.expect_op(".")?;
        if self.is_op("<") {
            return self.fail("explicit type arguments are not supported; the call infers them");
        }
        if self.eat_kw(Kw::Class) {
            let span = target.span().to(self.prev_span());
            return Ok(Expr::ClassLit(expr_type(&target)?, span));
        }
        if self.is_kw(Kw::This) || self.is_kw(Kw::New) {
            return self.fail("inner classes are not supported");
        }
        let name = self.ident()?;
        if self.is_op("(") {
            let args = self.args()?;
            return Ok(Expr::Call {
                target: Some(Box::new(target)),
                name,
                args,
                is_super: false,
            });
        }
        Ok(Expr::Field {
            target: Box::new(target),
            name,
        })
    }

    /// `.class` or `::` after a type written in expression position.
    fn type_suffix(&mut self, ty: TypeRef) -> Result<Expr> {
        if self.is_op("::") {
            return self.method_ref(Expr::TypeExpr(ty));
        }
        self.expect_op(".")?;
        self.expect_kw(Kw::Class)?;
        let span = ty.span().to(self.prev_span());
        Ok(Expr::ClassLit(ty, span))
    }

    fn method_ref(&mut self, target: Expr) -> Result<Expr> {
        self.expect_op("::")?;
        let name = if self.is_kw(Kw::New) {
            Ident {
                name: "new".into(),
                span: self.bump().span,
            }
        } else {
            self.ident()?
        };
        Ok(Expr::MethodRef {
            target: Box::new(target),
            name,
        })
    }

    fn cascade(&mut self, recv: Expr) -> Result<Expr> {
        let (recv, mut calls) = match recv {
            Expr::Cascade { recv, calls } => (recv, calls),
            other => (Box::new(other), Vec::new()),
        };
        while self.eat_op("..") {
            let name = self.ident()?;
            let mut args = self.args()?;
            if self.at_trailing_lambda() {
                args.push(self.trailing_lambda()?);
            }
            calls.push((name, args));
        }
        Ok(Expr::Cascade { recv, calls })
    }

    /// `{`, `{ a, b ->` or `async {` after a call or a member name.
    fn at_trailing_lambda(&self) -> bool {
        let skip = usize::from(*self.tok(0) == Tok::Kw(Kw::Async));
        self.is_op_at(skip, "{")
    }

    /// `x.m(a) { ... }` adds the lambda as the last argument; `x.m { ... }`
    /// is a call with only the lambda.
    fn trailing(&mut self, e: Expr) -> Result<Expr> {
        let lambda = self.trailing_lambda()?;
        Ok(match e {
            Expr::Call {
                target,
                name,
                mut args,
                is_super,
            } => {
                args.push(lambda);
                Expr::Call {
                    target,
                    name,
                    args,
                    is_super,
                }
            }
            Expr::Field { target, name } => Expr::Call {
                target: Some(target),
                name,
                args: vec![lambda],
                is_super: false,
            },
            Expr::Name(name) => Expr::Call {
                target: None,
                name,
                args: vec![lambda],
                is_super: false,
            },
            Expr::New { span, .. } => {
                return Err(Diag::new(
                    span,
                    "anonymous classes are not supported; pass a lambda or a named class",
                ));
            }
            other => {
                return Err(Diag::new(
                    other.span(),
                    "a lambda in braces must follow a call",
                ));
            }
        })
    }

    fn trailing_lambda(&mut self) -> Result<Expr> {
        let lo = self.span();
        let is_async = self.eat_kw(Kw::Async);
        self.expect_op("{")?;
        let mut params = Vec::new();
        let mut i = 0;
        while matches!(self.tok(i), Tok::Ident(_))
            && (self.is_op_at(i + 1, ",") || self.is_op_at(i + 1, "->"))
        {
            if self.is_op_at(i + 1, "->") {
                for _ in 0..=i / 2 {
                    params.push(LambdaParam {
                        ty: None,
                        name: self.ident()?,
                    });
                    self.eat_op(",");
                }
                self.expect_op("->")?;
                break;
            }
            i += 2;
        }
        let mut body = Vec::new();
        while !self.eat_op("}") {
            if *self.tok(0) == Tok::Eof {
                return self.fail("`}` expected");
            }
            body.push(self.stmt()?);
        }
        Ok(Expr::Lambda {
            params,
            body: LambdaBody::Block(body),
            is_async,
            span: lo.to(self.prev_span()),
        })
    }

    pub(super) fn args(&mut self) -> Result<Vec<Expr>> {
        self.expect_op("(")?;
        let mut out = Vec::new();
        while !self.is_op(")") {
            out.push(self.expr()?);
            if !self.eat_op(",") {
                break;
            }
        }
        self.expect_op(")")?;
        Ok(out)
    }

    fn primary(&mut self) -> Result<Expr> {
        let lo = self.span();
        let lit = |l| Ok(Expr::Lit(l, lo));
        match self.tok(0).clone() {
            Tok::Num(n) => {
                self.pos += 1;
                lit(Lit::Num(n))
            }
            Tok::Char(c) => {
                self.pos += 1;
                lit(Lit::Char(c))
            }
            Tok::Str(s) => {
                self.pos += 1;
                lit(Lit::Str(s))
            }
            Tok::Template(parts) => {
                self.pos += 1;
                self.template(parts, lo)
            }
            Tok::Kw(Kw::True) | Tok::Kw(Kw::False) => {
                let v = self.is_kw(Kw::True);
                self.pos += 1;
                lit(Lit::Bool(v))
            }
            Tok::Kw(Kw::Null) => {
                self.pos += 1;
                lit(Lit::Null)
            }
            Tok::Kw(Kw::This) => {
                self.pos += 1;
                Ok(Expr::This(lo))
            }
            Tok::Kw(Kw::Super) => self.super_call(),
            Tok::Kw(Kw::New) => self.new_expr(),
            Tok::Kw(k) if prim_of(k).is_some() || k == Kw::Void => {
                let ty = self.ty()?;
                self.type_suffix(ty)
            }
            Tok::Ident(_) => {
                let name = self.ident()?;
                if self.is_op("(") {
                    let args = self.args()?;
                    return Ok(Expr::Call {
                        target: None,
                        name,
                        args,
                        is_super: false,
                    });
                }
                if self.is_op("<")
                    && let Some(ty) = self.attempt(|p| p.generic_type_expr(name.clone()))
                {
                    return Ok(ty);
                }
                Ok(Expr::Name(name))
            }
            Tok::Op("(") => {
                self.pos += 1;
                let e = self.expr()?;
                self.expect_op(")")?;
                Ok(e)
            }
            Tok::Op("[") => self.collection(),
            Tok::Op("{") => {
                self.fail("an array initialiser needs a declared array type: `new int[] {...}`")
            }
            _ => self.fail("an expression expected"),
        }
    }

    /// `List<String>::new` and the like: a generic type before `::`.
    fn generic_type_expr(&mut self, first: Ident) -> Result<Expr> {
        let span = first.span;
        let args = self.type_args()?;
        let ty = TypeRef::Named {
            path: vec![first],
            args,
            span: span.to(self.prev_span()),
        };
        let ty = self.array_dims(ty)?;
        if !self.is_op("::") {
            return self.fail("not a type");
        }
        self.method_ref(Expr::TypeExpr(ty))
    }

    fn super_call(&mut self) -> Result<Expr> {
        self.expect_kw(Kw::Super)?;
        self.expect_op(".")?;
        let name = self.ident()?;
        if !self.is_op("(") {
            return self.fail("only `super.method(...)` is supported");
        }
        let args = self.args()?;
        Ok(Expr::Call {
            target: None,
            name,
            args,
            is_super: true,
        })
    }

    fn new_expr(&mut self) -> Result<Expr> {
        let lo = self.span();
        self.expect_kw(Kw::New)?;
        let elem = self.base_type()?;
        if self.is_op("(") {
            let args = self.args()?;
            if self.is_op("{") {
                return self
                    .fail("anonymous classes are not supported; pass a lambda or a named class");
            }
            return Ok(Expr::New {
                ty: elem,
                args,
                span: lo.to(self.prev_span()),
            });
        }
        let mut dims = Vec::new();
        while self.is_op("[") && !self.is_op_at(1, "]") {
            self.pos += 1;
            dims.push(self.expr()?);
            self.expect_op("]")?;
        }
        let mut extra = 0;
        while self.is_op("[") && self.is_op_at(1, "]") {
            self.pos += 2;
            extra += 1;
        }
        let init = if dims.is_empty() && self.is_op("{") {
            match self.array_init()? {
                Expr::ArrayInit(items, _) => Some(items),
                _ => None,
            }
        } else {
            None
        };
        if dims.is_empty() && init.is_none() {
            return self.fail("array dimensions or an initialiser expected");
        }
        Ok(Expr::NewArray {
            elem,
            dims,
            extra,
            init,
            span: lo.to(self.prev_span()),
        })
    }

    /// `[a, b]`, `[k: v]`, `[:]` and `[]`.
    fn collection(&mut self) -> Result<Expr> {
        let lo = self.expect_op("[")?;
        if self.eat_op(":") {
            self.expect_op("]")?;
            return Ok(Expr::Map(Vec::new(), lo.to(self.prev_span())));
        }
        if self.eat_op("]") {
            return Ok(Expr::List(Vec::new(), lo.to(self.prev_span())));
        }
        let first = self.expr()?;
        if self.eat_op(":") {
            let mut entries = vec![(first, self.expr()?)];
            while self.eat_op(",") && !self.is_op("]") {
                let k = self.expr()?;
                self.expect_op(":")?;
                entries.push((k, self.expr()?));
            }
            self.expect_op("]")?;
            return Ok(Expr::Map(entries, lo.to(self.prev_span())));
        }
        let mut items = vec![first];
        while self.eat_op(",") && !self.is_op("]") {
            items.push(self.expr()?);
        }
        self.expect_op("]")?;
        Ok(Expr::List(items, lo.to(self.prev_span())))
    }

    fn template(&mut self, parts: Vec<Part>, span: crate::diag::Span) -> Result<Expr> {
        let mut out = Vec::new();
        for part in parts {
            match part {
                Part::Text(t) if t.is_empty() => {}
                Part::Text(t) => out.push(TemplatePart::Text(t)),
                Part::Expr(toks) => {
                    let mut sub = self.sub(toks);
                    if *sub.tok(0) == Tok::Eof {
                        return Err(Diag::new(span, "empty `${}`"));
                    }
                    let e = sub.expr()?;
                    if *sub.tok(0) != Tok::Eof {
                        return sub.fail("`}` expected");
                    }
                    out.push(TemplatePart::Expr(e));
                }
            }
        }
        Ok(Expr::Template(out, span))
    }
}

/// The type a name such as `java.util.List` or `String` stands for, when it
/// is used before `[]`, `.class` or `::`.
fn expr_type(e: &Expr) -> Result<TypeRef> {
    fn path(e: &Expr, out: &mut Vec<Ident>) -> bool {
        match e {
            Expr::Name(n) => {
                out.push(n.clone());
                true
            }
            Expr::Field { target, name } => {
                let ok = path(target, out);
                out.push(name.clone());
                ok
            }
            _ => false,
        }
    }
    if let Expr::TypeExpr(t) = e {
        return Ok(t.clone());
    }
    let mut segs = Vec::new();
    if path(e, &mut segs) {
        return Ok(TypeRef::Named {
            path: segs,
            args: Vec::new(),
            span: e.span(),
        });
    }
    Err(Diag::new(e.span(), "a type expected"))
}
