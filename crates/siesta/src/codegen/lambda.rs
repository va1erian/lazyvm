//! Lambdas and method references (JLS 15.27, 15.13) as private synthetic
//! methods and `invokedynamic` to `LambdaMetafactory.metafactory`. A method
//! reference is rewritten as the lambda that calls it.

use std::collections::{HashMap, HashSet};

use super::asm::{Asm, VType};
use super::body::{Body, Local};
use crate::ast::{Expr, LambdaBody, LambdaParam};
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
        if is_async {
            let spec_params = captured
                .iter()
                .map(|(n, l)| (n.clone(), l.ty.clone(), l.boxed))
                .chain(
                    params
                        .iter()
                        .zip(&types)
                        .map(|(p, t)| (p.name.name.clone(), t.clone(), false)),
                )
                .collect();
            let m = self.async_lambda(&name, access, &impl_desc, spec_params, &ret, &body, span)?;
            self.extra.methods.push(m);
        } else {
            self.plain_lambda(
                &name, access, &impl_desc, &params, &types, &captured, &ret, &body, span,
            )?;
        }
        self.lambda_site(
            &target, &f, &name, &impl_desc, &types, &ret, &captured, &cap_types,
        );
        Ok(target)
    }

    /// The synthetic method of an ordinary lambda.
    #[allow(clippy::too_many_arguments)]
    fn plain_lambda(
        &mut self,
        name: &str,
        access: u16,
        impl_desc: &str,
        params: &[LambdaParam],
        types: &[Type],
        captured: &[(String, Local)],
        ret: &Type,
        body: &LambdaBody,
        span: Span,
    ) -> Result<()> {
        let ret = ret.clone();
        let asm = {
            let mut nested = self.lambda_body(params, types, captured, ret.clone(), false)?;
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
        let m = super::method_def(asm, self.pool, access, name, impl_desc)
            .ok_or_else(|| Diag::new(span, "this lambda is too large"))?;
        self.extra.methods.push(m);
        Ok(())
    }

    /// An `async` lambda: its synthetic method is an async entry. With a
    /// `void` interface method the lambda is fire-and-forget and a failure
    /// is rethrown where it happens (siesta-async.md section 2).
    #[allow(clippy::too_many_arguments)]
    fn async_lambda(
        &mut self,
        name: &str,
        access: u16,
        impl_desc: &str,
        params: Vec<(String, Type, bool)>,
        ret: &Type,
        body: &LambdaBody,
        span: Span,
    ) -> Result<classfile::writer::MethodDef> {
        let report = *ret == Type::Void;
        let value = if report {
            Type::Void
        } else {
            match self.w.as_super(ret, super::async_body::STAGE) {
                Some(Type::Class(_, a)) if a.len() == 1 => a[0].clone(),
                _ => {
                    return self.err(
                        span,
                        format!(
                            "an `async` lambda gives a CompletionStage, not {}",
                            crate::types::with_article(ret)
                        ),
                    );
                }
            }
        };
        let stmts = match body {
            LambdaBody::Expr(x) if report => vec![crate::ast::Stmt::Expr((**x).clone())],
            LambdaBody::Expr(x) => vec![crate::ast::Stmt::Return(Some((**x).clone()), span)],
            LambdaBody::Block(b) => b.clone(),
        };
        let body = super::hoist::Hoist::new(&format!("{name}_")).stmts(&stmts)?;
        let spec = super::async_body::AsyncSpec {
            name: name.to_string(),
            access,
            desc: impl_desc.to_string(),
            is_static: self.is_static,
            params,
            value,
            report,
            returns_frame: !report,
            body,
            span,
        };
        self.build_async(spec)
    }

    /// The `invokedynamic` that makes the lambda: captured values in, the
    /// interface out.
    #[allow(clippy::too_many_arguments)]
    fn lambda_site(
        &mut self,
        target: &Type,
        f: &Method,
        name: &str,
        impl_desc: &str,
        types: &[Type],
        ret: &Type,
        captured: &[(String, Local)],
        cap_types: &[Type],
    ) {
        let this_t = self.this_type();
        if !self.is_static {
            self.load(0, &this_t);
        }
        for (_, l) in captured {
            let t = if l.boxed {
                Type::Array(Box::new(l.ty.clone()))
            } else {
                l.ty.clone()
            };
            self.load(l.slot, &t);
        }
        let bsm = self.bootstrap(f, name, impl_desc, types, ret);
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
            Some(VType::of(target)),
        );
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
            async_cx: None,
        })
    }
}
