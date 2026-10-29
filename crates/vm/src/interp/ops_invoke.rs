//! The invoke instructions (JVMS 6.5 invoke*, 5.4.3.3-5.4.6).

use super::Flow;
use crate::class::MethodRef;
use crate::value::Value;
use crate::{Vm, VmError, VmResult};

impl Vm {
    pub(super) fn op_invoke(&mut self, op: u8) -> VmResult<Flow> {
        let (from, index) = {
            let f = self.top()?;
            (f.class, f.u2(1)?)
        };
        match op {
            0xb6 => {
                // The constant's tag must fit the instruction, so the
                // resolution cache (keyed by index) is never reused across kinds.
                if self.is_interface_ref(from, index)? {
                    return Err(VmError::verify("invokevirtual of an InterfaceMethodref"));
                }
                let resolved = self.resolve_method(from, index, false)?;
                if self.method(resolved).is_static() {
                    return Err(incompatible("Expecting non-static method", resolved, self));
                }
                let target = self.select_for_receiver(resolved)?;
                self.call(target, 3)
            }
            0xb7 => {
                let interface = self.is_interface_ref(from, index)?;
                let resolved = self.resolve_method(from, index, interface)?;
                if self.method(resolved).is_static() {
                    return Err(incompatible("Expecting non-static method", resolved, self));
                }
                self.receiver(resolved)?;
                let target = self.select_special(from, resolved)?;
                self.call(target, 3)
            }
            0xb8 => {
                let interface = self.is_interface_ref(from, index)?;
                let resolved = self.resolve_method(from, index, interface)?;
                if !self.method(resolved).is_static() {
                    return Err(incompatible("Expected static method", resolved, self));
                }
                self.initialize(resolved.class)?;
                self.call(resolved, 3)
            }
            0xb9 => {
                if !self.is_interface_ref(from, index)? {
                    return Err(VmError::verify("invokeinterface of a Methodref"));
                }
                let resolved = self.resolve_method(from, index, true)?;
                if self.method(resolved).is_static() {
                    return Err(incompatible("Expecting non-static method", resolved, self));
                }
                let receiver = self.receiver(resolved)?;
                let rc = self.heap.class_of(receiver)?;
                if !self.is_assignable(rc, resolved.class) {
                    let msg = format!(
                        "Class {} does not implement the requested interface {}",
                        self.class(rc).java_name(),
                        self.class(resolved.class).java_name()
                    );
                    return Err(VmError::exception(
                        "java/lang/IncompatibleClassChangeError",
                        msg,
                    ));
                }
                let target = self.select(rc, resolved)?;
                self.call(target, 5)
            }
            _ => {
                // JVMS 6.5 invokedynamic: the two bytes after the index are zero.
                let f = self.top()?;
                if f.u1(3)? != 0 || f.u1(4)? != 0 {
                    return Err(VmError::verify("invokedynamic operand bytes must be zero"));
                }
                let site = (f.method.index, f.pc);
                let factory = self.link_lambda(from, index, site)?;
                self.call(factory, 5)
            }
        }
    }

    /// `invokestatic`/`invokespecial` may name either kind of method ref.
    fn is_interface_ref(&self, from: crate::ClassId, index: u16) -> VmResult<bool> {
        let file = self
            .class(from)
            .file
            .as_ref()
            .ok_or_else(|| VmError::Internal("no pool".into()))?;
        match file.pool.get(index) {
            Some(classfile::Constant::MethodRef { .. }) => Ok(false),
            Some(classfile::Constant::InterfaceMethodRef { .. }) => Ok(true),
            other => Err(VmError::verify(format!("invoke of {other:?}"))),
        }
    }

    /// The receiver (`this`) of a call to `m`, which must not be null.
    fn receiver(&mut self, m: MethodRef) -> VmResult<crate::Ref> {
        let depth = usize::from(self.method(m).arg_values) - 1;
        self.top()?
            .peek(depth)?
            .reference()?
            .ok_or_else(VmError::npe)
    }

    fn select_for_receiver(&mut self, resolved: MethodRef) -> VmResult<MethodRef> {
        let receiver = self.receiver(resolved)?;
        let rc = self.heap.class_of(receiver)?;
        self.select(rc, resolved)
    }

    /// JVMS 6.5 invokespecial: a superclass method named from a subclass is
    /// looked up from the current class's superclass (`ACC_SUPER` semantics).
    fn select_special(&mut self, from: crate::ClassId, resolved: MethodRef) -> VmResult<MethodRef> {
        let m = self.method(resolved);
        let is_init = &*m.name == "<init>";
        let from_class = self.class(from);
        let use_super = !is_init
            && !self.class(resolved.class).is_interface()
            && from != resolved.class
            && self.is_subclass(from, resolved.class)
            && from_class.access & classfile::ACC_SUPER != 0;
        if !use_super {
            if m.is_abstract() {
                return Err(VmError::exception(
                    "java/lang/AbstractMethodError",
                    m.name.to_string(),
                ));
            }
            return Ok(resolved);
        }
        let (name, desc) = (m.name.clone(), m.descriptor.clone());
        let start = from_class
            .super_class
            .ok_or_else(|| VmError::Internal("no superclass".into()))?;
        // JVMS 6.5 invokespecial: the first *instance* method up the chain.
        let mut cur = Some(start);
        while let Some(c) = cur {
            if let Some(index) = self.class(c).find_method(&name, &desc) {
                let found = MethodRef { class: c, index };
                if !self.method(found).is_static() {
                    if self.method(found).is_abstract() {
                        break;
                    }
                    return Ok(found);
                }
            }
            cur = self.class(c).super_class;
        }
        Err(VmError::exception(
            "java/lang/AbstractMethodError",
            name.to_string(),
        ))
    }

    /// Calls `m` with its arguments from the top of the operand stack.
    /// `len` is the invoke instruction's length.
    fn call(&mut self, m: MethodRef, len: usize) -> VmResult<Flow> {
        let n = usize::from(self.method(m).arg_values);
        let f = self.top()?;
        if f.stack.len() < n {
            return Err(VmError::verify("too few arguments on the stack"));
        }
        f.next_pc = f.pc + len;
        let at = f.stack.len() - n;
        // The arguments are still on this frame's stack, so they stay rooted
        // while this thread waits for a `synchronized` method's monitor.
        let args: Vec<Value> = f.stack[at..].to_vec();
        if self.method(m).is_native() {
            let native = self.native_for(m)?;
            let lock = self.lock_for_call(m, &args)?;
            // The arguments stay on this frame's stack during the call, so they are rooted.
            let result = native(self, &args);
            self.drop_method_lock(lock);
            let result = result?;
            let ret = self.method(m).ret;
            let f = self.top()?;
            f.stack.truncate(at);
            match (result, ret) {
                (None, b'V') => {}
                (Some(v), r) if r != b'V' => f.push(v)?,
                _ => {
                    return Err(VmError::Internal(format!(
                        "native {} returned the wrong arity",
                        self.method(m).name
                    )));
                }
            }
            f.pc = f.next_pc;
            return Ok(Flow::Poll);
        }
        let lock = self.lock_for_call(m, &args)?;
        self.top()?.stack.truncate(at);
        if let Err(e) = self.push_frame(m, &args) {
            self.drop_method_lock(lock);
            return Err(e);
        }
        self.hold_method_lock(lock)?;
        Ok(Flow::Poll)
    }
}

fn incompatible(what: &str, m: MethodRef, vm: &Vm) -> VmError {
    let msg = format!(
        "{what} {}.{}{}",
        vm.class(m.class).java_name(),
        vm.method(m).name,
        vm.method(m).descriptor
    );
    VmError::exception("java/lang/IncompatibleClassChangeError", msg)
}
