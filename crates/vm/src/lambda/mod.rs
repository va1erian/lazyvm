//! `invokedynamic` for lambdas and method references (docs/design/lambdas.md).
//!
//! The VM links `LambdaMetafactory.metafactory` and `altMetafactory` call
//! sites itself, following the linkage rules in their Javadoc: it writes a
//! class file for a final class implementing the functional interface, with
//! one field per captured value, and defines it like any other class. The
//! call site then calls that class's static factory with the captured values.

mod asm;
mod spin;

use std::sync::Arc;

use classfile::descriptor::{self, FieldType, MethodDescriptor};
use classfile::{ClassFile, Constant, MemberRef};

use crate::class::{ClassId, MethodRef};
use crate::{Vm, VmError, VmResult};

const LMF: &str = "java/lang/invoke/LambdaMetafactory";
const FLAG_SERIALIZABLE: i32 = 1;
const FLAG_MARKERS: i32 = 2;
const FLAG_BRIDGES: i32 = 4;

/// Everything a lambda class needs, read from the call site.
pub(crate) struct Spec {
    pub class_name: String,
    pub interface: String,
    pub captured: Vec<FieldType>,
    pub sam_name: String,
    pub sam_desc: String,
    pub instantiated: MethodDescriptor,
    pub bridges: Vec<String>,
    pub extra_interfaces: Vec<String>,
    pub impl_kind: u8,
    pub impl_ref: MemberRef,
    pub impl_on_interface: bool,
}

pub(crate) fn error(msg: impl Into<String>) -> VmError {
    VmError::exception("java/lang/BootstrapMethodError", msg)
}

impl Vm {
    /// The static factory of the lambda class for call site `index` of
    /// `caller`'s pool, generating and loading the class the first time.
    pub(crate) fn link_lambda(&mut self, caller: ClassId, index: u16) -> VmResult<MethodRef> {
        if let Some(&m) = self.lambda_sites.get(&(caller, index)) {
            return Ok(m);
        }
        let file = self
            .class(caller)
            .file
            .clone()
            .ok_or_else(|| error("no constant pool"))?;
        self.lambda_count += 1;
        let name = format!("{}$$Lambda${}", self.class(caller).name, self.lambda_count);
        let spec = read_spec(&file, index, name).map_err(error)?;
        let bytes = spin::class_file(&spec).map_err(error)?;
        let boot = self.class(caller).from_boot;
        let class = self.define_class(&spec.class_name, &bytes, boot)?;
        self.initialize(class)?;
        let factory_desc = format!(
            "({}){}",
            spec.captured
                .iter()
                .map(FieldType::descriptor)
                .collect::<String>(),
            object(&spec.interface)
        );
        let method = self
            .class(class)
            .find_method(spin::FACTORY, &factory_desc)
            .ok_or_else(|| VmError::Internal("generated lambda class has no factory".into()))?;
        let factory = MethodRef {
            class,
            index: method,
        };
        self.lambda_sites.insert((caller, index), factory);
        Ok(factory)
    }
}

fn object(internal: &str) -> String {
    format!("L{internal};")
}

/// Reads the call site's name, type, bootstrap method and arguments.
fn read_spec(file: &Arc<ClassFile>, index: u16, class_name: String) -> Result<Spec, String> {
    let pool = &file.pool;
    let Some(Constant::InvokeDynamic {
        bootstrap,
        name_and_type,
    }) = pool.get(index)
    else {
        return Err(format!("constant {index} is not InvokeDynamic"));
    };
    let (sam_name, invoked) = pool.name_and_type(*name_and_type)?;
    let bsm = file
        .bootstrap_methods
        .get(usize::from(*bootstrap))
        .ok_or("no bootstrap method")?;
    let (kind, target) = handle(pool, bsm.method_ref)?;
    let alt = match (&*target.class, &*target.name, kind) {
        (LMF, "metafactory", 6) => false,
        (LMF, "altMetafactory", 6) => true,
        (c, n, _) => {
            return Err(format!(
                "unsupported bootstrap method {c}.{n} (LazyVM links only LambdaMetafactory)"
            ));
        }
    };
    let args = &bsm.arguments;
    let arg = |i: usize| {
        args.get(i)
            .copied()
            .ok_or_else(|| "missing bootstrap argument".to_string())
    };
    let sam_desc = method_type(pool, arg(0)?)?;
    let (impl_kind, impl_ref) = handle(pool, arg(1)?)?;
    let impl_on_interface = match pool.get(arg(1)?) {
        Some(Constant::MethodHandle { reference, .. }) => {
            matches!(
                pool.get(*reference),
                Some(Constant::InterfaceMethodRef { .. })
            )
        }
        _ => false,
    };
    let instantiated = descriptor::parse_method(&method_type(pool, arg(2)?)?)?;
    let invoked_md = descriptor::parse_method(invoked)?;
    let Some(FieldType::Object(interface)) = invoked_md.ret.clone() else {
        return Err("invokedynamic type must return an interface".into());
    };
    let (mut extra_interfaces, mut bridges) = (Vec::new(), Vec::new());
    if alt {
        let flags = integer(pool, arg(3)?)?;
        let mut next = 4;
        if flags & FLAG_MARKERS != 0 {
            let n = integer(pool, arg(next)?)?;
            next += 1;
            for _ in 0..n {
                extra_interfaces.push(pool.class_name(arg(next)?)?.to_string());
                next += 1;
            }
        }
        if flags & FLAG_BRIDGES != 0 {
            let n = integer(pool, arg(next)?)?;
            next += 1;
            for _ in 0..n {
                bridges.push(method_type(pool, arg(next)?)?);
                next += 1;
            }
        }
        if flags & FLAG_SERIALIZABLE != 0 {
            extra_interfaces.push("java/io/Serializable".into());
        }
    }
    extra_interfaces.retain(|i| *i != *interface);
    extra_interfaces.dedup();
    bridges.retain(|b| *b != sam_desc);
    Ok(Spec {
        class_name,
        interface: interface.to_string(),
        captured: invoked_md.params,
        sam_name: sam_name.to_string(),
        sam_desc,
        instantiated,
        bridges,
        extra_interfaces,
        impl_kind,
        impl_ref,
        impl_on_interface,
    })
}

fn handle(pool: &classfile::ConstantPool, index: u16) -> Result<(u8, MemberRef), String> {
    match pool.get(index) {
        Some(Constant::MethodHandle { kind, reference }) => {
            Ok((*kind, pool.member_ref(*reference)?))
        }
        other => Err(format!("expected a MethodHandle, found {other:?}")),
    }
}

fn method_type(pool: &classfile::ConstantPool, index: u16) -> Result<String, String> {
    match pool.get(index) {
        Some(Constant::MethodType(d)) => Ok(pool.utf8(*d)?.to_string()),
        other => Err(format!("expected a MethodType, found {other:?}")),
    }
}

fn integer(pool: &classfile::ConstantPool, index: u16) -> Result<i32, String> {
    match pool.get(index) {
        Some(Constant::Integer(v)) if *v >= 0 => Ok(*v),
        other => Err(format!("expected a non-negative Integer, found {other:?}")),
    }
}
