//! The methods of a class: those written, constructors with field
//! initialisers, a file's `main`, `<clinit>`, an enum's `values` and
//! `valueOf`, and the bridges erasure needs (JLS 15.12.4.5).

use std::collections::HashSet;

use classfile::writer::{MethodDef, Pool};
use classfile::{ACC_PRIVATE, ACC_PUBLIC, ACC_STATIC};

use super::asm::{Asm, VType};
use super::body::{Body, Local};
use super::synth::enum_constants;
use super::{Extra, Unit, is_generic, method_def};
use crate::ast::{Expr, Stmt};
use crate::declare::{Kind, SourceClass, SourceMethod};
use crate::diag::{Diag, Result, Span};
use crate::sig;
use crate::types::Type;

pub struct ClassGen<'a> {
    pub u: &'a Unit<'a>,
    pub c: &'a SourceClass,
    pub pool: &'a mut Pool,
    pub extra: &'a mut Extra,
}

impl ClassGen<'_> {
    pub(super) fn body(
        &mut self,
        is_static: bool,
        ret: Type,
        name: &str,
        locals: Vec<VType>,
    ) -> Body<'_> {
        Body {
            w: self.u.world,
            cls: self.c,
            fscope: &self.u.scopes[self.c.file],
            pool: self.pool,
            extra: self.extra,
            line_starts: &self.u.line_starts[self.c.file],
            asm: Asm::new(locals),
            scopes: vec![Vec::new()],
            is_static,
            ret,
            vars: self.c.vars.clone(),
            loops: Vec::new(),
            finallies: Vec::new(),
            boxed: HashSet::new(),
            dry: false,
            name: name.to_string(),
            ret_seen: None,
            async_cx: None,
        }
    }

    pub fn methods(&mut self, out: &mut Vec<MethodDef>, errors: &mut Vec<Diag>) {
        let c = self.c;
        let mut push = |r: Result<MethodDef>, out: &mut Vec<MethodDef>| match r {
            Ok(m) => out.push(m),
            Err(e) => errors.push(e),
        };
        for sm in &c.methods {
            let r = self.method(sm);
            push(r, out);
        }
        let has_ctor = c.methods.iter().any(|m| m.decl.ret.is_none());
        if !has_ctor && !matches!(c.kind, Kind::Interface | Kind::File) {
            let r = self.constructor(None);
            push(r, out);
        }
        if !c.stmts.is_empty() || c.kind == Kind::File {
            if c.methods.iter().any(|m| m.decl.name.name == "main") {
                errors.push(Diag::new(
                    c.span,
                    "a file with top-level statements already has a `main`",
                ));
            } else {
                let r = self.main();
                push(r, out);
            }
        }
        let r = self.clinit();
        match r {
            Ok(Some(m)) => out.push(m),
            Ok(None) => {}
            Err(e) => errors.push(e),
        }
        if c.kind == Kind::Enum {
            out.push(self.values());
            out.push(self.value_of());
        }
        for m in self.bridges() {
            out.push(m);
        }
    }

    fn method(&mut self, sm: &SourceMethod) -> Result<MethodDef> {
        let info = &sm.info;
        let span = sm.decl.name.span;
        let Some(stmts) = &sm.decl.body else {
            let mut m = MethodDef {
                access: info.access,
                name: info.name.clone(),
                descriptor: info.desc.clone(),
                ..MethodDef::default()
            };
            m.attributes = self.method_sig(sm);
            return Ok(m);
        };
        if sm.decl.mods.bits & crate::ast::mods::ASYNC != 0 {
            return self.async_method(sm, stmts);
        }
        if info.name == "<init>" {
            return self.constructor(Some(sm));
        }
        let is_static = info.access & ACC_STATIC != 0;
        let mut locals = Vec::new();
        if !is_static {
            locals.push(VType::of(&self.c.info.this_type()));
        }
        let ret = info.ret.clone();
        let sig_attrs = self.method_sig(sm);
        let (access, name, desc) = (info.access, info.name.clone(), info.desc.clone());
        let mut b = self.body(is_static, ret.clone(), &name, locals);
        b.vars = sm.vars.clone();
        b.boxed = super::captures::boxed_locals(stmts);
        params(&mut b, sm)?;
        b.stmts(stmts)?;
        end_of_body(&mut b, &ret, span)?;
        let asm = std::mem::take(&mut b.asm);
        let mut m = method_def(asm, self.pool, access, &name, &desc)
            .ok_or_else(|| Diag::new(span, "this method is too large for a class file"))?;
        m.attributes = sig_attrs;
        Ok(m)
    }

    pub(super) fn method_sig(&mut self, sm: &SourceMethod) -> Vec<(String, Vec<u8>)> {
        let info = &sm.info;
        let generic = !info.tparams.is_empty()
            || info
                .params
                .iter()
                .chain(std::iter::once(&info.ret))
                .any(is_generic);
        if !generic || info.name == "<init>" && self.c.kind == Kind::Enum {
            return Vec::new();
        }
        let params: String = info.params.iter().map(sig::write_type).collect();
        let s = format!(
            "{}({params}){}",
            sig::write_params(&info.tparams),
            sig::write_type(&info.ret)
        );
        let i = self.pool.utf8(&s);
        vec![("Signature".into(), i.to_be_bytes().to_vec())]
    }

    /// A constructor: `this(...)` or `super(...)` (written or implied),
    /// then the instance field initialisers unless `this(...)` ran them.
    fn constructor(&mut self, sm: Option<&SourceMethod>) -> Result<MethodDef> {
        let c = self.c;
        let span = sm.map_or(c.span, |m| m.decl.name.span);
        let is_enum = c.kind == Kind::Enum;
        let mut locals = vec![VType::UninitThis];
        if is_enum {
            locals.push(VType::Object(crate::types::STRING.into()));
            locals.push(VType::Int);
        }
        let stmts: &[Stmt] = sm.and_then(|m| m.decl.body.as_deref()).unwrap_or(&[]);
        let (access, desc) = match sm {
            Some(m) => (m.info.access, m.info.desc.clone()),
            None if is_enum => (ACC_PRIVATE, "(Ljava/lang/String;I)V".to_string()),
            None => (ACC_PUBLIC, "()V".to_string()),
        };
        let mut b = self.body(false, Type::Void, "new", locals);
        b.boxed = super::captures::boxed_locals(stmts);
        if let Some(m) = sm {
            b.vars = m.vars.clone();
            params(&mut b, m)?;
        }
        let (first, rest) = match stmts.first() {
            Some(Stmt::CtorCall {
                is_super,
                args,
                span,
            }) => (Some((*is_super, args.as_slice(), *span)), &stmts[1..]),
            _ => (None, stmts),
        };
        let this_call = matches!(first, Some((false, _, _)));
        super_call(&mut b, first, span)?;
        if !this_call {
            field_inits(&mut b)?;
        }
        b.stmts(rest)?;
        end_of_body(&mut b, &Type::Void, span)?;
        let asm = std::mem::take(&mut b.asm);
        method_def(asm, self.pool, access, "<init>", &desc)
            .ok_or_else(|| Diag::new(span, "this constructor is too large for a class file"))
    }

    fn main(&mut self) -> Result<MethodDef> {
        let c = self.c;
        let args_t = Type::Array(Box::new(Type::string()));
        if super::hoist::has_await(&c.stmts) {
            return self.async_main(&args_t);
        }
        let mut b = self.body(true, Type::Void, "main", vec![VType::of(&args_t)]);
        b.boxed = super::captures::boxed_locals(&c.stmts);
        if let Some(s) = b.scopes.last_mut() {
            s.push((
                "args".into(),
                Local {
                    slot: 0,
                    ty: args_t,
                    boxed: false,
                },
            ));
        }
        if b.boxed.contains("args") {
            return Err(Diag::new(c.span, "a lambda cannot change `args`"));
        }
        b.stmts(&c.stmts)?;
        end_of_body(&mut b, &Type::Void, c.span)?;
        let asm = std::mem::take(&mut b.asm);
        method_def(
            asm,
            self.pool,
            ACC_PUBLIC | ACC_STATIC,
            "main",
            "([Ljava/lang/String;)V",
        )
        .ok_or_else(|| Diag::new(c.span, "the top level is too large for a class file"))
    }

    /// Static field initialisers and an enum's constants, in order.
    fn clinit(&mut self) -> Result<Option<MethodDef>> {
        let c = self.c;
        let inits: Vec<_> = c
            .fields
            .iter()
            .filter(|f| f.is_static && !f.top && f.decl.init.is_some())
            .collect();
        if inits.is_empty() && c.kind != Kind::Enum {
            return Ok(None);
        }
        let mut b = self.body(true, Type::Void, "static", Vec::new());
        if c.kind == Kind::Enum {
            enum_constants(&mut b)?;
        }
        for f in inits {
            let Some(init) = &f.decl.init else { continue };
            let t = b.expr(init, Some(&f.ty))?;
            b.coerce(&t, &f.ty, init.span())?;
            let r = b
                .pool
                .field_ref(&c.name, &f.decl.name.name, &f.ty.descriptor());
            b.asm.emit_u2(0xb3, r, 1, None);
        }
        b.ret(&Type::Void);
        let asm = std::mem::take(&mut b.asm);
        method_def(asm, self.pool, ACC_STATIC, "<clinit>", "()V")
            .map(Some)
            .ok_or_else(|| Diag::new(c.span, "the static initialiser is too large"))
    }
}

/// Declares the parameters as locals; a parameter a lambda changes is
/// copied into a box.
fn params(b: &mut Body, sm: &SourceMethod) -> Result<()> {
    for (p, t) in sm.decl.params.iter().zip(&sm.info.params) {
        let slot = b.asm.push_local(VType::of(t));
        if b.boxed.contains(&p.name.name) {
            b.int_const(1);
            b.new_array(t);
            b.asm.dup_under(0);
            b.int_const(0);
            b.load(slot, t);
            b.array_store(t);
            let arr = Type::Array(Box::new(t.clone()));
            let boxed_slot = b.temp(&arr);
            b.store(boxed_slot, &arr);
            if let Some(s) = b.scopes.last_mut() {
                s.push((
                    p.name.name.clone(),
                    Local {
                        slot: boxed_slot,
                        ty: t.clone(),
                        boxed: true,
                    },
                ));
            }
            continue;
        }
        if b.find_local(&p.name.name).is_some() {
            return Err(Diag::new(
                p.name.span,
                format!("two parameters named `{}`", p.name.name),
            ));
        }
        if let Some(s) = b.scopes.last_mut() {
            s.push((
                p.name.name.clone(),
                Local {
                    slot,
                    ty: t.clone(),
                    boxed: false,
                },
            ));
        }
    }
    Ok(())
}

/// A method may not fall off its end unless it returns nothing (JLS 8.4.7).
fn end_of_body(b: &mut Body, ret: &Type, span: Span) -> Result<()> {
    if b.asm.reachable() {
        if *ret != Type::Void {
            return Err(Diag::new(
                span,
                "this method can reach its end without returning a value",
            ));
        }
        b.ret(&Type::Void);
    }
    Ok(())
}

fn super_call(b: &mut Body, first: Option<(bool, &[Expr], Span)>, span: Span) -> Result<()> {
    let c = b.cls;
    let this = Type::class(&c.name);
    if c.kind == Kind::Enum {
        if let Some((_, _, s)) = first {
            return Err(Diag::new(
                s,
                "an enum constructor cannot call `super(...)` or `this(...)`",
            ));
        }
        let _ = &this;
        b.asm.emit(&[0x2a], 0, Some(VType::UninitThis));
        b.asm
            .emit(&[0x2b], 0, Some(VType::Object(crate::types::STRING.into())));
        b.asm.emit(&[0x1c], 0, Some(VType::Int));
        let r = b
            .pool
            .method_ref("java/lang/Enum", "<init>", "(Ljava/lang/String;I)V");
        b.asm.emit_u2(0xb7, r, 3, None);
        b.asm.initialised(&VType::UninitThis, &c.name);
        return Ok(());
    }
    let (is_super, args, at) = first.unwrap_or((true, &[], span));
    let target = if is_super {
        c.info.sig.superclass.clone().unwrap_or_else(Type::object)
    } else {
        c.info.this_type()
    };
    let tname = target.erasure().internal();
    let cands: Vec<_> =
        b.w.methods(&target, "<init>")
            .into_iter()
            .filter(|m| *m.owner.name == *tname)
            .collect();
    let what = if is_super {
        "constructor of the superclass"
    } else {
        "constructor"
    };
    let ch = b.select(cands, args, None, what, at)?;
    b.asm.emit(&[0x2a], 0, Some(VType::UninitThis));
    b.finish_call(ch, args, 0xb7, &tname, at)?;
    b.asm.initialised(&VType::UninitThis, &c.name);
    Ok(())
}

fn field_inits(b: &mut Body) -> Result<()> {
    let c = b.cls;
    for f in c.fields.iter().filter(|f| !f.is_static) {
        let Some(init) = &f.decl.init else { continue };
        let this = Type::class(&c.name);
        b.load(0, &this);
        let t = b.expr(init, Some(&f.ty))?;
        b.coerce(&t, &f.ty, init.span())?;
        let r = b
            .pool
            .field_ref(&c.name, &f.decl.name.name, &f.ty.descriptor());
        b.asm.emit_u2(0xb5, r, 2, None);
    }
    Ok(())
}

impl ClassGen<'_> {
    /// The type of a top-level `var` initialiser, typed where `main` would
    /// run it: only `args` and the file's members are in scope.
    pub fn top_var_type(&mut self, init: &Expr) -> Result<Type> {
        let args_t = Type::Array(Box::new(Type::string()));
        let mut b = self.body(true, Type::Void, "main", vec![VType::of(&args_t)]);
        if let Some(s) = b.scopes.last_mut() {
            s.push((
                "args".into(),
                Local {
                    slot: 0,
                    ty: args_t,
                    boxed: false,
                },
            ));
        }
        let t = b.type_of(init, None)?;
        match t {
            Type::Null | Type::Void | Type::Infer(_) => Err(Diag::new(
                init.span(),
                "`var` cannot take the type of `null` or `void`",
            )),
            other => Ok(other),
        }
    }
}
