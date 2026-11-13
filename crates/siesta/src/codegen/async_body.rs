//! `async` bodies as state machines (siesta-async.md section 3): an entry
//! method that makes a frame and starts the body, a resume method holding
//! the body, and the frame class, which is the result stage and the
//! callback of every `await` at once.

use std::collections::BTreeMap;

use classfile::{ACC_STATIC, ACC_SYNTHETIC};

use super::asm::{Asm, Label, VType};
use super::body::{Body, Local};
use super::method_def;
use crate::ast::{Expr, Ident, Stmt};
use crate::diag::{Diag, Result, Span};
use crate::types::Type;

pub const STAGE: &str = "java/util/concurrent/CompletionStage";
pub const FUTURE: &str = "java/util/concurrent/CompletableFuture";

/// The async body being compiled.
pub struct AsyncCx {
    pub frame: String,
    pub fslot: u16,
    /// The first slot the body's own locals use.
    pub first: u16,
    /// Per `await`: where it resumes, and the slots saved there.
    pub awaits: Vec<(Label, Vec<(u16, VType)>)>,
    /// The type of the value the body completes with.
    pub value: Type,
}

/// What an async entry looks like to its callers.
pub struct AsyncSpec {
    pub name: String,
    pub access: u16,
    pub desc: String,
    pub is_static: bool,
    /// Parameters by name, type, and whether each is a lambda's box.
    pub params: Vec<(String, Type, bool)>,
    pub value: Type,
    /// A fire-and-forget lambda: a failure is rethrown where it happens.
    pub report: bool,
    pub returns_frame: bool,
    pub body: Vec<Stmt>,
    pub span: Span,
}

/// A field of the frame holding a saved slot: by slot and type.
pub fn slot_field(slot: u16, vt: &VType) -> (String, String) {
    let desc = match vt {
        VType::Int => "I".to_string(),
        VType::Long => "J".to_string(),
        VType::Float => "F".to_string(),
        VType::Double => "D".to_string(),
        VType::Object(n) if n.starts_with('[') => n.clone(),
        VType::Object(n) => format!("L{n};"),
        _ => "Ljava/lang/Object;".to_string(),
    };
    let tag: String = desc
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .collect();
    (format!("$s{slot}_{tag}"), desc)
}

fn vtype_type(vt: &VType) -> Type {
    match vt {
        VType::Int => Type::Prim(crate::ast::Prim::Int),
        VType::Long => Type::Prim(crate::ast::Prim::Long),
        VType::Float => Type::Prim(crate::ast::Prim::Float),
        VType::Double => Type::Prim(crate::ast::Prim::Double),
        VType::Object(n) if n.starts_with('[') => {
            crate::sig::field_type(n, &Default::default()).unwrap_or_else(Type::object)
        }
        VType::Object(n) => Type::class(n),
        _ => Type::object(),
    }
}

/// The value type `T` of an `async` method's declared `CompletionStage<T>`.
pub fn value_of_stage(body: &Body, declared: &Type, span: Span) -> Result<Type> {
    let ok = matches!(declared, Type::Class(n, _) if &**n == STAGE || &**n == FUTURE);
    if !ok {
        return Err(Diag::new(
            span,
            "an `async` method returns `CompletionStage<T>`",
        ));
    }
    Ok(match body.w.as_super(declared, STAGE) {
        Some(Type::Class(_, a)) if a.len() == 1 => a[0].clone(),
        _ => Type::object(),
    })
}

impl Body<'_> {
    fn field(&mut self, name: &str, desc: &str) -> u16 {
        let frame = self
            .async_cx
            .as_ref()
            .map(|c| c.frame.clone())
            .unwrap_or_default();
        self.pool.field_ref(&frame, name, desc)
    }

    fn load_frame(&mut self) {
        let (slot, frame) = match &self.async_cx {
            Some(c) => (c.fslot, c.frame.clone()),
            None => return,
        };
        self.asm
            .emit(&[0x19, slot as u8], 0, Some(VType::Object(frame)));
    }

    /// `T x = await e;`, `var x = await e;` or `await e;`.
    pub fn await_stmt(
        &mut self,
        target: Option<(&Ident, Option<Type>)>,
        e: &Expr,
        span: Span,
    ) -> Result<()> {
        if self.async_cx.is_none() {
            return self.err(span, "`await` needs an `async` method or lambda");
        }
        self.load_frame();
        let st = self.expr(e, None)?;
        let Some(stage) = self.w.as_super(&st, STAGE) else {
            return self.err(
                e.span(),
                format!(
                    "{} is not a CompletionStage",
                    crate::types::with_article(&st)
                ),
            );
        };
        let value = match stage {
            Type::Class(_, a) if a.len() == 1 => a[0].clone(),
            _ => Type::object(),
        };
        self.asm.dup_under(1);
        let aw = self.field("awaited", "Ljava/util/concurrent/CompletionStage;");
        self.asm.emit_u2(0xb5, aw, 2, None);
        let first = self.async_cx.as_ref().map_or(0, |c| c.first);
        let live = self.asm.live_slots(first);
        for (slot, vt) in &live {
            self.load_frame();
            self.load(*slot, &vtype_type(vt));
            let (n, d) = slot_field(*slot, vt);
            let f = self.field(&n, &d);
            self.asm.emit_u2(0xb5, f, 2, None);
        }
        let k = self.async_cx.as_ref().map_or(0, |c| c.awaits.len()) + 1;
        self.load_frame();
        self.int_const(k as i32);
        let state = self.field("state", "I");
        self.asm.emit_u2(0xb5, state, 2, None);
        self.load_frame();
        let wc = self.pool.interface_method_ref(
            STAGE,
            "whenComplete",
            "(Ljava/util/function/BiConsumer;)Ljava/util/concurrent/CompletionStage;",
        );
        let [h, l] = wc.to_be_bytes();
        self.asm
            .emit(&[0xb9, h, l, 2, 0], 2, Some(VType::Object(STAGE.into())));
        self.asm.emit(&[0x57], 1, None);
        // Already complete: go on here; else return until the callback.
        let resume = self.asm.label();
        self.asm.record(resume);
        self.load_frame();
        let frame = self
            .async_cx
            .as_ref()
            .map(|c| c.frame.clone())
            .unwrap_or_default();
        let sus = self.pool.method_ref(&frame, "suspend", "()Z");
        self.asm.emit_u2(0xb6, sus, 1, Some(VType::Int));
        self.asm.jump(0x9a, 1, resume);
        self.ret(&Type::Void);
        if let Some(c) = self.async_cx.as_mut() {
            c.awaits.push((resume, live));
        }
        self.asm.place(resume);
        let ok = self.asm.label();
        self.load_frame();
        let err = self.field("error", "Ljava/lang/Throwable;");
        self.asm.emit_u2(
            0xb4,
            err,
            1,
            Some(VType::Object("java/lang/Throwable".into())),
        );
        self.asm.jump(0xc6, 1, ok);
        self.load_frame();
        self.asm.emit_u2(
            0xb4,
            err,
            1,
            Some(VType::Object("java/lang/Throwable".into())),
        );
        let unwrap = self.pool.method_ref(
            &frame,
            "unwrap",
            "(Ljava/lang/Throwable;)Ljava/lang/Throwable;",
        );
        self.asm.emit_u2(
            0xb8,
            unwrap,
            1,
            Some(VType::Object("java/lang/Throwable".into())),
        );
        self.asm.emit(&[0xbf], 1, None);
        self.asm.end_flow();
        self.asm.place(ok);
        let Some((name, declared)) = target else {
            return Ok(());
        };
        self.load_frame();
        let v = self.field("value", "Ljava/lang/Object;");
        self.asm
            .emit_u2(0xb4, v, 1, Some(VType::Object(crate::types::OBJECT.into())));
        let ty = declared.unwrap_or_else(|| value.clone());
        if !value.erasure().is_class(crate::types::OBJECT) {
            self.checkcast(&value.erasure());
        }
        self.coerce(&value, &ty, span)?;
        let l = self.declare(&name.name, ty.clone(), name.span)?;
        if l.boxed {
            return self.err(
                name.span,
                "a lambda cannot change a variable set by `await`",
            );
        }
        self.store(l.slot, &ty);
        Ok(())
    }

    /// `return` in an async body: runs the `finally` blocks, then
    /// completes the frame.
    pub fn async_return(&mut self, v: &Type, value: Option<&Expr>, span: Span) -> Result<()> {
        let void = *v == Type::Void || v.is_class("java/lang/Void");
        match value {
            None if void => {
                self.unwind(0)?;
                self.async_complete(None);
            }
            None => {
                return self.err(
                    span,
                    format!(
                        "this async body completes with {}",
                        crate::types::with_article(v)
                    ),
                );
            }
            Some(e) if void => {
                return self.err(e.span(), "this async body completes with no value");
            }
            Some(e) => {
                let got = self.expr(e, Some(v))?;
                self.coerce(&got, v, e.span())?;
                if !self.finallies.is_empty() {
                    let slot = self.temp(v);
                    self.store(slot, v);
                    self.unwind(0)?;
                    self.load(slot, v);
                }
                self.async_complete(Some(v));
            }
        }
        Ok(())
    }

    /// Completes the frame with the value on the stack (none for `Void`).
    pub fn async_complete(&mut self, t: Option<&Type>) {
        match t {
            Some(Type::Prim(p)) => self.box_prim(*p),
            Some(_) => {}
            None => self.asm.emit(&[0x01], 0, Some(VType::Null)),
        }
        self.load_frame();
        self.asm.swap();
        let c = self
            .pool
            .method_ref(FUTURE, "complete", "(Ljava/lang/Object;)Z");
        self.asm.emit_u2(0xb6, c, 2, Some(VType::Int));
        self.asm.emit(&[0x57], 1, None);
        self.ret(&Type::Void);
    }

    /// Compiles an async body: the resume method and the frame class go to
    /// the class's extras; the entry method is returned.
    pub fn build_async(&mut self, spec: AsyncSpec) -> Result<classfile::writer::MethodDef> {
        let owner = self.cls.name.to_string();
        let frame = format!("{owner}${}$async", spec.name.replace(['<', '>'], ""));
        let resume_name = format!("{}$resume", spec.name);
        let resume_desc = format!("(L{frame};)V");
        let (resume, saved) = self.resume_method(&spec, &frame)?;
        let mut access = ACC_SYNTHETIC;
        if spec.is_static {
            access |= ACC_STATIC;
        }
        let r = method_def(resume, self.pool, access, &resume_name, &resume_desc)
            .ok_or_else(|| Diag::new(spec.span, "this async body is too large for a class file"))?;
        self.extra.methods.push(r);
        let bytes = super::frame_class::frame_class(&owner, &frame, &spec, &resume_name, &saved);
        self.extra.classes.push((frame.clone(), bytes));
        let entry = self.entry_method(&spec, &frame, &resume_name, &resume_desc);
        method_def(entry, self.pool, spec.access, &spec.name, &spec.desc)
            .ok_or_else(|| Diag::new(spec.span, "this async method is too large"))
    }

    fn resume_method(
        &mut self,
        spec: &AsyncSpec,
        frame: &str,
    ) -> Result<(Asm, BTreeMap<String, String>)> {
        let mut locals = Vec::new();
        if !spec.is_static {
            locals.push(VType::of(&self.this_type()));
        }
        let fslot = locals.len() as u16;
        locals.push(VType::Object(frame.to_string()));
        let saved_asm = std::mem::replace(&mut self.asm, Asm::new(locals));
        let saved_scopes = std::mem::replace(&mut self.scopes, vec![Vec::new()]);
        let saved_ret = std::mem::replace(&mut self.ret, Type::Void);
        let saved_static = std::mem::replace(&mut self.is_static, spec.is_static);
        let saved_boxed = std::mem::take(&mut self.boxed);
        let saved_loops = std::mem::take(&mut self.loops);
        let saved_finallies = std::mem::take(&mut self.finallies);
        let outer_cx = self.async_cx.take();
        self.async_cx = Some(AsyncCx {
            frame: frame.to_string(),
            fslot,
            first: fslot + 1,
            awaits: Vec::new(),
            value: spec.value.clone(),
        });
        let r = self.resume_body(spec);
        let cx = std::mem::replace(&mut self.async_cx, outer_cx);
        self.finallies = saved_finallies;
        self.loops = saved_loops;
        self.boxed = saved_boxed;
        self.is_static = saved_static;
        self.ret = saved_ret;
        self.scopes = saved_scopes;
        let asm = std::mem::replace(&mut self.asm, saved_asm);
        r?;
        let mut saved = BTreeMap::new();
        for (_, live) in cx.iter().flat_map(|c| &c.awaits) {
            for (slot, vt) in live {
                let (n, d) = slot_field(*slot, vt);
                saved.insert(n, d);
            }
        }
        Ok((asm, saved))
    }

    fn resume_body(&mut self, spec: &AsyncSpec) -> Result<()> {
        let (start, dispatch, handler, end) = (
            self.asm.label(),
            self.asm.label(),
            self.asm.label(),
            self.asm.label(),
        );
        self.asm.record(start);
        self.asm.jump(0xa7, 0, dispatch);
        self.asm.place(start);
        let mark = self.push_scope();
        for (i, (name, ty, boxed)) in spec.params.iter().enumerate() {
            let field_t = if *boxed {
                Type::Array(Box::new(ty.clone()))
            } else {
                ty.clone()
            };
            self.load_frame();
            let f = self.field(&format!("$p{i}"), &field_t.descriptor());
            self.asm.emit_u2(0xb4, f, 1, Some(VType::of(&field_t)));
            let slot = self.asm.push_local(VType::of(&field_t));
            self.store(slot, &field_t);
            if let Some(s) = self.scopes.last_mut() {
                s.push((
                    name.clone(),
                    Local {
                        slot,
                        ty: ty.clone(),
                        boxed: *boxed,
                    },
                ));
            }
        }
        self.boxed = super::captures::boxed_locals(&spec.body);
        self.stmts(&spec.body)?;
        if self.asm.reachable() {
            if spec.value != Type::Void && !spec.value.is_class("java/lang/Void") {
                return self.err(
                    spec.span,
                    "this async body can reach its end without returning a value",
                );
            }
            self.async_complete(None);
        }
        self.pop_scope(mark);
        self.asm.place(end);
        self.asm.place(dispatch);
        self.load_frame();
        let state = self.field("state", "I");
        self.asm.emit_u2(0xb4, state, 1, Some(VType::Int));
        let awaits: Vec<(Label, Vec<(u16, VType)>)> = self
            .async_cx
            .as_ref()
            .map(|c| c.awaits.clone())
            .unwrap_or_default();
        for (k, (label, live)) in awaits.iter().enumerate() {
            let next = self.asm.label();
            self.asm.dup_under(0);
            self.int_const(k as i32 + 1);
            self.asm.jump(0xa0, 2, next);
            self.asm.emit(&[0x57], 1, None);
            for (slot, vt) in live {
                self.load_frame();
                let (n, d) = slot_field(*slot, vt);
                let f = self.field(&n, &d);
                self.asm.emit_u2(0xb4, f, 1, Some(vt.clone()));
                self.store(*slot, &vtype_type(vt));
            }
            self.asm.jump(0xa7, 0, *label);
            self.asm.place(next);
        }
        self.asm.emit(&[0x57], 1, None);
        self.asm.jump(0xa7, 0, start);
        // Whatever the body throws fails the frame (rethrown when reporting).
        self.asm.handlers.push((start, end, handler, 0));
        self.asm.place_handler(handler, "java/lang/Throwable");
        self.asm.dup_under(0);
        self.load_frame();
        self.asm.swap();
        let ce = self
            .pool
            .method_ref(FUTURE, "completeExceptionally", "(Ljava/lang/Throwable;)Z");
        self.asm.emit_u2(0xb6, ce, 2, Some(VType::Int));
        self.asm.emit(&[0x57], 1, None);
        if spec.report {
            self.asm.emit(&[0xbf], 1, None);
            self.asm.end_flow();
        } else {
            self.asm.emit(&[0x57], 1, None);
            self.ret(&Type::Void);
        }
        Ok(())
    }
}
