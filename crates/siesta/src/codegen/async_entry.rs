//! Where async bodies start: the entry method of every `async` method and
//! lambda, `async` methods themselves, and a top level that awaits.

use classfile::writer::MethodDef;
use classfile::{ACC_PRIVATE, ACC_PUBLIC, ACC_STATIC, ACC_SYNTHETIC};

use super::asm::{Asm, VType};
use super::async_body::AsyncSpec;
use super::body::Body;
use super::members::ClassGen;
use super::method_def;
use crate::ast::Stmt;
use crate::declare::SourceMethod;
use crate::diag::{Diag, Result};
use crate::types::Type;

impl Body<'_> {
    pub(super) fn entry_method(
        &mut self,
        spec: &AsyncSpec,
        frame: &str,
        resume: &str,
        resume_desc: &str,
    ) -> Asm {
        let mut locals = Vec::new();
        if !spec.is_static {
            locals.push(VType::of(&self.this_type()));
        }
        for (_, t, boxed) in &spec.params {
            locals.push(VType::of(&if *boxed {
                Type::Array(Box::new(t.clone()))
            } else {
                t.clone()
            }));
        }
        let saved = std::mem::replace(&mut self.asm, Asm::new(locals));
        let frame_t = Type::class(frame);
        let c = self.pool.class(frame);
        self.asm.new_object(c);
        let was = self.asm.top().cloned();
        self.asm.dup_under(0);
        let init = self.pool.method_ref(frame, "<init>", "()V");
        self.asm.emit_u2(0xb7, init, 1, None);
        if let Some(w) = was {
            self.asm.initialised(&w, frame);
        }
        let tmp = self.asm.push_local(VType::of(&frame_t));
        self.store(tmp, &frame_t);
        let mut slot = u16::from(!spec.is_static);
        if !spec.is_static {
            self.load(tmp, &frame_t);
            self.load(0, &self.this_type());
            let o = self
                .pool
                .field_ref(frame, "owner", &self.this_type().descriptor());
            self.asm.emit_u2(0xb5, o, 2, None);
        }
        for (i, (_, t, boxed)) in spec.params.iter().enumerate() {
            let ft = if *boxed {
                Type::Array(Box::new(t.clone()))
            } else {
                t.clone()
            };
            self.load(tmp, &frame_t);
            self.load(slot, &ft);
            let f = self
                .pool
                .field_ref(frame, &format!("$p{i}"), &ft.descriptor());
            self.asm.emit_u2(0xb5, f, 2, None);
            slot += ft.slots();
        }
        self.load(tmp, &frame_t);
        let home = self.pool.method_ref(
            "lazy/async/Loop",
            "home",
            "()Ljava/util/concurrent/Executor;",
        );
        self.asm.emit_u2(
            0xb8,
            home,
            0,
            Some(VType::Object("java/util/concurrent/Executor".into())),
        );
        let hf = self
            .pool
            .field_ref(frame, "home", "Ljava/util/concurrent/Executor;");
        self.asm.emit_u2(0xb5, hf, 2, None);
        for (flag, on) in [("running", true), ("report", spec.report)] {
            if on {
                self.load(tmp, &frame_t);
                self.int_const(1);
                let f = self.pool.field_ref(frame, flag, "Z");
                self.asm.emit_u2(0xb5, f, 2, None);
            }
        }
        let owner = self.cls.name.to_string();
        let r = self.pool.method_ref(&owner, resume, resume_desc);
        if spec.is_static {
            self.load(tmp, &frame_t);
            self.asm.emit_u2(0xb8, r, 1, None);
        } else {
            self.load(0, &self.this_type());
            self.load(tmp, &frame_t);
            self.asm.emit_u2(0xb6, r, 2, None);
        }
        if spec.returns_frame {
            self.load(tmp, &frame_t);
            self.asm.emit(&[0xb0], 1, None);
        } else {
            self.asm.emit(&[0xb1], 0, None);
        }
        self.asm.end_flow();
        std::mem::replace(&mut self.asm, saved)
    }
}

impl ClassGen<'_> {
    pub(super) fn async_method(&mut self, sm: &SourceMethod, stmts: &[Stmt]) -> Result<MethodDef> {
        let info = &sm.info;
        let span = sm.decl.name.span;
        if info.name == "<init>" {
            return Err(Diag::new(span, "a constructor cannot be `async`"));
        }
        let is_static = info.access & ACC_STATIC != 0;
        let body = super::hoist::Hoist::new("").stmts(stmts)?;
        let params: Vec<(String, Type, bool)> = sm
            .decl
            .params
            .iter()
            .zip(&info.params)
            .map(|(p, t)| (p.name.name.clone(), t.clone(), false))
            .collect();
        let sig_attrs = self.method_sig(sm);
        let (access, name, desc) = (info.access, info.name.clone(), info.desc.clone());
        let mut b = self.body(is_static, Type::Void, &name, Vec::new());
        b.vars = sm.vars.clone();
        let value = super::async_body::value_of_stage(&b, &info.ret, span)?;
        let spec = super::async_body::AsyncSpec {
            name,
            access,
            desc,
            is_static,
            params,
            value,
            report: false,
            returns_frame: true,
            body,
            span,
        };
        let mut m = b.build_async(spec)?;
        m.attributes = sig_attrs;
        Ok(m)
    }

    /// A top level that awaits: `main$async` holds it, and `main` waits for
    /// it, rethrowing its failure without the `CompletionException` around
    /// it (siesta-async.md section 2).
    pub(super) fn async_main(&mut self, args_t: &Type) -> Result<MethodDef> {
        let c = self.c;
        let body = super::hoist::Hoist::new("").stmts(&c.stmts)?;
        let stage = format!("([Ljava/lang/String;)L{};", super::async_body::STAGE);
        let mut b = self.body(true, Type::Void, "main", Vec::new());
        let spec = super::async_body::AsyncSpec {
            name: "main$async".into(),
            access: ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
            desc: stage.clone(),
            is_static: true,
            params: vec![("args".into(), args_t.clone(), false)],
            value: Type::class("java/lang/Void"),
            report: false,
            returns_frame: true,
            body,
            span: c.span,
        };
        let entry = b.build_async(spec)?;
        b.extra.methods.push(entry);
        let mut a = Asm::new(vec![VType::of(args_t)]);
        let (start, end, handler) = (a.label(), a.label(), a.label());
        a.place(start);
        a.emit(&[0x2a], 0, Some(VType::of(args_t)));
        let m = self.pool.method_ref(&c.name, "main$async", &stage);
        a.emit_u2(
            0xb8,
            m,
            1,
            Some(VType::Object(super::async_body::STAGE.into())),
        );
        let tc = self.pool.interface_method_ref(
            super::async_body::STAGE,
            "toCompletableFuture",
            "()Ljava/util/concurrent/CompletableFuture;",
        );
        let [h, l] = tc.to_be_bytes();
        a.emit(
            &[0xb9, h, l, 1, 0],
            1,
            Some(VType::Object(super::async_body::FUTURE.into())),
        );
        let join = self
            .pool
            .method_ref(super::async_body::FUTURE, "join", "()Ljava/lang/Object;");
        a.emit_u2(
            0xb6,
            join,
            1,
            Some(VType::Object(crate::types::OBJECT.into())),
        );
        a.emit(&[0x57], 1, None);
        a.place(end);
        a.emit(&[0xb1], 0, None);
        a.end_flow();
        let ce = "java/util/concurrent/CompletionException";
        let cc = self.pool.class(ce);
        a.handlers.push((start, end, handler, cc));
        a.place_handler(handler, ce);
        let cause =
            self.pool
                .method_ref("java/lang/Throwable", "getCause", "()Ljava/lang/Throwable;");
        a.emit_u2(
            0xb6,
            cause,
            1,
            Some(VType::Object("java/lang/Throwable".into())),
        );
        a.emit(&[0xbf], 1, None);
        method_def(
            a,
            self.pool,
            ACC_PUBLIC | ACC_STATIC,
            "main",
            "([Ljava/lang/String;)V",
        )
        .ok_or_else(|| Diag::new(c.span, "the top level is too large for a class file"))
    }
}
