//! Class initialization (JVMS 5.5).
//!
//! The VM lock stands in for each class's initialization lock: the state
//! is only read and changed while holding the VM, and a thread that finds
//! another thread initializing the class parks until that one is done.

use classfile::Constant;

use crate::class::{ClassId, InitState};
use crate::thread::Blocked;
use crate::value::Value;
use crate::{Vm, VmError, VmResult};

impl Vm {
    /// Initializes `class` if it is not already initialized or being
    /// initialized by this thread. May run Java code (`<clinit>`).
    pub fn initialize(&mut self, class: ClassId) -> VmResult<()> {
        let me = self.current;
        loop {
            match self.class(class).state {
                // Step 4: a recursive request by the initializing thread.
                InitState::Initialized => return Ok(()),
                InitState::Initializing(t) if t == me => return Ok(()),
                // Step 2: wait for the other thread, then look again. Java 8
                // reports such a thread as RUNNABLE (observed), hence `No`.
                InitState::Initializing(_) => {
                    self.threads.init_waiters.push(me);
                    self.park(Blocked::No, None);
                }
                // Step 5.
                InitState::Failed => {
                    let msg = format!(
                        "Could not initialize class {}",
                        self.class(class).java_name()
                    );
                    return Err(VmError::exception("java/lang/NoClassDefFoundError", msg));
                }
                InitState::Linked => break,
            }
        }
        self.class_mut(class).state = InitState::Initializing(me);
        let from_boot = self.class(class).from_boot;
        let result = self.initialize_steps(class);
        // Steps 10 and 12: record the outcome and wake the waiters. A library
        // class left erroneous by a passing shortage would be broken for good,
        // and on the oracle such classes are mostly initialized at startup, so
        // the next use tries again. Not JVMS 5.5, but only library code can tell.
        self.class_mut(class).state = match &result {
            Ok(()) => InitState::Initialized,
            Err(e) if from_boot && self.is_heap_shortage(e) => InitState::Linked,
            Err(_) => InitState::Failed,
        };
        let mut waiters = std::mem::take(&mut self.threads.init_waiters);
        self.threads.unpark_all(&self.gil, &mut waiters);
        result
    }

    fn initialize_steps(&mut self, class: ClassId) -> VmResult<()> {
        let c = self.class(class);
        if !c.is_interface() {
            // Step 7: the superclass, then every superinterface (direct or
            // not) that declares a default method, in the JVMS order.
            let mut supers: Vec<ClassId> = c.super_class.into_iter().collect();
            let mut ifaces = Vec::new();
            for &i in &c.interfaces.clone() {
                self.enumerate_superinterfaces(i, &mut ifaces);
            }
            supers.extend(ifaces.into_iter().filter(|&i| self.declares_defaults(i)));
            for s in supers {
                self.initialize(s)?;
            }
        }
        self.set_constant_values(class)?;
        let Some(index) = self.class(class).find_method("<clinit>", "()V") else {
            return Ok(());
        };
        let clinit = crate::MethodRef { class, index };
        match self.invoke(clinit, &[]) {
            Ok(_) => Ok(()),
            Err(VmError::Throw(ex)) => Err(self.wrap_in_initializer_error(ex)),
            Err(e) if e.is_catchable() => {
                let ex = self.materialize(e)?;
                Err(self.wrap_in_initializer_error(ex))
            }
            Err(e) => Err(e),
        }
    }

    /// JVMS 5.5 step 7: recurse on an interface's superinterfaces in order
    /// before the interface itself; each interface appears once.
    fn enumerate_superinterfaces(&self, iface: ClassId, out: &mut Vec<ClassId>) {
        if out.contains(&iface) {
            return;
        }
        for &s in &self.class(iface).interfaces {
            self.enumerate_superinterfaces(s, out);
        }
        if !out.contains(&iface) {
            out.push(iface);
        }
    }

    fn declares_defaults(&self, iface: ClassId) -> bool {
        self.class(iface)
            .methods
            .iter()
            .any(|m| !m.is_abstract() && !m.is_static())
    }

    /// Step 6 of JVMS 5.5 as javac relies on it: `ConstantValue` statics.
    fn set_constant_values(&mut self, class: ClassId) -> VmResult<()> {
        let Some(file) = self.class(class).file.clone() else {
            return Ok(());
        };
        let consts: Vec<(usize, u16)> = self
            .class(class)
            .fields
            .iter()
            .filter(|f| f.is_static())
            .filter_map(|f| f.constant_value.map(|cv| (f.slot as usize, cv)))
            .collect();
        for (slot, cv) in consts {
            let v = match file.pool.get(cv) {
                Some(Constant::Integer(i)) => Value::Int(*i),
                Some(Constant::Long(l)) => Value::Long(*l),
                Some(Constant::Float(x)) => Value::Float(*x),
                Some(Constant::Double(x)) => Value::Double(*x),
                Some(Constant::String(s)) => {
                    let units = file.pool.utf16(*s).map_err(VmError::verify)?;
                    Value::Ref(Some(self.intern_utf16(&units)?))
                }
                other => {
                    return Err(VmError::exception(
                        "java/lang/ClassFormatError",
                        format!("bad ConstantValue {other:?}"),
                    ));
                }
            };
            if let Some(s) = self.class_mut(class).statics.get_mut(slot) {
                *s = v;
            }
        }
        Ok(())
    }

    /// Step 11: an `Error` propagates as is; anything else is wrapped in
    /// `ExceptionInInitializerError`.
    fn wrap_in_initializer_error(&mut self, ex: crate::Ref) -> VmError {
        let is_error = match self.load_class("java/lang/Error") {
            Ok(error) => self
                .heap
                .class_of(ex)
                .is_ok_and(|c| self.is_subclass(c, error)),
            Err(e) => return e,
        };
        if is_error {
            return VmError::Throw(ex);
        }
        let wrapped = self.with_root(ex, |vm| -> VmResult<crate::Ref> {
            let class = vm.load_class("java/lang/ExceptionInInitializerError")?;
            vm.initialize(class)?;
            let obj = vm.new_object(class)?;
            let ctor = vm
                .find_in_class(class, "<init>", "(Ljava/lang/Throwable;)V", false)
                .ok_or_else(|| {
                    VmError::Internal("ExceptionInInitializerError(Throwable) missing".into())
                })?;
            vm.invoke(ctor, &[Value::Ref(Some(obj)), Value::Ref(Some(ex))])?;
            Ok(obj)
        });
        match wrapped {
            Ok(obj) => VmError::Throw(obj),
            Err(e) => e,
        }
    }
}
