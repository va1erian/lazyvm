//! Array element loads and stores (`*aload`, `*astore`).

use super::Flow;
use crate::class::ClassKind;
use crate::heap::Array;
use crate::value::{Ref, Value};
use crate::{Vm, VmError, VmResult};

/// The array variant each opcode works on, in opcode order:
/// int, long, float, double, reference, byte/boolean, char, short.
fn matches(op_kind: u8, a: &Array) -> bool {
    matches!(
        (op_kind, a),
        (0, Array::Int(_))
            | (1, Array::Long(_))
            | (2, Array::Float(_))
            | (3, Array::Double(_))
            | (4, Array::Ref(_))
            | (5, Array::Byte(_))
            | (6, Array::Char(_))
            | (7, Array::Short(_))
    )
}

/// Java 8's message is the index alone.
pub(crate) fn out_of_bounds(index: i32, _len: usize) -> VmError {
    VmError::exception(
        "java/lang/ArrayIndexOutOfBoundsException",
        index.to_string(),
    )
}

/// The index as `usize` if it is inside an array of `len` elements.
fn check_index(index: i32, len: usize) -> VmResult<usize> {
    usize::try_from(index)
        .ok()
        .filter(|&i| i < len)
        .ok_or_else(|| out_of_bounds(index, len))
}

impl Vm {
    pub(super) fn op_array(&mut self, op: u8) -> VmResult<Flow> {
        if op <= 0x35 {
            self.array_load(op - 0x2e)?;
        } else {
            self.array_store(op - 0x4f)?;
        }
        self.top()?.advance(1);
        Ok(Flow::Next)
    }

    fn array_load(&mut self, kind: u8) -> VmResult<()> {
        let Vm { frames, heap, .. } = self;
        let f = frames
            .last_mut()
            .ok_or_else(|| VmError::Internal("no frame".into()))?;
        let index = f.pop_int()?;
        let array = f.pop_nonnull()?;
        let a = heap.array(array)?;
        if !matches(kind, a) {
            return Err(VmError::verify("array load of the wrong type"));
        }
        let i = check_index(index, a.len())?;
        let v = a.load(i).ok_or_else(|| out_of_bounds(index, a.len()))?;
        f.push(v)
    }

    fn array_store(&mut self, kind: u8) -> VmResult<()> {
        let (value, index, array) = {
            let f = self.top()?;
            (f.pop()?, f.pop_int()?, f.pop_nonnull()?)
        };
        let class = self.heap.class_of(array)?;
        let ClassKind::Array { component, element } = self.class(class).kind else {
            return Err(VmError::verify("array store into a non-array"));
        };
        // JVMS 6.5 aastore: the bounds check comes before the store check.
        let a = self.heap.array(array)?;
        if !matches(kind, a) {
            return Err(VmError::verify("array store of the wrong type"));
        }
        let i = check_index(index, a.len())?;
        if kind == 4 {
            self.check_array_store(component, value)?;
        }
        self.heap.array_mut(array)?.store(i, value, element == b'Z')
    }

    /// `aastore` requires the value to be assignable to the component type.
    fn check_array_store(&self, component: crate::class::ClassId, value: Value) -> VmResult<()> {
        let Some(r): Option<Ref> = value.reference()? else {
            return Ok(());
        };
        let vc = self.heap.class_of(r)?;
        if self.is_assignable(vc, component) {
            Ok(())
        } else {
            Err(VmError::exception(
                "java/lang/ArrayStoreException",
                self.class(vc).java_name(),
            ))
        }
    }
}
