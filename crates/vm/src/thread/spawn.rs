//! Starting and ending Java threads, and VM exit when the last non-daemon
//! thread is done.

use std::sync::Arc;

use super::{Blocked, Gil, Line, ThreadEntry, ThreadState};
use crate::value::{Ref, Value};
use crate::{Vm, VmError, VmResult};

/// Stack reserved for a started thread. A nested call from Rust into Java
/// was measured at under 8 KiB of stack, so `Options::max_nesting` (1024)
/// fits with a wide margin, as on the launcher's bigger main thread.
pub const THREAD_STACK_BYTES: usize = 64 << 20;
/// The `sysexits.h` status for an internal VM failure, as the launcher uses.
const EXIT_INTERNAL: i32 = 70;

const THREAD: &str = "java/lang/Thread";

impl Vm {
    /// `Thread.start`: runs `t.run()` on a new OS thread. The Java side has
    /// already checked that `t` was never started.
    pub fn start_thread(&mut self, t: Ref, daemon: bool) -> VmResult<()> {
        let mut entry = ThreadEntry::new(Some(t), daemon);
        entry.state = Some(ThreadState::default());
        let me = self.threads.insert(entry);
        let gil = Arc::clone(&self.gil);
        let spawned = std::thread::Builder::new()
            .stack_size(THREAD_STACK_BYTES)
            .spawn(move || thread_main(gil, me));
        match spawned {
            Ok(handle) => {
                // The new thread cannot look at its entry before this thread
                // gives the VM up, so the handle is in place in time.
                if let Some(e) = self.threads.get_mut(me) {
                    e.os = handle.thread().clone();
                }
                Ok(())
            }
            Err(_) => {
                self.threads.entries[me] = None;
                Err(VmError::exception(
                    "java/lang/OutOfMemoryError",
                    "unable to create new native thread",
                ))
            }
        }
    }

    /// Runs `Thread.runByVm` (run, the uncaught exception handler, then the
    /// end-of-thread bookkeeping in Java) for the running thread.
    fn run_java_thread(&mut self) -> VmResult<()> {
        let t = self
            .current_thread()
            .ok_or_else(|| VmError::Internal("started thread has no Thread".into()))?;
        self.call_thread_method(t, "runByVm")
    }

    fn call_thread_method(&mut self, t: Ref, name: &str) -> VmResult<()> {
        let class = self.load_class(THREAD)?;
        let m = self
            .find_in_class(class, name, "()V", false)
            .ok_or_else(|| VmError::Internal(format!("Thread.{name} is missing")))?;
        self.invoke(m, &[Value::Ref(Some(t))])?;
        Ok(())
    }

    /// Removes the running thread from the table and wakes threads waiting
    /// for the live thread count to change.
    fn end_thread(&mut self, me: usize) {
        if let Some(slot) = self.threads.entries.get_mut(me) {
            *slot = None;
        }
        let mut waiters = std::mem::take(&mut self.threads.exit_waiters);
        self.threads.unpark_all(&self.gil, &mut waiters);
    }

    /// What the main thread does after `main` returns or its uncaught
    /// exception was reported: marks its `Thread` terminated, waits for the
    /// other non-daemon threads, then runs the shutdown hooks.
    pub fn shutdown_after_main(&mut self) -> VmResult<()> {
        if let Some(t) = self.current_thread() {
            self.call_thread_method(t, "exitByVm")?;
        }
        self.await_non_daemon_threads();
        self.run_shutdown_hooks()
    }

    fn await_non_daemon_threads(&mut self) {
        let me = self.current;
        loop {
            let others = self
                .threads
                .entries
                .iter()
                .enumerate()
                .any(|(i, e)| i != me && e.as_ref().is_some_and(|e| !e.daemon));
            if !others {
                return;
            }
            self.threads.exit_waiters.push(me);
            self.park(Blocked::Waiting, None);
        }
    }

    /// Runs the hooks only if a program registered any: an untouched
    /// `java.lang.Shutdown` has none, and loading it would cost every exit.
    fn run_shutdown_hooks(&mut self) -> VmResult<()> {
        let Some(class) = self.loaded_class("java/lang/Shutdown") else {
            return Ok(());
        };
        let m = self
            .find_in_class(class, "runHooks", "()V", false)
            .ok_or_else(|| VmError::Internal("Shutdown.runHooks is missing".into()))?;
        self.invoke(m, &[])?;
        Ok(())
    }

    /// Ends the process from any thread: `System.exit` after the shutdown
    /// hooks have run, or `Runtime.halt`. Holding the VM, no other thread is
    /// running bytecode.
    pub fn exit_process(&mut self, code: i32) -> ! {
        let _ = platform::flush(platform::Stream::Out);
        let _ = platform::flush(platform::Stream::Err);
        // The status the launcher gives for an exit on the main thread.
        std::process::exit(i32::from(code as u8))
    }
}

/// The body of a started thread's OS thread. A panic (a VM bug) would drop
/// the VM with it and leave every other thread waiting for it forever, so
/// it ends the process instead.
fn thread_main(gil: Arc<Gil>, me: usize) {
    let body = std::panic::AssertUnwindSafe(|| run_thread(gil, me));
    if std::panic::catch_unwind(body).is_err() {
        eprintln!("lazyvm: internal error: a VM thread panicked");
        let _ = platform::flush(platform::Stream::Out);
        std::process::exit(EXIT_INTERNAL);
    }
}

fn run_thread(gil: Arc<Gil>, me: usize) {
    let mut vm = gil.acquire(me, Line::Woken);
    vm.current = me;
    vm.resume_state(me);
    match vm.run_java_thread() {
        Ok(()) => {}
        Err(VmError::Exit(code)) => vm.exit_process(code),
        // `runByVm` handles every exception; one escaping it, from what it does
        // after the handler, just ends the thread.
        Err(e) if e.is_catchable() => {}
        Err(e) => {
            eprintln!("lazyvm: internal error: {e}");
            vm.exit_process(EXIT_INTERNAL)
        }
    }
    vm.end_thread(me);
    gil.release(vm);
}
