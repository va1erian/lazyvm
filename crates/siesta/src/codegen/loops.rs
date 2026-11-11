//! The for-each loop over arrays and `Iterable`s (JLS 14.14.2), and arrow
//! `switch` as a chain of tests (siesta.md section 3).

use super::asm::Label;
use super::body::{Body, LoopCx};
use crate::ast::{Case, Expr, Stmt, TypeRef};
use crate::diag::Result;
use crate::types::Type;

impl Body<'_> {
    pub(super) fn for_each(
        &mut self,
        ty: &TypeRef,
        name: &crate::ast::Ident,
        iter: &Expr,
        body: &Stmt,
    ) -> Result<()> {
        let mark = self.push_scope();
        let it = self.expr(iter, None)?;
        // The labels are made once the loop's own locals exist, so their
        // frames include them.
        let (top, end);
        if let Type::Array(elem) = &it {
            let arr = self.temp(&it);
            self.store(arr, &it);
            let idx = self.temp(&Type::Prim(crate::ast::Prim::Int));
            self.int_const(0);
            self.store(idx, &Type::Prim(crate::ast::Prim::Int));
            let next;
            (top, next, end) = (self.asm.label(), self.asm.label(), self.asm.label());
            self.asm.place(top);
            self.load(idx, &Type::Prim(crate::ast::Prim::Int));
            self.load(arr, &it);
            self.asm.emit(&[0xbe], 1, Some(super::asm::VType::Int));
            self.asm.jump(0xa2, 2, end);
            let inner = self.push_scope();
            self.load(arr, &it);
            self.load(idx, &Type::Prim(crate::ast::Prim::Int));
            self.array_load(elem);
            self.bind_element(ty, name, elem)?;
            self.loop_body(body, end, next)?;
            self.pop_scope(inner);
            self.asm.place(next);
            let Ok(i) = u8::try_from(idx) else {
                return self.err(name.span, "too many locals for a loop index");
            };
            self.asm.emit(&[0x84, i, 1], 0, None);
        } else {
            let Some(iterable) = self.w.as_super(&it, "java/lang/Iterable") else {
                return self.err(
                    iter.span(),
                    format!("a {it} cannot be iterated: it is not an array or an Iterable"),
                );
            };
            let elem = match iterable {
                Type::Class(_, args) if args.len() == 1 => args[0].clone(),
                _ => Type::object(),
            };
            let iface = it.erasure().internal();
            let is_iface = self.w.class(&iface).is_some_and(|c| c.is_interface());
            let m = if is_iface {
                self.pool
                    .interface_method_ref(&iface, "iterator", "()Ljava/util/Iterator;")
            } else {
                self.pool
                    .method_ref(&iface, "iterator", "()Ljava/util/Iterator;")
            };
            let iter_t = Type::class("java/util/Iterator");
            if is_iface {
                self.asm.emit(
                    &[0xb9, (m >> 8) as u8, m as u8, 1, 0],
                    1,
                    Some(super::asm::VType::of(&iter_t)),
                );
            } else {
                self.asm
                    .emit_u2(0xb6, m, 1, Some(super::asm::VType::of(&iter_t)));
            }
            let slot = self.temp(&iter_t);
            self.store(slot, &iter_t);
            let next;
            (top, next, end) = (self.asm.label(), self.asm.label(), self.asm.label());
            self.asm.place(top);
            self.asm.place(next);
            self.load(slot, &iter_t);
            let has = self
                .pool
                .interface_method_ref("java/util/Iterator", "hasNext", "()Z");
            self.asm.emit(
                &[0xb9, (has >> 8) as u8, has as u8, 1, 0],
                1,
                Some(super::asm::VType::Int),
            );
            self.asm.jump(0x99, 1, end);
            let inner = self.push_scope();
            self.load(slot, &iter_t);
            let nx = self.pool.interface_method_ref(
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
            );
            self.asm.emit(
                &[0xb9, (nx >> 8) as u8, nx as u8, 1, 0],
                1,
                Some(super::asm::VType::of(&Type::object())),
            );
            if !elem.erasure().is_class(crate::types::OBJECT) {
                self.checkcast(&elem.erasure());
            }
            self.bind_element(ty, name, &elem)?;
            self.loop_body(body, end, next)?;
            self.pop_scope(inner);
        }
        self.asm.jump(0xa7, 0, top);
        self.asm.place(end);
        self.pop_scope(mark);
        Ok(())
    }

    /// The loop variable of a for-each, from the element on the stack.
    fn bind_element(&mut self, ty: &TypeRef, name: &crate::ast::Ident, elem: &Type) -> Result<()> {
        let t = match ty {
            TypeRef::Var(_) => elem.clone(),
            t => {
                let t = self.resolve_type(t)?;
                self.coerce(elem, &t, name.span)?;
                t
            }
        };
        if self.boxed.contains(&name.name) {
            return self.err(name.span, "a lambda cannot change a loop variable");
        }
        let l = self.declare(&name.name, t.clone(), name.span)?;
        self.store(l.slot, &t);
        Ok(())
    }

    pub(super) fn switch(
        &mut self,
        scrut: &Expr,
        cases: &[Case],
        span: crate::diag::Span,
    ) -> Result<()> {
        let mark = self.push_scope();
        let t = self.expr(scrut, None)?;
        let slot = self.temp(&t);
        self.store(slot, &t);
        let end = self.asm.label();
        let bodies: Vec<Label> = cases.iter().map(|_| self.asm.label()).collect();
        let mut default = None;
        for (c, l) in cases.iter().zip(&bodies) {
            if c.labels.is_empty() {
                default = Some(*l);
            }
            for label in &c.labels {
                self.case_test(slot, &t, label, *l, span)?;
            }
        }
        self.asm.jump(0xa7, 0, default.unwrap_or(end));
        self.loops.push(LoopCx {
            brk: end,
            cont: None,
            finally_depth: self.finallies.len(),
        });
        for (c, l) in cases.iter().zip(&bodies) {
            self.asm.place(*l);
            let r = self.scoped(&c.body);
            if r.is_err() {
                self.loops.pop();
                return r;
            }
            self.asm.jump(0xa7, 0, end);
        }
        self.loops.pop();
        self.asm.place(end);
        self.pop_scope(mark);
        Ok(())
    }
    /// Jumps to `to` when the value in `slot` equals the case label.
    fn case_test(
        &mut self,
        slot: u16,
        t: &Type,
        label: &Expr,
        to: Label,
        span: crate::diag::Span,
    ) -> Result<()> {
        if matches!(label, Expr::Lit(crate::ast::Lit::Null, _)) {
            self.load(slot, t);
            self.asm.jump(0xc6, 1, to);
            return Ok(());
        }
        if let Type::Class(n, _) = t
            && self
                .w
                .class(n)
                .is_some_and(|c| c.access & classfile::ACC_ENUM != 0)
        {
            let Expr::Name(k) = label else {
                return self.err(
                    label.span(),
                    "a case of an enum `switch` is a constant's name",
                );
            };
            if !self
                .w
                .class(n)
                .is_some_and(|c| c.fields.iter().any(|f| f.name == k.name))
            {
                return self.err(k.span, format!("{t} has no constant {}", k.name));
            }
            self.load(slot, t);
            let f = self.pool.field_ref(n, &k.name, &t.descriptor());
            self.asm.emit_u2(0xb2, f, 0, Some(super::asm::VType::of(t)));
            self.asm.jump(0xa5, 2, to);
            return Ok(());
        }
        if t.is_class(crate::types::STRING) {
            let lt = self.expr(label, None)?;
            if !lt.is_class(crate::types::STRING) {
                return self.err(label.span(), "a case of a `String` switch is a string");
            }
            self.load(slot, t);
            let m = self
                .pool
                .method_ref(crate::types::STRING, "equals", "(Ljava/lang/Object;)Z");
            self.asm.emit_u2(0xb6, m, 2, Some(super::asm::VType::Int));
            self.asm.jump(0x9a, 1, to);
            return Ok(());
        }
        let Some(p) = super::ops::prim_of(t).filter(|p| {
            !matches!(
                p,
                crate::ast::Prim::Long
                    | crate::ast::Prim::Float
                    | crate::ast::Prim::Double
                    | crate::ast::Prim::Boolean
            )
        }) else {
            return self.err(span, format!("cannot `switch` on a {t}"));
        };
        self.load(slot, t);
        self.to_prim(t);
        let lt = self.expr(label, Some(&Type::Prim(p)))?;
        self.coerce(&lt, &Type::Prim(crate::ast::Prim::Int), label.span())?;
        self.asm.jump(0x9f, 2, to);
        Ok(())
    }
}
