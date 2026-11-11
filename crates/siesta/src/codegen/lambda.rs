//! Lambdas and method references (JLS 15.27, 15.13) as private synthetic
//! methods and `invokedynamic` to `LambdaMetafactory.metafactory`. A method
//! reference is rewritten as the lambda that calls it.

use std::collections::{HashMap, HashSet};

use super::asm::{Asm, VType};
use super::body::{Body, Local};
use crate::ast::{Expr, Ident, LambdaBody, LambdaParam};
use crate::diag::{Diag, Result, Span};
use crate::types::Type;
use crate::world::Method;

const METAFACTORY: &str = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";

/// The type a functional interface method's types have inside a lambda:
/// inference variables and free type variables become their erasure.
fn concrete(t: &Type, vars: &HashMap<String, Type>) -> Type {
    match t {
        Type::Infer(_) => Type::object(),
        Type::Var(n, _) if !vars.contains_key(&**n) => t.erasure(),
        Type::Class(n, args) => {
            Type::Class(n.clone(), args.iter().map(|a| concrete(a, vars)).collect())
        }
        Type::Array(e) => Type::Array(Box::new(concrete(e, vars))),
        other => other.clone(),
    }
}

impl Body<'_> {
    fn sam(&self, target: Option<&Type>, span: Span) -> Result<(Type, Method)> {
        let Some(t) = target.map(|t| concrete(t, &self.vars)) else {
            return self.err(span, "a lambda needs a functional interface type here");
        };
        match self.w.functional_method(&t) {
            Some(m) => Ok((t, m)),
            None => self.err(span, format!("a {t} is not a functional interface")),
        }
    }

    /// The type a lambda's body gives, for inferring a call's type
    /// arguments; `None` when it gives nothing.
    pub fn lambda_result(&mut self, e: &Expr, target: &Type) -> Result<Option<Type>> {
        let (_, f) = self.sam(Some(target), e.span())?;
        let (params, body, _) = self.as_lambda(e, f.params.len(), true)?;
        let types: Vec<Type> = f.params.iter().map(|p| concrete(p, &self.vars)).collect();
        let mut nested = self.lambda_body(&params, &types, &[], Type::Infer(u32::MAX), true)?;
        let r = match &body {
            LambdaBody::Expr(x) => nested.expr(x, None).map(Some),
            LambdaBody::Block(b) => nested.stmts(b).map(|_| nested.ret_seen.clone()),
        };
        Ok(r?.filter(|t| *t != Type::Void))
    }

    pub fn lambda_value(&mut self, e: &Expr, want: Option<&Type>) -> Result<Type> {
        let (target, f) = self.sam(want, e.span())?;
        if self.dry {
            self.asm.push(VType::of(&target));
            return Ok(target);
        }
        let (params, body, is_async) = self.as_lambda(e, f.params.len(), false)?;
        if is_async {
            return self.err(e.span(), "`async` lambdas are not supported yet (S16-07)");
        }
        let mut types = Vec::new();
        for (p, ft) in params.iter().zip(&f.params) {
            types.push(match &p.ty {
                Some(t) => self.resolve_type(t)?,
                None => concrete(ft, &self.vars),
            });
        }
        if params.len() != f.params.len() {
            return self.err(
                e.span(),
                format!(
                    "this lambda takes {} parameters; {} needs {}",
                    params.len(),
                    target,
                    f.params.len()
                ),
            );
        }
        // Outer locals the body mentions are passed in, boxes included.
        let names = super::captures::names_in(&body);
        let captured: Vec<(String, Local)> = self
            .scopes
            .iter()
            .flatten()
            .filter(|(n, _)| names.contains(n) && !params.iter().any(|p| p.name.name == *n))
            .cloned()
            .collect();
        let ret = concrete(&f.ret, &self.vars);
        let n = self.extra.lambda_count;
        self.extra.lambda_count += 1;
        let name = format!("lambda${}${n}", self.name.replace(['<', '>'], ""));
        let cap_types: Vec<Type> = captured
            .iter()
            .map(|(_, l)| {
                if l.boxed {
                    Type::Array(Box::new(l.ty.clone()))
                } else {
                    l.ty.clone()
                }
            })
            .collect();
        let impl_params: String = cap_types
            .iter()
            .chain(&types)
            .map(Type::descriptor)
            .collect();
        let impl_desc = format!("({impl_params}){}", ret.descriptor());
        let mut access = classfile::ACC_PRIVATE | classfile::ACC_SYNTHETIC;
        if self.is_static {
            access |= classfile::ACC_STATIC;
        }
        let span = e.span();
        let asm = {
            let mut nested = self.lambda_body(&params, &types, &captured, ret.clone(), false)?;
            match &body {
                LambdaBody::Expr(x) if ret == Type::Void => nested.expr_stmt(x)?,
                LambdaBody::Expr(x) => {
                    let t = nested.expr(x, Some(&ret))?;
                    nested.coerce(&t, &ret, x.span())?;
                }
                LambdaBody::Block(b) => nested.stmts(b)?,
            }
            if nested.asm.reachable() {
                if ret != Type::Void && !matches!(body, LambdaBody::Expr(_)) {
                    return Err(Diag::new(span, "this lambda must return a value"));
                }
                nested.ret(&ret);
            }
            std::mem::take(&mut nested.asm)
        };
        let m = super::method_def(asm, self.pool, access, &name, &impl_desc)
            .ok_or_else(|| Diag::new(e.span(), "this lambda is too large"))?;
        self.extra.methods.push(m);
        // invokedynamic: the captured values in, the interface out.
        let this_t = self.this_type();
        if !self.is_static {
            self.load(0, &this_t);
        }
        for (_, l) in &captured {
            let t = if l.boxed {
                Type::Array(Box::new(l.ty.clone()))
            } else {
                l.ty.clone()
            };
            self.load(l.slot, &t);
        }
        let bsm = self.bootstrap(&f, &name, &impl_desc, &types, &ret);
        let site_params: String = (if self.is_static {
            None
        } else {
            Some(this_t.descriptor())
        })
        .into_iter()
        .chain(cap_types.iter().map(Type::descriptor))
        .collect();
        let site = format!("({site_params}){}", target.erasure().descriptor());
        let indy = self.pool.invoke_dynamic(bsm, &f.info.name, &site);
        let [h, l] = indy.to_be_bytes();
        self.asm.emit(
            &[0xba, h, l, 0, 0],
            captured.len() + usize::from(!self.is_static),
            Some(VType::of(&target)),
        );
        Ok(target)
    }

    fn bootstrap(
        &mut self,
        f: &Method,
        name: &str,
        impl_desc: &str,
        types: &[Type],
        ret: &Type,
    ) -> u16 {
        let mf = self.pool.method_ref(
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            METAFACTORY,
        );
        let handle = self.pool.method_handle(6, mf);
        let sam = self.pool.method_type(&f.info.desc);
        let kind = if self.is_static { 6 } else { 7 };
        let target = self.pool.method_ref(&self.cls.name, name, impl_desc);
        let impl_h = self.pool.method_handle(kind, target);
        let inst: String = f
            .params
            .iter()
            .zip(types)
            .map(|(_, t)| t.descriptor())
            .collect();
        let inst_t = self
            .pool
            .method_type(&format!("({inst}){}", ret.descriptor()));
        self.extra
            .bootstraps
            .push((handle, vec![sam, impl_h, inst_t]));
        (self.extra.bootstraps.len() - 1) as u16
    }

    /// A body for a lambda: `this` (when not static), the captured locals,
    /// then the parameters.
    fn lambda_body(
        &mut self,
        params: &[LambdaParam],
        types: &[Type],
        captured: &[(String, Local)],
        ret: Type,
        dry: bool,
    ) -> Result<Body<'_>> {
        let mut asm = Asm::new(Vec::new());
        if !self.is_static {
            asm.push_local(VType::of(&self.this_type()));
        }
        let mut scope = Vec::new();
        for (n, l) in captured {
            let t = if l.boxed {
                Type::Array(Box::new(l.ty.clone()))
            } else {
                l.ty.clone()
            };
            let slot = asm.push_local(VType::of(&t));
            scope.push((
                n.clone(),
                Local {
                    slot,
                    ty: l.ty.clone(),
                    boxed: l.boxed,
                },
            ));
        }
        if dry {
            for (n, l) in self.scopes.iter().flatten() {
                if !scope.iter().any(|(s, _)| s == n) {
                    let t = if l.boxed {
                        Type::Array(Box::new(l.ty.clone()))
                    } else {
                        l.ty.clone()
                    };
                    let slot = asm.push_local(VType::of(&t));
                    scope.push((
                        n.clone(),
                        Local {
                            slot,
                            ty: l.ty.clone(),
                            boxed: l.boxed,
                        },
                    ));
                }
            }
        }
        for (p, t) in params.iter().zip(types) {
            if scope.iter().any(|(n, _)| *n == p.name.name)
                || self.find_local(&p.name.name).is_some()
            {
                return self.err(
                    p.name.span,
                    format!("`{}` is already a local variable here", p.name.name),
                );
            }
            let slot = asm.push_local(VType::of(t));
            scope.push((
                p.name.name.clone(),
                Local {
                    slot,
                    ty: t.clone(),
                    boxed: false,
                },
            ));
        }
        let boxed: HashSet<String> = HashSet::new();
        Ok(Body {
            w: self.w,
            cls: self.cls,
            fscope: self.fscope,
            pool: self.pool,
            extra: self.extra,
            line_starts: self.line_starts,
            asm,
            scopes: vec![scope],
            is_static: self.is_static,
            ret,
            vars: self.vars.clone(),
            loops: Vec::new(),
            finallies: Vec::new(),
            boxed,
            dry: dry || self.dry,
            name: self.name.clone(),
            ret_seen: None,
        })
    }

    /// A lambda, or the lambda a method reference stands for. A bound
    /// receiver is evaluated into a local first, unless only typing.
    fn as_lambda(
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
