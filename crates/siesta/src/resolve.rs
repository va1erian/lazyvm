//! Names to classes (JLS 6.5, as Siesta needs it): a file's imports, its
//! package, nested classes, and type variables in scope.

use std::collections::HashMap;
use std::rc::Rc;

use crate::ast::{File, Ident, TypeArg, TypeRef};
use crate::diag::{Diag, Result};
use crate::types::Type;
use crate::world::World;

/// What a name means in one file.
#[derive(Clone, Default)]
pub struct FileScope {
    /// `demo/` for `package demo;`, empty for the default package.
    pub package: String,
    singles: HashMap<String, String>,
    on_demand: Vec<String>,
    /// `import static C.m;` as `(member, class)`, and `import static C.*;`.
    pub static_singles: Vec<(String, String)>,
    pub static_on_demand: Vec<String>,
}

impl FileScope {
    pub fn new(file: &File, world: &World) -> Result<FileScope> {
        let package: String = file
            .package
            .iter()
            .map(|i| format!("{}/", i.name))
            .collect();
        let mut s = FileScope {
            package,
            ..FileScope::default()
        };
        for imp in &file.imports {
            let path: Vec<&str> = imp.path.iter().map(|i| i.name.as_str()).collect();
            let span = imp.path.last().map_or_else(Default::default, |i| i.span);
            if imp.is_static {
                let (class_path, member) = if imp.wildcard {
                    (&path[..], None)
                } else {
                    (&path[..path.len() - 1], path.last())
                };
                let class = qualified_class(world, class_path)
                    .ok_or_else(|| Diag::new(span, "no such class"))?;
                match member {
                    Some(m) => s.static_singles.push((m.to_string(), class)),
                    None => s.static_on_demand.push(class),
                }
            } else if imp.wildcard {
                let pkg = path.join("/");
                match qualified_class(world, &path) {
                    Some(class) => s.on_demand.push(format!("{class}$")),
                    None => s.on_demand.push(format!("{pkg}/")),
                }
            } else {
                let class = qualified_class(world, &path)
                    .ok_or_else(|| Diag::new(span, format!("no class {}", path.join("."))))?;
                s.singles.insert(path[path.len() - 1].to_string(), class);
            }
        }
        s.on_demand.push("java/lang/".into());
        Ok(s)
    }

    /// The class a simple name stands for, seen from inside `enclosing`
    /// (its nested classes and its outer classes' come first).
    pub fn class(&self, world: &World, name: &str, enclosing: &[Rc<str>]) -> Option<String> {
        for outer in enclosing.iter().rev() {
            let nested = format!("{outer}${name}");
            if world.exists(&nested) {
                return Some(nested);
            }
        }
        if let Some(c) = self.singles.get(name) {
            return Some(c.clone());
        }
        let local = format!("{}{name}", self.package);
        if world.exists(&local) {
            return Some(local);
        }
        let mut found = self
            .on_demand
            .iter()
            .map(|p| format!("{p}{name}"))
            .filter(|c| world.exists(c));
        found.next()
    }
}

/// `java.util.Map.Entry` as `java/util/Map$Entry`: the longest prefix that
/// is a class, then nested classes.
pub fn qualified_class(world: &World, path: &[&str]) -> Option<String> {
    for split in (1..=path.len()).rev() {
        let class = path[..split].join("/");
        if world.exists(&class) {
            let mut name = class;
            for nested in &path[split..] {
                name = format!("{name}${nested}");
                if !world.exists(&name) {
                    return None;
                }
            }
            return Some(name);
        }
    }
    None
}

/// Everything needed to turn a written type into a [`Type`].
pub struct TypeCx<'a> {
    pub world: &'a World,
    pub file: &'a FileScope,
    /// The classes around the code, innermost last.
    pub enclosing: &'a [Rc<str>],
    pub vars: &'a HashMap<String, Type>,
}

impl TypeCx<'_> {
    pub fn resolve(&self, t: &TypeRef) -> Result<Type> {
        match t {
            TypeRef::Prim(p, _) => Ok(Type::Prim(*p)),
            TypeRef::Void(_) => Ok(Type::Void),
            TypeRef::Var(s) => Err(Diag::new(*s, "`var` is only for local variables")),
            TypeRef::Array(e, _) => Ok(Type::Array(Box::new(self.resolve(e)?))),
            TypeRef::Named { path, args, span } => {
                if path.len() == 1
                    && let Some(v) = self.vars.get(&path[0].name)
                {
                    if !args.is_empty() {
                        return Err(Diag::new(*span, "a type variable takes no type arguments"));
                    }
                    return Ok(v.clone());
                }
                let class = self.class_name(path)?;
                let args = args
                    .iter()
                    .map(|a| self.arg(a))
                    .collect::<Result<Vec<_>>>()?;
                if let Some(info) = self.world.class(&class)
                    && !args.is_empty()
                    && args.len() != info.sig.params.len()
                {
                    return Err(Diag::new(
                        *span,
                        format!(
                            "{} takes {} type arguments",
                            path_text(path),
                            info.sig.params.len()
                        ),
                    ));
                }
                Ok(Type::Class(class.into(), args))
            }
        }
    }

    fn arg(&self, a: &TypeArg) -> Result<Type> {
        match a {
            TypeArg::Type(t) => {
                let t = self.resolve(t)?;
                if t.is_prim() {
                    return Err(Diag::new(
                        Default::default(),
                        "a type argument cannot be primitive; use its wrapper class",
                    ));
                }
                Ok(t)
            }
            TypeArg::Wild { bound: Some(b), .. } => self.resolve(b),
            TypeArg::Wild { bound: None, .. } => Ok(Type::object()),
        }
    }

    pub fn class_name(&self, path: &[Ident]) -> Result<String> {
        let span = path[0].span.to(path[path.len() - 1].span);
        let first = self.file.class(self.world, &path[0].name, self.enclosing);
        let found = match first {
            Some(c) => path[1..].iter().try_fold(c, |c, seg| {
                let n = format!("{c}${}", seg.name);
                self.world.exists(&n).then_some(n)
            }),
            None => {
                let names: Vec<&str> = path.iter().map(|i| i.name.as_str()).collect();
                qualified_class(self.world, &names)
            }
        };
        found.ok_or_else(|| Diag::new(span, format!("no class {}", path_text(path))))
    }
}

pub fn path_text(path: &[Ident]) -> String {
    path.iter()
        .map(|i| i.name.as_str())
        .collect::<Vec<_>>()
        .join(".")
}
