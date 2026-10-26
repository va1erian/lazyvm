//! Writing the class file of a lambda class from its [`Spec`].

use std::sync::Arc;

use classfile::descriptor::{FieldType, parse_method};
use classfile::writer::{ClassDef, FieldDef, MethodDef, Pool, write};
use classfile::{ACC_FINAL, ACC_PRIVATE, ACC_PUBLIC, ACC_STATIC, ACC_SUPER, ACC_SYNTHETIC};

use super::Spec;
use super::asm::Asm;

pub const FACTORY: &str = "get$Lambda";
const OBJECT: &str = "java/lang/Object";

/// Method handle kinds (JVMS 4.4.8) a lambda implementation can have.
const REF_INVOKE_VIRTUAL: u8 = 5;
const REF_INVOKE_STATIC: u8 = 6;
const REF_INVOKE_SPECIAL: u8 = 7;
const REF_NEW_INVOKE_SPECIAL: u8 = 8;
const REF_INVOKE_INTERFACE: u8 = 9;

pub fn class_file(spec: &Spec) -> Result<Vec<u8>, String> {
    let mut pool = Pool::default();
    let fields: Vec<FieldDef> = spec
        .captured
        .iter()
        .enumerate()
        .map(|(i, t)| FieldDef {
            access: ACC_PRIVATE | ACC_FINAL,
            name: field(i),
            descriptor: t.descriptor(),
        })
        .collect();
    let mut methods = vec![constructor(spec, &mut pool), factory(spec, &mut pool)];
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
    format!("cap${i}")
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

/// `static get$Lambda(captured...)`: the call site's entry point.
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

/// The interface method (or a bridge with descriptor `desc`): loads the
/// captured values and the arguments, adapts them to the implementation
/// method's parameters, calls it, and adapts its result.
fn forward(spec: &Spec, desc: &str, pool: &mut Pool) -> Result<MethodDef, String> {
    let entry = parse_method(desc)?;
    let target = parse_method(&spec.impl_ref.descriptor)?;
    let owner = FieldType::Object(Arc::clone(&spec.impl_ref.class));
    let has_receiver = matches!(
        spec.impl_kind,
        REF_INVOKE_VIRTUAL | REF_INVOKE_SPECIAL | REF_INVOKE_INTERFACE
    );
    let mut params: Vec<FieldType> = Vec::new();
    if has_receiver {
        params.push(owner.clone());
    }
    params.extend(target.params.iter().cloned());
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
        a.op(0x2a);
        let f = a
            .pool
            .field_ref(&spec.class_name, &field(i), &t.descriptor());
        a.op_u2(0xb4, f);
    }
    let mut slot = 1;
    for (j, u) in entry.params.iter().enumerate() {
        a.load(u, slot);
        slot += u.slots();
        a.adapt(u, &spec.instantiated.params[j])?;
        a.adapt(&spec.instantiated.params[j], &params[n + j])?;
    }
    invoke(&mut a, spec, &target)?;
    let produced = if spec.impl_kind == REF_NEW_INVOKE_SPECIAL {
        Some(owner)
    } else {
        target.ret.clone()
    };
    match (&entry.ret, &produced) {
        (None, Some(t)) => a.pop(t),
        (None, None) => {}
        (Some(r), Some(t)) => {
            let middle = spec.instantiated.ret.clone().unwrap_or_else(|| r.clone());
            a.adapt(t, &middle)?;
            a.adapt(&middle, r)?;
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
