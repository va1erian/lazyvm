//! Declarations: every class of the sources, the class each file's top
//! level becomes (siesta.md section 5), and their members' types, known
//! before any method body is compiled.

use std::collections::HashMap;
use std::rc::Rc;

use classfile::{
    ACC_ABSTRACT, ACC_ENUM, ACC_FINAL, ACC_INTERFACE, ACC_PRIVATE, ACC_PROTECTED, ACC_PUBLIC,
    ACC_STATIC, ACC_SUPER, ACC_VARARGS,
};

use crate::ast::{
    EnumConst, FieldDecl, File, Item, Member, MethodDecl, Stmt, TypeDecl, TypeKind, mods,
};
use crate::classpath::{ClassInfo, FieldInfo, MethodInfo};
use crate::diag::{Diag, Result, Span};
use crate::resolve::{FileScope, TypeCx};
use crate::sig::{ClassSig, TypeParam};
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
    pub fields: Vec<(FieldDecl, Type, bool)>,
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
pub fn declare(
    world: &mut World,
    files: &[File],
    names: &[String],
    scopes: &[FileScope],
) -> Result<Vec<SourceClass>> {
    let mut pending: Vec<Pending> = Vec::new();
    for (fi, f) in files.iter().enumerate() {
        let pkg = &scopes[fi].package;
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
    Ok(out)
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
            Item::Field(f) => items.push(Member::Field(f.clone())),
            Item::Stmt(s) => c.stmts.push(s.clone()),
            Item::Type(_) => {}
        }
    }
    let file_level = kind == Kind::File || !top.is_empty();
    for m in &items {
        match m {
            Member::Field(f) => {
                let ty = match &f.ty {
                    crate::ast::TypeRef::Var(_) => Type::Null,
                    t => cx.resolve(t)?,
                };
                let is_static = file_level && top_level_field(top, f)
                    || f.mods.bits & mods::STATIC != 0
                    || kind == Kind::Interface;
                let access = field_access(
                    f.mods.bits,
                    is_static,
                    kind,
                    file_level && top_level_field(top, f),
                );
                c.fields.push((f.clone(), ty.clone(), is_static));
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

/// Fields are private unless marked; top-level variables are visible to
/// the file's other classes (package access); interface fields are
/// constants.
fn field_access(bits: u16, is_static: bool, kind: Kind, top: bool) -> u16 {
    if kind == Kind::Interface {
        return ACC_PUBLIC | ACC_STATIC | ACC_FINAL;
    }
    let vis = bits & (ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED);
    let vis = if vis != 0 || top { vis } else { ACC_PRIVATE };
    vis | (bits & ACC_FINAL) | if is_static { ACC_STATIC } else { 0 }
}

fn type_params(
    world: &World,
    scope: &FileScope,
    enclosing: &[Rc<str>],
    vars: &mut HashMap<String, Type>,
    tps: &[crate::ast::TypeParam],
) -> Result<Vec<TypeParam>> {
    for tp in tps {
        vars.insert(
            tp.name.name.clone(),
            Type::Var(tp.name.name.as_str().into(), crate::types::OBJECT.into()),
        );
    }
    let mut out = Vec::new();
    for tp in tps {
        let cx = TypeCx {
            world,
            file: scope,
            enclosing,
            vars,
        };
        let bound = match tp.bounds.first() {
            Some(b) => cx.resolve(b)?,
            None => Type::object(),
        };
        let erased: Rc<str> = bound.erasure().internal().into();
        vars.insert(
            tp.name.name.clone(),
            Type::Var(tp.name.name.as_str().into(), erased),
        );
        out.push(TypeParam {
            name: tp.name.name.as_str().into(),
            bound,
        });
    }
    Ok(out)
}

fn method(
    world: &World,
    scope: &FileScope,
    enclosing: &[Rc<str>],
    class_vars: &HashMap<String, Type>,
    md: &MethodDecl,
    kind: Kind,
    is_top: bool,
) -> Result<SourceMethod> {
    let mut vars = class_vars.clone();
    let tparams = type_params(world, scope, enclosing, &mut vars, &md.tparams)?;
    let cx = TypeCx {
        world,
        file: scope,
        enclosing,
        vars: &vars,
    };
    let mut params = Vec::new();
    for p in &md.params {
        let t = cx.resolve(&p.ty)?;
        params.push(if p.varargs {
            Type::Array(Box::new(t))
        } else {
            t
        });
    }
    let is_ctor = md.ret.is_none();
    let ret = match &md.ret {
        Some(t) => cx.resolve(t)?,
        None => Type::Void,
    };
    let bits = md.mods.bits;
    let mut access = bits
        & (ACC_PUBLIC
            | ACC_PRIVATE
            | ACC_PROTECTED
            | ACC_STATIC
            | ACC_FINAL
            | ACC_ABSTRACT
            | classfile::ACC_SYNCHRONIZED);
    if access & (ACC_PRIVATE | ACC_PROTECTED) == 0 {
        access |= ACC_PUBLIC;
    }
    if is_top || kind == Kind::File {
        access |= ACC_STATIC;
    }
    if kind == Kind::Interface && md.body.is_none() && bits & mods::STATIC == 0 {
        access |= ACC_ABSTRACT;
    }
    if md.body.is_none() && access & ACC_ABSTRACT == 0 {
        return Err(Diag::new(
            md.name.span,
            "a method without a body must be `abstract` or in an interface",
        ));
    }
    if md.params.last().is_some_and(|p| p.varargs) {
        access |= ACC_VARARGS;
    }
    let mut full_params = params.clone();
    if is_ctor && kind == Kind::Enum {
        full_params.splice(0..0, [Type::string(), Type::Prim(crate::ast::Prim::Int)]);
        access = (access & !(ACC_PUBLIC | ACC_PROTECTED)) | ACC_PRIVATE;
    }
    let desc = format!(
        "({}){}",
        full_params.iter().map(Type::descriptor).collect::<String>(),
        ret.descriptor()
    );
    let name = if is_ctor {
        "<init>".to_string()
    } else {
        md.name.name.clone()
    };
    let info = Rc::new(MethodInfo {
        name,
        access,
        tparams,
        params,
        ret,
        desc,
    });
    Ok(SourceMethod {
        decl: md.clone(),
        info,
        vars,
    })
}

/// The constants of an enum, `values()` and `valueOf(String)` (JLS 8.9.3).
fn enum_members(info: &mut ClassInfo, name: &str, constants: &[EnumConst]) {
    let this = Type::class(name);
    let arr = Type::Array(Box::new(this.clone()));
    for k in constants {
        let access = ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM;
        info.fields.push(FieldInfo {
            name: k.name.name.clone(),
            access,
            ty: this.clone(),
        });
    }
    info.methods.push(Rc::new(MethodInfo {
        name: "values".into(),
        access: ACC_PUBLIC | ACC_STATIC,
        tparams: Vec::new(),
        params: Vec::new(),
        ret: arr.clone(),
        desc: format!("(){}", arr.descriptor()),
    }));
    info.methods.push(Rc::new(MethodInfo {
        name: "valueOf".into(),
        access: ACC_PUBLIC | ACC_STATIC,
        tparams: Vec::new(),
        params: vec![Type::string()],
        ret: this.clone(),
        desc: format!("(Ljava/lang/String;){}", this.descriptor()),
    }));
}
