//! Expressions by precedence (JLS 15.7 to 15.27): assignment, `?:`,
//! binary operators, `instanceof`, casts, unary operators and lambdas.

use super::Parser;
use crate::ast::{Expr, LambdaBody, LambdaParam, TypeRef};
use crate::diag::Result;
use crate::lex::{Kw, Tok};

/// Binary operators with their precedence, loosest first (JLS 15.17 to 15.24).
const BINARY: &[(&str, u8)] = &[
    ("||", 1),
    ("&&", 2),
    ("|", 3),
    ("^", 4),
    ("&", 5),
    ("==", 6),
    ("!=", 6),
    ("<", 7),
    ("<=", 7),
    ("+", 9),
    ("-", 9),
    ("*", 10),
    ("/", 10),
    ("%", 10),
    ("<<", 8),
];

const COMPOUND: &[(&str, &str)] = &[
    ("+=", "+"),
    ("-=", "-"),
    ("*=", "*"),
    ("/=", "/"),
    ("%=", "%"),
    ("&=", "&"),
    ("|=", "|"),
    ("^=", "^"),
    ("<<=", "<<"),
];

const RELATIONAL: u8 = 7;

impl Parser {
    pub(crate) fn expr(&mut self) -> Result<Expr> {
        self.nested(|p| p.assignment())
    }

    fn assignment(&mut self) -> Result<Expr> {
        if self.at_lambda() {
            return self.lambda();
        }
        let target = self.ternary()?;
        let (op, len) = if self.is_op("=") {
            (None, 1)
        } else if let Some(&(_, op)) = COMPOUND.iter().find(|(t, _)| self.is_op(t)) {
            (Some(op), 1)
        } else if self.joined_gt_eq(3) {
            (Some(">>>"), 4)
        } else if self.joined_gt_eq(2) {
            (Some(">>"), 3)
        } else {
            return Ok(target);
        };
        self.pos += len;
        let value = self.expr()?;
        Ok(Expr::Assign {
            op,
            target: Box::new(target),
            value: Box::new(value),
        })
    }

    pub(super) fn ternary(&mut self) -> Result<Expr> {
        let cond = self.binary(1)?;
        if !self.eat_op("?") {
            return Ok(cond);
        }
        let then = self.expr()?;
        self.expect_op(":")?;
        let els = if self.at_lambda() {
            self.lambda()?
        } else {
            self.nested(|p| p.ternary())?
        };
        Ok(Expr::Cond {
            cond: Box::new(cond),
            then: Box::new(then),
            els: Box::new(els),
        })
    }

    /// The binary operator ahead, as `(text, tokens, precedence)`.
    fn binary_op(&self) -> Option<(&'static str, usize, u8)> {
        if self.is_op(">") {
            // `>>=` and `>>>=` are assignments, read by `assignment`.
            if self.joined_gt_eq(3) || self.joined_gt_eq(2) {
                return None;
            }
            return Some(if self.joined_gt(3) {
                (">>>", 3, 8)
            } else if self.joined_gt(2) {
                (">>", 2, 8)
            } else if self.joined_gt_eq(1) {
                (">=", 2, RELATIONAL)
            } else {
                (">", 1, RELATIONAL)
            });
        }
        match self.tok(0) {
            Tok::Op(op) => BINARY
                .iter()
                .find(|(t, _)| t == op)
                .map(|&(t, p)| (t, 1, p)),
            _ => None,
        }
    }

    fn binary(&mut self, min: u8) -> Result<Expr> {
        let mut left = self.unary()?;
        loop {
            if self.is_kw(Kw::Instanceof) && RELATIONAL >= min {
                self.pos += 1;
                let ty = self.ty()?;
                let bind = if matches!(self.tok(0), Tok::Ident(_)) {
                    Some(self.ident()?)
                } else {
                    None
                };
                left = Expr::InstanceOf {
                    expr: Box::new(left),
                    ty,
                    bind,
                };
                continue;
            }
            let Some((op, len, prec)) = self.binary_op() else {
                return Ok(left);
            };
            if prec < min {
                return Ok(left);
            }
            self.pos += len;
            let right = self.nested(|p| p.binary(prec + 1))?;
            left = Expr::Binary {
                op,
                left: Box::new(left),
                right: Box::new(right),
            };
        }
    }

    pub(super) fn unary(&mut self) -> Result<Expr> {
        self.nested(|p| p.unary_inner())
    }

    fn unary_inner(&mut self) -> Result<Expr> {
        let lo = self.span();
        if self.eat_kw(Kw::Await) {
            let e = self.unary()?;
            return Ok(Expr::Await(Box::new(e), lo.to(self.prev_span())));
        }
        for op in ["++", "--"] {
            if self.eat_op(op) {
                let target = Box::new(self.unary()?);
                return Ok(Expr::Step {
                    op,
                    post: false,
                    target,
                    span: lo.to(self.prev_span()),
                });
            }
        }
        for op in ["!", "~", "-", "+"] {
            if self.eat_op(op) {
                let expr = Box::new(self.unary()?);
                return Ok(Expr::Unary {
                    op,
                    expr,
                    span: lo.to(self.prev_span()),
                });
            }
        }
        if self.is_op("(")
            && !self.at_lambda()
            && let Some(cast) = self.attempt(|p| p.cast())
        {
            return Ok(cast);
        }
        self.postfix()
    }

    /// `(Type) operand` (JLS 15.16): a reference type is a cast only when
    /// what follows cannot continue a parenthesised expression.
    fn cast(&mut self) -> Result<Expr> {
        let lo = self.expect_op("(")?;
        let ty = self.ty()?;
        self.expect_op(")")?;
        let prim = matches!(ty, TypeRef::Prim(..));
        let operand_start = match self.tok(0) {
            Tok::Ident(_) | Tok::Num(_) | Tok::Char(_) | Tok::Str(_) | Tok::Template(_) => true,
            Tok::Kw(k) => matches!(
                k,
                Kw::This | Kw::New | Kw::Super | Kw::True | Kw::False | Kw::Null
            ),
            Tok::Op(o) => {
                matches!(*o, "(" | "!" | "~" | "[")
                    || (prim && matches!(*o, "-" | "+" | "++" | "--"))
            }
            Tok::Eof => false,
        };
        if !operand_start || matches!(ty, TypeRef::Var(_) | TypeRef::Void(_)) {
            return self.fail("not a cast");
        }
        let expr = Box::new(self.unary()?);
        Ok(Expr::Cast {
            ty,
            expr,
            span: lo.to(self.prev_span()),
        })
    }

    /// `x ->`, `(...) ->` or `async` before either.
    pub(super) fn at_lambda(&self) -> bool {
        let skip = usize::from(*self.tok(0) == Tok::Kw(Kw::Async));
        if matches!(self.tok(skip), Tok::Ident(_)) {
            return self.is_op_at(skip + 1, "->");
        }
        if !self.is_op_at(skip, "(") {
            return false;
        }
        let mut depth = 0usize;
        let mut i = skip;
        loop {
            match self.tok(i) {
                Tok::Op("(") => depth += 1,
                Tok::Op(")") => {
                    depth -= 1;
                    if depth == 0 {
                        return self.is_op_at(i + 1, "->");
                    }
                }
                Tok::Eof => return false,
                _ => {}
            }
            i += 1;
        }
    }

    fn lambda(&mut self) -> Result<Expr> {
        let lo = self.span();
        let is_async = self.eat_kw(Kw::Async);
        let mut params = Vec::new();
        if matches!(self.tok(0), Tok::Ident(_)) {
            params.push(LambdaParam {
                ty: None,
                name: self.ident()?,
            });
        } else {
            self.expect_op("(")?;
            while !self.is_op(")") {
                let typed = !(matches!(self.tok(0), Tok::Ident(_))
                    && (self.is_op_at(1, ",") || self.is_op_at(1, ")")));
                let ty = if typed { Some(self.ty()?) } else { None };
                params.push(LambdaParam {
                    ty,
                    name: self.ident()?,
                });
                if !self.eat_op(",") {
                    break;
                }
            }
            self.expect_op(")")?;
        }
        self.expect_op("->")?;
        let body = if self.is_op("{") {
            LambdaBody::Block(self.block()?)
        } else {
            LambdaBody::Expr(Box::new(self.expr()?))
        };
        Ok(Expr::Lambda {
            params,
            body,
            is_async,
            span: lo.to(self.prev_span()),
        })
    }
}
