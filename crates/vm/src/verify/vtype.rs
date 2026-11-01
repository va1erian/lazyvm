//! Verification types (JVMS 4.10.1.2) and their assignability.
//!
//! Reference types are interned so that a type is one small `Copy` value:
//! frames hold many of them, and hostile stack maps must stay cheap to compare.

use std::collections::HashMap;
use std::sync::Arc;

use classfile::descriptor::FieldType;

use super::{ClassInfo, Hierarchy};
use crate::VmResult;

pub const OBJECT: &str = "java/lang/Object";
pub const THROWABLE: &str = "java/lang/Throwable";

/// Remembered answers per class. They save time, so past this they stop
/// being kept: a hostile class could otherwise fill memory with pairs.
const MAX_MEMO: usize = 1 << 20;
/// What working out an unremembered assignment costs against the budget, in the
/// budget's units: it reads names and walks classes where a known one compares two
/// words. Also how many name bytes make one more such unit.
pub const SLOW_ASSIGNMENT: usize = 256;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct NameId(u32);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum VType {
    Top,
    Int,
    Float,
    Long,
    Double,
    Null,
    UninitThis,
    /// A `new` at this offset whose object is not initialized yet.
    Uninit(u16),
    Ref(NameId),
}

impl VType {
    /// Slots: 2 for long and double.
    pub fn size(self) -> u32 {
        match self {
            VType::Long | VType::Double => 2,
            _ => 1,
        }
    }

    /// What `aload`, `astore`, `ifnull`, `if_acmp` and monitors accept.
    pub fn is_reference(self) -> bool {
        matches!(
            self,
            VType::Null | VType::UninitThis | VType::Uninit(_) | VType::Ref(_)
        )
    }
}

/// The interned names of one class's verification, with what is known about
/// them, so a class named a thousand times is looked at once.
pub struct Types<'h, H: Hierarchy + ?Sized> {
    pub hierarchy: &'h mut H,
    list: Vec<Arc<str>>,
    index: HashMap<Arc<str>, NameId>,
    assignable: HashMap<(NameId, NameId), bool>,
    /// `(sub, sup)` pairs the protected check has already walked.
    superclass: HashMap<(NameId, NameId), bool>,
    /// Assignments worked out and classes walked since the last `take_slow`,
    /// long names counting for more.
    slow: usize,
}

impl<'h, H: Hierarchy + ?Sized> Types<'h, H> {
    pub fn new(hierarchy: &'h mut H) -> Self {
        Types {
            hierarchy,
            list: Vec::new(),
            index: HashMap::new(),
            assignable: HashMap::new(),
            superclass: HashMap::new(),
            slow: 0,
        }
    }

    /// The budget units of the assignments worked out since the last call.
    pub fn take_slow(&mut self) -> usize {
        std::mem::take(&mut self.slow) * SLOW_ASSIGNMENT
    }

    pub fn intern(&mut self, name: &str) -> NameId {
        if let Some(&id) = self.index.get(name) {
            return id;
        }
        let id = NameId(self.list.len() as u32);
        let text: Arc<str> = name.into();
        self.list.push(text.clone());
        self.index.insert(text, id);
        id
    }

    pub fn name(&self, id: NameId) -> Arc<str> {
        self.list[id.0 as usize].clone()
    }

    pub fn class(&mut self, name: &str) -> VType {
        VType::Ref(self.intern(name))
    }

    /// The verification type of a descriptor type: the small ints are `int`.
    pub fn of_field(&mut self, t: &FieldType) -> VType {
        match t {
            FieldType::Byte
            | FieldType::Char
            | FieldType::Short
            | FieldType::Boolean
            | FieldType::Int => VType::Int,
            FieldType::Long => VType::Long,
            FieldType::Float => VType::Float,
            FieldType::Double => VType::Double,
            FieldType::Object(name) => self.class(name),
            FieldType::Array(_) => self.class(&t.descriptor()),
        }
    }

    /// JVMS 4.10.1.2 `isAssignable`.
    pub fn assignable(&mut self, from: VType, to: VType) -> VmResult<bool> {
        Ok(match (from, to) {
            _ if from == to => true,
            (_, VType::Top) => true,
            (VType::Null, VType::Ref(_)) => true,
            (VType::Ref(a), VType::Ref(b)) => self.names_assignable(a, b)?,
            _ => false,
        })
    }

    fn names_assignable(&mut self, from: NameId, to: NameId) -> VmResult<bool> {
        if let Some(&known) = self.assignable.get(&(from, to)) {
            return Ok(known);
        }
        let (f, t) = (self.name(from), self.name(to));
        self.slow += 1 + (f.len() + t.len()) / SLOW_ASSIGNMENT;
        let answer = self.java_assignable(&f, &t)?;
        if self.assignable.len() < MAX_MEMO {
            self.assignable.insert((from, to), answer);
        }
        Ok(answer)
    }

    /// JVMS 4.10.1.2 `isJavaAssignable`, on internal names and array
    /// descriptors. Interfaces count as `Object` here; `invokeinterface`
    /// checks the receiver when it runs.
    fn java_assignable(&mut self, mut from: &str, mut to: &str) -> VmResult<bool> {
        loop {
            if from == to || to == OBJECT {
                return Ok(true);
            }
            match (from.strip_prefix('['), to.strip_prefix('[')) {
                (Some(f), Some(t)) => match (element_name(f), element_name(t)) {
                    (Some(f), Some(t)) => (from, to) = (f, t),
                    // Primitive arrays: only the identity, which failed above.
                    _ => return Ok(false),
                },
                // Observed: any interface, where JVMS 4.10.1.2 lists two
                // (tests/verify/good/ArrayAsAnyInterface).
                (Some(_), None) => return Ok(self.info(to)?.is_interface),
                (None, Some(_)) => return Ok(false),
                (None, None) => {
                    if self.info(to)?.is_interface {
                        return Ok(true);
                    }
                    return self.is_subclass(from, to);
                }
            }
        }
    }

    /// Whether `sup` is `sub` or one of its superclasses.
    pub fn is_subclass(&mut self, sub: &str, sup: &str) -> VmResult<bool> {
        let mut cur: Arc<str> = sub.into();
        loop {
            if &*cur == sup {
                return Ok(true);
            }
            self.slow += 1 + cur.len() / SLOW_ASSIGNMENT;
            match self.info(&cur)?.super_name {
                Some(next) => cur = next,
                None => return Ok(false),
            }
        }
    }

    /// [`Types::is_subclass`], remembered: every member access asks this.
    pub fn is_superclass(&mut self, sub: &str, sup: &str) -> VmResult<bool> {
        let key = (self.intern(sub), self.intern(sup));
        if let Some(&known) = self.superclass.get(&key) {
            return Ok(known);
        }
        let answer = self.is_subclass(sub, sup)?;
        if self.superclass.len() < MAX_MEMO {
            self.superclass.insert(key, answer);
        }
        Ok(answer)
    }

    pub fn info(&mut self, name: &str) -> VmResult<ClassInfo> {
        self.hierarchy.info(name)
    }
}

/// The class name or array descriptor of an array's element descriptor;
/// `None` when the element is primitive.
pub fn element_name(element: &str) -> Option<&str> {
    match element.as_bytes().first()? {
        b'[' => Some(element),
        b'L' => element.strip_prefix('L')?.strip_suffix(';'),
        _ => None,
    }
}

/// The descriptor of an array of `class_or_array`, as `anewarray` makes it.
pub fn array_of(class_or_array: &str) -> String {
    if class_or_array.starts_with('[') {
        format!("[{class_or_array}")
    } else {
        format!("[L{class_or_array};")
    }
}
