//! The signatures of source members: access defaults (siesta.md section 5),
//! type parameters, methods' types and descriptors, and an enum's implicit
//! members.

use std::collections::HashMap;
use std::rc::Rc;

use classfile::{
    ACC_ABSTRACT, ACC_ENUM, ACC_FINAL, ACC_PRIVATE, ACC_PROTECTED, ACC_PUBLIC, ACC_STATIC,
    ACC_VARARGS,
};

use crate::ast::{EnumConst, MethodDecl, mods};
use crate::classpath::{ClassInfo, FieldInfo, MethodInfo};
use crate::declare::{Kind, SourceMethod};
use crate::diag::{Diag, Result};
use crate::resolve::{FileScope, TypeCx};
use crate::sig::TypeParam;
use crate::types::Type;
use crate::world::World;

/// Fields are private unless marked; top-level variables are visible to
/// the file's other classes (package access); interface fields are
/// constants.
pub fn field_access(bits: u16, is_static: bool, kind: Kind, top: bool) -> u16 {
    if kind == Kind::Interface {
        return ACC_PUBLIC | ACC_STATIC | ACC_FINAL;
    }
    let vis = bits & (ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED);
    let vis = if vis != 0 || top { vis } else { ACC_PRIVATE };
    vis | (bits & ACC_FINAL) | if is_static { ACC_STATIC } else { 0 }
}

pub fn type_params(
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

pub fn method(
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
pub fn enum_members(info: &mut ClassInfo, name: &str, constants: &[EnumConst]) {
    let this = Type::class(name);
    let arr = Type::Array(Box::new(this.clone()));
    for k in constants {
        let access = ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM;
        info.fields.push(FieldInfo {
            name: k.name.name.clone(),
            access,
            ty: this.clone(),
            constant: None,
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
