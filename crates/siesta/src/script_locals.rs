//! A script's own variables as locals of its `main`. A top-level variable is
//! a static field (siesta.md section 5), so another file or a method can use
//! it; when none does, it becomes a local, which the interpreter and the JIT
//! read without a field access, as javac's locals are.

use std::collections::HashSet;

use crate::ast::{File, Item, Member, Stmt, TypeDecl};
use crate::codegen::Usage;

/// `files` with each eligible top-level variable turned into a local.
pub fn rewrite(mut files: Vec<File>) -> Vec<File> {
    let outside: Vec<HashSet<String>> = (0..files.len())
        .map(|fi| used_outside(&files, fi))
        .collect();
    for (f, outside) in files.iter_mut().zip(outside) {
        if f.items.iter().any(|i| matches!(i, Item::Stmt(_))) {
            let locals = locals(&f.items, outside);
            for item in &mut f.items {
                if let Item::Field(fd) = item
                    && let Some(init) = fd.init.clone().filter(|_| locals.contains(&fd.name.name))
                {
                    let (ty, name) = (fd.ty.clone(), fd.name.clone());
                    *item = Item::Stmt(Stmt::Local { ty, name, init });
                }
            }
        }
    }
    files
}

/// The top-level variables of a script that can be locals: initialised,
/// without modifiers (`public` exposes one to other code, `final` must
/// stay checked), unused outside its statements, declared once, used by no statement
/// before their declaration, and used by no variable that stays a field
/// (which is typed and initialised where `main`'s locals are not in scope).
fn locals(items: &[Item], mut blocked: HashSet<String>) -> HashSet<String> {
    let mut own = Usage::default();
    let mut seen = HashSet::new();
    for item in items {
        match item {
            Item::Stmt(s) => own.stmt(s),
            Item::Field(fd) => {
                if fd.init.is_none()
                    || fd.mods.bits != 0
                    || !fd.mods.annotations.is_empty()
                    || !seen.insert(&fd.name.name)
                {
                    blocked.insert(fd.name.name.clone());
                }
                if blocked.contains(&fd.name.name) || own.used.contains(&fd.name.name) {
                    blocked.insert(fd.name.name.clone());
                }
                fd.init.iter().for_each(|e| own.expr(e));
            }
            _ => {}
        }
    }
    blocked.extend(own.declared);
    loop {
        let before = blocked.len();
        for item in items {
            if let Item::Field(fd) = item
                && blocked.contains(&fd.name.name)
            {
                let mut u = Usage::default();
                fd.init.iter().for_each(|e| u.expr(e));
                blocked.extend(u.used);
            }
        }
        if blocked.len() == before {
            break;
        }
    }
    items
        .iter()
        .filter_map(|i| match i {
            Item::Field(fd) if !blocked.contains(&fd.name.name) => Some(fd.name.name.clone()),
            _ => None,
        })
        .collect()
}

/// The names used anywhere but file `fi`'s top-level statements and
/// variable initialisers: methods, classes, and other files.
fn used_outside(files: &[File], fi: usize) -> HashSet<String> {
    let mut u = Usage::default();
    for (i, f) in files.iter().enumerate() {
        for item in &f.items {
            match item {
                Item::Type(t) => type_names(t, &mut u),
                Item::Method(m) => u.stmts(m.body.as_deref().unwrap_or_default()),
                Item::Field(fd) if i != fi => fd.init.iter().for_each(|e| u.expr(e)),
                Item::Stmt(s) if i != fi => u.stmt(s),
                _ => {}
            }
        }
    }
    u.used
}

fn type_names(t: &TypeDecl, u: &mut Usage) {
    t.constants
        .iter()
        .flat_map(|k| &k.args)
        .for_each(|e| u.expr(e));
    for m in &t.members {
        match m {
            Member::Field(f) => f.init.iter().for_each(|e| u.expr(e)),
            Member::Method(md) => u.stmts(md.body.as_deref().unwrap_or_default()),
            Member::Type(inner) => type_names(inner, u),
        }
    }
}
