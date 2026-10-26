//! Writing the class file of a lambda class from its [`Spec`].
//!
//! Generated member names contain `-`, which Java source cannot, so they
//! never collide with the functional interface's own methods or fields.

use std::sync::Arc;

use classfile::descriptor::{FieldType, MethodDescriptor, parse_method};
use classfile::writer::{ClassDef, FieldDef, MethodDef, Pool, write};
use classfile::{ACC_FINAL, ACC_PRIVATE, ACC_PUBLIC, ACC_STATIC, ACC_SUPER, ACC_SYNTHETIC};

use super::Spec;
use super::asm::Asm;

pub const FACTORY: &str = "lambda-factory";
/// The one instance of a lambda class that captures nothing.
const INSTANCE: &str = "lambda-instance";
const OBJECT: &str = "java/lang/Object";

/// Method handle kinds (JVMS 4.4.8) a lambda implementation can have.
const REF_INVOKE_VIRTUAL: u8 = 5;
const REF_INVOKE_STATIC: u8 = 6;
const REF_INVOKE_SPECIAL: u8 = 7;
const REF_NEW_INVOKE_SPECIAL: u8 = 8;
const REF_INVOKE_INTERFACE: u8 = 9;

pub fn class_file(spec: &Spec) -> Result<Vec<u8>, String> {
    let mut pool = Pool::default();
    let mut fields: Vec<FieldDef> = spec
        .captured
        .iter()
        .enumerate()
        .map(|(i, t)| FieldDef {
            access: ACC_PRIVATE | ACC_FINAL,
            name: field(i),
            descriptor: t.descriptor(),
        })
        .collect();
    let mut methods = vec![constructor(spec, &mut pool)];
    if spec.captured.is_empty() {
        // A non-capturing lambda evaluates to the same object every time.
        fields.push(FieldDef {
            access: ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
            name: INSTANCE.into(),
            descriptor: own(spec),
        });
        methods.push(singleton_init(spec, &mut pool));
        methods.push(singleton_factory(spec, &mut pool));
    } else {
        methods.push(factory(spec, &mut pool));
    }
    methods.push(forward(spec, &spec.sam_desc, &mut pool)?);
    for bridge in &spec.bridges {
        methods.push(forward(spec, bridge, &mut pool)?);
    }
    let mut interfaces = vec![spec.interface.clone()];
    interfaces.extend(spec.extra_interfaces.iter().cloned());
    let class = ClassDef {
        access: ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC,
        name: spec.class_name.clone(),
        super_name: OBJECT.into(),
        interfaces,
        fields,
        methods,
    };
    Ok(write(&class, pool))
}

fn field(i: usize) -> String {
    format!("capture-{i}")
}

fn own(spec: &Spec) -> String {
    format!("L{};", spec.class_name)
}

fn captured_desc(spec: &Spec) -> String {
    spec.captured.iter().map(FieldType::descriptor).collect()
}

fn slots(types: &[FieldType]) -> u16 {
    types.iter().map(FieldType::slots).sum()
}

/// `<init>(captured...)`: stores each captured value in its field.
fn constructor(spec: &Spec, pool: &mut Pool) -> MethodDef {
    let mut a = Asm {
        code: Vec::new(),
        pool,
    };
    a.op(0x2a);
    let init = a.pool.method_ref(OBJECT, "<init>", "()V");
    a.op_u2(0xb7, init);
    let mut slot = 1;
    for (i, t) in spec.captured.iter().enumerate() {
        a.op(0x2a);
        a.load(t, slot);
        let f = a
            .pool
            .field_ref(&spec.class_name, &field(i), &t.descriptor());
        a.op_u2(0xb5, f);
        slot += t.slots();
    }
    a.ret(None);
    MethodDef {
        access: ACC_PRIVATE,
        name: "<init>".into(),
        descriptor: format!("({})V", captured_desc(spec)),
        max_stack: 3,
        max_locals: 1 + slots(&spec.captured),
        code: a.code,
    }
}

/// `static factory(captured...)`: the call site's entry point.
fn factory(spec: &Spec, pool: &mut Pool) -> MethodDef {
    let mut a = Asm {
        code: Vec::new(),
        pool,
    };
    let this = a.pool.class(&spec.class_name);
    a.op_u2(0xbb, this);
    a.op(0x59);
    let mut slot = 0;
    for t in &spec.captured {
        a.load(t, slot);
        slot += t.slots();
    }
    let init = a.pool.method_ref(
        &spec.class_name,
        "<init>",
        &format!("({})V", captured_desc(spec)),
    );
    a.op_u2(0xb7, init);
    a.op(0xb0);
    MethodDef {
        access: ACC_STATIC,
        name: FACTORY.into(),
        descriptor: format!("({})L{};", captured_desc(spec), spec.interface),
        max_stack: 2 + slots(&spec.captured),
        max_locals: slots(&spec.captured),
        code: a.code,
    }
}

/// `<clinit>`: creates the single instance of a non-capturing lambda.
fn singleton_init(spec: &Spec, pool: &mut Pool) -> MethodDef {
    let mut a = Asm {
        code: Vec::new(),
        pool,
    };
    let this = a.pool.class(&spec.class_name);
    a.op_u2(0xbb, this);
    a.op(0x59);
    let init = a.pool.method_ref(&spec.class_name, "<init>", "()V");
    a.op_u2(0xb7, init);
    let f = a.pool.field_ref(&spec.class_name, INSTANCE, &own(spec));
    a.op_u2(0xb3, f);
    a.ret(None);
    MethodDef {
        access: ACC_STATIC,
        name: "<clinit>".into(),
        descriptor: "()V".into(),
        max_stack: 2,
        max_locals: 0,
        code: a.code,
    }
}

/// The factory of a non-capturing lambda: returns the single instance.
fn singleton_factory(spec: &Spec, pool: &mut Pool) -> MethodDef {
    let mut a = Asm {
        code: Vec::new(),
        pool,
    };
    let f = a.pool.field_ref(&spec.class_name, INSTANCE, &own(spec));
    a.op_u2(0xb2, f);
    a.op(0xb0);
    MethodDef {
        access: ACC_STATIC,
        name: FACTORY.into(),
        descriptor: format!("()L{};", spec.interface),
        max_stack: 1,
        max_locals: 0,
        code: a.code,
    }
}

/// The implementation's parameters as the call passes them: the receiver
/// first for instance methods, then the declared parameters.
pub(super) fn impl_params(spec: &Spec, target: &MethodDescriptor) -> Vec<FieldType> {
    let has_receiver = matches!(
        spec.impl_kind,
        REF_INVOKE_VIRTUAL | REF_INVOKE_SPECIAL | REF_INVOKE_INTERFACE
    );
    let mut params = Vec::with_capacity(target.params.len() + 1);
    if has_receiver {
        params.push(FieldType::Object(Arc::clone(&spec.impl_ref.class)));
    }
    params.extend(target.params.iter().cloned());
    params
}

/// The type the implementation produces: the new object for a constructor.
pub(super) fn impl_result(spec: &Spec, target: &MethodDescriptor) -> Option<FieldType> {
    if spec.impl_kind == REF_NEW_INVOKE_SPECIAL {
        Some(FieldType::Object(Arc::clone(&spec.impl_ref.class)))
    } else {
        target.ret.clone()
    }
}

/// Captured values reach the implementation unconverted (only a reference
/// may be cast), as `LambdaMetafactory` requires; anything else is a
/// linkage error.
fn check_captured(captured: &FieldType, param: &FieldType) -> Result<(), String> {
    if captured == param || (captured.is_reference() && param.is_reference()) {
        Ok(())
    } else {
        Err(format!(
            "captured {} cannot be passed as {}",
            captured.descriptor(),
            param.descriptor()
        ))
    }
}

/// The interface method (or a bridge with descriptor `desc`): loads the
/// captured values and the arguments, adapts them to the implementation
/// method's parameters, calls it, and adapts its result.
fn forward(spec: &Spec, desc: &str, pool: &mut Pool) -> Result<MethodDef, String> {
    let entry = parse_method(desc)?;
    let target = parse_method(&spec.impl_ref.descriptor)?;
    let params = impl_params(spec, &target);
    let (n, m) = (spec.captured.len(), entry.params.len());
    if params.len() != n + m || spec.instantiated.params.len() != m {
        return Err(format!(
            "{} cannot implement {}{desc}",
            spec.impl_ref.name, spec.sam_name
        ));
    }
    let mut a = Asm {
        code: Vec::new(),
        pool,
    };
    if spec.impl_kind == REF_NEW_INVOKE_SPECIAL {
        let c = a.pool.class(&spec.impl_ref.class);
        a.op_u2(0xbb, c);
        a.op(0x59);
    }
    for (i, t) in spec.captured.iter().enumerate() {
        check_captured(t, &params[i])?;
        a.op(0x2a);
        let f = a
            .pool
            .field_ref(&spec.class_name, &field(i), &t.descriptor());
        a.op_u2(0xb4, f);
        a.adapt(t, &params[i], false)?;
    }
    let mut slot = 1;
    for (j, u) in entry.params.iter().enumerate() {
        a.load(u, slot);
        slot += u.slots();
        a.adapt(u, &spec.instantiated.params[j], false)?;
        a.adapt(&spec.instantiated.params[j], &params[n + j], false)?;
    }
    invoke(&mut a, spec, &target)?;
    let produced = impl_result(spec, &target);
    match (&entry.ret, &produced) {
        (None, Some(t)) => a.pop(t),
        (None, None) => {}
        (Some(r), Some(t)) => {
            // A reference result is not cast to the instantiated type (Java
            // 8 does not, so heap pollution passes through); a primitive one
            // is converted through it.
            let middle = spec.instantiated.ret.clone().unwrap_or_else(|| r.clone());
            if middle.is_reference() {
                a.adapt(t, r, true)?;
            } else {
                a.adapt(t, &middle, true)?;
                a.adapt(&middle, r, true)?;
            }
        }
        (Some(_), None) => return Err(format!("{} returns void", spec.impl_ref.name)),
    }
    a.ret(entry.ret.as_ref());
    let arg_slots = slots(&entry.params);
    Ok(MethodDef {
        access: ACC_PUBLIC,
        name: spec.sam_name.clone(),
        descriptor: desc.into(),
        // Receiver or new object (2), every value at two slots, and room
        // for a boxing call's result.
        max_stack: 4 + 2 * (n as u16 + m as u16),
        max_locals: 1 + arg_slots,
        code: a.code,
    })
}

fn invoke(
    a: &mut Asm<'_>,
    spec: &Spec,
    target: &classfile::descriptor::MethodDescriptor,
) -> Result<(), String> {
    let r = &spec.impl_ref;
    let member = |a: &mut Asm<'_>| {
        if spec.impl_on_interface {
            a.pool
                .interface_method_ref(&r.class, &r.name, &r.descriptor)
        } else {
            a.pool.method_ref(&r.class, &r.name, &r.descriptor)
        }
    };
    match spec.impl_kind {
        REF_INVOKE_STATIC => {
            let m = member(a);
            a.op_u2(0xb8, m);
        }
        REF_INVOKE_VIRTUAL => {
            let m = member(a);
            a.op_u2(0xb6, m);
        }
        REF_INVOKE_SPECIAL | REF_NEW_INVOKE_SPECIAL => {
            let m = member(a);
            a.op_u2(0xb7, m);
        }
        REF_INVOKE_INTERFACE => {
            let m = a
                .pool
                .interface_method_ref(&r.class, &r.name, &r.descriptor);
            a.op_u2(0xb9, m);
            let count = 1 + target.params.iter().map(FieldType::slots).sum::<u16>();
            a.code.push(count as u8);
            a.code.push(0);
        }
        k => return Err(format!("method handle kind {k} cannot implement a lambda")),
    }
    Ok(())
}
