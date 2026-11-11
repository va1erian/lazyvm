//! One method body being compiled: its locals, scopes and control-flow
//! context. Statements are in `stmt.rs`, expressions in `expr.rs`.

use std::collections::{HashMap, HashSet};

use classfile::writer::Pool;

use super::Extra;
use super::asm::{Asm, Label, VType};
use crate::ast::{Expr, Stmt, TypeRef};
use crate::declare::SourceClass;
use crate::diag::{Diag, Result, Span};
use crate::resolve::{FileScope, TypeCx};
use crate::types::Type;
use crate::world::World;

#[derive(Clone, Debug)]
pub struct Local {
    pub slot: u16,
    pub ty: Type,
    /// Lives in a one-element array a lambda shares (captures.rs).
    pub boxed: bool,
}

pub struct LoopCx {
    pub brk: Label,
    pub cont: Option<Label>,
    /// How many `finally` blocks were active when the loop began.
    pub finally_depth: usize,
}

/// An active `try` with a `finally`: its block, and the ranges its
/// catch-all handler protects, which skip the inlined copies of the block.
pub struct FinallyCx {
    pub body: Vec<Stmt>,
    pub ranges: Vec<(Label, Label)>,
    pub open: Label,
}

pub struct Body<'a> {
    pub w: &'a World,
    pub cls: &'a SourceClass,
    pub fscope: &'a FileScope,
    pub pool: &'a mut Pool,
    pub extra: &'a mut Extra,
    pub line_starts: &'a [u32],
    pub asm: Asm,
    pub scopes: Vec<Vec<(String, Local)>>,
    pub is_static: bool,
    pub ret: Type,
    pub vars: HashMap<String, Type>,
    pub loops: Vec<LoopCx>,
    pub finallies: Vec<FinallyCx>,
    pub boxed: HashSet<String>,
    /// Typing only: the code is thrown away and no lambda is generated.
    pub dry: bool,
    /// For naming lambdas: `lambda$<name>$<n>`.
    pub name: String,
    /// The type of the first `return` in a lambda body being typed, whose
    /// `ret` is the marker `Infer(u32::MAX)`.
    pub ret_seen: Option<Type>,
    /// Set while compiling an `async` body's resume method.
    pub async_cx: Option<super::async_body::AsyncCx>,
}

impl Body<'_> {
    pub fn this_type(&self) -> Type {
        self.cls.info.this_type()
    }

    pub fn tcx(&self) -> TypeCx<'_> {
        TypeCx {
            world: self.w,
            file: self.fscope,
            enclosing: &self.cls.enclosing,
            vars: &self.vars,
        }
    }

    pub fn resolve_type(&self, t: &TypeRef) -> Result<Type> {
        self.tcx().resolve(t)
    }

    pub fn err<T>(&self, span: Span, msg: impl Into<String>) -> Result<T> {
        Err(Diag::new(span, msg))
    }

    /// The type `e` would have, without keeping any code: the assembler and
    /// constant pool are swapped for scratch ones meanwhile.
    pub fn type_of(&mut self, e: &Expr, want: Option<&Type>) -> Result<Type> {
        let locals: Vec<VType> = (0..self.asm.max_locals).map(|_| VType::Top).collect();
        let mut scratch = Asm::new(locals);
        std::mem::swap(&mut self.asm, &mut scratch);
        let mut pool = Pool::default();
        std::mem::swap(self.pool, &mut pool);
        let dry = std::mem::replace(&mut self.dry, true);
        let r = self.expr(e, want);
        self.dry = dry;
        std::mem::swap(self.pool, &mut pool);
        std::mem::swap(&mut self.asm, &mut scratch);
        r
    }

    // ---- locals ----

    pub fn find_local(&self, name: &str) -> Option<&Local> {
        self.scopes
            .iter()
            .rev()
            .flat_map(|s| s.iter().rev())
            .find(|(n, _)| n == name)
            .map(|(_, l)| l)
    }

    /// Declares a local in the innermost scope; Java forbids shadowing one.
    pub fn declare(&mut self, name: &str, ty: Type, span: Span) -> Result<Local> {
        if self.find_local(name).is_some() {
            return self.err(span, format!("`{name}` is already a local variable here"));
        }
        let boxed = self.boxed.contains(name);
        let vt = if boxed {
            VType::of(&Type::Array(Box::new(ty.clone())))
        } else {
            VType::of(&ty)
        };
        let slot = self.asm.push_local(vt);
        let l = Local { slot, ty, boxed };
        if let Some(s) = self.scopes.last_mut() {
            s.push((name.to_string(), l.clone()));
        }
        Ok(l)
    }

    /// A local initialised by `init`, boxed if a lambda changes it.
    pub fn local_from(
        &mut self,
        name: &str,
        declared: Option<Type>,
        init: &Expr,
        span: Span,
    ) -> Result<Local> {
        if self.boxed.contains(name) {
            let ty = match declared {
                Some(t) => t,
                None => self.var_type(init)?,
            };
            self.int_const(1);
            self.new_array(&ty);
            self.asm.emit(&[0x59], 0, self.asm.top().cloned());
            self.int_const(0);
            let got = self.expr(init, Some(&ty))?;
            self.coerce(&got, &ty, init.span())?;
            self.array_store(&ty);
            let l = self.declare(name, ty.clone(), span)?;
            self.store(l.slot, &Type::Array(Box::new(ty)));
            return Ok(l);
        }
        let got = self.expr(init, declared.as_ref())?;
        let ty = match declared {
            Some(t) => {
                self.coerce(&got, &t, init.span())?;
                t
            }
            None => var_of(&got).ok_or_else(|| {
                Diag::new(
                    init.span(),
                    "`var` cannot take the type of `null` or `void`",
                )
            })?,
        };
        let l = self.declare(name, ty.clone(), span)?;
        self.store(l.slot, &ty);
        Ok(l)
    }

    fn var_type(&mut self, init: &Expr) -> Result<Type> {
        let t = self.type_of(init, None)?;
        var_of(&t).ok_or_else(|| {
            Diag::new(
                init.span(),
                "`var` cannot take the type of `null` or `void`",
            )
        })
    }

    /// Loads a local's value, through its box if it has one.
    pub fn load_local(&mut self, l: &Local) {
        if l.boxed {
            self.load(l.slot, &Type::Array(Box::new(l.ty.clone())));
            self.int_const(0);
            self.array_load(&l.ty);
        } else {
            self.load(l.slot, &l.ty);
        }
    }

    pub fn push_scope(&mut self) -> usize {
        self.scopes.push(Vec::new());
        self.asm.scope()
    }

    pub fn pop_scope(&mut self, mark: usize) {
        self.scopes.pop();
        self.asm.end_scope(mark);
    }

    pub fn line_of(&self, span: Span) -> u16 {
        let line = self.line_starts.partition_point(|&s| s <= span.lo);
        u16::try_from(line).unwrap_or(u16::MAX)
    }

    /// A temporary local for the generator's own use, in the current scope.
    pub fn temp(&mut self, ty: &Type) -> u16 {
        self.asm.push_local(VType::of(ty))
    }
}

/// The type a `var` gets: the initializer's, which must be a real type.
fn var_of(t: &Type) -> Option<Type> {
    match t {
        Type::Null | Type::Void | Type::Infer(_) => None,
        other => Some(other.clone()),
    }
}
