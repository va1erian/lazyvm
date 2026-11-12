//! Which locals a lambda captures and someone assigns: those live in a
//! one-element array shared with the lambda (siesta.md section 8), so the
//! lambda sees and makes changes. Locals cannot shadow each other, so names
//! identify them.

use std::collections::HashSet;

use crate::ast::{Expr, LambdaBody, Stmt, TemplatePart};

#[derive(Default)]
struct Walk {
    assigned: HashSet<String>,
    captured: HashSet<String>,
    /// Locals, loop variables, catch and lambda parameters declared.
    declared: HashSet<String>,
    /// Names after a dot (`Writer.count`), which may be top-level variables.
    members: HashSet<String>,
    /// Lambda nesting depth at the current point.
    depth: usize,
}

/// The names to box in a method body (or a lambda body being compiled).
pub fn boxed_locals(body: &[Stmt]) -> HashSet<String> {
    let mut w = Walk::default();
    for s in body {
        w.stmt(s);
    }
    w.assigned.intersection(&w.captured).cloned().collect()
}

impl Walk {
    fn stmt(&mut self, s: &Stmt) {
        match s {
            Stmt::Local { name, init, .. } => {
                self.declared.insert(name.name.clone());
                self.expr(init);
            }
            Stmt::Expr(e) | Stmt::Throw(e, _) => self.expr(e),
            Stmt::Return(e, _) => e.iter().for_each(|e| self.expr(e)),
            Stmt::Block(b, _) => b.iter().for_each(|s| self.stmt(s)),
            Stmt::If { cond, then, els } => {
                self.expr(cond);
                self.stmt(then);
                els.iter().for_each(|s| self.stmt(s));
            }
            Stmt::While { cond, body } | Stmt::DoWhile { body, cond } => {
                self.expr(cond);
                self.stmt(body);
            }
            Stmt::For {
                init,
                cond,
                update,
                body,
            } => {
                init.iter().for_each(|s| self.stmt(s));
                cond.iter().for_each(|e| self.expr(e));
                update.iter().for_each(|e| self.expr(e));
                self.stmt(body);
            }
            Stmt::ForEach {
                name, iter, body, ..
            } => {
                self.declared.insert(name.name.clone());
                self.expr(iter);
                self.stmt(body);
            }
            Stmt::Switch { scrut, cases, .. } => {
                self.expr(scrut);
                for c in cases {
                    c.labels.iter().for_each(|e| self.expr(e));
                    self.stmt(&c.body);
                }
            }
            Stmt::Try {
                body,
                catches,
                finally,
                ..
            } => {
                body.iter().for_each(|s| self.stmt(s));
                for c in catches {
                    self.declared.insert(c.name.name.clone());
                    c.body.iter().for_each(|s| self.stmt(s));
                }
                finally.iter().flatten().for_each(|s| self.stmt(s));
            }
            Stmt::CtorCall { args, .. } => args.iter().for_each(|e| self.expr(e)),
            Stmt::Break(_) | Stmt::Continue(_) => {}
        }
    }

    fn target(&mut self, e: &Expr) {
        if let Expr::Name(n) = e {
            self.assigned.insert(n.name.clone());
            if self.depth > 0 {
                self.captured.insert(n.name.clone());
            }
        }
        self.expr(e);
    }

    fn expr(&mut self, e: &Expr) {
        match e {
            Expr::Name(n) if self.depth > 0 => {
                self.captured.insert(n.name.clone());
            }
            Expr::Assign { target, value, .. } => {
                self.target(target);
                self.expr(value);
            }
            Expr::Step { target, .. } => self.target(target),
            Expr::Lambda { params, body, .. } => {
                self.declared
                    .extend(params.iter().map(|p| p.name.name.clone()));
                self.depth += 1;
                match body {
                    LambdaBody::Expr(e) => self.expr(e),
                    LambdaBody::Block(b) => b.iter().for_each(|s| self.stmt(s)),
                }
                self.depth -= 1;
            }
            Expr::Template(parts, _) => {
                for p in parts {
                    if let TemplatePart::Expr(e) = p {
                        self.expr(e);
                    }
                }
            }
            Expr::Field { target, name } => {
                self.members.insert(name.name.clone());
                self.expr(target);
            }
            Expr::MethodRef { target, .. } => self.expr(target),
            Expr::Call { target, args, .. } => {
                target.iter().for_each(|t| self.expr(t));
                args.iter().for_each(|a| self.expr(a));
            }
            Expr::New { args, .. } | Expr::ArrayInit(args, _) | Expr::List(args, _) => {
                args.iter().for_each(|a| self.expr(a))
            }
            Expr::NewArray { dims, init, .. } => {
                dims.iter().for_each(|a| self.expr(a));
                init.iter().flatten().for_each(|a| self.expr(a));
            }
            Expr::Index { array, index } => {
                self.expr(array);
                self.expr(index);
            }
            Expr::Unary { expr, .. }
            | Expr::Cast { expr, .. }
            | Expr::InstanceOf { expr, .. }
            | Expr::Await(expr, _) => self.expr(expr),
            Expr::Binary { left, right, .. } => {
                self.expr(left);
                self.expr(right);
            }
            Expr::Cond { cond, then, els } => {
                self.expr(cond);
                self.expr(then);
                self.expr(els);
            }
            Expr::Cascade { recv, calls } => {
                self.expr(recv);
                calls.iter().flat_map(|(_, a)| a).for_each(|a| self.expr(a));
            }
            Expr::Map(entries, _) => {
                for (k, v) in entries {
                    self.expr(k);
                    self.expr(v);
                }
            }
            Expr::Lit(..)
            | Expr::Name(_)
            | Expr::This(_)
            | Expr::ClassLit(..)
            | Expr::TypeExpr(_) => {}
        }
    }
}

/// Every simple name a lambda body mentions, for finding what it captures.
pub fn names_in(body: &LambdaBody) -> HashSet<String> {
    let mut w = Names::default();
    match body {
        LambdaBody::Expr(e) => w.expr(e),
        LambdaBody::Block(b) => b.iter().for_each(|s| w.stmt(s)),
    }
    w.0
}

/// The simple names statements use, and the names they declare.
#[derive(Default)]
pub struct Usage {
    pub used: HashSet<String>,
    pub declared: HashSet<String>,
}

impl Usage {
    pub fn stmts(&mut self, body: &[Stmt]) {
        body.iter().for_each(|s| self.stmt(s));
    }

    pub fn stmt(&mut self, s: &Stmt) {
        self.with(|w| w.stmt(s));
    }

    pub fn expr(&mut self, e: &Expr) {
        self.with(|w| w.expr(e));
    }

    fn with(&mut self, f: impl FnOnce(&mut Walk)) {
        let mut walk = Walk {
            depth: 1,
            ..Walk::default()
        };
        f(&mut walk);
        self.used.extend(walk.captured);
        self.used.extend(walk.members);
        self.declared.extend(walk.declared);
    }
}

#[derive(Default)]
struct Names(HashSet<String>);

impl Names {
    fn stmt(&mut self, s: &Stmt) {
        let mut walk = Walk {
            depth: 1,
            ..Walk::default()
        };
        walk.stmt(s);
        self.0.extend(walk.captured);
    }

    fn expr(&mut self, e: &Expr) {
        let mut walk = Walk {
            depth: 1,
            ..Walk::default()
        };
        walk.expr(e);
        self.0.extend(walk.captured);
    }
}
