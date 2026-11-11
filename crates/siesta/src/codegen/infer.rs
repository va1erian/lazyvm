//! Choosing among overloads (JLS 15.12.2, simplified as siesta.md section 7
//! says) and inferring a generic method's type arguments one way: from the
//! arguments, left to right, then from the expected type.

use std::collections::HashMap;
use std::rc::Rc;

use super::body::Body;
use crate::ast::{Expr, LambdaBody};
use crate::diag::{Result, Span};
use crate::types::Type;
use crate::world::Method;

/// An argument as overload resolution sees it.
#[derive(Clone, Debug)]
pub enum Arg {
    Typed(Type),
    /// A lambda: its parameter count, and whether its body gives a value.
    Lambda {
        arity: usize,
        value: Option<bool>,
    },
    /// A method reference, applicable to any functional interface.
    Ref,
}

/// A method chosen for a call, with its parameter and result types after
/// inference, and whether the arguments go through its varargs array.
pub struct Chosen {
    pub method: Method,
    pub params: Vec<Type>,
    pub ret: Type,
    pub varargs: bool,
}

impl Body<'_> {
    pub fn arg_kinds(&mut self, args: &[Expr]) -> Result<Vec<Arg>> {
        args.iter()
            .map(|a| match a {
                Expr::Lambda { params, body, .. } => Ok(Arg::Lambda {
                    arity: params.len(),
                    value: lambda_value_kind(body),
                }),
                Expr::MethodRef { .. } => Ok(Arg::Ref),
                other => Ok(Arg::Typed(self.type_of(other, None)?)),
            })
            .collect()
    }

    /// Picks the method of `cands` the arguments select.
    pub fn select(
        &mut self,
        cands: Vec<Method>,
        args: &[Expr],
        want: Option<&Type>,
        what: &str,
        span: Span,
    ) -> Result<Chosen> {
        if cands.is_empty() {
            return self.err(span, format!("no method {what}"));
        }
        let kinds = self.arg_kinds(args)?;
        for phase in 1..=3 {
            let ok: Vec<&Method> = cands
                .iter()
                .filter(|m| self.applicable(m, &kinds, phase))
                .collect();
            if ok.is_empty() {
                continue;
            }
            let best = self.most_specific(&ok, &kinds);
            let Some(m) = best else {
                let names: Vec<String> = ok.iter().map(|m| sig_text(m)).collect();
                return self.err(
                    span,
                    format!("the call {what} is ambiguous: {}", names.join(", ")),
                );
            };
            return self.instantiate(m.clone(), args, &kinds, phase == 3, want, span);
        }
        let shown: Vec<String> = cands.iter().take(6).map(sig_text).collect();
        let got: Vec<String> = kinds.iter().map(arg_text).collect();
        self.err(
            span,
            format!(
                "no {what} takes ({}); there is {}",
                got.join(", "),
                shown.join(", ")
            ),
        )
    }

    fn applicable(&self, m: &Method, kinds: &[Arg], phase: u8) -> bool {
        let params = generic_free(&m.params, &m.info.tparams);
        let n = params.len();
        let fits = |b: &Self, k: &Arg, p: &Type| match k {
            Arg::Typed(t) => b.assignable(t, p, phase >= 2),
            Arg::Lambda { arity, .. } => {
                b.w.functional_method(&p.erasure())
                    .is_some_and(|f| f.params.len() == *arity)
            }
            Arg::Ref => b.w.functional_method(&p.erasure()).is_some(),
        };
        if phase < 3 {
            return kinds.len() == n && kinds.iter().zip(&params).all(|(k, p)| fits(self, k, p));
        }
        if !m.info.is_varargs() || kinds.len() + 1 < n {
            return false;
        }
        let Some(Type::Array(elem)) = params.last() else {
            return false;
        };
        kinds.iter().enumerate().all(|(i, k)| {
            if i + 1 < n {
                fits(self, k, &params[i])
            } else {
                fits(self, k, elem)
            }
        })
    }

    /// The candidate whose parameters all fit the others' (JLS 15.12.2.5).
    fn most_specific<'m>(&self, ok: &[&'m Method], kinds: &[Arg]) -> Option<&'m Method> {
        if ok.len() == 1 {
            return Some(ok[0]);
        }
        let more = |a: &Method, b: &Method| -> bool {
            let (pa, pb) = (
                generic_free(&a.params, &a.info.tparams),
                generic_free(&b.params, &b.info.tparams),
            );
            pa.len() == pb.len()
                && pa.iter().zip(&pb).zip(kinds).all(|((x, y), k)| match k {
                    Arg::Lambda { value, .. } => self.lambda_prefers(x, y, *value),
                    _ => self.assignable(&x.erasure(), &y.erasure(), false),
                })
        };
        let best: Vec<&&Method> = ok
            .iter()
            .filter(|a| ok.iter().all(|b| std::ptr::eq(**a, *b) || more(a, b)))
            .collect();
        match best.as_slice() {
            [one] => Some(one),
            // Equal signatures from an interface and a class: the first
            // found, which is the most derived.
            [first, ..] if best.iter().all(|m| m.params == first.params) => Some(first),
            _ => None,
        }
    }

    /// For a lambda, a functional interface whose method returns a value
    /// suits a value body, and one returning `void` a statement body.
    fn lambda_prefers(&self, x: &Type, y: &Type, value: Option<bool>) -> bool {
        let rx = self
            .w
            .functional_method(&x.erasure())
            .map(|m| m.ret != Type::Void);
        let ry = self
            .w
            .functional_method(&y.erasure())
            .map(|m| m.ret != Type::Void);
        match value {
            Some(v) => rx == Some(v) || rx == ry,
            None => true,
        }
    }

    fn instantiate(
        &mut self,
        m: Method,
        args: &[Expr],
        kinds: &[Arg],
        varargs: bool,
        want: Option<&Type>,
        span: Span,
    ) -> Result<Chosen> {
        if m.info.tparams.is_empty() {
            let (params, ret) = (m.params.clone(), m.ret.clone());
            return Ok(Chosen {
                method: m,
                params,
                ret,
                varargs,
            });
        }
        let mut map: HashMap<Rc<str>, Type> = HashMap::new();
        for (i, tp) in m.info.tparams.iter().enumerate() {
            map.insert(tp.name.clone(), Type::Infer(i as u32));
        }
        let params: Vec<Type> = m.params.iter().map(|p| p.subst(&map)).collect();
        let ret = m.ret.subst(&map);
        let mut b: HashMap<u32, Type> = HashMap::new();
        let n = params.len();
        let param_at = |i: usize| -> Type {
            match (&params[n - 1], varargs && i + 1 >= n) {
                (Type::Array(e), true) => (**e).clone(),
                _ => params[i.min(n - 1)].clone(),
            }
        };
        for (i, k) in kinds.iter().enumerate() {
            if let Arg::Typed(t) = k {
                self.unify(&param_at(i), t, &mut b);
            }
        }
        for (i, a) in args.iter().enumerate() {
            if let Expr::Lambda { .. } | Expr::MethodRef { .. } = a {
                // `Type::m` tells the interface's first parameter, as Java
                // infers from an exact method reference (JLS 15.13.1).
                if let Some(ct) = self.ref_class(a)
                    && let Some(f) = self.w.functional_method(&apply(&param_at(i), &b))
                    && let Some(p0) = f.params.first()
                {
                    self.unify(p0, &ct, &mut b);
                }
                let target = resolve_infer(&param_at(i), &b, false);
                if let Some(body_t) = self.lambda_result(a, &target)?
                    && let Some(f) = self.w.functional_method(&apply(&param_at(i), &b))
                {
                    self.unify(&f.ret, &body_t, &mut b);
                }
            }
        }
        if let Some(w) = want
            && ret.has_infer()
        {
            self.unify(&ret, w, &mut b);
        }
        let fin = |t: &Type| resolve_infer(t, &b, true);
        let _ = span;
        Ok(Chosen {
            params: params.iter().map(fin).collect(),
            ret: fin(&ret),
            method: m,
            varargs,
        })
    }

    /// Binds inference variables in `p` from the argument type `a`.
    fn unify(&self, p: &Type, a: &Type, b: &mut HashMap<u32, Type>) {
        match p {
            Type::Infer(i) => {
                let a = match a {
                    Type::Prim(q) => Type::class(crate::types::boxed(*q)),
                    Type::Null | Type::Void | Type::Infer(_) => return,
                    other => other.clone(),
                };
                b.entry(*i).or_insert(a);
            }
            Type::Class(n, pargs) if p.has_infer() => {
                if let Some(Type::Class(_, aargs)) = self.w.as_super(a, n)
                    && aargs.len() == pargs.len()
                {
                    for (x, y) in pargs.iter().zip(&aargs) {
                        self.unify(x, y, b);
                    }
                }
            }
            Type::Array(pe) => {
                if let Type::Array(ae) = a {
                    self.unify(pe, ae, b);
                }
            }
            _ => {}
        }
    }
}

/// Replaces bound inference variables; unbound ones become `Object` when
/// `finish` (siesta.md section 6: what nothing pins down is its bound).
pub fn resolve_infer(t: &Type, b: &HashMap<u32, Type>, finish: bool) -> Type {
    match t {
        Type::Infer(i) => match b.get(i) {
            Some(x) => x.clone(),
            None if finish => Type::object(),
            None => t.clone(),
        },
        Type::Class(n, args) => Type::Class(
            n.clone(),
            args.iter().map(|a| resolve_infer(a, b, finish)).collect(),
        ),
        Type::Array(e) => Type::Array(Box::new(resolve_infer(e, b, finish))),
        other => other.clone(),
    }
}

fn apply(t: &Type, b: &HashMap<u32, Type>) -> Type {
    resolve_infer(t, b, false)
}

/// Parameters with a method's own type variables replaced by their bounds,
/// for applicability checks before inference.
fn generic_free(params: &[Type], tparams: &[crate::sig::TypeParam]) -> Vec<Type> {
    if tparams.is_empty() {
        return params.to_vec();
    }
    let map: HashMap<Rc<str>, Type> = tparams
        .iter()
        .map(|t| (t.name.clone(), Type::Infer(u32::MAX)))
        .collect();
    params.iter().map(|p| p.subst(&map)).collect()
}

/// `Some(true)` for a lambda body that gives a value, `Some(false)` for a
/// statement body, `None` when it could be either (`x -> f(x)`).
fn lambda_value_kind(body: &LambdaBody) -> Option<bool> {
    match body {
        LambdaBody::Expr(e) => match **e {
            Expr::Call { .. } | Expr::New { .. } | Expr::Assign { .. } | Expr::Step { .. } => None,
            _ => Some(true),
        },
        LambdaBody::Block(stmts) => Some(returns_value(stmts)),
    }
}

fn returns_value(stmts: &[crate::ast::Stmt]) -> bool {
    use crate::ast::Stmt;
    stmts.iter().any(|s| match s {
        Stmt::Return(Some(_), _) => true,
        Stmt::Block(b, _) => returns_value(b),
        Stmt::If { then, els, .. } => {
            returns_value(std::slice::from_ref(then))
                || els
                    .as_ref()
                    .is_some_and(|e| returns_value(std::slice::from_ref(e)))
        }
        Stmt::While { body, .. }
        | Stmt::DoWhile { body, .. }
        | Stmt::For { body, .. }
        | Stmt::ForEach { body, .. } => returns_value(std::slice::from_ref(body)),
        Stmt::Try { body, catches, .. } => {
            returns_value(body) || catches.iter().any(|c| returns_value(&c.body))
        }
        Stmt::Switch { cases, .. } => cases
            .iter()
            .any(|c| returns_value(std::slice::from_ref(&c.body))),
        _ => false,
    })
}

pub fn sig_text(m: &Method) -> String {
    let params: Vec<String> = m.params.iter().map(|p| p.to_string()).collect();
    format!("{}({})", m.info.name, params.join(", "))
}

fn arg_text(a: &Arg) -> String {
    match a {
        Arg::Typed(t) => t.to_string(),
        Arg::Lambda { arity, .. } => format!("a lambda of {arity}"),
        Arg::Ref => "a method reference".into(),
    }
}
