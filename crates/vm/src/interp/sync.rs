//! `monitorenter`/`monitorexit` and `synchronized` methods, with the
//! structured locking rules enforced (JVMS 2.11.10, 6.5).
//!
//! Each frame lists the monitors it holds (`Frame::monitors`). Popping a
//! frame, normally or by an exception, releases what is left on it.

use super::Flow;
use crate::class::MethodRef;
use crate::value::{Ref, Value};
use crate::{Vm, VmError, VmResult};

/// Monitors one frame may hold through `monitorenter`.
const MAX_FRAME_MONITORS: usize = 4096;

fn imse() -> VmError {
    VmError::bare("java/lang/IllegalMonitorStateException")
}

impl Vm {
    pub(super) fn op_monitor(&mut self, op: u8) -> VmResult<Flow> {
        if op == 0xc2 {
            // The object stays on the stack, so it is rooted while this
            // thread waits for the monitor.
            let obj = self.top()?.peek(0)?.reference()?.ok_or_else(VmError::npe)?;
            // javac nests blocks a handful deep; only hostile code goes on
            // entering in a loop, and it must not grow the list forever.
            if self.top()?.monitors.len() >= MAX_FRAME_MONITORS {
                return Err(VmError::bare("java/lang/StackOverflowError"));
            }
            self.monitor_enter(obj)?;
            let f = self.top()?;
            f.pop()?;
            f.monitors.push(obj);
            f.advance(1);
            return Ok(Flow::Next);
        }
        let f = self.top()?;
        let obj = f.pop_nonnull()?;
        // Only a monitor this frame entered may be exited here (rule 2).
        let at = f
            .monitors
            .iter()
            .rposition(|&m| m == obj)
            .ok_or_else(imse)?;
        f.monitors.remove(at);
        f.advance(1);
        self.monitor_exit(obj)?;
        Ok(Flow::Next)
    }

    /// The object a call to `m` must lock: `None` unless `m` is
    /// `synchronized`; then the receiver, or the class's mirror for a
    /// static method. `args` must be rooted (they are the call's arguments).
    pub(super) fn lock_for_call(&mut self, m: MethodRef, args: &[Value]) -> VmResult<Option<Ref>> {
        let method = self.method(m);
        if method.access & classfile::ACC_SYNCHRONIZED == 0 {
            return Ok(None);
        }
        let obj = if method.is_static() {
            self.mirror(m.class)?
        } else {
            args.first()
                .copied()
                .unwrap_or(Value::NULL)
                .reference()?
                .ok_or_else(VmError::npe)?
        };
        self.monitor_enter(obj)?;
        Ok(Some(obj))
    }

    /// Records the method's monitor on the frame just pushed.
    pub(super) fn hold_method_lock(&mut self, lock: Option<Ref>) -> VmResult<()> {
        if lock.is_some() {
            self.top()?.method_lock = lock;
        }
        Ok(())
    }

    /// Gives back a method monitor taken for a call that did not run.
    pub(super) fn drop_method_lock(&mut self, lock: Option<Ref>) {
        if let Some(obj) = lock {
            let _ = self.monitor_exit(obj);
        }
    }

    /// Rule 1 at a return instruction: every monitor the method entered
    /// with `monitorenter` must have been exited.
    pub(super) fn check_balanced(&mut self) -> VmResult<()> {
        if !self.top()?.monitors.is_empty() {
            return Err(imse());
        }
        Ok(())
    }

    /// Releases the monitors of a frame being popped, newest first.
    pub(super) fn release_frame_monitors(&mut self, f: &super::Frame) {
        for &obj in f.monitors.iter().rev().chain(&f.method_lock) {
            let _ = self.monitor_exit(obj);
        }
    }
}
