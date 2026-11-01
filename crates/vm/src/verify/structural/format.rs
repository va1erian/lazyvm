//! Format checks that need no other class (JVMS 4.8): access flags (4.1, 4.5,
//! 4.6), names (4.2), the names and descriptors the constant pool holds (4.4)
//! and `ConstantValue` types (4.7.2). A failure is `ClassFormatError`.

use classfile::descriptor::{FieldType, parse_field, parse_method};
use classfile::{
    ACC_ABSTRACT, ACC_ANNOTATION, ACC_BRIDGE, ACC_ENUM, ACC_FINAL, ACC_INTERFACE, ACC_NATIVE,
    ACC_PRIVATE, ACC_PROTECTED, ACC_PUBLIC, ACC_STATIC, ACC_STRICT, ACC_SUPER, ACC_SYNCHRONIZED,
    ACC_SYNTHETIC, ACC_VARARGS, ACC_VOLATILE, ClassFile, Constant, ConstantPool, Member,
};

/// From class file version 51 `<clinit>` is only an initializer if static (JVMS 2.9).
const CLINIT_NEEDS_STATIC: u16 = 51;
/// Class file version 52 gave interfaces private and static methods (JVMS 4.6).
const INTERFACE_BODIES: u16 = 52;

/// Checks `cf` as a whole. `max_code` is the per-method code limit (`Options`).
pub fn check_format(cf: &ClassFile, max_code: usize) -> Result<(), String> {
    check_class_flags(cf)?;
    check_class_names(cf)?;
    check_pool(&cf.pool)?;
    let interface = cf.is_interface();
    for field in &cf.fields {
        check_field(cf, field, interface)
            .map_err(|e| format!("field {} {}: {e}", field.name, field.descriptor))?;
    }
    for method in &cf.methods {
        check_method(cf, method, interface, max_code)
            .map_err(|e| format!("method {}{}: {e}", method.name, method.descriptor))?;
    }
    Ok(())
}

fn check_class_flags(cf: &ClassFile) -> Result<(), String> {
    let a = cf.access;
    let has = |flag| a & flag != 0;
    if has(ACC_INTERFACE) {
        if !has(ACC_ABSTRACT) {
            return Err("interface is not abstract".into());
        }
        if has(ACC_FINAL) || has(ACC_SUPER) || has(ACC_ENUM) {
            return Err("interface is final, super or enum".into());
        }
    } else if has(ACC_ANNOTATION) {
        return Err("annotation type is not an interface".into());
    }
    if has(ACC_FINAL) && has(ACC_ABSTRACT) {
        return Err("class is both final and abstract".into());
    }
    Ok(())
}

fn check_class_names(cf: &ClassFile) -> Result<(), String> {
    if cf.this_class.starts_with('[') {
        return Err(format!("this_class is an array: {}", cf.this_class));
    }
    if cf
        .super_class
        .as_deref()
        .is_some_and(|s| s.starts_with('['))
    {
        return Err("super_class is an array".into());
    }
    if cf.interfaces.iter().any(|i| i.starts_with('[')) {
        return Err("a superinterface is an array".into());
    }
    Ok(())
}

/// Every name and descriptor the pool holds is checked once here, so the rest
/// of the VM and the verifier can parse them without new failure paths.
fn check_pool(pool: &ConstantPool) -> Result<(), String> {
    for i in 0..pool.len() {
        let index = i as u16;
        let check = match pool.get(index) {
            Some(Constant::Class(_)) => pool.class_name(index).and_then(|n| check_class_name(n)),
            Some(Constant::FieldRef { .. }) => check_member_ref(pool, index, false),
            Some(Constant::MethodRef { .. } | Constant::InterfaceMethodRef { .. }) => {
                check_member_ref(pool, index, true)
            }
            Some(Constant::MethodType(d)) => pool
                .utf8(*d)
                .and_then(|d| parse_method(d).and_then(|m| check_types(&m.params, m.ret.as_ref()))),
            Some(Constant::InvokeDynamic { name_and_type, .. }) => {
                pool.name_and_type(*name_and_type).and_then(|(name, desc)| {
                    check_unqualified(name, true)?;
                    parse_method(desc).and_then(|m| check_types(&m.params, m.ret.as_ref()))
                })
            }
            _ => Ok(()),
        };
        check.map_err(|e| format!("constant {i}: {e}"))?;
    }
    Ok(())
}

fn check_member_ref(pool: &ConstantPool, index: u16, method: bool) -> Result<(), String> {
    let r = pool.member_ref(index)?;
    if !method {
        check_unqualified(&r.name, false)?;
        let t = parse_field(&r.descriptor)?;
        return check_types(std::slice::from_ref(&t), None);
    }
    let md = parse_method(&r.descriptor)?;
    check_types(&md.params, md.ret.as_ref())?;
    if &*r.name == "<init>" {
        if md.ret.is_some() {
            return Err(format!("<init> must return void: {}", r.descriptor));
        }
        return Ok(());
    }
    check_unqualified(&r.name, true)
}

/// JVMS 4.2.1: a class name, or an array descriptor where the pool allows one.
fn check_class_name(name: &str) -> Result<(), String> {
    if name.starts_with('[') {
        let t = parse_field(name)?;
        return check_types(std::slice::from_ref(&t), None);
    }
    check_internal(name)
}

/// `/`-separated unqualified names (JVMS 4.2.1).
fn check_internal(name: &str) -> Result<(), String> {
    if name
        .split('/')
        .all(|seg| check_unqualified(seg, false).is_ok())
    {
        Ok(())
    } else {
        Err(format!("bad class name {name:?}"))
    }
}

/// JVMS 4.2.2: non-empty, none of `. ; [ /`, and for methods no `<` or `>`.
fn check_unqualified(name: &str, method: bool) -> Result<(), String> {
    let bad = name.is_empty()
        || name.contains(['.', ';', '[', '/'])
        || (method && name.contains(['<', '>']));
    if bad {
        Err(format!("bad name {name:?}"))
    } else {
        Ok(())
    }
}

fn check_types(params: &[FieldType], ret: Option<&FieldType>) -> Result<(), String> {
    for t in params.iter().chain(ret) {
        let mut t = t;
        while let FieldType::Array(inner) = t {
            t = inner;
        }
        if let FieldType::Object(name) = t {
            check_internal(name)?;
        }
    }
    Ok(())
}

fn at_most_one_visibility(a: u16) -> Result<(), String> {
    let n = [ACC_PUBLIC, ACC_PRIVATE, ACC_PROTECTED]
        .iter()
        .filter(|&&f| a & f != 0)
        .count();
    if n > 1 {
        Err("more than one of public, private and protected".into())
    } else {
        Ok(())
    }
}

fn check_field(cf: &ClassFile, f: &Member, interface: bool) -> Result<(), String> {
    check_unqualified(&f.name, false)?;
    let ty = parse_field(&f.descriptor)?;
    check_types(std::slice::from_ref(&ty), None)?;
    let a = f.access;
    at_most_one_visibility(a)?;
    if a & ACC_FINAL != 0 && a & ACC_VOLATILE != 0 {
        return Err("both final and volatile".into());
    }
    if interface {
        let needed = ACC_PUBLIC | ACC_STATIC | ACC_FINAL;
        let allowed = needed | ACC_SYNTHETIC;
        if a & needed != needed || a & !allowed != 0 {
            return Err("interface field must be public static final".into());
        }
    }
    match f.constant_value {
        // JVMS 4.7.2: an instance field's value attribute is ignored.
        Some(index) if a & ACC_STATIC != 0 => check_constant_value(cf, index, &ty),
        _ => Ok(()),
    }
}

fn check_constant_value(cf: &ClassFile, index: u16, ty: &FieldType) -> Result<(), String> {
    let ok = match (cf.pool.get(index), ty) {
        (Some(Constant::Long(_)), FieldType::Long)
        | (Some(Constant::Float(_)), FieldType::Float)
        | (Some(Constant::Double(_)), FieldType::Double) => true,
        (
            Some(Constant::Integer(_)),
            FieldType::Int
            | FieldType::Short
            | FieldType::Char
            | FieldType::Byte
            | FieldType::Boolean,
        ) => true,
        (Some(Constant::String(_)), FieldType::Object(n)) => &**n == "java/lang/String",
        _ => false,
    };
    if ok {
        Ok(())
    } else {
        Err(format!("ConstantValue {index} does not fit the field type"))
    }
}

fn check_method(
    cf: &ClassFile,
    m: &Member,
    interface: bool,
    max_code: usize,
) -> Result<(), String> {
    let md = parse_method(&m.descriptor)?;
    check_types(&md.params, md.ret.as_ref())?;
    let this = u16::from(!is_static_method(cf, m));
    if md.arg_slots() + this > 255 {
        return Err("more than 255 argument slots".into());
    }
    if let Some(code) = &m.code
        && md.arg_slots() + this > code.max_locals
    {
        return Err("arguments do not fit max_locals".into());
    }
    // JVMS 4.7.5: what the attribute lists are classes.
    for &e in &m.exceptions {
        cf.pool.class_name(e)?;
    }
    match &*m.name {
        "<clinit>" => {}
        "<init>" => check_init(m, md.ret.is_none())?,
        name => {
            check_unqualified(name, true)?;
            check_method_flags(cf, m.access, interface)?;
        }
    }
    if let Some(code) = &m.code
        && code.bytecode.len() > max_code
    {
        return Err(format!(
            "code of {} bytes passes the limit of {max_code} (-Xmaxcode)",
            code.bytecode.len()
        ));
    }
    Ok(())
}

/// JVMS 2.9: before version 51 a `<clinit>()V` is static whatever its flags.
/// From 51 one without `ACC_STATIC` is an ordinary method that never runs, like
/// a `<clinit>` with other arguments (observed: both load).
pub fn is_static_method(cf: &ClassFile, m: &Member) -> bool {
    m.access & ACC_STATIC != 0
        || (cf.major < CLINIT_NEEDS_STATIC && &*m.name == "<clinit>" && &*m.descriptor == "()V")
}

/// JVMS 4.6: an `<init>` may only carry a visibility, varargs, strict or synthetic.
fn check_init(m: &Member, void: bool) -> Result<(), String> {
    if !void {
        return Err("<init> must return void".into());
    }
    at_most_one_visibility(m.access)?;
    let allowed =
        ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED | ACC_VARARGS | ACC_STRICT | ACC_SYNTHETIC;
    if m.access & !allowed != 0 {
        return Err(format!("<init> has illegal flags {:#06x}", m.access));
    }
    Ok(())
}

fn check_method_flags(cf: &ClassFile, a: u16, interface: bool) -> Result<(), String> {
    at_most_one_visibility(a)?;
    if a & ACC_ABSTRACT != 0 {
        let excluded =
            ACC_PRIVATE | ACC_STATIC | ACC_FINAL | ACC_SYNCHRONIZED | ACC_NATIVE | ACC_STRICT;
        if a & excluded != 0 {
            return Err(format!("abstract method has illegal flags {a:#06x}"));
        }
    }
    if !interface {
        return Ok(());
    }
    if cf.major >= INTERFACE_BODIES {
        if a & (ACC_PUBLIC | ACC_PRIVATE) == 0 {
            return Err("interface method must be public or private".into());
        }
        let illegal = ACC_PROTECTED | ACC_FINAL | ACC_SYNCHRONIZED | ACC_NATIVE;
        if a & illegal != 0 {
            return Err(format!("interface method has illegal flags {a:#06x}"));
        }
    } else {
        let needed = ACC_PUBLIC | ACC_ABSTRACT;
        let allowed = needed | ACC_BRIDGE | ACC_VARARGS | ACC_SYNTHETIC;
        if a & needed != needed || a & !allowed != 0 {
            return Err("interface method must be public abstract".into());
        }
    }
    Ok(())
}
