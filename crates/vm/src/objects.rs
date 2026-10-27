//! Allocating objects and arrays, and class mirrors.

use crate::class::{ClassId, ClassKind};
use crate::heap::{Array, Body, Object, array::ElemKind};
use crate::value::{Ref, Value};
use crate::{Vm, VmError, VmResult};

/// Observed on Java 8: from `Integer.MAX_VALUE - 1` elements an array is
/// "Requested array size exceeds VM limit", whatever the heap.
pub(crate) const MAX_ARRAY: usize = i32::MAX as usize - 2;

/// The error for an array longer than `MAX_ARRAY`.
pub(crate) fn array_limit() -> VmError {
    VmError::exception(
        "java/lang/OutOfMemoryError",
        "Requested array size exceeds VM limit",
    )
}

impl Vm {
    /// A new instance with default field values. The class must be an
    /// initialized, non-abstract instance class; callers check that.
    pub fn new_object(&mut self, class: ClassId) -> VmResult<Ref> {
        let body = Body::Fields(self.class(class).instance_template.clone());
        self.heap.alloc(Object { class, body })
    }

    /// A new array of `len` elements of array class `class`.
    pub fn new_array(&mut self, class: ClassId, len: i32) -> VmResult<Ref> {
        let ClassKind::Array { element, .. } = self.class(class).kind else {
            return Err(VmError::Internal(format!(
                "{} is not an array class",
                self.class(class).name
            )));
        };
        if len < 0 {
            return Err(VmError::bare("java/lang/NegativeArraySizeException"));
        }
        let len = len as usize;
        if len > MAX_ARRAY {
            return Err(array_limit());
        }
        let kind = ElemKind::from_descriptor(element);
        // The interpreter collects before allocating (`Vm::ensure_room`); here
        // only an array larger than the whole heap is refused outright.
        if len.saturating_mul(kind.bytes()) > self.heap.limit() {
            return Err(VmError::OutOfMemory);
        }
        self.heap.alloc(Object {
            class,
            body: Body::Array(Array::new(kind, len)),
        })
    }

    /// A new array by element descriptor: `b'I'` for `int[]`, or `b'L'` with
    /// `component` naming the element class.
    pub fn new_array_of(&mut self, element_desc: &str, len: i32) -> VmResult<Ref> {
        let class = self.load_class(&format!("[{element_desc}"))?;
        self.new_array(class, len)
    }

    /// The `java.lang.Class` object of `class`, created on first use.
    pub fn mirror(&mut self, class: ClassId) -> VmResult<Ref> {
        if let Some(m) = self.class(class).mirror {
            return Ok(m);
        }
        let mirror_class = self
            .wk
            .class
            .ok_or_else(|| VmError::Internal("mirror before boot".into()))?;
        let r = self.new_object(mirror_class)?;
        let slot = self.wk.class_id_field as usize;
        let id = i32::try_from(class.0).map_err(|_| VmError::Internal("class id".into()))?;
        if let Some(f) = self.heap.fields_mut(r)?.get_mut(slot) {
            *f = Value::Int(id);
        }
        self.class_mut(class).mirror = Some(r);
        Ok(r)
    }

    /// The class a mirror stands for.
    pub fn class_of_mirror(&self, mirror: Ref) -> VmResult<ClassId> {
        let slot = self.wk.class_id_field as usize;
        let id = self
            .heap
            .fields(mirror)?
            .get(slot)
            .copied()
            .unwrap_or(Value::Int(-1))
            .int()?;
        let id = u32::try_from(id).map_err(|_| VmError::Internal("bad mirror".into()))?;
        if id as usize >= self.classes.len() {
            return Err(VmError::Internal("bad mirror".into()));
        }
        Ok(ClassId(id))
    }

    /// Reads an instance field by slot.
    pub fn get_field(&self, obj: Ref, slot: u32) -> VmResult<Value> {
        self.heap
            .fields(obj)?
            .get(slot as usize)
            .copied()
            .ok_or_else(|| VmError::verify("field slot out of range"))
    }

    pub fn set_field(&mut self, obj: Ref, slot: u32, v: Value) -> VmResult<()> {
        let f = self
            .heap
            .fields_mut(obj)?
            .get_mut(slot as usize)
            .ok_or_else(|| VmError::verify("field slot out of range"))?;
        *f = v;
        Ok(())
    }

    /// Reads an instance field by name, for natives that touch library fields.
    pub fn field_by_name(&self, obj: Ref, name: &str, desc: &str) -> VmResult<Value> {
        let class = self.heap.class_of(obj)?;
        let (owner, i) = self
            .lookup_field(class, name, desc)
            .ok_or_else(|| VmError::Internal(format!("no field {name} {desc}")))?;
        self.get_field(obj, self.class(owner).fields[i].slot)
    }

    pub fn set_field_by_name(
        &mut self,
        obj: Ref,
        name: &str,
        desc: &str,
        v: Value,
    ) -> VmResult<()> {
        let class = self.heap.class_of(obj)?;
        let (owner, i) = self
            .lookup_field(class, name, desc)
            .ok_or_else(|| VmError::Internal(format!("no field {name} {desc}")))?;
        let slot = self.class(owner).fields[i].slot;
        self.set_field(obj, slot, v)
    }
}
