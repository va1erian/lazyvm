//! Expressions as bytecode (JLS 15): the dispatch, literals, arrays,
//! casts, class literals, collection literals and cascades. Operators are
//! in `arith.rs`, conditions in `cond.rs`, calls in `call.rs`.

use super::asm::VType;
use super::body::Body;
use super::names::Recv;
use crate::ast::{Expr, Prim};
use crate::diag::{Diag, Result, Span};
use crate::types::Type;

impl Body<'_> {
    /// Emits `e`, leaving its value on the stack, and returns its type.
    /// `want` is the type the context expects, for lambdas, literals and
    /// inference; the caller converts to it.
    pub fn expr(&mut self, e: &Expr, want: Option<&Type>) -> Result<Type> {
        self.nested_expr(e, want)
    }

    fn nested_expr(&mut self, e: &Expr, want: Option<&Type>) -> Result<Type> {
        match e {
            Expr::Lit(l, span) => self.literal(l, *span, want, false),
            Expr::Template(parts, _) => self.template(parts),
            Expr::Name(n) => match self.place_of_name(n)? {
                Some(p) => {
                    self.place_operands_load(&p);
                    Ok(p.ty())
                }
                None => self.err(n.span, format!("no variable `{}`", n.name)),
            },
            Expr::This(span) => {
                if self.is_static {
                    return self.err(*span, "`this` in static code");
                }
                let t = self.this_type();
                self.load(0, &t);
                Ok(t)
            }
            Expr::Field { target, name } => match self.receiver(target)? {
                Recv::Value(t) => self.field_of_value(t, name),
                Recv::Class(c) => {
                    let p = self.place_on_class(&c, name)?;
                    self.place_load(&p);
                    Ok(p.ty())
                }
                Recv::Package(p) => self.err(
                    e.span(),
                    format!("no class or variable {}", p.replace('/', ".")),
                ),
            },
            Expr::Call {
                target,
                name,
                args,
                is_super,
            } => self.call(target.as_deref(), name, args, *is_super, want),
            Expr::New { ty, args, span } => self.new_object(ty, args, *span, want),
            Expr::NewArray {
                elem,
                dims,
                extra,
                init,
                span,
            } => self.new_array_expr(elem, dims, *extra, init.as_deref(), *span),
            Expr::ArrayInit(items, span) => match want {
                Some(t @ Type::Array(_)) => self.array_init(t, items, *span),
                _ => self.err(*span, "`{...}` needs an array type here: `new int[] {...}`"),
            },
            Expr::Index { array, index } => {
                let t = self.expr(array, None)?;
                let Type::Array(elem) = t else {
                    return self.err(array.span(), format!("a {t} is not an array"));
                };
                let it = self.expr(index, None)?;
                self.coerce(&it, &Type::Prim(Prim::Int), index.span())?;
                self.array_load(&elem);
                Ok(*elem)
            }
            Expr::Unary { op, expr, span } => self.unary(op, expr, *span, want),
            Expr::Step {
                op,
                post,
                target,
                span,
            } => self.step(op, *post, target, *span, true),
            Expr::Binary { op, .. }
                if matches!(*op, "&&" | "||" | "==" | "!=" | "<" | ">" | "<=" | ">=") =>
            {
                self.bool_value(e)
            }
            Expr::Binary { .. } if let Some(v) = self.const_int(e) => {
                self.int_const(v);
                Ok(Type::Prim(crate::ast::Prim::Int))
            }
            Expr::Binary { op, left, right } => self.binary(op, left, right, e),
            Expr::Assign { op, target, value } => self.assign(*op, target, value, true),
            Expr::Cond { cond, then, els } => self.conditional(cond, then, els, want),
            Expr::Cast { ty, expr, span } => self.cast(ty, expr, *span),
            Expr::InstanceOf { .. } => self.bool_value(e),
            Expr::Lambda { .. } | Expr::MethodRef { .. } => self.lambda_value(e, want),
            Expr::ClassLit(ty, _) => self.class_literal(ty),
            Expr::Await(_, span) => self.err(*span, "`await` needs an `async` method or lambda"),
            Expr::Cascade { recv, calls } => self.cascade(recv, calls),
            Expr::List(items, span) => self.list_literal(items, *span, want),
            Expr::Map(entries, span) => self.map_literal(entries, *span, want),
            Expr::TypeExpr(t) => self.err(t.span(), "a type is not a value"),
        }
    }

    fn place_operands_load(&mut self, p: &super::names::Place) {
        if let super::names::Place::Field { .. } = p {
            self.load(0, &self.this_type());
        }
        if let super::names::Place::Local(l) = p
            && l.boxed
        {
            self.load_local(l);
            return;
        }
        self.place_load(p);
    }

    /// Compiles `e` for its effect and drops its value.
    pub fn expr_stmt(&mut self, e: &Expr) -> Result<()> {
        match e {
            Expr::Assign { op, target, value } => {
                self.assign(*op, target, value, false)?;
            }
            Expr::Step {
                op,
                post,
                target,
                span,
            } => {
                self.step(op, *post, target, *span, false)?;
            }
            Expr::Call { .. } | Expr::New { .. } | Expr::Cascade { .. } | Expr::Await(..) => {
                let t = self.expr(e, None)?;
                if matches!(e, Expr::Call { .. }) {
                    self.asm.drop_trailing_cast();
                }
                self.pop_value(&t);
            }
            other => {
                return self.err(
                    other.span(),
                    "not a statement: this value is computed and thrown away",
                );
            }
        }
        Ok(())
    }

    // ---- literals ----

    // ---- other forms ----

    fn cast(&mut self, ty: &crate::ast::TypeRef, e: &Expr, span: Span) -> Result<Type> {
        let to = self.resolve_type(ty)?;
        let from = self.expr(e, Some(&to))?;
        match (&from, &to) {
            (Type::Prim(a), Type::Prim(b)) if (*a == Prim::Boolean) == (*b == Prim::Boolean) => {
                self.prim_convert(*a, *b)
            }
            (f, Type::Prim(_)) if f.is_reference() => self.coerce(&from, &to, span)?,
            (Type::Prim(_), t) if t.is_reference() => self.coerce(&from, &to, span)?,
            (f, t) if f.is_reference() && t.is_reference() => {
                if !self.subtype(f, t) {
                    self.checkcast(&t.erasure());
                }
            }
            _ => return self.err(span, format!("a {from} cannot be cast to {to}")),
        }
        Ok(to)
    }

    fn class_literal(&mut self, ty: &crate::ast::TypeRef) -> Result<Type> {
        let t = self.resolve_type(ty)?;
        let class_t = Type::Class(
            "java/lang/Class".into(),
            vec![match &t {
                Type::Prim(p) => Type::class(crate::types::boxed(*p)),
                Type::Void => Type::class("java/lang/Void"),
                other => other.erasure(),
            }],
        );
        match &t {
            Type::Prim(_) | Type::Void => {
                let owner = match &t {
                    Type::Prim(p) => crate::types::boxed(*p),
                    _ => "java/lang/Void",
                };
                let f = self.pool.field_ref(owner, "TYPE", "Ljava/lang/Class;");
                self.asm
                    .emit_u2(0xb2, f, 0, Some(VType::Object("java/lang/Class".into())));
            }
            other => {
                let c = self.pool.class(&other.internal());
                self.ldc(c, VType::Object("java/lang/Class".into()));
            }
        }
        Ok(class_t)
    }

    fn cascade(&mut self, recv: &Expr, calls: &[(crate::ast::Ident, Vec<Expr>)]) -> Result<Type> {
        let t = self.expr(recv, None)?;
        if !t.is_reference() || t == Type::Null {
            return self.err(recv.span(), "a cascade needs an object");
        }
        for (name, args) in calls {
            self.asm.dup_under(0);
            let r = self.call_on(t.clone(), name, args, None)?;
            self.pop_value(&r);
        }
        Ok(t)
    }

    fn list_literal(&mut self, items: &[Expr], span: Span, want: Option<&Type>) -> Result<Type> {
        let elem = match want.and_then(|w| self.w.as_super(w, "java/util/List")) {
            Some(Type::Class(_, a)) if a.len() == 1 => a[0].clone(),
            _ => self.join_types(items)?,
        };
        let list = "java/util/ArrayList";
        let c = self.pool.class(list);
        self.asm.new_object(c);
        let was = self.asm.top().cloned();
        self.asm.dup_under(0);
        self.int_const(
            i32::try_from(items.len()).map_err(|_| Diag::new(span, "too many elements"))?,
        );
        let init = self.pool.method_ref(list, "<init>", "(I)V");
        self.asm.emit_u2(0xb7, init, 2, None);
        if let Some(w) = was {
            self.asm.initialised(&w, list);
        }
        let add = self.pool.method_ref(list, "add", "(Ljava/lang/Object;)Z");
        for item in items {
            self.asm.dup_under(0);
            let t = self.expr(item, Some(&elem))?;
            self.coerce(&t, &elem, item.span())?;
            self.asm.emit_u2(0xb6, add, 2, Some(VType::Int));
            self.asm.emit(&[0x57], 1, None);
        }
        Ok(Type::Class(list.into(), vec![elem]))
    }

    fn map_literal(
        &mut self,
        entries: &[(Expr, Expr)],
        _span: Span,
        want: Option<&Type>,
    ) -> Result<Type> {
        let (k, v) = match want.and_then(|w| self.w.as_super(w, "java/util/Map")) {
            Some(Type::Class(_, a)) if a.len() == 2 => (a[0].clone(), a[1].clone()),
            _ => {
                let keys: Vec<Expr> = entries.iter().map(|e| e.0.clone()).collect();
                let vals: Vec<Expr> = entries.iter().map(|e| e.1.clone()).collect();
                (self.join_types(&keys)?, self.join_types(&vals)?)
            }
        };
        let map = "java/util/LinkedHashMap";
        let c = self.pool.class(map);
        self.asm.new_object(c);
        let was = self.asm.top().cloned();
        self.asm.dup_under(0);
        let init = self.pool.method_ref(map, "<init>", "()V");
        self.asm.emit_u2(0xb7, init, 1, None);
        if let Some(w) = was {
            self.asm.initialised(&w, map);
        }
        let put = self.pool.method_ref(
            map,
            "put",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
        );
        for (ke, ve) in entries {
            self.asm.dup_under(0);
            let kt = self.expr(ke, Some(&k))?;
            self.coerce(&kt, &k, ke.span())?;
            let vt = self.expr(ve, Some(&v))?;
            self.coerce(&vt, &v, ve.span())?;
            self.asm.emit_u2(
                0xb6,
                put,
                3,
                Some(VType::Object(crate::types::OBJECT.into())),
            );
            self.asm.emit(&[0x57], 1, None);
        }
        Ok(Type::Class(map.into(), vec![k, v]))
    }

    /// The element type of a literal with no expected type: the elements'
    /// common type when they agree, boxed, else `Object`.
    fn join_types(&mut self, items: &[Expr]) -> Result<Type> {
        let mut out: Option<Type> = None;
        for i in items {
            let t = match self.type_of(i, None)? {
                Type::Prim(p) => Type::class(crate::types::boxed(p)),
                Type::Null => continue,
                other => other,
            };
            out = Some(match out {
                None => t,
                Some(o) if o == t => o,
                Some(_) => return Ok(Type::object()),
            });
        }
        Ok(out.unwrap_or_else(Type::object))
    }
}
