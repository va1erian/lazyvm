//! `java.lang.Thread` and the monitor methods of `java.lang.Object`
//! (docs/design/threads.md).

use vm::thread::Blocked;
use vm::{Value, Vm, VmError, VmResult};

use crate::{long, obj, opt, ret_bool, ret_int, ret_ref, ret_void};

const THREAD: &str = "java/lang/Thread";
const OBJECT: &str = "java/lang/Object";
const LOCK_SUPPORT: &str = "java/util/concurrent/locks/LockSupport";

pub fn register(vm: &mut Vm) {
    vm.register_native(OBJECT, "notify", "()V", |vm, a| {
        vm.monitor_notify(obj(a, 0)?, false)?;
        ret_void()
    });
    vm.register_native(OBJECT, "notifyAll", "()V", |vm, a| {
        vm.monitor_notify(obj(a, 0)?, true)?;
        ret_void()
    });
    vm.register_native(OBJECT, "wait", "(J)V", wait);
    vm.register_native(THREAD, "sleep", "(J)V", sleep);
    vm.register_native(THREAD, "yield", "()V", |vm, _| {
        vm.yield_thread();
        ret_void()
    });
    vm.register_native(THREAD, "current0", "()Ljava/lang/Thread;", |vm, _| {
        ret_ref(vm.current_thread())
    });
    vm.register_native(THREAD, "attach0", "(Ljava/lang/Thread;)V", |vm, a| {
        vm.attach_current_thread(obj(a, 0)?);
        ret_void()
    });
    vm.register_native(THREAD, "start0", "(Z)V", |vm, a| {
        let daemon = crate::int(a, 1)? != 0;
        vm.start_thread(obj(a, 0)?, daemon)?;
        ret_void()
    });
    vm.register_native(THREAD, "interrupt0", "()V", |vm, a| {
        vm.interrupt(obj(a, 0)?);
        ret_void()
    });
    vm.register_native(THREAD, "isInterrupted0", "(Z)Z", |vm, a| {
        let clear = crate::int(a, 1)? != 0;
        ret_bool(vm.is_interrupted(obj(a, 0)?, clear))
    });
    vm.register_native(THREAD, "holdsLock", "(Ljava/lang/Object;)Z", |vm, a| {
        let o = opt(a, 0)?.ok_or_else(VmError::npe)?;
        ret_bool(vm.holds_lock(o))
    });
    vm.register_native(THREAD, "state0", "()I", |vm, a| {
        // The numbers index Thread.State from RUNNABLE; -1 is not alive.
        let state = match vm.blocked_state(obj(a, 0)?) {
            None => -1,
            Some(Blocked::No) => 0,
            Some(Blocked::Monitor) => 1,
            Some(Blocked::Waiting) => 2,
            Some(Blocked::TimedWaiting) => 3,
        };
        ret_int(state)
    });
    vm.register_native(THREAD, "liveThreads0", "()[Ljava/lang/Thread;", |vm, _| {
        let len = vm.live_threads().len();
        let size = i32::try_from(len).map_err(|_| VmError::OutOfMemory)?;
        let array = vm.new_array_of("Ljava/lang/Thread;", size)?;
        // Read after allocating, which can run the collector and let others in:
        // what no longer fits has just started, and what is gone leaves a null.
        for (i, t) in vm.live_threads().into_iter().take(len).enumerate() {
            vm.heap
                .array_mut(array)?
                .store(i, Value::Ref(Some(t)), false)?;
        }
        ret_ref(Some(array))
    });
    vm.register_native(
        THREAD,
        "stackTrace0",
        "()[Ljava/lang/StackTraceElement;",
        |vm, a| {
            let frames = vm.thread_backtrace(obj(a, 0)?);
            ret_ref(Some(crate::lang_throwable::stack_elements(vm, &frames)?))
        },
    );
    vm.register_native(LOCK_SUPPORT, "park0", "(J)V", |vm, a| {
        // Java passes 0 for no limit and a positive time otherwise.
        vm.park_thread(u64::try_from(long(a, 0)?).ok().filter(|n| *n > 0));
        ret_void()
    });
    vm.register_native(LOCK_SUPPORT, "unpark0", "(Ljava/lang/Thread;)V", |vm, a| {
        vm.unpark_thread(obj(a, 0)?);
        ret_void()
    });
}

fn negative_timeout() -> VmError {
    VmError::exception(
        "java/lang/IllegalArgumentException",
        "timeout value is negative",
    )
}

fn wait(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let this = obj(args, 0)?;
    let millis = long(args, 1)?;
    if millis < 0 {
        return Err(negative_timeout());
    }
    vm.monitor_wait(this, millis)?;
    ret_void()
}

fn sleep(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let millis = long(args, 0)?;
    if millis < 0 {
        return Err(negative_timeout());
    }
    // What was printed before a pause should be visible during it.
    let _ = platform::flush(platform::Stream::Out);
    vm.sleep(millis)?;
    ret_void()
}
