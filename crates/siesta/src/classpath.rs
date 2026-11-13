//! The classes a program compiles against: jars and directories, read on
//! first use (siesta.md section 9, "Fast to compile"), and summarised as
//! [`ClassInfo`]s with their generic types.

use std::cell::RefCell;
use std::collections::HashMap;
use std::path::PathBuf;
use std::rc::Rc;

use classfile::jar::Jar;
use classfile::{ACC_VARARGS, ClassFile, Member};

use crate::sig::{self, ClassSig, Scope, TypeParam};
use crate::types::Type;

#[derive(Clone, Debug)]
pub struct FieldInfo {
    pub name: String,
    pub access: u16,
    pub ty: Type,
    /// A source constant (JLS 4.12.4): a `final` static field initialised
    /// with a literal of its own type, which reads use in place of the field.
    pub constant: Option<Constant>,
}

/// A literal, and whether a minus sign comes before it.
#[derive(Clone, Debug)]
pub struct Constant {
    pub lit: crate::ast::Lit,
    pub negate: bool,
}

#[derive(Debug)]
pub struct MethodInfo {
    pub name: String,
    pub access: u16,
    pub tparams: Vec<TypeParam>,
    pub params: Vec<Type>,
    pub ret: Type,
    /// The erased descriptor the method is declared with.
    pub desc: String,
}

impl MethodInfo {
    pub fn is_static(&self) -> bool {
        self.access & classfile::ACC_STATIC != 0
    }

    pub fn is_abstract(&self) -> bool {
        self.access & classfile::ACC_ABSTRACT != 0
    }

    pub fn is_varargs(&self) -> bool {
        self.access & ACC_VARARGS != 0
    }
}

#[derive(Clone, Debug)]
pub struct ClassInfo {
    pub name: Rc<str>,
    pub access: u16,
    pub sig: ClassSig,
    pub fields: Vec<FieldInfo>,
    pub methods: Vec<Rc<MethodInfo>>,
}

impl ClassInfo {
    pub fn is_interface(&self) -> bool {
        self.access & classfile::ACC_INTERFACE != 0
    }

    /// The type variables of the class, for reading member signatures.
    pub fn scope(&self) -> Scope {
        self.sig
            .params
            .iter()
            .map(|p| (p.name.clone(), p.bound.erasure().internal().into()))
            .collect()
    }

    /// The class with its own type variables as arguments: `List<E>`.
    pub fn this_type(&self) -> Type {
        let args = self
            .sig
            .params
            .iter()
            .map(|p| Type::Var(p.name.clone(), p.bound.erasure().internal().into()));
        Type::Class(self.name.clone(), args.collect())
    }

    pub fn from_class_file(cf: &ClassFile) -> Result<ClassInfo, String> {
        let super_t = cf.super_class.as_deref().map(Type::class);
        let interfaces: Vec<Type> = cf.interfaces.iter().map(|i| Type::class(i)).collect();
        let sig = cf
            .signature
            .as_deref()
            .and_then(|s| sig::class_sig(s, &Scope::new()))
            .filter(|s| s.interfaces.len() == interfaces.len())
            .unwrap_or(ClassSig {
                params: Vec::new(),
                superclass: super_t,
                interfaces,
            });
        let mut info = ClassInfo {
            name: Rc::from(&*cf.this_class),
            access: cf.access,
            sig,
            fields: Vec::new(),
            methods: Vec::new(),
        };
        let scope = info.scope();
        for f in &cf.fields {
            let ty = member_type(f, &scope)
                .ok_or_else(|| format!("bad field descriptor in {}", cf.this_class))?;
            info.fields.push(FieldInfo {
                name: f.name.to_string(),
                access: f.access,
                ty,
                constant: None,
            });
        }
        for m in &cf.methods {
            info.methods
                .push(Rc::new(method_info(m, &scope).ok_or_else(|| {
                    format!("bad method descriptor in {}", cf.this_class)
                })?));
        }
        Ok(info)
    }
}

fn member_type(f: &Member, scope: &Scope) -> Option<Type> {
    f.signature
        .as_deref()
        .and_then(|s| sig::field_type(s, scope))
        .or_else(|| sig::field_type(&f.descriptor, scope))
}

fn method_info(m: &Member, scope: &Scope) -> Option<MethodInfo> {
    let erased = sig::method_sig(&m.descriptor, scope)?;
    // A signature that disagrees with the descriptor on the parameter count
    // (enum and inner class constructors) is ignored for the descriptor.
    let generic = m
        .signature
        .as_deref()
        .and_then(|s| sig::method_sig(s, scope))
        .filter(|g| g.params.len() == erased.params.len());
    let s = generic.unwrap_or(erased);
    Some(MethodInfo {
        name: m.name.to_string(),
        access: m.access,
        tparams: s.params_t,
        params: s.params,
        ret: s.ret,
        desc: m.descriptor.to_string(),
    })
}

enum Entry {
    Jar(Jar),
    Dir(PathBuf),
}

/// Jars and directories, searched in order, with every class read once.
#[derive(Default)]
pub struct ClassPath {
    entries: Vec<Entry>,
    cache: RefCell<HashMap<String, Option<Rc<ClassInfo>>>>,
}

impl ClassPath {
    pub fn add_jar(&mut self, data: Vec<u8>) -> Result<(), String> {
        self.entries
            .push(Entry::Jar(Jar::open(data).map_err(|e| e.message)?));
        Ok(())
    }

    pub fn add_dir(&mut self, dir: PathBuf) {
        self.entries.push(Entry::Dir(dir));
    }

    /// Opens each entry of a `;`- or `:`-separated path: jars by extension,
    /// directories otherwise.
    pub fn from_paths(paths: &[PathBuf]) -> Result<ClassPath, String> {
        let mut cp = ClassPath::default();
        for p in paths {
            if p.extension().is_some_and(|e| e == "jar") {
                let data = std::fs::read(p).map_err(|e| format!("{}: {e}", p.display()))?;
                cp.add_jar(data)
                    .map_err(|e| format!("{}: {e}", p.display()))?;
            } else {
                cp.add_dir(p.clone());
            }
        }
        Ok(cp)
    }

    fn bytes(&self, name: &str) -> Result<Option<Vec<u8>>, String> {
        let file = format!("{name}.class");
        for e in &self.entries {
            match e {
                Entry::Jar(j) => {
                    if let Some(b) = j.read(&file).map_err(|e| e.message)? {
                        return Ok(Some(b));
                    }
                }
                Entry::Dir(d) => {
                    let p = d.join(&file);
                    if p.is_file() {
                        return std::fs::read(&p)
                            .map(Some)
                            .map_err(|e| format!("{}: {e}", p.display()));
                    }
                }
            }
        }
        Ok(None)
    }

    pub fn exists(&self, name: &str) -> bool {
        if self.cache.borrow().get(name).is_some_and(Option::is_some) {
            return true;
        }
        let file = format!("{name}.class");
        self.entries.iter().any(|e| match e {
            Entry::Jar(j) => j.contains(&file),
            Entry::Dir(d) => d.join(&file).is_file(),
        })
    }

    /// The class named `name` (internal form), or `None` if no entry has it.
    pub fn load(&self, name: &str) -> Result<Option<Rc<ClassInfo>>, String> {
        if let Some(c) = self.cache.borrow().get(name) {
            return Ok(c.clone());
        }
        let info = match self.bytes(name)? {
            None => None,
            Some(b) => {
                let cf = ClassFile::parse(&b).map_err(|e| format!("{name}.class: {e}"))?;
                if &*cf.this_class != name {
                    return Err(format!("{name}.class declares {}", cf.this_class));
                }
                Some(Rc::new(ClassInfo::from_class_file(&cf)?))
            }
        };
        self.cache
            .borrow_mut()
            .insert(name.to_string(), info.clone());
        Ok(info)
    }
}
