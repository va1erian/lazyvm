//! Values on the stack: constants, locals, primitive conversions (JLS 5.1.2,
//! 5.1.3), boxing (5.1.7, 5.1.8) and assignment conversion (5.2).

use super::asm::VType;
use super::body::Body;
use crate::ast::Prim;
use crate::diag::{Diag, Result, Span};
use crate::types::{OBJECT, Type, boxed, unboxed};

/// Which of the five typed instruction families a type uses.
pub fn family(t: &Type) -> u8 {
    match t {
        Type::Prim(Prim::Long) => 1,
        Type::Prim(Prim::Float) => 2,
        Type::Prim(Prim::Double) => 3,
        Type::Prim(_) => 0,
        _ => 4,
    }
}

fn widening(from: Prim, to: Prim) -> bool {
    use Prim::*;
    let rank = |p| match p {
        Byte => 1,
        Short => 2,
        Char => 2,
        Int => 3,
        Long => 4,
        Float => 5,
        Double => 6,
        Boolean => 0,
    };
    from == to
        || (from != Boolean
            && to != Boolean
            && rank(from) < rank(to)
            && !(to == Char)
            && !(from == Char && to == Short))
}

impl Body<'_> {
    pub fn int_const(&mut self, v: i32) {
        let a = &mut self.asm;
        match v {
            -1..=5 => a.emit(&[(0x03 + v) as u8], 0, Some(VType::Int)),
            -128..=127 => a.emit(&[0x10, v as i8 as u8], 0, Some(VType::Int)),
            -32768..=32767 => {
                let [h, l] = (v as i16).to_be_bytes();
                a.emit(&[0x11, h, l], 0, Some(VType::Int));
            }
            _ => {
                let i = self.pool.int(v);
                self.ldc(i, VType::Int);
            }
        }
    }

    pub fn long_const(&mut self, v: i64) {
        if v == 0 || v == 1 {
            self.asm.emit(&[0x09 + v as u8], 0, Some(VType::Long));
        } else {
            let i = self.pool.long(v);
            self.asm.emit_u2(0x14, i, 0, Some(VType::Long));
        }
    }

    pub fn float_const(&mut self, v: f32) {
        if (v == 0.0 && v.is_sign_positive()) || v == 1.0 || v == 2.0 {
            self.asm.emit(&[0x0b + v as u8], 0, Some(VType::Float));
        } else {
            let i = self.pool.float(v);
            self.ldc(i, VType::Float);
        }
    }

    pub fn double_const(&mut self, v: f64) {
        if (v == 0.0 && v.is_sign_positive()) || v == 1.0 {
            self.asm.emit(&[0x0e + v as u8], 0, Some(VType::Double));
        } else {
            let i = self.pool.double(v);
            self.asm.emit_u2(0x14, i, 0, Some(VType::Double));
        }
    }

    pub fn string_const(&mut self, s: &str, span: Span) -> Result<()> {
        if classfile::writer::mutf8_len(s) > usize::from(u16::MAX) {
            return Err(Diag::new(span, "string constant longer than 65535 bytes"));
        }
        let i = self.pool.string(s);
        self.ldc(i, VType::Object(crate::types::STRING.into()));
        Ok(())
    }

    pub fn ldc(&mut self, index: u16, t: VType) {
        match u8::try_from(index) {
            Ok(i) => self.asm.emit(&[0x12, i], 0, Some(t)),
            Err(_) => self.asm.emit_u2(0x13, index, 0, Some(t)),
        }
    }

    pub fn load(&mut self, slot: u16, t: &Type) {
        self.local_op(0x15 + family(t), slot, 0, Some(VType::of(t)));
    }

    pub fn store(&mut self, slot: u16, t: &Type) {
        self.local_op(0x36 + family(t), slot, 1, None);
    }

    fn local_op(&mut self, op: u8, slot: u16, pops: usize, push: Option<VType>) {
        match u8::try_from(slot) {
            Ok(s) => self.asm.emit(&[op, s], pops, push),
            Err(_) => {
                let [h, l] = slot.to_be_bytes();
                self.asm.emit(&[0xc4, op, h, l], pops, push);
            }
        }
    }

    pub fn pop_value(&mut self, t: &Type) {
        match t.slots() {
            0 => {}
            2 => self.asm.emit(&[0x58], 1, None),
            _ => self.asm.emit(&[0x57], 1, None),
        }
    }

    pub fn dup_value(&mut self, t: &Type) {
        let top = self.asm.top().cloned();
        if let Some(v) = top {
            self.asm
                .emit(&[if t.slots() == 2 { 0x5c } else { 0x59 }], 0, Some(v));
        }
    }

    /// Emits the return instruction for `t`.
    pub fn ret(&mut self, t: &Type) {
        let op = if *t == Type::Void {
            0xb1
        } else {
            0xac + family(t)
        };
        let pops = usize::from(*t != Type::Void);
        self.asm.emit(&[op], pops, None);
        self.asm.end_flow();
    }

    /// Converts the primitive on the stack from `from` to `to`.
    pub fn prim_convert(&mut self, from: Prim, to: Prim) {
        use Prim::*;
        if from == to || to == Boolean || from == Boolean {
            return;
        }
        let f = family(&Type::Prim(from));
        let t = family(&Type::Prim(to));
        let to_v = VType::of(&Type::Prim(to));
        // int, long, float, double → each other: i2l at 0x85 ...
        const TABLE: [[u8; 4]; 4] = [
            [0, 0x85, 0x86, 0x87],
            [0x88, 0, 0x89, 0x8a],
            [0x8b, 0x8c, 0, 0x8d],
            [0x8e, 0x8f, 0x90, 0],
        ];
        let op = TABLE[f as usize][t as usize];
        if op != 0 {
            self.asm.emit(
                &[op],
                1,
                Some(if t == 0 { VType::Int } else { to_v.clone() }),
            );
        }
        let narrow = match to {
            Byte if from != Byte => Some(0x91),
            Char if from != Char => Some(0x92),
            Short if !matches!(from, Byte | Short) => Some(0x93),
            _ => None,
        };
        if let Some(n) = narrow {
            self.asm.emit(&[n], 1, Some(VType::Int));
        }
    }

    pub fn box_prim(&mut self, p: Prim) {
        let class = boxed(p);
        let desc = format!(
            "({}){}",
            Type::Prim(p).descriptor(),
            Type::class(class).descriptor()
        );
        let m = self.pool.method_ref(class, "valueOf", &desc);
        self.asm
            .emit_u2(0xb8, m, 1, Some(VType::Object(class.into())));
    }

    pub fn unbox(&mut self, class: &str, p: Prim) {
        let name = format!("{}Value", crate::types::prim_name(p));
        let desc = format!("(){}", Type::Prim(p).descriptor());
        let m = self.pool.method_ref(class, &name, &desc);
        self.asm
            .emit_u2(0xb6, m, 1, Some(VType::of(&Type::Prim(p))));
    }

    pub fn checkcast(&mut self, t: &Type) {
        if t.is_class(OBJECT) {
            return;
        }
        let c = self.pool.class(&t.internal());
        self.asm.emit_u2(0xc0, c, 1, Some(VType::of(t)));
    }

    /// Whether a value of type `from` may be assigned to `to` (JLS 5.2),
    /// boxing and unboxing only when `boxing` allows it (JLS 15.12.2's
    /// first and second phases).
    pub fn assignable(&self, from: &Type, to: &Type, boxing: bool) -> bool {
        match (from, to) {
            _ if from == to => true,
            (_, Type::Infer(_)) | (Type::Infer(_), _) => true,
            (Type::Prim(a), Type::Prim(b)) => widening(*a, *b),
            (Type::Null, t) => t.is_reference(),
            (Type::Prim(p), t) if boxing && t.is_reference() => {
                self.subtype(&Type::class(boxed(*p)), t)
            }
            (f, Type::Prim(p)) if boxing && f.is_reference() => {
                let e = f.erasure();
                matches!(&e, Type::Class(n, _) if unboxed(n).is_some_and(|q| widening(q, *p)))
            }
            (f, t) if f.is_reference() && t.is_reference() => self.subtype(f, t),
            _ => false,
        }
    }

    /// Reference subtyping by erasure; type arguments are not checked
    /// (unchecked, as raw types are).
    pub fn subtype(&self, from: &Type, to: &Type) -> bool {
        let (f, t) = (from.erasure(), to.erasure());
        match (&f, &t) {
            (_, Type::Class(n, _)) if &**n == OBJECT => true,
            (Type::Class(a, _), Type::Class(b, _)) => self.w.is_subclass(a, b),
            (Type::Array(a), Type::Array(b)) => {
                if a.is_prim() || b.is_prim() {
                    a == b
                } else {
                    self.subtype(a, b)
                }
            }
            (Type::Array(_), Type::Class(n, _)) => {
                matches!(&**n, "java/lang/Cloneable" | "java/io/Serializable")
            }
            _ => false,
        }
    }

    /// Converts the value on the stack from `from` to `to` for an
    /// assignment, a call or a return, or fails with a message at `span`.
    pub fn coerce(&mut self, from: &Type, to: &Type, span: Span) -> Result<()> {
        if !self.assignable(from, to, true) {
            return Err(Diag::new(
                span,
                format!("a {from} cannot be used as a {to}"),
            ));
        }
        match (from, to) {
            (Type::Prim(a), Type::Prim(b)) => self.prim_convert(*a, *b),
            (Type::Prim(p), _) => self.box_prim(*p),
            (f, Type::Prim(p)) => {
                let Type::Class(n, _) = f.erasure() else {
                    return Ok(());
                };
                let q = unboxed(&n).unwrap_or(*p);
                if !matches!(f, Type::Class(..)) {
                    self.checkcast(&Type::class(&n));
                }
                self.unbox(&n, q);
                self.prim_convert(q, *p);
            }
            _ => {}
        }
        Ok(())
    }

    /// Unboxes a wrapper on the stack, or leaves a primitive, for an
    /// operator; `None` when the type has no primitive meaning.
    pub fn to_prim(&mut self, t: &Type) -> Option<Prim> {
        match t {
            Type::Prim(p) => Some(*p),
            other => {
                let Type::Class(n, _) = other.erasure() else {
                    return None;
                };
                let p = unboxed(&n)?;
                self.unbox(&n, p);
                Some(p)
            }
        }
    }
}

/// The type an operand has for arithmetic (JLS 5.6.2), without emitting.
pub fn prim_of(t: &Type) -> Option<Prim> {
    match t {
        Type::Prim(p) => Some(*p),
        Type::Class(n, _) => unboxed(n),
        _ => None,
    }
}

/// Binary numeric promotion (JLS 5.6.2).
pub fn promote(a: Prim, b: Prim) -> Prim {
    use Prim::*;
    if a == Double || b == Double {
        Double
    } else if a == Float || b == Float {
        Float
    } else if a == Long || b == Long {
        Long
    } else {
        Int
    }
}
