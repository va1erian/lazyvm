//! Loaded classes: the runtime form of a class file, arrays and primitives.

mod hierarchy;
mod layout;
mod load;
mod path;
mod resolve;

use std::sync::Arc;

use classfile::{ClassFile, Code};

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

/// JVMS 5.5 initialization states, single-threaded until S6.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InitState {
    Linked,
    Initializing,
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
    pub code: Option<Arc<Code>>,
    /// Argument slots in the callee's locals, including `this`.
    pub arg_slots: u16,
    /// Arguments as values (`this` included), the operand stack's count.
    pub arg_values: u16,
    /// The return descriptor's first character, `V` for void.
    pub ret: u8,
    /// Resolved on first call for native methods.
    pub native: Option<NativeFn>,
}

impl Method {
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
    pub methods: Vec<Method>,
    /// Default field values of a new instance, superclass fields first.
    pub instance_template: Box<[Value]>,
    pub statics: Vec<Value>,
    pub state: InitState,
    pub mirror: Option<Ref>,
    /// Resolved constant pool entries, indexed like the pool.
    pub cp_cache: Vec<Option<Resolved>>,
    /// Loaded from the boot class path (`Class.getClassLoader` is null).
    pub from_boot: bool,
}

impl Class {
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
