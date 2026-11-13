//! Arrays (JLS 10, 15.10): creation, initialisers, loads and stores.

use super::asm::VType;
use super::body::Body;
use crate::ast::{Expr, Prim};
use crate::diag::{Diag, Result, Span};
use crate::types::Type;

impl Body<'_> {
    // ---- arrays ----

    /// `newarray` or `anewarray` for one dimension, length on the stack.
    pub fn new_array(&mut self, elem: &Type) {
        let arr = Type::Array(Box::new(elem.clone()));
        match elem {
            Type::Prim(p) => {
                let code = match p {
                    Prim::Boolean => 4,
                    Prim::Char => 5,
                    Prim::Float => 6,
                    Prim::Double => 7,
                    Prim::Byte => 8,
                    Prim::Short => 9,
                    Prim::Int => 10,
                    Prim::Long => 11,
                };
                self.asm.emit(&[0xbc, code], 1, Some(VType::of(&arr)));
            }
            other => {
                let c = self.pool.class(&other.internal());
                self.asm.emit_u2(0xbd, c, 1, Some(VType::of(&arr)));
            }
        }
    }

    fn elem_op(t: &Type, base: u8) -> u8 {
        base + match t {
            Type::Prim(Prim::Int) => 0,
            Type::Prim(Prim::Long) => 1,
            Type::Prim(Prim::Float) => 2,
            Type::Prim(Prim::Double) => 3,
            Type::Prim(Prim::Boolean | Prim::Byte) => 5,
            Type::Prim(Prim::Char) => 6,
            Type::Prim(Prim::Short) => 7,
            _ => 4,
        }
    }

    pub fn array_load(&mut self, elem: &Type) {
        self.asm
            .emit(&[Self::elem_op(elem, 0x2e)], 2, Some(VType::of(elem)));
    }

    pub fn array_store(&mut self, elem: &Type) {
        self.asm.emit(&[Self::elem_op(elem, 0x4f)], 3, None);
    }

    pub(super) fn new_array_expr(
        &mut self,
        elem: &crate::ast::TypeRef,
        dims: &[Expr],
        extra: usize,
        init: Option<&[Expr]>,
        span: Span,
    ) -> Result<Type> {
        let mut t = self.resolve_type(elem)?;
        if t == Type::Void {
            return self.err(span, "no arrays of `void`");
        }
        for _ in 0..dims.len() + extra {
            t = Type::Array(Box::new(t));
        }
        if let Some(items) = init {
            return self.array_init(&t, items, span);
        }
        for d in dims {
            let dt = self.expr(d, None)?;
            self.coerce(&dt, &Type::Prim(Prim::Int), d.span())?;
        }
        if dims.len() == 1 {
            let Type::Array(e) = &t else { return Ok(t) };
            self.new_array(e);
        } else {
            let c = self.pool.class(&t.internal());
            let n = u8::try_from(dims.len()).map_err(|_| Diag::new(span, "too many dimensions"))?;
            let [h, l] = c.to_be_bytes();
            self.asm
                .emit(&[0xc5, h, l, n], dims.len(), Some(VType::of(&t)));
        }
        Ok(t)
    }

    pub fn array_init(&mut self, t: &Type, items: &[Expr], span: Span) -> Result<Type> {
        let Type::Array(elem) = t else {
            return self.err(span, "an initialiser needs an array type");
        };
        let n = i32::try_from(items.len()).map_err(|_| Diag::new(span, "too many elements"))?;
        self.int_const(n);
        self.new_array(elem);
        for (i, item) in items.iter().enumerate() {
            self.asm.dup_under(0);
            self.int_const(i as i32);
            let it = self.expr(item, Some(elem))?;
            self.coerce(&it, elem, item.span())?;
            self.array_store(elem);
        }
        Ok(t.clone())
    }
}
