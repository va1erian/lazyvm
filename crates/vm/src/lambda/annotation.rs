//! Generated annotation implementations (docs/design/reflection.md): for an
//! annotation interface `A`, a class `A$$Annotation` extending
//! `lazy.internal.AnnotationBase` whose element methods return the stored
//! values. Equality, hashing and `toString` live in `AnnotationBase` (Java).

use classfile::descriptor::{FieldType, parse_method};
use classfile::writer::{ClassDef, MethodDef, Pool, write};
use classfile::{ACC_FINAL, ACC_PUBLIC, ACC_SUPER, ACC_SYNTHETIC};

use super::asm::Asm;
use crate::class::ClassId;
use crate::{Vm, VmError, VmResult};

const BASE: &str = "lazy/internal/AnnotationBase";
const CTOR: &str = "(Ljava/lang/Class;[Ljava/lang/String;[Ljava/lang/Object;)V";

impl Vm {
    /// The implementation class of annotation interface `annotation`,
    /// generated once and loaded with the annotation's loader.
    pub fn annotation_class(&mut self, annotation: ClassId) -> VmResult<ClassId> {
        let a = self.class(annotation);
        if !a.is_interface() || a.access & classfile::ACC_ANNOTATION == 0 {
            let msg = format!("{} is not an annotation type", a.java_name());
            return Err(VmError::exception(
                "java/lang/IllegalArgumentException",
                msg,
            ));
        }
        // `-` cannot appear in a Java class name, so no user class collides.
        let name = format!("{}-Annotation", a.name);
        if let Some(c) = self.loaded_class(&name) {
            return Ok(c);
        }
        let elements: Vec<(String, String)> = a
            .methods
            .iter()
            .filter(|m| m.is_abstract() && !m.is_static())
            .map(|m| (m.name.to_string(), m.descriptor.to_string()))
            .collect();
        let bytes = class_file(&name, &a.name, &elements)
            .map_err(|e| VmError::Internal(format!("annotation class: {e}")))?;
        let boot = a.from_boot;
        let class = self.define_class(&name, &bytes, boot)?;
        self.initialize(class)?;
        Ok(class)
    }
}

fn class_file(
    name: &str,
    annotation: &str,
    elements: &[(String, String)],
) -> Result<Vec<u8>, String> {
    let mut pool = Pool::default();
    let mut methods = vec![constructor(&mut pool)];
    for (i, (element, desc)) in elements.iter().enumerate() {
        methods.push(element_method(&mut pool, name, i, element, desc)?);
    }
    let class = ClassDef {
        access: ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC,
        name: name.into(),
        super_name: BASE.into(),
        interfaces: vec![annotation.into()],
        fields: Vec::new(),
        methods,
    };
    Ok(write(&class, pool))
}

fn constructor(pool: &mut Pool) -> MethodDef {
    let mut a = Asm {
        code: Vec::new(),
        pool,
    };
    for op in [0x2a, 0x2b, 0x2c, 0x2d] {
        a.op(op);
    }
    let sup = a.pool.method_ref(BASE, "<init>", CTOR);
    a.op_u2(0xb7, sup);
    a.ret(None);
    MethodDef {
        access: ACC_PUBLIC,
        name: "<init>".into(),
        descriptor: CTOR.into(),
        max_stack: 4,
        max_locals: 4,
        code: a.code,
    }
}

/// `R element() { return (R) value(i); }`, unboxing primitive results.
fn element_method(
    pool: &mut Pool,
    class: &str,
    i: usize,
    element: &str,
    desc: &str,
) -> Result<MethodDef, String> {
    let md = parse_method(desc)?;
    let ret = md.ret.ok_or("an annotation element returns void")?;
    let index = u16::try_from(i).map_err(|_| "too many elements")?;
    let mut a = Asm {
        code: Vec::new(),
        pool,
    };
    a.op(0x2a);
    a.op_u2(0x11, index);
    let value = a.pool.method_ref(class, "value", "(I)Ljava/lang/Object;");
    a.op_u2(0xb6, value);
    a.adapt(&FieldType::Object("java/lang/Object".into()), &ret, true)?;
    a.ret(Some(&ret));
    Ok(MethodDef {
        access: ACC_PUBLIC,
        name: element.into(),
        descriptor: desc.into(),
        max_stack: 3,
        max_locals: 1,
        code: a.code,
    })
}
