//! The frame class of an `async` body: a `CompletableFuture` (the stage the
//! entry returns) that is also the callback of each `await`
//! (`BiConsumer`) and the task that resumes the body on its home
//! (`Runnable`). Its fields are the body's saved slots.
//!
//! `deliver` and `suspend` are `synchronized`, so a stage that completes on
//! another thread while the body is still running is never lost: the body
//! either sees `ready` and goes on, or has stopped and the callback runs it.

use std::collections::BTreeMap;

use classfile::writer::{ClassDef, FieldDef, MethodDef, Pool, write};
use classfile::{ACC_FINAL, ACC_PUBLIC, ACC_STATIC, ACC_SUPER, ACC_SYNCHRONIZED, ACC_SYNTHETIC};

use super::asm::{Asm, VType};
use super::async_body::{AsyncSpec, FUTURE, STAGE};
use super::method_def;
use crate::types::Type;

const EXECUTOR: &str = "java/util/concurrent/Executor";
const THROWABLE: &str = "java/lang/Throwable";

pub fn frame_class(
    owner: &str,
    frame: &str,
    spec: &AsyncSpec,
    resume: &str,
    saved: &BTreeMap<String, String>,
) -> Vec<u8> {
    let mut pool = Pool::default();
    let mut fields: Vec<(String, String)> = vec![
        ("state".into(), "I".into()),
        ("value".into(), "Ljava/lang/Object;".into()),
        ("error".into(), "Ljava/lang/Throwable;".into()),
        ("awaited".into(), format!("L{STAGE};")),
        ("home".into(), format!("L{EXECUTOR};")),
        ("running".into(), "Z".into()),
        ("ready".into(), "Z".into()),
        ("report".into(), "Z".into()),
    ];
    if !spec.is_static {
        fields.push(("owner".into(), format!("L{owner};")));
    }
    for (i, (_, t, boxed)) in spec.params.iter().enumerate() {
        let ft = if *boxed {
            Type::Array(Box::new(t.clone()))
        } else {
            t.clone()
        };
        fields.push((format!("$p{i}"), ft.descriptor()));
    }
    fields.extend(saved.iter().map(|(n, d)| (n.clone(), d.clone())));
    let g = Gen {
        frame,
        owner,
        is_static: spec.is_static,
    };
    let methods = vec![
        g.init(&mut pool),
        g.accept(&mut pool),
        g.deliver(&mut pool),
        g.suspend(&mut pool),
        g.run(&mut pool, resume),
        g.cancel(&mut pool),
        g.unwrap(&mut pool),
    ];
    let class = ClassDef {
        access: ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC,
        name: frame.to_string(),
        super_name: FUTURE.to_string(),
        interfaces: vec![
            "java/util/function/BiConsumer".into(),
            "java/lang/Runnable".into(),
        ],
        fields: fields
            .into_iter()
            .map(|(name, descriptor)| FieldDef {
                access: ACC_PUBLIC,
                name,
                descriptor,
                attributes: Vec::new(),
            })
            .collect(),
        methods: methods.into_iter().flatten().collect(),
        attributes: Vec::new(),
    };
    write(&class, pool)
}

struct Gen<'a> {
    frame: &'a str,
    owner: &'a str,
    is_static: bool,
}

impl Gen<'_> {
    fn this(&self) -> VType {
        VType::Object(self.frame.to_string())
    }

    fn aload(&self, a: &mut Asm, slot: u8, t: VType) {
        a.emit(&[0x19, slot], 0, Some(t));
    }

    fn get(&self, a: &mut Asm, p: &mut Pool, name: &str, desc: &str) {
        let f = p.field_ref(self.frame, name, desc);
        let t =
            crate::sig::field_type(desc, &Default::default()).map_or(VType::Int, |t| VType::of(&t));
        a.emit_u2(0xb4, f, 1, Some(t));
    }

    fn put(&self, a: &mut Asm, p: &mut Pool, name: &str, desc: &str) {
        let f = p.field_ref(self.frame, name, desc);
        a.emit_u2(0xb5, f, 2, None);
    }

    fn def(&self, a: Asm, p: &mut Pool, access: u16, name: &str, desc: &str) -> Option<MethodDef> {
        method_def(a, p, access, name, desc)
    }

    fn init(&self, p: &mut Pool) -> Option<MethodDef> {
        let mut a = Asm::new(vec![VType::UninitThis]);
        a.emit(&[0x2a], 0, Some(VType::UninitThis));
        let m = p.method_ref(FUTURE, "<init>", "()V");
        a.emit_u2(0xb7, m, 1, None);
        a.emit(&[0xb1], 0, None);
        self.def(a, p, ACC_PUBLIC, "<init>", "()V")
    }

    /// The callback: records the outcome, and runs the body now or on its
    /// home unless the body is still running and will see it.
    fn accept(&self, p: &mut Pool) -> Option<MethodDef> {
        let obj = VType::Object(crate::types::OBJECT.into());
        let mut a = Asm::new(vec![self.this(), obj.clone(), obj]);
        let done = a.label();
        self.aload(&mut a, 0, self.this());
        self.aload(&mut a, 1, VType::Object(crate::types::OBJECT.into()));
        self.aload(&mut a, 2, VType::Object(crate::types::OBJECT.into()));
        let tc = p.class(THROWABLE);
        a.emit_u2(0xc0, tc, 1, Some(VType::Object(THROWABLE.into())));
        let d = p.method_ref(
            self.frame,
            "deliver",
            "(Ljava/lang/Object;Ljava/lang/Throwable;)Z",
        );
        a.emit_u2(0xb6, d, 3, Some(VType::Int));
        a.jump(0x99, 1, done);
        self.aload(&mut a, 0, self.this());
        self.get(&mut a, p, "home", &format!("L{EXECUTOR};"));
        let ex = a.push_local(VType::Object(EXECUTOR.into()));
        a.emit(&[0x3a, ex as u8], 1, None);
        let now = a.label();
        self.aload(&mut a, ex as u8, VType::Object(EXECUTOR.into()));
        a.jump(0xc6, 1, now);
        let home = p.method_ref(
            "lazy/async/Loop",
            "home",
            "()Ljava/util/concurrent/Executor;",
        );
        a.emit_u2(0xb8, home, 0, Some(VType::Object(EXECUTOR.into())));
        self.aload(&mut a, ex as u8, VType::Object(EXECUTOR.into()));
        a.jump(0xa5, 2, now);
        self.aload(&mut a, ex as u8, VType::Object(EXECUTOR.into()));
        self.aload(&mut a, 0, self.this());
        let run = p.interface_method_ref(EXECUTOR, "execute", "(Ljava/lang/Runnable;)V");
        let [h, l] = run.to_be_bytes();
        a.emit(&[0xb9, h, l, 2, 0], 2, None);
        a.emit(&[0xb1], 0, None);
        a.end_flow();
        a.place(now);
        self.aload(&mut a, 0, self.this());
        let r = p.method_ref(self.frame, "run", "()V");
        a.emit_u2(0xb6, r, 1, None);
        a.place(done);
        a.emit(&[0xb1], 0, None);
        self.def(
            a,
            p,
            ACC_PUBLIC,
            "accept",
            "(Ljava/lang/Object;Ljava/lang/Object;)V",
        )
    }

    fn deliver(&self, p: &mut Pool) -> Option<MethodDef> {
        let mut a = Asm::new(vec![
            self.this(),
            VType::Object(crate::types::OBJECT.into()),
            VType::Object(THROWABLE.into()),
        ]);
        for (slot, name, desc) in [
            (1, "value", "Ljava/lang/Object;"),
            (2, "error", "Ljava/lang/Throwable;"),
        ] {
            self.aload(&mut a, 0, self.this());
            self.aload(&mut a, slot, VType::Object(crate::types::OBJECT.into()));
            self.put(&mut a, p, name, desc);
        }
        let idle = a.label();
        self.aload(&mut a, 0, self.this());
        self.get(&mut a, p, "running", "Z");
        a.jump(0x99, 1, idle);
        self.flag(&mut a, p, "ready", 1);
        a.emit(&[0x03], 0, Some(VType::Int));
        a.emit(&[0xac], 1, None);
        a.end_flow();
        a.place(idle);
        self.flag(&mut a, p, "running", 1);
        a.emit(&[0x04], 0, Some(VType::Int));
        a.emit(&[0xac], 1, None);
        self.def(
            a,
            p,
            ACC_PUBLIC | ACC_SYNCHRONIZED,
            "deliver",
            "(Ljava/lang/Object;Ljava/lang/Throwable;)Z",
        )
    }

    fn flag(&self, a: &mut Asm, p: &mut Pool, name: &str, v: u8) {
        self.aload(a, 0, self.this());
        a.emit(&[0x03 + v], 0, Some(VType::Int));
        self.put(a, p, name, "Z");
    }

    /// After an `await` registered its callback: true to go on now (the
    /// stage was already done), false once the body has stopped.
    fn suspend(&self, p: &mut Pool) -> Option<MethodDef> {
        let mut a = Asm::new(vec![self.this()]);
        let stop = a.label();
        self.aload(&mut a, 0, self.this());
        self.get(&mut a, p, "ready", "Z");
        a.jump(0x99, 1, stop);
        self.flag(&mut a, p, "ready", 0);
        a.emit(&[0x04], 0, Some(VType::Int));
        a.emit(&[0xac], 1, None);
        a.end_flow();
        a.place(stop);
        self.flag(&mut a, p, "running", 0);
        a.emit(&[0x03], 0, Some(VType::Int));
        a.emit(&[0xac], 1, None);
        self.def(a, p, ACC_PUBLIC | ACC_SYNCHRONIZED, "suspend", "()Z")
    }

    fn run(&self, p: &mut Pool, resume: &str) -> Option<MethodDef> {
        let mut a = Asm::new(vec![self.this()]);
        let desc = format!("(L{};)V", self.frame);
        let r = p.method_ref(self.owner, resume, &desc);
        if self.is_static {
            self.aload(&mut a, 0, self.this());
            a.emit_u2(0xb8, r, 1, None);
        } else {
            self.aload(&mut a, 0, self.this());
            self.get(&mut a, p, "owner", &format!("L{};", self.owner));
            self.aload(&mut a, 0, self.this());
            a.emit_u2(0xb6, r, 2, None);
        }
        a.emit(&[0xb1], 0, None);
        self.def(a, p, ACC_PUBLIC, "run", "()V")
    }

    /// Cancelling the result cancels the stage being awaited.
    fn cancel(&self, p: &mut Pool) -> Option<MethodDef> {
        let mut a = Asm::new(vec![self.this(), VType::Int]);
        self.aload(&mut a, 0, self.this());
        a.emit(&[0x1b], 0, Some(VType::Int));
        let sup = p.method_ref(FUTURE, "cancel", "(Z)Z");
        a.emit_u2(0xb7, sup, 2, Some(VType::Int));
        let r = a.push_local(VType::Int);
        a.emit(&[0x36, r as u8], 1, None);
        let out = a.label();
        a.emit(&[0x15, r as u8], 0, Some(VType::Int));
        a.jump(0x99, 1, out);
        self.aload(&mut a, 0, self.this());
        self.get(&mut a, p, "awaited", &format!("L{STAGE};"));
        let st = a.push_local(VType::Object(STAGE.into()));
        a.emit(&[0x3a, st as u8], 1, None);
        let fut = "java/util/concurrent/Future";
        self.aload(&mut a, st as u8, VType::Object(STAGE.into()));
        let fc = p.class(fut);
        a.emit_u2(0xc1, fc, 1, Some(VType::Int));
        a.jump(0x99, 1, out);
        self.aload(&mut a, st as u8, VType::Object(STAGE.into()));
        a.emit_u2(0xc0, fc, 1, Some(VType::Object(fut.into())));
        a.emit(&[0x1b], 0, Some(VType::Int));
        let c = p.interface_method_ref(fut, "cancel", "(Z)Z");
        let [h, l] = c.to_be_bytes();
        a.emit(&[0xb9, h, l, 2, 0], 2, Some(VType::Int));
        a.emit(&[0x57], 1, None);
        a.place(out);
        a.emit(&[0x15, r as u8], 0, Some(VType::Int));
        a.emit(&[0xac], 1, None);
        self.def(a, p, ACC_PUBLIC, "cancel", "(Z)Z")
    }

    /// A failed stage's exception without its `CompletionException` wrapper.
    fn unwrap(&self, p: &mut Pool) -> Option<MethodDef> {
        let t = VType::Object(THROWABLE.into());
        let mut a = Asm::new(vec![t.clone()]);
        let plain = a.label();
        self.aload(&mut a, 0, t.clone());
        let ce = p.class("java/util/concurrent/CompletionException");
        a.emit_u2(0xc1, ce, 1, Some(VType::Int));
        a.jump(0x99, 1, plain);
        let cause = p.method_ref(THROWABLE, "getCause", "()Ljava/lang/Throwable;");
        self.aload(&mut a, 0, t.clone());
        a.emit_u2(0xb6, cause, 1, Some(t.clone()));
        a.jump(0xc6, 1, plain);
        self.aload(&mut a, 0, t.clone());
        a.emit_u2(0xb6, cause, 1, Some(t.clone()));
        a.emit(&[0xb0], 1, None);
        a.end_flow();
        a.place(plain);
        self.aload(&mut a, 0, t);
        a.emit(&[0xb0], 1, None);
        self.def(
            a,
            p,
            ACC_PUBLIC | ACC_STATIC,
            "unwrap",
            "(Ljava/lang/Throwable;)Ljava/lang/Throwable;",
        )
    }
}
