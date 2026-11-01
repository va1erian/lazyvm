//! The natives of `lazy.internal.Reactor` (docs/async.md). The reactor itself
//! is `platform::reactor`; these check arguments and move ids.

use platform::reactor;
use vm::heap::Array;
use vm::{Value, Vm, VmError, VmResult};

use crate::{long, obj, ret_int, ret_void};

const REACTOR: &str = "lazy/internal/Reactor";

pub fn register(vm: &mut Vm) {
    vm.register_native(REACTOR, "timer0", "(JJ)V", |_, a| {
        let nanos = u64::try_from(long(a, 1)?)
            .map_err(|_| VmError::exception("java/lang/IllegalArgumentException", "negative"))?;
        reactor::timer(long(a, 0)? as u64, nanos)
            .map_err(|e| VmError::exception("java/io/IOException", e.to_string()))?;
        ret_void()
    });
    vm.register_native(REACTOR, "cancel0", "(J)V", |_, a| {
        reactor::cancel(long(a, 0)? as u64);
        ret_void()
    });
    vm.register_native(REACTOR, "take0", "([J)I", take0);
    vm.register_native(REACTOR, "wake0", "()V", |_, _| {
        reactor::wake_taker();
        ret_void()
    });
    vm.register_native(REACTOR, "wakes0", "()J", |_, _| {
        Ok(Some(Value::Long(reactor::wakes() as i64)))
    });
}

fn take0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let array = obj(args, 0)?;
    let capacity = vm.heap.array(array)?.len();
    // An idle loop waits here for ever, so other threads run meanwhile.
    // `array` is an argument, rooted on the caller's stack.
    let ids = vm.blocking(|| reactor::take(capacity));
    let Array::Long(slots) = vm.heap.array_mut(array)? else {
        return Err(VmError::Internal("take0 needs a long[]".into()));
    };
    for (slot, id) in slots.iter_mut().zip(&ids) {
        *slot = *id as i64;
    }
    ret_int(ids.len() as i32)
}
