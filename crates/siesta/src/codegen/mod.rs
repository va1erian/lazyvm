//! Code generation: each source class to a version 52 class file, with
//! checking done as the code is emitted (siesta.md section 9: one walk per
//! method, no separate typed tree).

mod arith;
mod array;
pub mod asm;
mod body;
mod call;
mod captures;
mod cond;
mod expr;
mod infer;
mod lambda;
mod literal;
mod loops;
mod members;
mod names;
mod ops;
mod stmt;
mod synth;
mod trys;

use std::rc::Rc;

use classfile::writer::{Attribute, ClassDef, FieldDef, MethodDef, Pool};

use crate::declare::SourceClass;
use crate::diag::Diag;
use crate::resolve::FileScope;
use crate::sig;
use crate::types::Type;
use crate::world::World;

pub use body::Body;

/// What a class's methods add to it besides themselves.
#[derive(Default)]
pub struct Extra {
    pub methods: Vec<MethodDef>,
    /// `(bootstrap method handle, arguments)` for `BootstrapMethods`.
    pub bootstraps: Vec<(u16, Vec<u16>)>,
    pub lambda_count: u32,
    /// The classes whose top-level members unqualified names reach.
    pub file_classes: Vec<Rc<str>>,
}

/// Shared by every class of a compilation.
pub struct Unit<'a> {
    pub world: &'a World,
    pub scopes: &'a [FileScope],
    pub file_names: &'a [String],
    pub line_starts: &'a [Vec<u32>],
    pub file_classes: &'a [Rc<str>],
}

/// Compiles one class, or returns every error its methods have.
pub fn compile_class(u: &Unit, c: &SourceClass) -> Result<Vec<u8>, Vec<Diag>> {
    let mut pool = Pool::default();
    let mut extra = Extra {
        file_classes: u.file_classes.to_vec(),
        ..Extra::default()
    };
    let mut errors = Vec::new();
    let mut methods = Vec::new();
    let mut cg = members::ClassGen {
        u,
        c,
        pool: &mut pool,
        extra: &mut extra,
    };
    cg.methods(&mut methods, &mut errors);
    if !errors.is_empty() {
        return Err(errors);
    }
    let fields = class_fields(c);
    let mut attributes: Vec<Attribute> = Vec::new();
    let src = std::path::Path::new(&u.file_names[c.file])
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("")
        .to_string();
    let sf = pool.utf8(&src);
    attributes.push(("SourceFile".into(), sf.to_be_bytes().to_vec()));
    if let Some(s) = class_signature(c) {
        let i = pool.utf8(&s);
        attributes.push(("Signature".into(), i.to_be_bytes().to_vec()));
    }
    methods.append(&mut extra.methods);
    if !extra.bootstraps.is_empty() {
        let mut b = (extra.bootstraps.len() as u16).to_be_bytes().to_vec();
        for (h, args) in &extra.bootstraps {
            b.extend_from_slice(&h.to_be_bytes());
            b.extend_from_slice(&(args.len() as u16).to_be_bytes());
            for a in args {
                b.extend_from_slice(&a.to_be_bytes());
            }
        }
        attributes.push(("BootstrapMethods".into(), b));
    }
    if pool.len() > 65000 {
        return Err(vec![Diag::new(
            c.span,
            "this class has too many constants for a class file",
        )]);
    }
    let info = &c.info;
    let def = ClassDef {
        access: info.access,
        name: c.name.to_string(),
        super_name: info.sig.superclass.as_ref().map_or_else(
            || crate::types::OBJECT.to_string(),
            |s| s.erasure().internal(),
        ),
        interfaces: info
            .sig
            .interfaces
            .iter()
            .map(|i| i.erasure().internal())
            .collect(),
        fields: fields
            .into_iter()
            .map(|(access, name, ty)| field_def(&mut pool, access, name, &ty))
            .collect(),
        methods,
        attributes,
    };
    Ok(classfile::writer::write(&def, pool))
}

fn class_fields(c: &SourceClass) -> Vec<(u16, String, Type)> {
    let mut out: Vec<(u16, String, Type)> = c
        .info
        .fields
        .iter()
        .map(|f| (f.access, f.name.clone(), f.ty.clone()))
        .collect();
    if c.kind == crate::declare::Kind::Enum {
        let arr = Type::Array(Box::new(Type::class(&c.name)));
        out.push((
            classfile::ACC_PRIVATE
                | classfile::ACC_STATIC
                | classfile::ACC_FINAL
                | classfile::ACC_SYNTHETIC,
            "$VALUES".into(),
            arr,
        ));
    }
    out
}

fn field_def(pool: &mut Pool, access: u16, name: String, ty: &Type) -> FieldDef {
    let mut attributes = Vec::new();
    if is_generic(ty) {
        let i = pool.utf8(&sig::write_type(ty));
        attributes.push(("Signature".into(), i.to_be_bytes().to_vec()));
    }
    FieldDef {
        access,
        name,
        descriptor: ty.descriptor(),
        attributes,
    }
}

pub fn is_generic(t: &Type) -> bool {
    match t {
        Type::Var(..) => true,
        Type::Class(_, args) => !args.is_empty(),
        Type::Array(e) => is_generic(e),
        _ => false,
    }
}

fn class_signature(c: &SourceClass) -> Option<String> {
    let s = &c.info.sig;
    let generic = !s.params.is_empty() || s.superclass.iter().chain(&s.interfaces).any(is_generic);
    if !generic {
        return None;
    }
    let sup = s.superclass.clone().unwrap_or_else(Type::object);
    let ifaces: String = s.interfaces.iter().map(sig::write_type).collect();
    Some(format!(
        "{}{}{ifaces}",
        sig::write_params(&s.params),
        sig::write_type(&sup)
    ))
}

/// A method from its assembled code, or `None` when it outgrew a class
/// file (code over 64 KiB, a jump too far).
pub fn method_def(
    mut asm: asm::Asm,
    pool: &mut Pool,
    access: u16,
    name: &str,
    desc: &str,
) -> Option<MethodDef> {
    if !asm.finish() {
        return None;
    }
    let mut handlers = Vec::new();
    for &(s, e, h, t) in &asm.handlers {
        let (Some(s), Some(e), Some(h)) = (asm.label_pos(s), asm.label_pos(e), asm.label_pos(h))
        else {
            continue;
        };
        if s < e {
            handlers.push((s, e, h, t));
        }
    }
    let mut code_attributes = Vec::new();
    if let Some(map) = asm.stack_map(pool) {
        code_attributes.push(("StackMapTable".to_string(), map));
    }
    if !asm.lines.is_empty() {
        let mut b = (asm.lines.len() as u16).to_be_bytes().to_vec();
        for (pc, line) in &asm.lines {
            b.extend_from_slice(&pc.to_be_bytes());
            b.extend_from_slice(&line.to_be_bytes());
        }
        code_attributes.push(("LineNumberTable".to_string(), b));
    }
    Some(MethodDef {
        access,
        name: name.to_string(),
        descriptor: desc.to_string(),
        max_stack: asm.max_stack,
        max_locals: asm.max_locals,
        code: Some(asm.code),
        handlers,
        code_attributes,
        attributes: Vec::new(),
    })
}

/// The type a top-level `var` takes from its initialiser.
pub fn top_var_type(u: &Unit, c: &SourceClass, init: &crate::ast::Expr) -> Result<Type, Diag> {
    let mut pool = Pool::default();
    let mut extra = Extra {
        file_classes: u.file_classes.to_vec(),
        ..Extra::default()
    };
    members::ClassGen {
        u,
        c,
        pool: &mut pool,
        extra: &mut extra,
    }
    .top_var_type(init)
}
