//! A compilation: sources and a class path in, class files or every error
//! out (siesta.md section 9).

use std::collections::HashSet;
use std::rc::Rc;

use crate::ast::TypeRef;
use crate::classpath::ClassPath;
use crate::codegen::{self, Unit};
use crate::declare::{self, SourceClass, file_class_name};
use crate::diag::{Diag, Source};
use crate::types::Type;
use crate::world::World;

/// One generated class: its internal name and its bytes.
pub struct ClassOut {
    pub name: String,
    pub bytes: Vec<u8>,
}

pub fn compile(sources: &[Source], classpath: ClassPath) -> Result<Vec<ClassOut>, Vec<Diag>> {
    let mut files = Vec::new();
    let mut errors = Vec::new();
    for (i, s) in sources.iter().enumerate() {
        match crate::parse::parse_file(i as u32, &s.text) {
            Ok(f) => files.push(f),
            Err(d) => errors.push(d),
        }
    }
    if !errors.is_empty() {
        return Err(errors);
    }
    let names: Vec<String> = sources.iter().map(|s| s.name.clone()).collect();
    let mut world = World::new(classpath);
    let files = crate::script_locals::rewrite(files);
    let (mut classes, scopes) =
        declare::declare(&mut world, &files, &names).map_err(|d| vec![d])?;
    let line_starts: Vec<Vec<u32>> = sources.iter().map(|s| line_starts(&s.text)).collect();
    let file_classes: Vec<Rc<str>> = classes
        .iter()
        .filter(|c| {
            let pkg: String = files[c.file]
                .package
                .iter()
                .map(|i| format!("{}/", i.name))
                .collect();
            *c.name == *format!("{pkg}{}", file_class_name(&names[c.file]))
        })
        .map(|c| c.name.clone())
        .collect();
    infer_top_vars(
        &mut world,
        &mut classes,
        &scopes,
        &names,
        &line_starts,
        &file_classes,
    )?;
    let unit = Unit {
        world: &world,
        scopes: &scopes,
        file_names: &names,
        line_starts: &line_starts,
        file_classes: &file_classes,
    };
    let mut out = Vec::new();
    for c in &classes {
        match codegen::compile_class(&unit, c) {
            Ok(classes) => out.extend(
                classes
                    .into_iter()
                    .map(|(name, bytes)| ClassOut { name, bytes }),
            ),
            Err(mut e) => errors.append(&mut e),
        }
    }
    errors.extend(
        world
            .broken
            .borrow()
            .iter()
            .map(|m| Diag::new(Default::default(), m.clone())),
    );
    if errors.is_empty() {
        Ok(out)
    } else {
        Err(errors)
    }
}

/// Gives each top-level `var` its initialiser's type, in source order, so
/// a later one can use an earlier one.
fn infer_top_vars(
    world: &mut World,
    classes: &mut [SourceClass],
    scopes: &[crate::resolve::FileScope],
    names: &[String],
    lines: &[Vec<u32>],
    file_classes: &[Rc<str>],
) -> Result<(), Vec<Diag>> {
    // Typed in dependency order, so a `var` may use one from a file listed
    // after its own. A lambda parameter is no dependency where it hides a
    // top-level variable of its name.
    let mut pending: Vec<Pending> = Vec::new();
    for (ci, c) in classes.iter().enumerate() {
        for (fi, f) in c.fields.iter().enumerate() {
            if let (true, TypeRef::Var(_), Some(init)) = (f.top, &f.decl.ty, &f.decl.init) {
                let mut u = codegen::Usage::default();
                u.expr(init);
                pending.push((ci, fi, f.decl.name.name.clone(), u.free));
            }
        }
    }
    while !pending.is_empty() {
        let blocked = |names: &HashSet<String>| pending.iter().any(|p| names.contains(&p.2));
        let Some(next) = pending.iter().position(|p| !blocked(&p.3)) else {
            let (ci, fi, name, ..) = &pending[in_cycle(&pending)];
            let span = classes[*ci].fields[*fi].decl.name.span;
            let msg = format!("the type of `{name}` depends on itself; write it instead of `var`");
            return Err(vec![Diag::new(span, msg)]);
        };
        let (ci, fi, ..) = pending.remove(next);
        let c = &mut classes[ci];
        let Some(init) = c.fields[fi].decl.init.clone() else {
            continue;
        };
        let t: Type = {
            let unit = Unit {
                world,
                scopes,
                file_names: names,
                line_starts: lines,
                file_classes,
            };
            codegen::top_var_type(&unit, c, &init).map_err(|d| vec![d])?
        };
        c.fields[fi].ty = t.clone();
        let mut info = (*c.info).clone();
        let name = c.fields[fi].decl.name.name.clone();
        if let Some(fd) = info.fields.iter_mut().find(|x| x.name == name) {
            fd.ty = t;
        }
        c.info = Rc::new(info);
        world.sources.insert(c.name.to_string(), c.info.clone());
    }
    Ok(())
}

/// A top-level `var` to type: class and field index, name, and the names
/// its initialiser may use as top-level variables.
type Pending = (usize, usize, String, HashSet<String>);

/// A pending variable on a dependency cycle, found by following what
/// blocks each from the first: every one is blocked, so the walk repeats.
fn in_cycle(pending: &[Pending]) -> usize {
    let mut seen = vec![false; pending.len()];
    let mut at = 0;
    while !seen[at] {
        seen[at] = true;
        let Some(next) = pending.iter().position(|p| pending[at].3.contains(&p.2)) else {
            break;
        };
        at = next;
    }
    at
}

fn line_starts(text: &str) -> Vec<u32> {
    std::iter::once(0)
        .chain(text.match_indices('\n').map(|(i, _)| (i + 1) as u32))
        .collect()
}
