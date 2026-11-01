//! `invokedynamic` for lambdas and method references (docs/design/lambdas.md).
//!
//! The VM links `LambdaMetafactory.metafactory` and `altMetafactory` call
//! sites itself, following the linkage rules in their Javadoc: it writes a
//! class file for a final class implementing the functional interface, with
//! one field per captured value, and defines it like any other class. The
//! call site then calls that class's static factory with the captured values.

mod annotation;
mod asm;
mod check;
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
    /// The class holding the call site.
    pub caller: Arc<str>,
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
    /// A call site that cannot be linked: `BootstrapMethodError` caused by a
    /// `LambdaConversionException`, as Java 8 reports it.
    fn link_error(&mut self, msg: &str) -> VmError {
        match self.throw_new("java/lang/invoke/LambdaConversionException", Some(msg)) {
            Ok(cause) => self.bootstrap_error(msg, cause),
            Err(e) => e,
        }
    }

    /// Wraps a failure of linking (a class that cannot be loaded, say) in
    /// `BootstrapMethodError`, as Java 8 does.
    fn wrap_link_failure(&mut self, e: VmError) -> VmError {
        if !e.is_catchable() {
            return e;
        }
        match self.materialize(e) {
            Ok(cause) => self.bootstrap_error("call site initialization exception", cause),
            Err(e) => e,
        }
    }

    fn bootstrap_error(&mut self, msg: &str, cause: crate::Ref) -> VmError {
        let built = self.with_root(cause, |vm| -> VmResult<crate::Ref> {
            let class = vm.load_class("java/lang/BootstrapMethodError")?;
            vm.initialize(class)?;
            let ctor = vm
                .find_in_class(
                    class,
                    "<init>",
                    "(Ljava/lang/String;Ljava/lang/Throwable;)V",
                    false,
                )
                .ok_or_else(|| {
                    VmError::Internal("BootstrapMethodError(String, Throwable) missing".into())
                })?;
            let text = vm.new_string(msg)?;
            let obj = vm.new_object(class)?;
            let args = [
                crate::Value::Ref(Some(obj)),
                crate::Value::Ref(Some(text)),
                crate::Value::Ref(Some(cause)),
            ];
            vm.invoke(ctor, &args)?;
            Ok(obj)
        });
        match built {
            Ok(obj) => VmError::Throw(obj),
            Err(e) => e,
        }
    }

    /// The static factory of the lambda class for call site `index` of
    /// `caller`'s pool, generating and loading the class the first time.
    /// `site` is the instruction (method index and pc): each
    /// `invokedynamic` instruction is its own call site (JVMS 6.5).
    pub(crate) fn link_lambda(
        &mut self,
        caller: ClassId,
        index: u16,
        site: (u16, usize),
    ) -> VmResult<MethodRef> {
        let key = (caller, site.0, site.1);
        if let Some(&m) = self.lambda_sites.get(&key) {
            return Ok(m);
        }
        let file = self
            .class(caller)
            .file
            .clone()
            .ok_or_else(|| error("no constant pool"))?;
        // A site that failed before retries with its class, so failing in a
        // loop defines no more classes and repeats the error (JVMS 5.4.3).
        let earlier = self.lambda_failed.get(&key).copied();
        let name = match earlier {
            Some(c) => self.class(c).name.to_string(),
            None => self.next_lambda_name(caller),
        };
        let linked = read_spec(&file, index, name)
            .and_then(|spec| spin::class_file(&spec).map(|b| (spec, b)));
        let (spec, bytes) = match linked {
            Ok(l) => l,
            Err(msg) => return Err(self.link_error(&msg)),
        };
        match self.check_lambda_types(&spec) {
            Ok(Ok(())) => {}
            Ok(Err(msg)) => return Err(self.link_error(&msg)),
            Err(e) => return Err(self.wrap_link_failure(e)),
        }
        let class = match earlier {
            Some(c) => c,
            None => {
                let boot = self.class(caller).from_boot;
                let class = match self.define_class(&spec.class_name, &bytes, boot) {
                    Ok(c) => c,
                    Err(e) => return Err(self.wrap_link_failure(e)),
                };
                self.class_mut(class).host = Some(caller);
                class
            }
        };
        if let Err(e) = self.link_class(class) {
            self.lambda_failed.insert(key, class);
            return Err(self.wrap_link_failure(e));
        }
        // Only a class that failed is kept: code this initialization runs can
        // reach the same site, and must not get this half-initialized class.
        self.lambda_failed.remove(&key);
        if let Err(e) = self.initialize(class) {
            self.lambda_failed.insert(key, class);
            return Err(self.wrap_link_failure(e));
        }
        // Initializing the interface can run code that links this same site;
        // the first link to finish wins, as in Java 8.
        if let Some(&m) = self.lambda_sites.get(&key) {
            return Ok(m);
        }
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
        self.lambda_sites.insert(key, factory);
        Ok(factory)
    }

    fn next_lambda_name(&mut self, caller: ClassId) -> String {
        // `-` cannot appear in a Java class name, so no user class collides.
        loop {
            self.lambda_count += 1;
            let name = format!("{}-Lambda-{}", self.class(caller).name, self.lambda_count);
            if self.loaded_class(&name).is_none() && self.classpath.find_class(&name).is_none() {
                return name;
            }
        }
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
    let mut unique = Vec::new();
    for i in extra_interfaces {
        if !unique.contains(&i) {
            unique.push(i);
        }
    }
    let extra_interfaces = unique;
    bridges.retain(|b| *b != sam_desc);
    Ok(Spec {
        class_name,
        caller: file.this_class.clone(),
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
