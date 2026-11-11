//! Every class a compilation can see, from source and from the class path,
//! with the questions the checker asks of them: subtyping, supertypes with
//! their type arguments, member lookup, and functional interfaces.

use std::collections::HashMap;
use std::rc::Rc;

use crate::classpath::{ClassInfo, ClassPath, FieldInfo, MethodInfo};
use crate::types::{OBJECT, Type};

pub struct World {
    pub classpath: ClassPath,
    /// Classes declared in the sources being compiled.
    pub sources: HashMap<String, Rc<ClassInfo>>,
    /// Their names, known before their members are.
    pub declared: std::collections::HashSet<String>,
    /// Errors met while reading class files, reported once.
    pub broken: std::cell::RefCell<Vec<String>>,
}

/// A method found on a type, with the type's arguments substituted.
#[derive(Clone, Debug)]
pub struct Method {
    pub owner: Rc<ClassInfo>,
    pub info: Rc<MethodInfo>,
    pub params: Vec<Type>,
    pub ret: Type,
}

pub struct Field<'a> {
    pub owner: Rc<ClassInfo>,
    pub info: &'a FieldInfo,
    pub ty: Type,
}

impl World {
    pub fn new(classpath: ClassPath) -> World {
        World {
            classpath,
            sources: HashMap::new(),
            declared: Default::default(),
            broken: Default::default(),
        }
    }

    pub fn class(&self, name: &str) -> Option<Rc<ClassInfo>> {
        if let Some(c) = self.sources.get(name) {
            return Some(c.clone());
        }
        match self.classpath.load(name) {
            Ok(c) => c,
            Err(e) => {
                self.broken.borrow_mut().push(e);
                None
            }
        }
    }

    pub fn exists(&self, name: &str) -> bool {
        self.declared.contains(name) || self.classpath.exists(name)
    }

    /// The direct supertypes of `t`, with `t`'s type arguments substituted;
    /// a raw `t` gives raw supertypes (JLS 4.8).
    pub fn supertypes(&self, t: &Type) -> Vec<Type> {
        let Type::Class(name, args) = t else {
            return match t {
                Type::Array(_) => vec![
                    Type::object(),
                    Type::class("java/lang/Cloneable"),
                    Type::class("java/io/Serializable"),
                ],
                Type::Var(_, bound) => vec![Type::class(bound)],
                _ => Vec::new(),
            };
        };
        let Some(c) = self.class(name) else {
            return Vec::new();
        };
        let raw = args.is_empty() || args.len() != c.sig.params.len();
        let map: HashMap<Rc<str>, Type> = if raw {
            HashMap::new()
        } else {
            c.sig
                .params
                .iter()
                .map(|p| p.name.clone())
                .zip(args.iter().cloned())
                .collect()
        };
        let fix = |s: &Type| if raw { s.erasure() } else { s.subst(&map) };
        let mut out: Vec<Type> = c.sig.superclass.iter().map(fix).collect();
        out.extend(c.sig.interfaces.iter().map(fix));
        if out.is_empty() && c.is_interface() {
            out.push(Type::object());
        }
        out
    }

    /// `t` seen as its supertype named `target`, with arguments: `ArrayList<String>`
    /// as `java/util/List` is `List<String>`.
    pub fn as_super(&self, t: &Type, target: &str) -> Option<Type> {
        let mut todo = vec![t.clone()];
        let mut seen = 0;
        while let Some(cur) = todo.pop() {
            if let Type::Class(n, _) = &cur
                && &**n == target
            {
                return Some(cur);
            }
            seen += 1;
            if seen > 4096 {
                return None;
            }
            todo.extend(self.supertypes(&cur));
        }
        None
    }

    /// Whether class `a` is `b` or a subclass or subinterface of it.
    pub fn is_subclass(&self, a: &str, b: &str) -> bool {
        b == OBJECT || self.as_super(&Type::class(a), b).is_some()
    }

    /// The field `name` visible on `t`, searched up the hierarchy.
    pub fn field(&self, t: &Type, name: &str) -> Option<(Rc<ClassInfo>, usize, Type)> {
        let mut todo = vec![t.clone()];
        let mut guard = 0;
        while let Some(cur) = todo.pop() {
            guard += 1;
            if guard > 4096 {
                return None;
            }
            if let Type::Class(n, _) = &cur
                && let Some(c) = self.class(n)
                && let Some(i) = c.fields.iter().position(|f| f.name == name)
            {
                let ty = substitute(&c, &cur, &c.fields[i].ty);
                return Some((c, i, ty));
            }
            let mut sup = self.supertypes(&cur);
            sup.reverse();
            todo.extend(sup);
        }
        None
    }

    /// Every method named `name` on `t` and its supertypes, the most derived
    /// first, without the ones a subtype overrides (same erased descriptor).
    pub fn methods(&self, t: &Type, name: &str) -> Vec<Method> {
        let mut out: Vec<Method> = Vec::new();
        let mut todo = vec![t.clone()];
        let mut visited: Vec<Rc<str>> = Vec::new();
        while !todo.is_empty() && visited.len() < 4096 {
            let cur = todo.remove(0);
            let Type::Class(n, _) = &cur else {
                todo.extend(self.supertypes(&cur));
                continue;
            };
            if visited.contains(n) {
                continue;
            }
            visited.push(n.clone());
            if let Some(c) = self.class(n) {
                for m in c.methods.iter().filter(|m| m.name == name) {
                    if out.iter().any(|o| same_params(&o.info.desc, &m.desc)) {
                        continue;
                    }
                    out.push(Method {
                        owner: c.clone(),
                        info: m.clone(),
                        params: m.params.iter().map(|p| substitute(&c, &cur, p)).collect(),
                        ret: substitute(&c, &cur, &m.ret),
                    });
                }
            }
            todo.extend(self.supertypes(&cur));
        }
        out
    }

    /// The single abstract method of functional interface `t` (JLS 9.8):
    /// abstract, not a public method of `Object`, counting inherited ones.
    pub fn functional_method(&self, t: &Type) -> Option<Method> {
        let Type::Class(n, _) = t else { return None };
        if !self.class(n)?.is_interface() {
            return None;
        }
        let mut found: Option<Method> = None;
        let mut todo = vec![t.clone()];
        let mut seen: Vec<Rc<str>> = Vec::new();
        while let Some(cur) = todo.pop() {
            let Type::Class(cn, _) = &cur else { continue };
            if seen.contains(cn) || seen.len() > 256 {
                continue;
            }
            seen.push(cn.clone());
            let c = self.class(cn)?;
            for m in c
                .methods
                .iter()
                .filter(|m| m.is_abstract() && !m.is_static())
            {
                if is_object_method(m) {
                    continue;
                }
                let params: Vec<Type> = m.params.iter().map(|p| substitute(&c, &cur, p)).collect();
                match &found {
                    Some(f) if f.info.name == m.name && same_params(&f.info.desc, &m.desc) => {}
                    Some(_) => return None,
                    None => {
                        let ret = substitute(&c, &cur, &m.ret);
                        found = Some(Method {
                            owner: c.clone(),
                            info: m.clone(),
                            params,
                            ret,
                        });
                    }
                }
            }
            todo.extend(
                self.supertypes(&cur)
                    .into_iter()
                    .filter(|s| !s.is_class(OBJECT)),
            );
        }
        // A default method in a subinterface may implement the abstract one.
        let f = found?;
        let concrete = self
            .methods(t, &f.info.name)
            .into_iter()
            .any(|m| !m.info.is_abstract() && same_params(&m.info.desc, &f.info.desc));
        (!concrete).then_some(f)
    }
}

/// `member` as seen on `on`, a use of `owner`'s class with arguments.
fn substitute(owner: &ClassInfo, on: &Type, member: &Type) -> Type {
    match on {
        Type::Class(_, args) if !args.is_empty() && args.len() == owner.sig.params.len() => {
            let map: HashMap<Rc<str>, Type> = owner
                .sig
                .params
                .iter()
                .map(|p| p.name.clone())
                .zip(args.iter().cloned())
                .collect();
            member.subst(&map)
        }
        Type::Class(..) if !owner.sig.params.is_empty() => raw_member(owner, member),
        _ => member.clone(),
    }
}

/// A member of a raw type is erased (JLS 4.8), but only the class's own
/// variables: a generic method's variables stay for inference.
fn raw_member(owner: &ClassInfo, member: &Type) -> Type {
    let map: HashMap<Rc<str>, Type> = owner
        .sig
        .params
        .iter()
        .map(|p| (p.name.clone(), p.bound.erasure()))
        .collect();
    member.subst(&map)
}

fn same_params(a: &str, b: &str) -> bool {
    a.split(')').next() == b.split(')').next()
}

fn is_object_method(m: &MethodInfo) -> bool {
    matches!(
        (m.name.as_str(), m.desc.as_str()),
        ("equals", "(Ljava/lang/Object;)Z")
            | ("hashCode", "()I")
            | ("toString", "()Ljava/lang/String;")
    )
}

#[cfg(test)]
#[path = "world_tests.rs"]
mod tests;
