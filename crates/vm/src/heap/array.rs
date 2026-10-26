//! Array bodies, one variant per element representation.

use crate::VmError;
use crate::value::{Ref, Value};

/// `boolean[]` and `byte[]` share `Byte`; the array's class tells them apart.
#[derive(Debug)]
pub enum Array {
    Byte(Box<[i8]>),
    Char(Box<[u16]>),
    Short(Box<[i16]>),
    Int(Box<[i32]>),
    Long(Box<[i64]>),
    Float(Box<[f32]>),
    Double(Box<[f64]>),
    Ref(Box<[Option<Ref>]>),
}

/// The element kind of an array, from its descriptor character.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ElemKind {
    Boolean,
    Byte,
    Char,
    Short,
    Int,
    Long,
    Float,
    Double,
    Ref,
}

impl ElemKind {
    pub fn from_descriptor(c: u8) -> ElemKind {
        match c {
            b'Z' => ElemKind::Boolean,
            b'B' => ElemKind::Byte,
            b'C' => ElemKind::Char,
            b'S' => ElemKind::Short,
            b'I' => ElemKind::Int,
            b'J' => ElemKind::Long,
            b'F' => ElemKind::Float,
            b'D' => ElemKind::Double,
            _ => ElemKind::Ref,
        }
    }

    /// The `newarray` atype codes (JVMS 6.5 newarray).
    pub fn from_atype(atype: u8) -> Option<ElemKind> {
        Some(match atype {
            4 => ElemKind::Boolean,
            5 => ElemKind::Char,
            6 => ElemKind::Float,
            7 => ElemKind::Double,
            8 => ElemKind::Byte,
            9 => ElemKind::Short,
            10 => ElemKind::Int,
            11 => ElemKind::Long,
            _ => return None,
        })
    }

    pub fn bytes(self) -> usize {
        match self {
            ElemKind::Boolean | ElemKind::Byte => 1,
            ElemKind::Char | ElemKind::Short => 2,
            ElemKind::Int | ElemKind::Float => 4,
            ElemKind::Long | ElemKind::Double | ElemKind::Ref => 8,
        }
    }
}

impl Array {
    pub fn new(kind: ElemKind, len: usize) -> Array {
        match kind {
            ElemKind::Boolean | ElemKind::Byte => Array::Byte(vec![0; len].into()),
            ElemKind::Char => Array::Char(vec![0; len].into()),
            ElemKind::Short => Array::Short(vec![0; len].into()),
            ElemKind::Int => Array::Int(vec![0; len].into()),
            ElemKind::Long => Array::Long(vec![0; len].into()),
            ElemKind::Float => Array::Float(vec![0.0; len].into()),
            ElemKind::Double => Array::Double(vec![0.0; len].into()),
            ElemKind::Ref => Array::Ref(vec![None; len].into()),
        }
    }

    pub fn len(&self) -> usize {
        match self {
            Array::Byte(a) => a.len(),
            Array::Char(a) => a.len(),
            Array::Short(a) => a.len(),
            Array::Int(a) => a.len(),
            Array::Long(a) => a.len(),
            Array::Float(a) => a.len(),
            Array::Double(a) => a.len(),
            Array::Ref(a) => a.len(),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn bytes(&self) -> usize {
        let per = match self {
            Array::Byte(_) => 1,
            Array::Char(_) | Array::Short(_) => 2,
            Array::Int(_) | Array::Float(_) => 4,
            Array::Long(_) | Array::Double(_) | Array::Ref(_) => 8,
        };
        self.len() * per
    }

    /// Reads element `i` as a stack value; the caller has bounds-checked `i`.
    pub fn load(&self, i: usize) -> Option<Value> {
        Some(match self {
            Array::Byte(a) => Value::Int(i32::from(*a.get(i)?)),
            Array::Char(a) => Value::Int(i32::from(*a.get(i)?)),
            Array::Short(a) => Value::Int(i32::from(*a.get(i)?)),
            Array::Int(a) => Value::Int(*a.get(i)?),
            Array::Long(a) => Value::Long(*a.get(i)?),
            Array::Float(a) => Value::Float(*a.get(i)?),
            Array::Double(a) => Value::Double(*a.get(i)?),
            Array::Ref(a) => Value::Ref(*a.get(i)?),
        })
    }

    /// Stores a stack value, narrowing ints as the `*astore` opcodes do.
    /// `boolean_array` makes `bastore` keep only the low bit (JVMS 6.5 bastore).
    pub fn store(&mut self, i: usize, v: Value, boolean_array: bool) -> Result<(), VmError> {
        let oob = || VmError::verify("array store out of bounds");
        match self {
            Array::Byte(a) => {
                let x = v.int()?;
                *a.get_mut(i).ok_or_else(oob)? = if boolean_array {
                    (x & 1) as i8
                } else {
                    x as i8
                };
            }
            Array::Char(a) => *a.get_mut(i).ok_or_else(oob)? = v.int()? as u16,
            Array::Short(a) => *a.get_mut(i).ok_or_else(oob)? = v.int()? as i16,
            Array::Int(a) => *a.get_mut(i).ok_or_else(oob)? = v.int()?,
            Array::Long(a) => *a.get_mut(i).ok_or_else(oob)? = v.long()?,
            Array::Float(a) => *a.get_mut(i).ok_or_else(oob)? = v.float()?,
            Array::Double(a) => *a.get_mut(i).ok_or_else(oob)? = v.double()?,
            Array::Ref(a) => *a.get_mut(i).ok_or_else(oob)? = v.reference()?,
        }
        Ok(())
    }

    /// A shallow copy, for `clone()`.
    pub fn duplicate(&self) -> Array {
        match self {
            Array::Byte(a) => Array::Byte(a.clone()),
            Array::Char(a) => Array::Char(a.clone()),
            Array::Short(a) => Array::Short(a.clone()),
            Array::Int(a) => Array::Int(a.clone()),
            Array::Long(a) => Array::Long(a.clone()),
            Array::Float(a) => Array::Float(a.clone()),
            Array::Double(a) => Array::Double(a.clone()),
            Array::Ref(a) => Array::Ref(a.clone()),
        }
    }
}
