//! The native method registry (docs/decisions.md D7, docs/design/natives.md).

use crate::class::MethodRef;
use crate::value::Value;
use crate::{Vm, VmError, VmResult};

/// A native method. `args` holds one value per argument, `this` first for
/// instance methods; `long`/`double` are one value each. Return `None` for
/// `void`. The arguments stay on the caller's operand stack during the call,
/// so they are rooted; any other `Ref` a native keeps across a call into Java
/// must go through `Vm::with_root`.
pub type NativeFn = fn(&mut Vm, &[Value]) -> VmResult<Option<Value>>;

fn key(class: &str, name: &str, descriptor: &str) -> String {
    format!("{class}.{name}{descriptor}")
}

impl Vm {
    pub fn register_native(&mut self, class: &str, name: &str, descriptor: &str, f: NativeFn) {
        self.natives.insert(key(class, name, descriptor), f);
    }

    /// The Rust function behind a native method, looked up once and cached.
    pub(crate) fn native_for(&mut self, m: MethodRef) -> VmResult<NativeFn> {
        if let Some(f) = self.method(m).native {
            return Ok(f);
        }
        let method = self.method(m);
        let k = key(&self.class(m.class).name, &method.name, &method.descriptor);
        let Some(&f) = self.natives.get(&k) else {
            let msg = format!(
                "{}.{}{}",
                self.class(m.class).java_name(),
                method.name,
                method.descriptor
            );
            return Err(VmError::exception("java/lang/UnsatisfiedLinkError", msg));
        };
        self.class_mut(m.class).methods[usize::from(m.index)].native = Some(f);
        Ok(f)
    }
}
