//! Constants from the pool, fields, allocation, type checks, and `athrow`.
//!
//! Rooting rule: resolution and class initialization can run Java code, so
//! each instruction resolves and initializes *before* popping its operands.

use classfile::Constant;

use super::Flow;
use crate::class::{ClassId, ClassKind, Resolved};
use crate::value::Value;
use crate::{Vm, VmError, VmResult};

impl Vm {
    pub(super) fn op_object(&mut self, op: u8) -> VmResult<Flow> {
        match op {
            0x12 => {
                let index = u16::from(self.top()?.u1(1)?);
                self.ldc(index, 2)?;
            }
            0x13 | 0x14 => {
                let index = self.top()?.u2(1)?;
                self.ldc(index, 3)?;
            }
            0xb2..=0xb5 => self.field_op(op)?,
            0xbb => self.op_new()?,
            0xbc => self.op_newarray()?,
            0xbd => self.op_anewarray()?,
            0xbe => {
                let f = self
                    .frames
                    .last_mut()
                    .ok_or_else(|| VmError::Internal("no frame".into()))?;
                let a = f.pop_nonnull()?;
                let len = self.heap.array(a)?.len();
                let f = self.top()?;
                f.push(Value::Int(len as i32))?;
                f.advance(1);
            }
            0xbf => {
                let ex = self.top()?.pop_nonnull()?;
                return Err(VmError::Throw(ex));
            }
            0xc0 | 0xc1 => self.type_check(op)?,
            0xc5 => self.op_multianewarray()?,
            _ => return Err(VmError::verify(format!("op_object cannot run {op:#04x}"))),
        }
        Ok(Flow::Next)
    }

    fn current_class(&self) -> VmResult<ClassId> {
        self.frames
            .last()
            .map(|f| f.class)
            .ok_or_else(|| VmError::Internal("no frame".into()))
    }

    fn ldc(&mut self, index: u16, len: usize) -> VmResult<()> {
        let class = self.current_class()?;
        let file = self
            .class(class)
            .file
            .clone()
            .ok_or_else(|| VmError::Internal("ldc in synthetic class".into()))?;
        let v = match file.pool.get(index) {
            Some(Constant::Integer(i)) => Value::Int(*i),
            Some(Constant::Float(x)) => Value::Float(*x),
            Some(Constant::Long(x)) => Value::Long(*x),
            Some(Constant::Double(x)) => Value::Double(*x),
            Some(Constant::String(s)) => {
                if let Some(Some(Resolved::String(r))) =
                    self.class(class).cp_cache.get(usize::from(index))
                {
                    Value::Ref(Some(*r))
                } else {
                    let units = file.pool.utf16(*s).map_err(VmError::verify)?;
                    let r = self.intern_utf16(&units)?;
                    if let Some(slot) = self.class_mut(class).cp_cache.get_mut(usize::from(index)) {
                        *slot = Some(Resolved::String(r));
                    }
                    Value::Ref(Some(r))
                }
            }
            Some(Constant::Class(_)) => {
                let c = self.resolve_class(class, index)?;
                Value::Ref(Some(self.mirror(c)?))
            }
            Some(Constant::MethodType(_) | Constant::MethodHandle { .. }) => {
                return Err(VmError::exception(
                    "java/lang/UnsupportedOperationException",
                    "ldc of a method handle or type",
                ));
            }
            other => return Err(VmError::verify(format!("ldc of {other:?}"))),
        };
        let f = self.top()?;
        f.push(v)?;
        f.advance(len);
        Ok(())
    }

    fn field_op(&mut self, op: u8) -> VmResult<()> {
        let index = self.top()?.u2(1)?;
        let from = self.current_class()?;
        let is_static = op <= 0xb3;
        let (owner, slot) = self.resolve_field(from, index, is_static)?;
        if is_static {
            self.initialize(owner)?;
        }
        let slot_us = slot as usize;
        match op {
            0xb2 => {
                let v = self
                    .class(owner)
                    .statics
                    .get(slot_us)
                    .copied()
                    .ok_or_else(|| VmError::Internal("static slot".into()))?;
                self.top()?.push(v)?;
            }
            0xb3 => {
                let v = self.top()?.pop()?;
                let s = self
                    .class_mut(owner)
                    .statics
                    .get_mut(slot_us)
                    .ok_or_else(|| VmError::Internal("static slot".into()))?;
                same_kind(*s, v)?;
                *s = v;
            }
            0xb4 => {
                let obj = self.top()?.pop_nonnull()?;
                let v = self.get_field(obj, slot)?;
                self.top()?.push(v)?;
            }
            _ => {
                let v = self.top()?.pop()?;
                let obj = self.top()?.pop_nonnull()?;
                same_kind(self.get_field(obj, slot)?, v)?;
                self.set_field(obj, slot, v)?;
            }
        }
        self.top()?.advance(3);
        Ok(())
    }

    /// Before an allocating instruction: collect if `bytes` would pass the
    /// limit, then fail with `OutOfMemoryError` if it still would. Safe here
    /// because every live reference is in a frame.
    fn ensure_room(&mut self, bytes: usize) -> VmResult<()> {
        if self.heap.would_exceed(bytes) {
            self.collect_garbage();
            if self.heap.would_exceed(bytes) {
                // Soft references are cleared before running out of memory.
                self.collect_garbage_all();
            }
            if self.heap.would_exceed(bytes) {
                return Err(VmError::OutOfMemory);
            }
        }
        Ok(())
    }

    fn op_new(&mut self) -> VmResult<()> {
        let index = self.top()?.u2(1)?;
        let from = self.current_class()?;
        let class = self.resolve_class(from, index)?;
        let c = self.class(class);
        if c.is_interface()
            || c.access & classfile::ACC_ABSTRACT != 0
            || !matches!(c.kind, ClassKind::Instance)
        {
            return Err(VmError::exception(
                "java/lang/InstantiationError",
                c.java_name(),
            ));
        }
        self.initialize(class)?;
        self.ensure_room(16 + self.class(class).instance_template.len() * 8)?;
        let r = self.new_object(class)?;
        let f = self.top()?;
        f.push(Value::Ref(Some(r)))?;
        f.advance(3);
        Ok(())
    }

    fn op_newarray(&mut self) -> VmResult<()> {
        let atype = self.top()?.u1(1)?;
        let elem = match atype {
            4 => "Z",
            5 => "C",
            6 => "F",
            7 => "D",
            8 => "B",
            9 => "S",
            10 => "I",
            11 => "J",
            _ => return Err(VmError::verify(format!("bad newarray type {atype}"))),
        };
        let class = self.load_class(&format!("[{elem}"))?;
        self.alloc_array(class, 2)
    }

    fn op_anewarray(&mut self) -> VmResult<()> {
        let index = self.top()?.u2(1)?;
        let from = self.current_class()?;
        let component = self.resolve_class(from, index)?;
        let class = self.load_class(&array_name(&self.class(component).name))?;
        self.alloc_array(class, 3)
    }

    fn alloc_array(&mut self, class: ClassId, len: usize) -> VmResult<()> {
        let count = self.top()?.peek(0)?.int()?;
        // A length past the VM limit is new_array's error, not a lack of room.
        let elements = usize::try_from(count)
            .ok()
            .filter(|&n| n <= crate::objects::MAX_ARRAY)
            .unwrap_or(0);
        let bytes = elements.saturating_mul(self.element_bytes(class));
        self.ensure_room(bytes)?;
        let count = self.top()?.pop_int()?;
        let r = self.new_array(class, count)?;
        let f = self.top()?;
        f.push(Value::Ref(Some(r)))?;
        f.advance(len);
        Ok(())
    }

    /// The element size of array class `class`.
    fn element_bytes(&self, class: ClassId) -> usize {
        match self.class(class).kind {
            ClassKind::Array { element, .. } => {
                crate::heap::array::ElemKind::from_descriptor(element).bytes()
            }
            _ => 8,
        }
    }

    /// Bytes a `multianewarray` will allocate: each level's arrays (header
    /// plus elements), stopping at the first zero count, which allocates
    /// nothing below it. Observed: the counts are checked left to right, a
    /// negative one (allocated or not) first, then a level that is allocated
    /// and past the VM limit (`new int[M][-1]` is the limit, `new int[0][-1]`
    /// and `new int[-1][M]` negative). Room is judged only after: when an
    /// earlier level does not fit, the oracle's heap may say OutOfMemory first.
    fn multi_array_bytes(&self, class: ClassId, counts: &[i32]) -> VmResult<usize> {
        let mut bytes = 0usize;
        let mut arrays = 1usize;
        let mut level = class;
        for &n in counts {
            let Ok(n) = usize::try_from(n) else {
                return Err(VmError::bare("java/lang/NegativeArraySizeException"));
            };
            if arrays == 0 {
                continue;
            }
            if n > crate::objects::MAX_ARRAY {
                return Err(crate::objects::array_limit());
            }
            bytes = bytes.saturating_add(
                arrays.saturating_mul(16 + n.saturating_mul(self.element_bytes(level))),
            );
            arrays = arrays.saturating_mul(n);
            if let ClassKind::Array { component, .. } = self.class(level).kind {
                level = component;
            }
        }
        Ok(bytes)
    }

    fn op_multianewarray(&mut self) -> VmResult<()> {
        let (index, dims) = {
            let f = self.top()?;
            (f.u2(1)?, usize::from(f.u1(3)?))
        };
        let from = self.current_class()?;
        let class = self.resolve_class(from, index)?;
        let depth = self
            .class(class)
            .name
            .bytes()
            .take_while(|&b| b == b'[')
            .count();
        if dims == 0 || dims > depth {
            return Err(VmError::verify("bad multianewarray dimensions"));
        }
        let mut counts = Vec::with_capacity(dims);
        for d in 0..dims {
            counts.push(self.top()?.peek(dims - 1 - d)?.int()?);
        }
        let bytes = self.multi_array_bytes(class, &counts)?;
        self.ensure_room(bytes)?;
        let r = self.multi_array(class, &counts)?;
        let f = self.top()?;
        f.stack.truncate(f.stack.len() - dims);
        f.push(Value::Ref(Some(r)))?;
        f.advance(4);
        Ok(())
    }

    /// Allocation never collects, so the partly built tree needs no rooting.
    fn multi_array(&mut self, class: ClassId, counts: &[i32]) -> VmResult<crate::Ref> {
        let r = self.new_array(class, counts[0])?;
        if counts.len() > 1 {
            let ClassKind::Array { component, .. } = self.class(class).kind else {
                return Err(VmError::Internal("multianewarray of a non-array".into()));
            };
            for i in 0..counts[0] as usize {
                let sub = self.multi_array(component, &counts[1..])?;
                self.heap
                    .array_mut(r)?
                    .store(i, Value::Ref(Some(sub)), false)?;
            }
        }
        Ok(r)
    }

    fn type_check(&mut self, op: u8) -> VmResult<()> {
        let index = self.top()?.u2(1)?;
        let from = self.current_class()?;
        let target = self.resolve_class(from, index)?;
        let v = self.top()?.peek(0)?.reference()?;
        let ok = match v {
            None => None,
            Some(r) => Some(self.is_assignable(self.heap.class_of(r)?, target)),
        };
        let f = self.top()?;
        if op == 0xc1 {
            f.pop()?;
            f.push(Value::Int(i32::from(ok == Some(true))))?;
        } else if ok == Some(false) {
            let r = v.ok_or_else(|| VmError::Internal("cast".into()))?;
            let msg = format!(
                "{} cannot be cast to {}",
                self.class(self.heap.class_of(r)?).java_name(),
                self.class(target).java_name()
            );
            return Err(VmError::exception("java/lang/ClassCastException", msg));
        }
        self.top()?.advance(3);
        Ok(())
    }
}

/// The array class name whose component is the class named `name`.
pub(crate) fn array_name(name: &str) -> String {
    if name.starts_with('[') {
        format!("[{name}")
    } else {
        format!("[L{name};")
    }
}

/// A field store must keep the field's value kind (a verifier would ensure it).
fn same_kind(old: Value, new: Value) -> VmResult<()> {
    if std::mem::discriminant(&old) == std::mem::discriminant(&new) {
        Ok(())
    } else {
        Err(VmError::verify(format!(
            "field of kind {old:?} stored {new:?}"
        )))
    }
}
