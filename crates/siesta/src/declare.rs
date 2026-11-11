//! Declarations: every class of the sources, the class each file's top
//! level becomes (siesta.md section 5), and their members' types, known
//! before any method body is compiled.

use std::collections::HashMap;
use std::rc::Rc;

use classfile::{
    ACC_ABSTRACT, ACC_ENUM, ACC_FINAL, ACC_INTERFACE, ACC_PRIVATE, ACC_PUBLIC, ACC_STATIC,
    ACC_SUPER,
};

use crate::ast::{
    EnumConst, FieldDecl, File, Item, Member, MethodDecl, Stmt, TypeDecl, TypeKind, mods,
};
use crate::classpath::{ClassInfo, FieldInfo, MethodInfo};
use crate::diag::{Diag, Result, Span};
use crate::resolve::{FileScope, TypeCx};
use crate::sig::ClassSig;
use crate::signatures::{enum_members, field_access, method, type_params};
use crate::types::Type;
use crate::world::World;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Class,
    Interface,
    Enum,
    /// A file's top level: static members and `main`.
    File,
}

/// A field, and whether it is a top-level variable, which `main`
/// initialises in source order (siesta.md section 5).
pub struct SourceField {
    pub decl: FieldDecl,
    pub ty: Type,
    pub is_static: bool,
    pub top: bool,
}

pub struct SourceMethod {
    pub decl: MethodDecl,
    pub info: Rc<MethodInfo>,
    /// Type variables of the class and the method.
    pub vars: HashMap<String, Type>,
}

pub struct SourceClass {
    pub name: Rc<str>,
    pub kind: Kind,
    pub file: usize,
    /// This class and the classes around it, outermost first.
    pub enclosing: Vec<Rc<str>>,
    pub vars: HashMap<String, Type>,
    pub fields: Vec<SourceField>,
    pub methods: Vec<SourceMethod>,
    pub constants: Vec<EnumConst>,
    /// A file class's top-level statements, in order.
    pub stmts: Vec<Stmt>,
    pub info: Rc<ClassInfo>,
    pub span: Span,
}

/// The class a file's top level becomes: `hello_world.sie` is `HelloWorld`.
pub fn file_class_name(path: &str) -> String {
    let stem = std::path::Path::new(path)
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("Main");
    stem.split(['_', '-', ' ', '.'])
        .filter(|w| !w.is_empty())
        .map(|w| {
            let mut c = w.chars();
            c.next()
                .map_or(String::new(), |f| f.to_uppercase().chain(c).collect())
        })
        .collect()
}

/// A class to declare: its file, its name with its outer classes', and
/// its declaration (`None` for a file's top level).
type Pending<'a> = (usize, Vec<Rc<str>>, Option<&'a TypeDecl>);

/// Registers every class name, then builds each class's members.
/// Imports are read after the names are registered, so a file can import a
/// class another file of the compilation declares.
pub fn declare(
    world: &mut World,
    files: &[File],
    names: &[String],
) -> Result<(Vec<SourceClass>, Vec<FileScope>)> {
    let pkgs: Vec<String> = files
        .iter()
        .map(|f| f.package.iter().map(|i| format!("{}/", i.name)).collect())
        .collect();
    let mut pending: Vec<Pending> = Vec::new();
    for (fi, f) in files.iter().enumerate() {
        let pkg = &pkgs[fi];
        let file_class: Rc<str> = format!("{pkg}{}", file_class_name(&names[fi])).into();
        let has_top = f.items.iter().any(|i| !matches!(i, Item::Type(_)));
        let mut own = false;
        for item in &f.items {
            if let Item::Type(t) = item {
                own |= *file_class == *format!("{pkg}{}", t.name.name);
                collect(&mut pending, fi, Vec::new(), pkg, t);
            }
        }
        if has_top && !own {
            pending.push((fi, vec![file_class], None));
        }
    }
    for (_, chain, _) in &pending {
        let name = chain[chain.len() - 1].to_string();
        if !world.declared.insert(name.clone()) {
            return Err(Diag::new(
                Span::default(),
                format!("class {} is declared twice", name.replace('/', ".")),
            ));
        }
    }
    let scopes = files
        .iter()
        .map(|f| FileScope::new(f, world))
        .collect::<Result<Vec<_>>>()?;
    let mut out = Vec::new();
    for (fi, chain, decl) in pending {
        let f = &files[fi];
        let is_file_class = decl.is_none_or(|d| {
            chain.len() == 1
                && *chain[0] == *format!("{}{}", scopes[fi].package, file_class_name(&names[fi]))
                && d.kind == TypeKind::Class
        });
        let top: Vec<&Item> = if is_file_class {
            f.items
                .iter()
                .filter(|i| !matches!(i, Item::Type(_)))
                .collect()
        } else {
            Vec::new()
        };
        let c = build(world, &scopes[fi], fi, chain, decl, &top)?;
        world.sources.insert(c.name.to_string(), c.info.clone());
        out.push(c);
    }
    Ok((out, scopes))
}

fn collect<'a>(
    out: &mut Vec<Pending<'a>>,
    fi: usize,
    mut chain: Vec<Rc<str>>,
    pkg: &str,
    t: &'a TypeDecl,
) {
    let name: Rc<str> = match chain.last() {
        Some(outer) => format!("{outer}${}", t.name.name).into(),
        None => format!("{pkg}{}", t.name.name).into(),
    };
    chain.push(name);
    out.push((fi, chain.clone(), Some(t)));
    for m in &t.members {
        if let Member::Type(inner) = m {
            collect(out, fi, chain.clone(), pkg, inner);
        }
    }
}

fn build(
    world: &World,
    scope: &FileScope,
    fi: usize,
    enclosing: Vec<Rc<str>>,
    decl: Option<&TypeDecl>,
    top: &[&Item],
) -> Result<SourceClass> {
    let name = enclosing[enclosing.len() - 1].clone();
    let kind = match decl.map(|d| d.kind) {
        Some(TypeKind::Interface) => Kind::Interface,
        Some(TypeKind::Enum) => Kind::Enum,
        Some(TypeKind::Class) => Kind::Class,
        None => Kind::File,
    };
    let mut vars = HashMap::new();
    let params = match decl {
        Some(d) => type_params(world, scope, &enclosing, &mut vars, &d.tparams)?,
        None => Vec::new(),
    };
    let cx = TypeCx {
        world,
        file: scope,
        enclosing: &enclosing,
        vars: &vars,
    };
    let mut sig = ClassSig {
        params,
        superclass: Some(Type::object()),
        interfaces: Vec::new(),
    };
    let mut access = ACC_PUBLIC | ACC_SUPER;
    if let Some(d) = decl {
        access |= d.mods.bits & (ACC_FINAL | ACC_ABSTRACT);
        let ifaces = if d.kind == TypeKind::Interface {
            &d.extends
        } else {
            &d.implements
        };
        sig.interfaces = ifaces
            .iter()
            .map(|t| cx.resolve(t))
            .collect::<Result<_>>()?;
        match d.kind {
            TypeKind::Interface => access = ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            TypeKind::Enum => {
                access |= ACC_FINAL | ACC_ENUM;
                sig.superclass = Some(Type::Class(
                    "java/lang/Enum".into(),
                    vec![Type::class(&name)],
                ));
            }
            TypeKind::Class => {
                if let Some(e) = d.extends.first() {
                    sig.superclass = Some(cx.resolve(e)?);
                }
            }
        }
    }
    let mut c = SourceClass {
        name: name.clone(),
        kind,
        file: fi,
        enclosing: enclosing.clone(),
        vars: vars.clone(),
        fields: Vec::new(),
        methods: Vec::new(),
        constants: decl.map(|d| d.constants.clone()).unwrap_or_default(),
        stmts: Vec::new(),
        info: Rc::new(ClassInfo {
            name: name.clone(),
            access,
            sig: sig.clone(),
            fields: Vec::new(),
            methods: Vec::new(),
        }),
        span: decl.map_or_else(Span::default, |d| d.span),
    };
    let mut info = ClassInfo {
        name: name.clone(),
        access,
        sig,
        fields: Vec::new(),
        methods: Vec::new(),
    };
    let members: Vec<Member> = decl.map(|d| d.members.clone()).unwrap_or_default();
    let mut items: Vec<Member> = members;
    for item in top {
        match item {
            Item::Method(m) => items.push(Member::Method(m.clone())),
            Item::Field(f) => {
                items.push(Member::Field(f.clone()));
                if let Some(init) = &f.init {
                    let target = Box::new(crate::ast::Expr::Name(f.name.clone()));
                    let value = Box::new(init.clone());
                    c.stmts.push(Stmt::Expr(crate::ast::Expr::Assign {
                        op: None,
                        target,
                        value,
                    }));
                }
            }
            Item::Stmt(s) => c.stmts.push(s.clone()),
            Item::Type(_) => {}
        }
    }
    let file_level = kind == Kind::File || !top.is_empty();
    for m in &items {
        match m {
            Member::Field(f) => {
                let is_top = file_level && top_level_field(top, f);
                let ty = match &f.ty {
                    crate::ast::TypeRef::Var(s) if !is_top => {
                        return Err(Diag::new(
                            *s,
                            "`var` is only for local and top-level variables",
                        ));
                    }
                    crate::ast::TypeRef::Var(_) => Type::Null,
                    t => cx.resolve(t)?,
                };
                let is_static =
                    is_top || f.mods.bits & mods::STATIC != 0 || kind == Kind::Interface;
                let access = field_access(f.mods.bits, is_static, kind, is_top);
                c.fields.push(SourceField {
                    decl: f.clone(),
                    ty: ty.clone(),
                    is_static,
                    top: is_top,
                });
                info.fields.push(FieldInfo {
                    name: f.name.name.clone(),
                    access,
                    ty,
                });
            }
            Member::Method(md) => {
                let is_top = file_level && top_level_method(top, md);
                let sm = method(world, scope, &enclosing, &vars, md, kind, is_top)?;
                info.methods.push(sm.info.clone());
                c.methods.push(sm);
            }
            Member::Type(_) => {}
        }
    }
    if kind == Kind::Enum {
        enum_members(&mut info, &name, &c.constants);
    }
    if !matches!(kind, Kind::Interface | Kind::File)
        && !c.methods.iter().any(|m| m.decl.ret.is_none())
    {
        let access = if kind == Kind::Enum {
            ACC_PRIVATE
        } else {
            ACC_PUBLIC
        };
        let desc = if kind == Kind::Enum {
            "(Ljava/lang/String;I)V"
        } else {
            "()V"
        };
        info.methods.push(Rc::new(MethodInfo {
            name: "<init>".into(),
            access,
            tparams: Vec::new(),
            params: Vec::new(),
            ret: Type::Void,
            desc: desc.into(),
        }));
    }
    if kind == Kind::File || !c.stmts.is_empty() {
        let args = Type::Array(Box::new(Type::string()));
        info.methods.push(Rc::new(MethodInfo {
            name: "main".into(),
            access: ACC_PUBLIC | ACC_STATIC,
            tparams: Vec::new(),
            params: vec![args],
            ret: Type::Void,
            desc: "([Ljava/lang/String;)V".into(),
        }));
    }
    c.info = Rc::new(info);
    Ok(c)
}

fn top_level_field(top: &[&Item], f: &FieldDecl) -> bool {
    top.iter()
        .any(|i| matches!(i, Item::Field(t) if t.name == f.name))
}

fn top_level_method(top: &[&Item], m: &MethodDecl) -> bool {
    top.iter()
        .any(|i| matches!(i, Item::Method(t) if t.name == m.name && t.span == m.span))
}
