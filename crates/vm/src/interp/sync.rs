//! `monitorenter`/`monitorexit` and `synchronized` methods, with the
//! structured locking rules enforced (JVMS 2.11.10, 6.5).
//!
//! Each frame lists the monitors it holds (`Frame::monitors`). Popping a
//! frame, normally or by an exception, releases what is left on it.
//!
//! A call or return that holds no lock only tests a flag, so those tests stay
//! inline and the locking is kept out of line: inlining the locking here pushed
//! unrelated code out of the dispatch loop (docs/design/threads.md).

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
    #[inline(always)]
    pub(super) fn lock_for_call(&mut self, m: MethodRef, args: &[Value]) -> VmResult<Option<Ref>> {
        if self.method(m).access & classfile::ACC_SYNCHRONIZED == 0 {
            return Ok(None);
        }
        self.lock_synchronized(m, args).map(Some)
    }

    #[cold]
    fn lock_synchronized(&mut self, m: MethodRef, args: &[Value]) -> VmResult<Ref> {
        let obj = if self.method(m).is_static() {
            self.mirror(m.class)?
        } else {
            args.first()
                .copied()
                .unwrap_or(Value::NULL)
                .reference()?
                .ok_or_else(VmError::npe)?
        };
        self.monitor_enter(obj)?;
        Ok(obj)
    }

    /// Locks a `synchronized` method's monitor for the frame just pushed
    /// for `m`. The frame's locals root the receiver while this thread
    /// waits; if locking fails, the frame goes again.
    #[inline(always)]
    pub(super) fn lock_pushed_frame(&mut self, m: MethodRef) -> VmResult<()> {
        if self.method(m).access & classfile::ACC_SYNCHRONIZED == 0 {
            return Ok(());
        }
        self.lock_frame_method(m)
    }

    #[cold]
    fn lock_frame_method(&mut self, m: MethodRef) -> VmResult<()> {
        let receiver = self.top()?.locals.first().copied().unwrap_or(Value::NULL);
        match self.lock_synchronized(m, &[receiver]) {
            Ok(obj) => {
                self.top()?.method_lock = Some(obj);
                Ok(())
            }
            Err(e) => {
                self.pop_frame();
                Err(e)
            }
        }
    }

    /// Gives back a method monitor taken for a call that did not run.
    pub(super) fn drop_method_lock(&mut self, lock: Option<Ref>) {
        if let Some(obj) = lock {
            let _ = self.monitor_exit(obj);
        }
    }

    /// Rule 1 at a return instruction: every monitor the method entered
    /// with `monitorenter` must have been exited.
    #[inline(always)]
    pub(super) fn check_balanced(&mut self) -> VmResult<()> {
        if !self.top()?.monitors.is_empty() {
            return Err(imse());
        }
        Ok(())
    }

    /// Releases the monitors of a frame being popped, newest first.
    #[cold]
    pub(super) fn release_frame_monitors(&mut self, f: &super::Frame) {
        for &obj in f.monitors.iter().rev().chain(&f.method_lock) {
            let _ = self.monitor_exit(obj);
        }
    }
}
