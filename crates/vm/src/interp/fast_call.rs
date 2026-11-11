//! Calls and returns for the fast loop (`fast.rs`): an invoke whose method
//! ref is already resolved, and a return to a caller in the same run.
//!
//! Like the rest of the fast loop, each function either does the whole
//! instruction or returns false with nothing changed, and `step` then runs
//! the instruction and raises whatever it raises. What is left to `step`:
//! unresolved refs, natives, `synchronized` methods, a static method whose
//! class is not initialised, `invokespecial` that needs the superclass
//! lookup (JVMS 6.5), a selection that fails, more than `INLINE_ARGS`
//! arguments, a frame holding monitors, and every malformed case.

use super::ops_invoke::INLINE_ARGS;
use crate::class::{InitState, MethodRef, Resolved};
use crate::value::Value;
use crate::{ClassId, Vm};

impl Vm {
    /// Runs the invoke or return at the top frame's `pc`, if it is one the
    /// fast loop handles. `base` is the frame count below which this run
    /// loop must not return (`Vm::run`).
    #[inline]
    pub(crate) fn fast_transfer(&mut self, op: u8, base: usize) -> bool {
        match op {
            0xac..=0xb1 => self.fast_return(op, base),
            0xb6..=0xb9 => self.fast_invoke(op),
            _ => false,
        }
    }

    fn fast_return(&mut self, op: u8, base: usize) -> bool {
        if self.frames.len() <= base + 1 {
            return false;
        }
        let [.., caller, callee] = &mut self.frames[..] else {
            return false;
        };
        if !callee.monitors.is_empty() || callee.method_lock.is_some() {
            return false;
        }
        let value = match op {
            0xb1 => None,
            _ => match (op, callee.stack.last().copied()) {
                (0xac, Some(v @ Value::Int(_)))
                | (0xad, Some(v @ Value::Long(_)))
                | (0xae, Some(v @ Value::Float(_)))
                | (0xaf, Some(v @ Value::Double(_)))
                | (0xb0, Some(v @ Value::Ref(_))) => Some(v),
                _ => return false,
            },
        };
        if value.is_some() && caller.stack.len() >= usize::from(caller.code.max_stack) {
            return false;
        }
        caller.pc = caller.next_pc;
        caller.stack.extend(value);
        self.pop_frame();
        self.prof_top();
        true
    }

    fn fast_invoke(&mut self, op: u8) -> bool {
        let Some(f) = self.frames.last() else {
            return false;
        };
        let Some(bytes) = f.code.bytecode.get(f.pc + 1..f.pc + 3) else {
            return false;
        };
        let index = usize::from(u16::from_be_bytes([bytes[0], bytes[1]]));
        let from = f.class;
        let Some(resolved) = self.cached_method(from, index, op) else {
            return false;
        };
        let Some(target) = self.fast_target(op, from, index, resolved) else {
            return false;
        };
        let len = if op == 0xb9 { 5 } else { 3 };
        self.fast_call(target, len)
    }

    /// The resolved method at `index`, if the pool entry's kind fits `op`
    /// (an `InterfaceMethodref` only for `invokeinterface`, `invokestatic` and
    /// `invokespecial`, a `Methodref` for all but `invokeinterface`).
    fn cached_method(&self, from: ClassId, index: usize, op: u8) -> Option<MethodRef> {
        let class = self.class(from);
        let Some(Resolved::Method(m)) = class.cp_cache.get(index).copied().flatten() else {
            return None;
        };
        let interface = match class.file.as_ref()?.pool.get(index as u16)? {
            classfile::Constant::MethodRef { .. } => false,
            classfile::Constant::InterfaceMethodRef { .. } => true,
            _ => return None,
        };
        let fits = match op {
            0xb6 => !interface,
            0xb9 => interface,
            // Interface `invokespecial` has a receiver check of its own.
            0xb7 => !interface,
            _ => true,
        };
        fits.then_some(m)
    }

    /// The method the call runs, for the cases the fast path takes.
    fn fast_target(
        &mut self,
        op: u8,
        from: ClassId,
        index: usize,
        resolved: MethodRef,
    ) -> Option<MethodRef> {
        let m = self.method(resolved);
        if (op == 0xb8) != m.is_static() {
            return None;
        }
        match op {
            0xb8 => {
                (self.class(resolved.class).state == InitState::Initialized).then_some(resolved)
            }
            // Constructors and calls within the class need no superclass lookup.
            0xb7 => {
                let plain = (resolved.class == from || &*m.name == "<init>") && !m.is_abstract();
                (plain && self.receiver_class(resolved).is_some()).then_some(resolved)
            }
            _ => {
                let rc = self.receiver_class(resolved)?;
                self.select_at(from, index, rc, resolved, op == 0xb9)
            }
        }
    }

    /// The class of the call's receiver, if it is a non-null reference.
    fn receiver_class(&self, m: MethodRef) -> Option<ClassId> {
        let depth = usize::from(self.method(m).arg_values).checked_sub(1)?;
        let f = self.frames.last()?;
        let at = f.stack.len().checked_sub(depth + 1)?;
        match f.stack[at] {
            Value::Ref(Some(r)) => self.heap.class_of(r).ok(),
            _ => None,
        }
    }

    /// `Vm::select` behind the call site's one-entry cache. An entry for
    /// `invokeinterface` is only stored after the receiver passed the
    /// interface check, so a hit needs no check.
    fn select_at(
        &mut self,
        from: ClassId,
        index: usize,
        rc: ClassId,
        resolved: MethodRef,
        interface: bool,
    ) -> Option<MethodRef> {
        if let Some(ways) = self.class(from).call_cache.get(index) {
            for &(seen, target) in ways.iter().flatten() {
                if seen == rc {
                    return Some(target);
                }
            }
        }
        if interface && !self.is_assignable(rc, resolved.class) {
            return None;
        }
        let target = self.select(rc, resolved).ok()?;
        if let Some(ways) = self.class_mut(from).call_cache.get_mut(index) {
            ways[1] = ways[0];
            ways[0] = Some((rc, target));
        }
        Some(target)
    }

    /// Pushes a frame for `target` with its arguments from the caller's
    /// stack; the caller resumes `len` bytes on when it returns.
    pub(crate) fn fast_call(&mut self, target: MethodRef, len: usize) -> bool {
        let m = self.method(target);
        if m.is_native() || m.access & classfile::ACC_SYNCHRONIZED != 0 || m.code.is_none() {
            return false;
        }
        let n = usize::from(m.arg_values);
        let Some(caller) = self.frames.last() else {
            return false;
        };
        let Some(at) = caller.stack.len().checked_sub(n) else {
            return false;
        };
        if n > INLINE_ARGS {
            return false;
        }
        let mut args = [Value::Top; INLINE_ARGS];
        args[..n].copy_from_slice(&caller.stack[at..]);
        if self.push_frame(target, &args[..n]).is_err() {
            return false;
        }
        let [.., caller, _] = &mut self.frames[..] else {
            return false;
        };
        caller.stack.truncate(at);
        caller.next_pc = caller.pc + len;
        true
    }
}
