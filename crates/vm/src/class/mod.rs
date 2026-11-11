//! Loaded classes: the runtime form of a class file, arrays and primitives.

mod access;
mod hierarchy;
mod layout;
mod load;
mod path;
mod resolve;

use std::sync::Arc;

use classfile::{ClassFile, Code};

pub(crate) use access::package_of;
pub use path::ClassPath;
pub use resolve::Resolved;

use crate::native::NativeFn;
use crate::value::{Ref, Value};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ClassId(pub u32);

/// A method: its class and index in that class's method table.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct MethodRef {
    pub class: ClassId,
    pub index: u16,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ClassKind {
    Instance,
    /// The component's descriptor (`I`, `Ljava/lang/String;`, `[J`) and class.
    Array {
        component: ClassId,
        element: u8,
    },
    /// `int`, `void`, ...: the descriptor character.
    Primitive(u8),
}

/// JVMS 5.5 initialization states.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InitState {
    Linked,
    /// Being initialized by the thread with this index (`Vm::threads`).
    Initializing(usize),
    Initialized,
    Failed,
}

#[derive(Debug)]
pub struct Field {
    pub name: Arc<str>,
    pub descriptor: Arc<str>,
    pub access: u16,
    /// Index into the instance's field values or the class's statics.
    pub slot: u32,
    pub constant_value: Option<u16>,
}

impl Field {
    pub fn is_static(&self) -> bool {
        self.access & classfile::ACC_STATIC != 0
    }
}

pub struct Method {
    pub name: Arc<str>,
    pub descriptor: Arc<str>,
    pub access: u16,
    /// Set when the class is laid out and never replaced: frames point
    /// into it (`interp::CodeRef`), so it is crate-private; `code()` reads it.
    pub(crate) code: Option<Arc<Code>>,
    /// Argument slots in the callee's locals, including `this`.
    pub arg_slots: u16,
    /// Arguments as values (`this` included), the operand stack's count.
    pub arg_values: u16,
    /// The return descriptor's first character, `V` for void.
    pub ret: u8,
    /// Resolved on first call for native methods.
    pub native: Option<NativeFn>,
    /// Run counts and compiled code (`jit`).
    pub(crate) jit: crate::jit::Slot,
}

impl Method {
    pub fn code(&self) -> Option<&Arc<Code>> {
        self.code.as_ref()
    }

    pub fn is_static(&self) -> bool {
        self.access & classfile::ACC_STATIC != 0
    }

    pub fn is_native(&self) -> bool {
        self.access & classfile::ACC_NATIVE != 0
    }

    pub fn is_abstract(&self) -> bool {
        self.access & classfile::ACC_ABSTRACT != 0
    }

    pub fn is_private(&self) -> bool {
        self.access & classfile::ACC_PRIVATE != 0
    }
}

pub struct Class {
    pub id: ClassId,
    /// The internal name: `java/lang/String`, `[I`, or `int` for primitives.
    pub name: Arc<str>,
    pub kind: ClassKind,
    pub access: u16,
    pub super_class: Option<ClassId>,
    pub interfaces: Vec<ClassId>,
    /// Every superinterface, direct or inherited, sorted and deduplicated:
    /// computed once at link time so subtype checks are a binary search.
    pub all_interfaces: Box<[ClassId]>,
    pub file: Option<Arc<ClassFile>>,
    pub fields: Vec<Field>,
    /// Crate-private for `interp::CodeRef`'s sake; `methods()` reads them.
    pub(crate) methods: Vec<Method>,
    /// Default field values of a new instance, superclass fields first.
    pub instance_template: Box<[Value]>,
    pub statics: Vec<Value>,
    pub state: InitState,
    pub mirror: Option<Ref>,
    /// Resolved constant pool entries, indexed like the pool.
    pub cp_cache: Vec<Option<Resolved>>,
    /// Per method-ref entry, the receiver class `invokevirtual` or
    /// `invokeinterface` last saw there and the method it selected: a
    /// one-entry cache in front of `Vm::select` (`interp/fast_call.rs`).
    pub call_cache: Vec<Option<(ClassId, MethodRef)>>,
    /// Loaded from the boot class path (`Class.getClassLoader` is null).
    pub from_boot: bool,
    /// Verification passed, or nothing to check (`Vm::link_class`).
    pub verified: bool,
    /// The verifier ran and passed (not skipped by `-Xverify`): only such a
    /// class's methods are compiled (`jit`).
    pub(crate) checked: bool,
    /// A generated lambda class: the class it counts as part of.
    pub host: Option<ClassId>,
}

impl Class {
    pub fn methods(&self) -> &[Method] {
        &self.methods
    }

    pub fn is_interface(&self) -> bool {
        self.access & classfile::ACC_INTERFACE != 0
    }

    pub fn is_array(&self) -> bool {
        matches!(self.kind, ClassKind::Array { .. })
    }

    /// The binary name Java code sees: dots instead of slashes.
    pub fn java_name(&self) -> String {
        self.name.replace('/', ".")
    }

    pub fn find_method(&self, name: &str, descriptor: &str) -> Option<u16> {
        self.methods
            .iter()
            .position(|m| &*m.name == name && &*m.descriptor == descriptor)
            .and_then(|i| u16::try_from(i).ok())
    }

    pub fn find_field(&self, name: &str, descriptor: &str) -> Option<&Field> {
        self.fields
            .iter()
            .find(|f| &*f.name == name && &*f.descriptor == descriptor)
    }

    pub fn source_file(&self) -> Option<&str> {
        self.file.as_ref().and_then(|f| f.source_file.as_deref())
    }
}
