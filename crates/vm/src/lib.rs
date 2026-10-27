//! The LazyVM core: class loading, the heap, and the interpreter.
//!
//! One [`Vm`] owns everything. All of it is safe Rust: references are
//! checked slot indices (`heap`), and Java exceptions are `VmError` values.
//! See `docs/decisions.md` D5 for the design and the rooting rule.

pub mod class;
mod error;
mod gc;
pub mod heap;
mod init;
pub mod interp;
mod lambda;
pub mod native;
mod objects;
mod strings;
mod throw;
pub mod value;
pub mod wtf;

use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;

pub use class::{Class, ClassId, ClassPath, MethodRef};
pub use error::{VmError, VmResult};
pub use interp::Frame;
pub use native::NativeFn;
pub use value::{Ref, Value};

/// Settings from the command line.
#[derive(Clone, Debug)]
pub struct Options {
    pub heap_limit: usize,
    /// Java frames per thread before `StackOverflowError`.
    pub max_frames: usize,
    /// Local and operand stack slots across all frames of a thread.
    pub max_frame_slots: usize,
    /// Nested calls from Rust into Java (each uses native stack).
    pub max_nesting: u32,
    /// `-ea`: `assert` is checked in application classes.
    pub assertions: bool,
    pub trace_interp: bool,
    pub verbose_class: bool,
}

impl Default for Options {
    fn default() -> Self {
        Options {
            heap_limit: 256 << 20,
            max_frames: 4096,
            max_frame_slots: 4 << 20,
            max_nesting: 1024,
            assertions: false,
            trace_interp: false,
            verbose_class: false,
        }
    }
}

/// Classes and field slots the VM itself uses, filled in by [`Vm::boot`].
#[derive(Clone, Copy, Debug, Default)]
pub struct WellKnown {
    pub object: Option<ClassId>,
    pub string: Option<ClassId>,
    pub class: Option<ClassId>,
    pub char_array: Option<ClassId>,
    pub string_value: u32,
    pub string_hash: u32,
    pub class_id_field: u32,
    pub throwable: Option<ClassId>,
    pub throwable_backtrace: u32,
}

pub struct Vm {
    pub classes: Vec<Class>,
    pub(crate) by_name: HashMap<Arc<str>, ClassId>,
    /// Names being loaded, to detect `ClassCircularityError`.
    pub(crate) loading: Vec<Arc<str>>,
    pub classpath: ClassPath,
    pub heap: heap::Heap,
    pub frames: Vec<Frame>,
    /// References held by Rust code across a call into Java (see `with_root`).
    pub roots: Vec<Ref>,
    pub(crate) interned: HashMap<Box<[u16]>, Ref>,
    pub(crate) natives: HashMap<String, NativeFn>,
    pub(crate) select_cache: HashMap<(ClassId, MethodRef), MethodRef>,
    /// Linked lambda call sites: (caller, InvokeDynamic index) to factory.
    pub(crate) lambda_sites: HashMap<(ClassId, u16, usize), MethodRef>,
    /// Numbers generated lambda class names.
    pub(crate) lambda_count: u64,
    pub properties: BTreeMap<String, String>,
    pub options: Options,
    pub wk: WellKnown,
    /// Depth of nested `throw_new` calls, to stop runaway exception creation.
    pub(crate) creating_exception: u32,
    /// Slots used by `frames`, checked against `Options::max_frame_slots`.
    pub(crate) frame_slots: usize,
    /// Current nesting of `invoke` from Rust.
    pub(crate) nesting: u32,
}

impl Vm {
    pub fn new(classpath: ClassPath, options: Options) -> Vm {
        Vm {
            classes: Vec::new(),
            by_name: HashMap::new(),
            loading: Vec::new(),
            classpath,
            heap: heap::Heap::new(options.heap_limit),
            frames: Vec::new(),
            roots: Vec::new(),
            interned: HashMap::new(),
            natives: HashMap::new(),
            select_cache: HashMap::new(),
            lambda_sites: HashMap::new(),
            lambda_count: 0,
            properties: BTreeMap::new(),
            options,
            wk: WellKnown::default(),
            creating_exception: 0,
            frame_slots: 0,
            nesting: 0,
        }
    }

    pub fn class(&self, id: ClassId) -> &Class {
        &self.classes[id.0 as usize]
    }

    pub fn class_mut(&mut self, id: ClassId) -> &mut Class {
        &mut self.classes[id.0 as usize]
    }

    pub fn method(&self, m: MethodRef) -> &class::Method {
        &self.class(m.class).methods[usize::from(m.index)]
    }

    /// Runs `f` with `r` kept alive for the collector, for Rust code that
    /// needs a reference after calling into Java.
    pub fn with_root<T>(&mut self, r: Ref, f: impl FnOnce(&mut Vm) -> T) -> T {
        let mark = self.roots.len();
        self.roots.push(r);
        let out = f(self);
        self.roots.truncate(mark);
        out
    }

    /// Loads the classes the VM relies on and records their field slots.
    /// Fails if the class library is missing or does not match the native
    /// contract (docs/design/natives.md).
    pub fn boot(&mut self) -> VmResult<()> {
        let object = self.load_class("java/lang/Object")?;
        let string = self.load_class("java/lang/String")?;
        let class = self.load_class("java/lang/Class")?;
        let char_array = self.load_class("[C")?;
        let throwable = self.load_class("java/lang/Throwable")?;
        self.wk = WellKnown {
            object: Some(object),
            string: Some(string),
            class: Some(class),
            char_array: Some(char_array),
            string_value: self.field_slot(string, "value", "[C")?,
            string_hash: self.field_slot(string, "hash", "I")?,
            class_id_field: self.field_slot(class, "vmClassId", "I")?,
            throwable: Some(throwable),
            throwable_backtrace: self.field_slot(throwable, "backtrace", "Ljava/lang/Object;")?,
        };
        Ok(())
    }

    /// The slot of an instance field declared by `class`, or an internal error.
    pub fn field_slot(&self, class: ClassId, name: &str, descriptor: &str) -> VmResult<u32> {
        let c = self.class(class);
        c.find_field(name, descriptor)
            .filter(|f| !f.is_static())
            .map(|f| f.slot)
            .ok_or_else(|| {
                VmError::Internal(format!("{} has no field {name} {descriptor}", c.name))
            })
    }
}
