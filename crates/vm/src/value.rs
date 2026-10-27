//! Values on the operand stack, in locals and in fields.

use std::num::NonZeroU32;

use crate::VmError;

/// A reference to a heap object: a slot index plus the slot's generation.
/// The generation catches a stale reference to a slot the collector has
/// reused, which would otherwise alias a different object.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Ref {
    pub(crate) index: NonZeroU32,
    pub(crate) generation: u32,
}

impl Ref {
    pub fn index(self) -> u32 {
        self.index.get()
    }
}

/// One JVM value. A `long` or `double` is one `Value` on the operand stack
/// and in a field; in local variables it takes its slot and the slot after,
/// which holds `Top` (JVMS 2.6.1).
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Value {
    Int(i32),
    Long(i64),
    Float(f32),
    Double(f64),
    Ref(Option<Ref>),
    Top,
}

impl Value {
    pub const NULL: Value = Value::Ref(None);

    /// Category 2 values take two slots (JVMS 2.11.1).
    pub fn is_wide(self) -> bool {
        matches!(self, Value::Long(_) | Value::Double(_))
    }

    pub fn int(self) -> Result<i32, VmError> {
        match self {
            Value::Int(v) => Ok(v),
            other => Err(VmError::verify(format!("expected int, found {other:?}"))),
        }
    }

    pub fn long(self) -> Result<i64, VmError> {
        match self {
            Value::Long(v) => Ok(v),
            other => Err(VmError::verify(format!("expected long, found {other:?}"))),
        }
    }

    pub fn float(self) -> Result<f32, VmError> {
        match self {
            Value::Float(v) => Ok(v),
            other => Err(VmError::verify(format!("expected float, found {other:?}"))),
        }
    }

    pub fn double(self) -> Result<f64, VmError> {
        match self {
            Value::Double(v) => Ok(v),
            other => Err(VmError::verify(format!("expected double, found {other:?}"))),
        }
    }

    /// A reference, possibly null.
    pub fn reference(self) -> Result<Option<Ref>, VmError> {
        match self {
            Value::Ref(r) => Ok(r),
            other => Err(VmError::verify(format!(
                "expected reference, found {other:?}"
            ))),
        }
    }

    pub fn boolean(self) -> Result<bool, VmError> {
        Ok(self.int()? != 0)
    }
}

/// The default value of a field or array element with this descriptor's
/// first character (JVMS 2.3, 2.4).
pub fn default_for(descriptor: &str) -> Value {
    match descriptor.as_bytes().first() {
        Some(b'J') => Value::Long(0),
        Some(b'F') => Value::Float(0.0),
        Some(b'D') => Value::Double(0.0),
        Some(b'L' | b'[') => Value::NULL,
        _ => Value::Int(0),
    }
}
