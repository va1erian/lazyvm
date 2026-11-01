//! The natives of `lazy.internal.Reactor` (docs/async.md). The reactor itself
//! is `platform::reactor`, its sockets `platform::net`; these check arguments
//! and move values.

use platform::net;
use platform::reactor::{self, Completion, Outcome};
use vm::heap::Array;
use vm::{Ref, Value, Vm, VmError, VmResult};

use crate::{arg, int, long, obj, ret_int, ret_void};

const REACTOR: &str = "lazy/internal/Reactor";

fn id(args: &[Value], i: usize) -> VmResult<u64> {
    Ok(long(args, i)? as u64)
}

fn io_failure(e: std::io::Error) -> VmError {
    VmError::exception("java/io/IOException", e.to_string())
}

fn port(args: &[Value], i: usize) -> VmResult<u16> {
    u16::try_from(int(args, i)?)
        .map_err(|_| VmError::exception("java/lang/IllegalArgumentException", "port"))
}

pub fn register(vm: &mut Vm) {
    vm.register_native(REACTOR, "timer0", "(JJ)V", |_, a| {
        let nanos = u64::try_from(long(a, 1)?)
            .map_err(|_| VmError::exception("java/lang/IllegalArgumentException", "negative"))?;
        reactor::timer(id(a, 0)?, nanos).map_err(io_failure)?;
        ret_void()
    });
    vm.register_native(REACTOR, "cancel0", "(J)V", |_, a| {
        reactor::cancel(id(a, 0)?);
        ret_void()
    });
    vm.register_native(REACTOR, "take0", "([J[J[Ljava/lang/Object;)I", take0);
    vm.register_native(REACTOR, "wake0", "()V", |_, _| {
        reactor::wake_taker();
        ret_void()
    });
    vm.register_native(REACTOR, "wakes0", "()J", |_, _| {
        Ok(Some(Value::Long(reactor::wakes() as i64)))
    });
    vm.register_native(REACTOR, "connect0", "(JLjava/lang/String;I)V", |vm, a| {
        let host = vm.string_arg(arg(a, 1)?)?;
        net::connect(id(a, 0)?, host, port(a, 2)?).map_err(io_failure)?;
        ret_void()
    });
    vm.register_native(REACTOR, "listen0", "(JLjava/lang/String;I)V", |vm, a| {
        let host = vm.string_arg(arg(a, 1)?)?;
        net::listen(id(a, 0)?, host, port(a, 2)?).map_err(io_failure)?;
        ret_void()
    });
    vm.register_native(REACTOR, "accept0", "(JJ)V", |_, a| {
        net::accept(id(a, 0)?, id(a, 1)?).map_err(io_failure)?;
        ret_void()
    });
    vm.register_native(REACTOR, "read0", "(JJZ)V", |_, a| {
        net::read(id(a, 0)?, id(a, 1)?, int(a, 2)? != 0).map_err(io_failure)?;
        ret_void()
    });
    vm.register_native(REACTOR, "write0", "(JJ[B)V", write0);
    vm.register_native(REACTOR, "close0", "(JJ)V", |_, a| {
        net::close(id(a, 0)?, id(a, 1)?).map_err(io_failure)?;
        ret_void()
    });
    vm.register_native(REACTOR, "closeListener0", "(JJ)V", |_, a| {
        net::close_listener(id(a, 0)?, id(a, 1)?).map_err(io_failure)?;
        ret_void()
    });
    vm.register_native(REACTOR, "abort0", "(J)V", |_, a| {
        // A failed wake leaves it queued for the reactor's next turn.
        let _ = net::abort(id(a, 0)?);
        ret_void()
    });
}

fn write0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let Array::Byte(bytes) = vm.heap.array(obj(args, 2)?)? else {
        return Err(VmError::Internal("write0 needs a byte[]".into()));
    };
    // Copied here, so the caller may reuse its array at once.
    let bytes = bytes.iter().map(|&b| b as u8).collect();
    net::write(id(args, 0)?, id(args, 1)?, bytes).map_err(io_failure)?;
    ret_void()
}

/// `(ids, handles, payloads)`: a payload is a `byte[]`, a `String` naming a
/// failure, or null. The arrays are arguments, rooted on the caller's stack.
fn take0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let (ids, handles, payloads) = (obj(args, 0)?, obj(args, 1)?, obj(args, 2)?);
    let capacity = vm.heap.array(ids)?.len();
    // An idle loop waits here for ever, so other threads run meanwhile.
    let mut taken: std::collections::VecDeque<Completion> =
        vm.blocking(|| reactor::take(capacity)).into();
    let mut n = 0;
    while let Some(c) = taken.pop_front() {
        // Making a payload can fail for lack of memory, and what was taken
        // must not be lost: the dispatcher goes on with what it has and takes
        // the rest again.
        let payload = match payload_of(vm, &c.outcome) {
            Ok(payload) => payload,
            Err(e) => {
                let mut rest = vec![c];
                rest.extend(taken);
                reactor::untake(rest);
                if n == 0 {
                    return Err(e);
                }
                break;
            }
        };
        set_long(vm, ids, n, c.id as i64)?;
        set_long(vm, handles, n, c.handle as i64)?;
        if let Array::Ref(slots) = vm.heap.array_mut(payloads)? {
            slots[n] = payload;
        }
        n += 1;
    }
    ret_int(n as i32)
}

fn set_long(vm: &mut Vm, array: Ref, i: usize, value: i64) -> VmResult<()> {
    if let Array::Long(slots) = vm.heap.array_mut(array)? {
        slots[i] = value;
    }
    Ok(())
}

fn payload_of(vm: &mut Vm, outcome: &Outcome) -> VmResult<Option<Ref>> {
    match outcome {
        Outcome::Done => Ok(None),
        Outcome::Bytes(bytes) => {
            let array = vm.new_array_of("B", bytes.len() as i32)?;
            if let Array::Byte(slots) = vm.heap.array_mut(array)? {
                for (slot, &b) in slots.iter_mut().zip(bytes) {
                    *slot = b as i8;
                }
            }
            Ok(Some(array))
        }
        Outcome::Failed(text) => vm.new_string(text).map(Some),
    }
}
