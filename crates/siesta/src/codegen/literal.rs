//! Literals (JLS 3.10): numbers with their range rules, strings, and
//! string templates built with `StringBuilder`.

use super::asm::VType;
use super::body::Body;
use crate::ast::{Lit, Prim, TemplatePart};
use crate::diag::{Diag, Result, Span};
use crate::lex::{Num, NumKind};
use crate::types::{STRING, Type};

impl Body<'_> {
    // ---- literals ----

    pub fn literal(
        &mut self,
        l: &Lit,
        span: Span,
        want: Option<&Type>,
        negate: bool,
    ) -> Result<Type> {
        match l {
            Lit::Num(n) => self.number(n, span, want, negate),
            Lit::Char(c) => {
                self.int_const(i32::from(*c));
                Ok(Type::Prim(Prim::Char))
            }
            Lit::Str(s) => {
                self.string_const(s, span)?;
                Ok(Type::string())
            }
            Lit::Bool(b) => {
                self.int_const(i32::from(*b));
                Ok(Type::Prim(Prim::Boolean))
            }
            Lit::Null => {
                self.asm.emit(&[0x01], 0, Some(VType::Null));
                Ok(Type::Null)
            }
        }
    }

    fn number(&mut self, n: &Num, span: Span, want: Option<&Type>, negate: bool) -> Result<Type> {
        let bad = || Diag::new(span, "number out of range");
        match n.kind {
            NumKind::Int | NumKind::Long => {
                let long = n.kind == NumKind::Long;
                let max: u64 = if long { u64::MAX } else { u64::from(u32::MAX) };
                let v = u64::from_str_radix(&n.text, n.radix).map_err(|_| bad())?;
                if v > max {
                    return Err(bad());
                }
                // A decimal literal only reaches the sign bit with a minus
                // (JLS 3.10.1); other radixes are two's complement.
                let limit = if long { 1u64 << 63 } else { 1u64 << 31 };
                if n.radix == 10 && (v > limit || (v == limit && !negate)) {
                    return Err(bad());
                }
                let signed = if long {
                    v as i64
                } else {
                    i64::from(v as u32 as i32)
                };
                let signed = if negate {
                    signed.wrapping_neg()
                } else {
                    signed
                };
                if long {
                    self.long_const(signed);
                    return Ok(Type::Prim(Prim::Long));
                }
                let i = signed as i32;
                self.int_const(i);
                // `byte b = 10;`: a constant that fits narrows (JLS 5.2).
                if let Some(Type::Prim(p @ (Prim::Byte | Prim::Short | Prim::Char))) = want {
                    let fits = match p {
                        Prim::Byte => i8::try_from(i).is_ok(),
                        Prim::Short => i16::try_from(i).is_ok(),
                        _ => u16::try_from(i).is_ok(),
                    };
                    if fits {
                        return Ok(Type::Prim(*p));
                    }
                }
                Ok(Type::Prim(Prim::Int))
            }
            NumKind::Float => {
                let v: f32 = n.text.parse().map_err(|_| bad())?;
                if v.is_infinite() {
                    return Err(bad());
                }
                self.float_const(if negate { -v } else { v });
                Ok(Type::Prim(Prim::Float))
            }
            NumKind::Double => {
                let v: f64 = n.text.parse().map_err(|_| bad())?;
                if v.is_infinite() {
                    return Err(bad());
                }
                self.double_const(if negate { -v } else { v });
                Ok(Type::Prim(Prim::Double))
            }
        }
    }

    pub(super) fn template(&mut self, parts: &[TemplatePart]) -> Result<Type> {
        self.builder_start();
        for p in parts {
            match p {
                TemplatePart::Text(t) => {
                    self.string_const(t, Span::default())?;
                    self.append(&Type::string());
                }
                TemplatePart::Expr(e) => {
                    let t = self.expr(e, None)?;
                    if t == Type::Void {
                        return self.err(e.span(), "this expression has no value");
                    }
                    self.append(&t);
                }
            }
        }
        self.builder_finish();
        Ok(Type::string())
    }

    pub fn builder_start(&mut self) {
        let sb = "java/lang/StringBuilder";
        let c = self.pool.class(sb);
        self.asm.new_object(c);
        let was = self.asm.top().cloned();
        self.asm.dup_under(0);
        let init = self.pool.method_ref(sb, "<init>", "()V");
        self.asm.emit_u2(0xb7, init, 1, None);
        if let Some(w) = was {
            self.asm.initialised(&w, sb);
        }
    }

    /// `StringBuilder.append` with the overload for `t` (JLS 15.18.1).
    pub fn append(&mut self, t: &Type) {
        let arg = match t {
            Type::Prim(Prim::Byte | Prim::Short) => "I".to_string(),
            Type::Prim(_) => t.descriptor(),
            t if t.is_class(STRING) => "Ljava/lang/String;".into(),
            _ => "Ljava/lang/Object;".into(),
        };
        let m = self.pool.method_ref(
            "java/lang/StringBuilder",
            "append",
            &format!("({arg})Ljava/lang/StringBuilder;"),
        );
        self.asm.emit_u2(
            0xb6,
            m,
            2,
            Some(VType::Object("java/lang/StringBuilder".into())),
        );
    }

    pub fn builder_finish(&mut self) {
        let m = self.pool.method_ref(
            "java/lang/StringBuilder",
            "toString",
            "()Ljava/lang/String;",
        );
        self.asm
            .emit_u2(0xb6, m, 1, Some(VType::Object(STRING.into())));
    }
}
