//! Method references (JLS 15.13) rewritten as the lambdas that call them,
//! so they share the lambda path: `String::length` is `$p0 -> $p0.length()`.

use super::asm::VType;
use super::body::Body;
use crate::ast::{Expr, Ident, LambdaBody, LambdaParam};
use crate::diag::Result;
use crate::types::Type;

impl Body<'_> {
    /// A lambda, or the lambda a method reference stands for. A bound
    /// receiver is evaluated into a local first, unless only typing.
    pub(super) fn as_lambda(
        &mut self,
        e: &Expr,
        arity: usize,
        dry: bool,
    ) -> Result<(Vec<LambdaParam>, LambdaBody, bool)> {
        match e {
            Expr::Lambda {
                params,
                body,
                is_async,
                ..
            } => Ok((params.clone(), body.clone(), *is_async)),
            Expr::MethodRef { target, name } => {
                let span = name.span;
                let params: Vec<LambdaParam> = (0..arity)
                    .map(|i| LambdaParam {
                        ty: None,
                        name: Ident {
                            name: format!("$p{i}"),
                            span,
                        },
                    })
                    .collect();
                let arg = |i: usize| {
                    Expr::Name(Ident {
                        name: format!("$p{i}"),
                        span,
                    })
                };
                let args: Vec<Expr> = (0..arity).map(arg).collect();
                let body = self.ref_body(target, name, args, arity, dry)?;
                Ok((params, LambdaBody::Expr(Box::new(body)), false))
            }
            other => self.err(other.span(), "not a lambda"),
        }
    }

    fn ref_body(
        &mut self,
        target: &Expr,
        name: &Ident,
        args: Vec<Expr>,
        arity: usize,
        dry: bool,
    ) -> Result<Expr> {
        let span = name.span;
        if name.name == "new" {
            let (Expr::TypeExpr(t) | Expr::ClassLit(t, _)) = target else {
                let ty = self.ref_type(target)?;
                return Ok(Expr::New { ty, args, span });
            };
            if let crate::ast::TypeRef::Array(elem, _) = t {
                return Ok(Expr::NewArray {
                    elem: (**elem).clone(),
                    dims: args,
                    extra: 0,
                    init: None,
                    span,
                });
            }
            return Ok(Expr::New {
                ty: t.clone(),
                args,
                span,
            });
        }
        let is_class = match target {
            Expr::TypeExpr(_) => true,
            Expr::Name(n) => {
                self.find_local(&n.name).is_none() && self.place_of_name(n).ok().flatten().is_none()
            }
            Expr::Field { .. } => self.is_class_path(target),
            _ => false,
        };
        if !is_class {
            if matches!(target, Expr::This(_)) || dry || self.dry {
                return Ok(Expr::Call {
                    target: Some(Box::new(target.clone())),
                    name: name.clone(),
                    args,
                    is_super: false,
                });
            }
            // A bound receiver is evaluated once, when the reference is,
            // and must not be null then (JLS 15.13.3).
            let recv = format!("$recv{}", self.extra.lambda_count);
            let t = self.expr(target, None)?;
            self.asm.dup_under(0);
            let gc = self
                .pool
                .method_ref("java/lang/Object", "getClass", "()Ljava/lang/Class;");
            self.asm
                .emit_u2(0xb6, gc, 1, Some(VType::Object("java/lang/Class".into())));
            self.asm.emit(&[0x57], 1, None);
            let l = self.declare(&recv, t.clone(), span)?;
            self.store(l.slot, &t);
            let r = Expr::Name(Ident { name: recv, span });
            return Ok(Expr::Call {
                target: Some(Box::new(r)),
                name: name.clone(),
                args,
                is_super: false,
            });
        }
        // `Type::m`: a static method taking every argument, or an
        // instance method of the first.
        let class = match self.receiver(target)? {
            super::names::Recv::Class(c) => c,
            _ => return self.err(target.span(), "not a class"),
        };
        let statics = self
            .w
            .methods(&Type::class(&class), &name.name)
            .iter()
            .any(|m| m.info.is_static() && m.params.len() == arity);
        if statics || arity == 0 {
            return Ok(Expr::Call {
                target: Some(Box::new(target.clone())),
                name: name.clone(),
                args,
                is_super: false,
            });
        }
        let mut rest = args;
        let recv = rest.remove(0);
        Ok(Expr::Call {
            target: Some(Box::new(recv)),
            name: name.clone(),
            args: rest,
            is_super: false,
        })
    }

    /// The class of `Type::method` (not `::new`), for inference.
    pub fn ref_class(&self, e: &Expr) -> Option<Type> {
        let Expr::MethodRef { target, name } = e else {
            return None;
        };
        if name.name == "new" {
            return None;
        }
        match &**target {
            Expr::TypeExpr(t) => self.resolve_type(t).ok(),
            t @ (Expr::Name(_) | Expr::Field { .. }) if self.is_class_path(t) => {
                let ty = self.ref_type(t).ok()?;
                self.resolve_type(&ty).ok()
            }
            _ => None,
        }
    }

    /// Whether a dotted name is a class, without emitting anything.
    fn is_class_path(&self, target: &Expr) -> bool {
        let mut path = Vec::new();
        let mut cur = target;
        while let Expr::Field { target, name } = cur {
            path.push(name.name.as_str());
            cur = target;
        }
        let Expr::Name(first) = cur else { return false };
        if self.find_local(&first.name).is_some() {
            return false;
        }
        path.push(first.name.as_str());
        path.reverse();
        crate::resolve::qualified_class(self.w, &path).is_some()
            || self
                .fscope
                .class(self.w, &first.name, &self.cls.enclosing)
                .is_some()
    }

    fn ref_type(&self, target: &Expr) -> Result<crate::ast::TypeRef> {
        let mut path = Vec::new();
        let mut cur = target;
        while let Expr::Field { target, name } = cur {
            path.push(name.clone());
            cur = target;
        }
        let Expr::Name(first) = cur else {
            return self.err(target.span(), "a class expected before `::new`");
        };
        path.push(first.clone());
        path.reverse();
        Ok(crate::ast::TypeRef::Named {
            path,
            args: Vec::new(),
            span: target.span(),
        })
    }
}
