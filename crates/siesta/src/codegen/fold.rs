//! Constant `int` expressions (JLS 15.29) computed while compiling, as javac
//! does: `TILE - 1` with `final int TILE = 16` is `bipush 15`. Only `int`
//! is folded, which is where loops spend their constants; other types are
//! computed at run time with the same result.

use super::body::Body;
use crate::ast::{Expr, Lit, Prim};
use crate::lex::{Num, NumKind};
use crate::types::Type;

impl Body<'_> {
    /// The value of `e` if it is a constant `int` expression.
    pub(super) fn const_int(&self, e: &Expr) -> Option<i32> {
        match e {
            Expr::Lit(Lit::Num(n), _) => int_lit(n, false),
            Expr::Unary { op: "-", expr, .. } => match &**expr {
                Expr::Lit(Lit::Num(n), _) => int_lit(n, true),
                inner => self.const_int(inner).map(i32::wrapping_neg),
            },
            Expr::Unary { op: "~", expr, .. } => self.const_int(expr).map(|v| !v),
            Expr::Name(n) if self.find_local(&n.name).is_none() => {
                let (owner, ty, is_static) = self.own_field(&n.name)?;
                if !is_static || ty != Type::Prim(Prim::Int) {
                    return None;
                }
                let k = owner
                    .fields
                    .iter()
                    .find(|f| f.name == n.name)?
                    .constant
                    .clone()?;
                match &k.lit {
                    Lit::Num(num) => int_lit(num, k.negate),
                    _ => None,
                }
            }
            Expr::Binary { op, left, right } => {
                fold(op, self.const_int(left)?, self.const_int(right)?)
            }
            _ => None,
        }
    }
}

impl Body<'_> {
    /// Emits `e` as a `long`, `float` or `double` constant when it is a
    /// constant `int` an operator widens to `p` (`t * 6` with a float `t`
    /// is `ldc 6.0f`, not `bipush 6; i2f`). False when it is not one.
    pub(super) fn widened_const(&mut self, e: &Expr, p: Prim) -> bool {
        let Some(v) = self.const_int(e) else {
            return false;
        };
        match p {
            Prim::Long => self.long_const(i64::from(v)),
            Prim::Float => self.float_const(v as f32),
            Prim::Double => self.double_const(f64::from(v)),
            _ => return false,
        }
        true
    }
}

/// An `int` literal's value, with the range rules of JLS 3.10.1.
fn int_lit(n: &Num, negate: bool) -> Option<i32> {
    if n.kind != NumKind::Int {
        return None;
    }
    let v = u32::from_str_radix(&n.text, n.radix).ok()?;
    if n.radix == 10 && (v > 1 << 31 || (v == 1 << 31 && !negate)) {
        return None;
    }
    let v = v as i32;
    Some(if negate { v.wrapping_neg() } else { v })
}

/// `a op b` with Java's `int` semantics; `None` for a division by zero,
/// which must throw at run time.
fn fold(op: &str, a: i32, b: i32) -> Option<i32> {
    Some(match op {
        "+" => a.wrapping_add(b),
        "-" => a.wrapping_sub(b),
        "*" => a.wrapping_mul(b),
        "/" if b != 0 => a.wrapping_div(b),
        "%" if b != 0 => a.wrapping_rem(b),
        "<<" => a.wrapping_shl(b as u32 & 31),
        ">>" => a.wrapping_shr(b as u32 & 31),
        ">>>" => ((a as u32) >> (b as u32 & 31)) as i32,
        "&" => a & b,
        "|" => a | b,
        "^" => a ^ b,
        _ => return None,
    })
}
