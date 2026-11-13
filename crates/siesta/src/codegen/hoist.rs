//! Moves every `await` inside a larger expression into a statement of its
//! own before it, `var $aN = await e;`, so the code generator only meets
//! `await` at statement level, with an empty operand stack
//! (siesta-async.md section 3). The awaits of one statement run first, left
//! to right; one that may not run at all (after `&&`, `||` or `?`, or in a
//! loop's condition) is refused rather than moved.

use crate::ast::{Expr, Ident, Stmt, TemplatePart, TypeRef};
use crate::diag::{Diag, Result, Span};

pub struct Hoist {
    prefix: String,
    next: usize,
}

impl Hoist {
    /// `prefix` keeps the temporaries of nested async bodies apart.
    pub fn new(prefix: &str) -> Hoist {
        Hoist {
            prefix: prefix.to_string(),
            next: 0,
        }
    }

    pub fn stmts(&mut self, stmts: &[Stmt]) -> Result<Vec<Stmt>> {
        let mut out = Vec::new();
        for s in stmts {
            self.stmt(s, &mut out)?;
        }
        Ok(out)
    }

    fn block(&mut self, s: &Stmt) -> Result<Stmt> {
        let mut out = Vec::new();
        self.stmt(s, &mut out)?;
        Ok(if out.len() == 1 {
            out.remove(0)
        } else {
            Stmt::Block(out, Span::default())
        })
    }

    fn stmt(&mut self, s: &Stmt, out: &mut Vec<Stmt>) -> Result<()> {
        let s = match s {
            Stmt::Local {
                ty,
                name,
                init: Expr::Await(e, span),
            } => {
                let e = self.expr(e, out)?;
                Stmt::Local {
                    ty: ty.clone(),
                    name: name.clone(),
                    init: Expr::Await(Box::new(e), *span),
                }
            }
            Stmt::Expr(Expr::Await(e, span)) => {
                Stmt::Expr(Expr::Await(Box::new(self.expr(e, out)?), *span))
            }
            Stmt::Local { ty, name, init } => Stmt::Local {
                ty: ty.clone(),
                name: name.clone(),
                init: self.expr(init, out)?,
            },
            Stmt::Expr(e) => Stmt::Expr(self.expr(e, out)?),
            Stmt::Return(Some(e), span) => Stmt::Return(Some(self.expr(e, out)?), *span),
            Stmt::Throw(e, span) => Stmt::Throw(self.expr(e, out)?, *span),
            Stmt::Block(b, span) => Stmt::Block(self.stmts(b)?, *span),
            Stmt::If { cond, then, els } => Stmt::If {
                cond: self.expr(cond, out)?,
                then: Box::new(self.block(then)?),
                els: match els {
                    Some(e) => Some(Box::new(self.block(e)?)),
                    None => None,
                },
            },
            Stmt::While { cond, body } => {
                no_await(cond, "a loop's condition")?;
                Stmt::While {
                    cond: cond.clone(),
                    body: Box::new(self.block(body)?),
                }
            }
            Stmt::DoWhile { body, cond } => {
                no_await(cond, "a loop's condition")?;
                Stmt::DoWhile {
                    body: Box::new(self.block(body)?),
                    cond: cond.clone(),
                }
            }
            Stmt::For {
                init,
                cond,
                update,
                body,
            } => {
                for e in cond.iter().chain(update) {
                    no_await(e, "a loop's condition or update")?;
                }
                let mut pre = Vec::new();
                for s in init {
                    self.stmt(s, &mut pre)?;
                }
                let body = Box::new(self.block(body)?);
                // Awaits of the initialisers run before the loop, in its scope.
                let f = Stmt::For {
                    init: pre,
                    cond: cond.clone(),
                    update: update.clone(),
                    body,
                };
                out.push(f);
                return Ok(());
            }
            Stmt::ForEach {
                ty,
                name,
                iter,
                body,
            } => Stmt::ForEach {
                ty: ty.clone(),
                name: name.clone(),
                iter: self.expr(iter, out)?,
                body: Box::new(self.block(body)?),
            },
            Stmt::Switch { scrut, cases, span } => {
                let scrut = self.expr(scrut, out)?;
                let mut cs = Vec::new();
                for c in cases {
                    cs.push(crate::ast::Case {
                        labels: c.labels.clone(),
                        body: self.block(&c.body)?,
                    });
                }
                Stmt::Switch {
                    scrut,
                    cases: cs,
                    span: *span,
                }
            }
            Stmt::Try {
                body,
                catches,
                finally,
                span,
            } => {
                for c in catches {
                    no_await_stmts(&c.body, "a `catch` block")?;
                }
                if let Some(f) = finally {
                    no_await_stmts(f, "a `finally` block")?;
                }
                Stmt::Try {
                    body: self.stmts(body)?,
                    catches: catches.clone(),
                    finally: finally.clone(),
                    span: *span,
                }
            }
            other => other.clone(),
        };
        out.push(s);
        Ok(())
    }

    /// `e` with its awaits replaced by temporaries declared into `out`.
    fn expr(&mut self, e: &Expr, out: &mut Vec<Stmt>) -> Result<Expr> {
        let mut x = e.clone();
        self.walk(&mut x, out)?;
        Ok(x)
    }

    fn walk(&mut self, e: &mut Expr, out: &mut Vec<Stmt>) -> Result<()> {
        if let Expr::Await(inner, span) = e {
            self.walk(inner, out)?;
            let name = Ident {
                name: format!("$a{}{}", self.prefix, self.next),
                span: *span,
            };
            self.next += 1;
            let init = Expr::Await(inner.clone(), *span);
            out.push(Stmt::Local {
                ty: TypeRef::Var(*span),
                name: name.clone(),
                init,
            });
            *e = Expr::Name(name);
            return Ok(());
        }
        match e {
            Expr::Binary {
                op: "&&" | "||",
                left,
                right,
            } => {
                self.walk(left, out)?;
                no_await(right, "the right side of `&&` or `||`")
            }
            Expr::Cond { cond, then, els } => {
                self.walk(cond, out)?;
                no_await(then, "a branch of `?:`")?;
                no_await(els, "a branch of `?:`")
            }
            Expr::Lambda { .. } => Ok(()),
            _ => {
                for child in children(e) {
                    self.walk(child, out)?;
                }
                Ok(())
            }
        }
    }
}

fn children(e: &mut Expr) -> Vec<&mut Expr> {
    match e {
        Expr::Template(parts, _) => parts
            .iter_mut()
            .filter_map(|p| {
                if let TemplatePart::Expr(x) = p {
                    Some(x)
                } else {
                    None
                }
            })
            .collect(),
        Expr::Field { target, .. } | Expr::MethodRef { target, .. } => vec![&mut **target],
        Expr::Call { target, args, .. } => target
            .iter_mut()
            .map(|t| &mut **t)
            .chain(args.iter_mut())
            .collect(),
        Expr::New { args, .. } | Expr::ArrayInit(args, _) | Expr::List(args, _) => {
            args.iter_mut().collect()
        }
        Expr::NewArray { dims, init, .. } => {
            dims.iter_mut().chain(init.iter_mut().flatten()).collect()
        }
        Expr::Index { array, index } => vec![&mut **array, &mut **index],
        Expr::Unary { expr, .. }
        | Expr::Cast { expr, .. }
        | Expr::InstanceOf { expr, .. }
        | Expr::Await(expr, _) => vec![&mut **expr],
        Expr::Step { target, .. } => vec![&mut **target],
        Expr::Binary { left, right, .. }
        | Expr::Assign {
            target: left,
            value: right,
            ..
        } => vec![&mut **left, &mut **right],
        Expr::Cond { cond, then, els } => vec![&mut **cond, &mut **then, &mut **els],
        Expr::Cascade { recv, calls } => std::iter::once(&mut **recv)
            .chain(calls.iter_mut().flat_map(|(_, a)| a.iter_mut()))
            .collect(),
        Expr::Map(entries, _) => entries.iter_mut().flat_map(|(k, v)| [k, v]).collect(),
        _ => Vec::new(),
    }
}

fn find_await(e: &Expr) -> Option<Span> {
    let mut x = e.clone();
    let mut stack = vec![&mut x];
    while let Some(cur) = stack.pop() {
        match cur {
            Expr::Await(_, span) => return Some(*span),
            Expr::Lambda { .. } => {}
            other => stack.extend(children(other)),
        }
    }
    None
}

fn no_await(e: &Expr, place: &str) -> Result<()> {
    match find_await(e) {
        Some(span) => Err(Diag::new(
            span,
            format!("`await` cannot be in {place}: await into a variable first"),
        )),
        None => Ok(()),
    }
}

fn no_await_stmts(stmts: &[Stmt], place: &str) -> Result<()> {
    let mut h = Probe(None);
    for s in stmts {
        h.stmt(s);
    }
    match h.0 {
        Some(span) => Err(Diag::new(span, format!("`await` cannot be in {place}"))),
        None => Ok(()),
    }
}

/// Finds an `await` anywhere in statements, outside lambdas.
struct Probe(Option<Span>);

impl Probe {
    fn stmt(&mut self, s: &Stmt) {
        if self.0.is_some() {
            return;
        }
        let exprs: Vec<&Expr> = match s {
            Stmt::Local { init, .. } => vec![init],
            Stmt::Expr(e) | Stmt::Throw(e, _) => vec![e],
            Stmt::Return(e, _) => e.iter().collect(),
            Stmt::If { cond, then, els } => {
                self.stmt(then);
                els.iter().for_each(|e| self.stmt(e));
                vec![cond]
            }
            Stmt::While { cond, body } | Stmt::DoWhile { body, cond } => {
                self.stmt(body);
                vec![cond]
            }
            Stmt::Block(b, _) => {
                b.iter().for_each(|s| self.stmt(s));
                Vec::new()
            }
            Stmt::For {
                init,
                body,
                cond,
                update,
            } => {
                init.iter().for_each(|s| self.stmt(s));
                self.stmt(body);
                cond.iter().chain(update).collect()
            }
            Stmt::ForEach { iter, body, .. } => {
                self.stmt(body);
                vec![iter]
            }
            Stmt::Switch { scrut, cases, .. } => {
                cases.iter().for_each(|c| self.stmt(&c.body));
                vec![scrut]
            }
            Stmt::Try {
                body,
                catches,
                finally,
                ..
            } => {
                body.iter()
                    .chain(catches.iter().flat_map(|c| &c.body))
                    .chain(finally.iter().flatten())
                    .for_each(|s| self.stmt(s));
                Vec::new()
            }
            _ => Vec::new(),
        };
        for e in exprs {
            if let Some(span) = find_await(e) {
                self.0 = Some(span);
                return;
            }
        }
    }
}

/// Whether statements contain an `await` outside lambdas.
pub fn has_await(stmts: &[Stmt]) -> bool {
    let mut p = Probe(None);
    stmts.iter().for_each(|s| p.stmt(s));
    p.0.is_some()
}
