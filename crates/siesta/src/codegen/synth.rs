//! Methods Java writes for you: an enum's `values` and `valueOf` and its
//! constants' creation (JLS 8.9.3), and bridge methods (JLS 15.12.4.5).

use std::collections::HashSet;

use classfile::writer::MethodDef;
use classfile::{ACC_BRIDGE, ACC_PRIVATE, ACC_PUBLIC, ACC_STATIC, ACC_SYNTHETIC};

use super::asm::{Asm, VType};
use super::body::Body;
use super::members::ClassGen;
use super::method_def;
use crate::diag::Result;
use crate::sig;
use crate::types::Type;

impl ClassGen<'_> {
    /// `values()`: a copy of `$VALUES` (JLS 8.9.3).
    pub(super) fn values(&mut self) -> MethodDef {
        let name = &self.c.name;
        let arr = format!("[L{name};");
        let mut a = Asm::new(Vec::new());
        let f = self.pool.field_ref(name, "$VALUES", &arr);
        a.emit_u2(0xb2, f, 0, Some(VType::Object(arr.clone())));
        let clone = self.pool.method_ref(&arr, "clone", "()Ljava/lang/Object;");
        a.emit_u2(
            0xb6,
            clone,
            1,
            Some(VType::Object(crate::types::OBJECT.into())),
        );
        let ac = self.pool.class(&arr);
        a.emit_u2(0xc0, ac, 1, Some(VType::Object(arr.clone())));
        a.emit(&[0xb0], 1, None);
        let desc = format!("(){arr}");
        method_def(a, self.pool, ACC_PUBLIC | ACC_STATIC, "values", &desc).unwrap_or_default()
    }

    pub(super) fn value_of(&mut self) -> MethodDef {
        let name = &self.c.name;
        let mut a = Asm::new(vec![VType::Object(crate::types::STRING.into())]);
        let cls = self.pool.class(name);
        let [h, l] = cls.to_be_bytes();
        a.emit(
            &[0x13, h, l],
            0,
            Some(VType::Object("java/lang/Class".into())),
        );
        a.emit(&[0x2a], 0, Some(VType::Object(crate::types::STRING.into())));
        let vo = self.pool.method_ref(
            "java/lang/Enum",
            "valueOf",
            "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;",
        );
        a.emit_u2(0xb8, vo, 2, Some(VType::Object("java/lang/Enum".into())));
        a.emit_u2(0xc0, cls, 1, Some(VType::Object(name.to_string())));
        a.emit(&[0xb0], 1, None);
        let desc = format!("(Ljava/lang/String;)L{name};");
        method_def(a, self.pool, ACC_PUBLIC | ACC_STATIC, "valueOf", &desc).unwrap_or_default()
    }

    /// For each inherited method a method here overrides with another
    /// erasure, a bridge with the inherited descriptor that calls it.
    pub(super) fn bridges(&mut self) -> Vec<MethodDef> {
        let c = self.c;
        let this = c.info.this_type();
        let mut out = Vec::new();
        let mut seen: HashSet<String> = c
            .info
            .methods
            .iter()
            .map(|m| format!("{}{}", m.name, m.desc))
            .collect();
        for sm in &c.methods {
            let info = &sm.info;
            if info.name == "<init>" || info.access & (ACC_STATIC | ACC_PRIVATE) != 0 {
                continue;
            }
            let mine: Vec<Type> = info.params.iter().map(Type::erasure).collect();
            for m in self.u.world.methods(&this, &info.name) {
                if *m.owner.name == *c.name
                    || m.params.len() != mine.len()
                    || m.info.access & (ACC_STATIC | ACC_PRIVATE) != 0
                {
                    continue;
                }
                let theirs: Vec<Type> = m.params.iter().map(Type::erasure).collect();
                if theirs != mine || !seen.insert(format!("{}{}", m.info.name, m.info.desc)) {
                    continue;
                }
                out.push(self.bridge(&m.info.desc, &info.name, &info.desc, &mine));
            }
        }
        out
    }

    fn bridge(&mut self, desc: &str, name: &str, target_desc: &str, params: &[Type]) -> MethodDef {
        let theirs = sig::method_sig(desc, &Default::default())
            .map(|s| s.params)
            .unwrap_or_default();
        let ret = sig::method_sig(desc, &Default::default()).map_or(Type::Void, |s| s.ret);
        let this = Type::class(&self.c.name);
        let mut locals = vec![VType::of(&this)];
        locals.extend(theirs.iter().map(VType::of));
        let mut b = self.body(false, ret.clone(), name, locals);
        b.load(0, &this);
        let mut slot = 1;
        for (t, p) in theirs.iter().zip(params) {
            b.load(slot, t);
            if t != p && p.is_reference() {
                b.checkcast(p);
            }
            slot += t.slots();
        }
        let r = b.pool.method_ref(&b.cls.name, name, target_desc);
        let pops = theirs.len() + 1;
        let tret = sig::method_sig(target_desc, &Default::default()).map_or(Type::Void, |s| s.ret);
        b.asm.emit_u2(
            0xb6,
            r,
            pops,
            (tret != Type::Void).then(|| VType::of(&tret)),
        );
        b.ret(&ret);
        let asm = std::mem::take(&mut b.asm);
        method_def(
            asm,
            self.pool,
            ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC,
            name,
            desc,
        )
        .unwrap_or_default()
    }
}

pub fn enum_constants(b: &mut Body) -> Result<()> {
    let c = b.cls;
    let name = c.name.clone();
    let this = Type::class(&name);
    for (i, k) in c.constants.iter().enumerate() {
        let cls = b.pool.class(&name);
        b.asm.new_object(cls);
        let was = b.asm.top().cloned();
        b.asm.dup_under(0);
        b.string_const(&k.name.name, k.name.span)?;
        b.int_const(i as i32);
        let cands: Vec<_> =
            b.w.methods(&this, "<init>")
                .into_iter()
                .filter(|m| *m.owner.name == *name)
                .collect();
        let ch = b.select(cands, &k.args, None, "constructor", k.name.span)?;
        b.finish_call(ch, &k.args, 0xb7, &name, k.name.span)?;
        if let Some(w) = was {
            b.asm.initialised(&w, &name);
        }
        let f = b.pool.field_ref(&name, &k.name.name, &this.descriptor());
        b.asm.emit_u2(0xb3, f, 1, None);
    }
    let arr = Type::Array(Box::new(this.clone()));
    b.int_const(c.constants.len() as i32);
    b.new_array(&this);
    for (i, k) in c.constants.iter().enumerate() {
        b.asm.dup_under(0);
        b.int_const(i as i32);
        let f = b.pool.field_ref(&name, &k.name.name, &this.descriptor());
        b.asm.emit_u2(0xb2, f, 0, Some(VType::of(&this)));
        b.array_store(&this);
    }
    let v = b.pool.field_ref(&name, "$VALUES", &arr.descriptor());
    b.asm.emit_u2(0xb3, v, 1, None);
    Ok(())
}
