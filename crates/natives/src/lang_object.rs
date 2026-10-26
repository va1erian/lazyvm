//! `java.lang.Object` and `java.lang.String.intern`.

use vm::heap::{Body, Object};
use vm::{Value, Vm, VmError, VmResult};

use crate::{long, obj, ret_int, ret_ref, ret_void};

const OBJECT: &str = "java/lang/Object";

pub fn register(vm: &mut Vm) {
    vm.register_native(OBJECT, "getClass", "()Ljava/lang/Class;", get_class);
    vm.register_native(OBJECT, "hashCode", "()I", hash_code);
    vm.register_native(OBJECT, "clone", "()Ljava/lang/Object;", clone);
    vm.register_native(OBJECT, "notify", "()V", notify);
    vm.register_native(OBJECT, "notifyAll", "()V", notify);
    vm.register_native(OBJECT, "wait", "(J)V", wait);
    vm.register_native("java/lang/String", "intern", "()Ljava/lang/String;", intern);
}

/// The identity hash: stable for the object's life because the heap never
/// moves objects, and mixed so nearby slots do not get nearby hashes.
pub fn identity_hash(r: vm::Ref) -> i32 {
    let mut x = u64::from(r.index()).wrapping_mul(0x9E37_79B9_7F4A_7C15);
    x ^= x >> 29;
    (x as u32 & 0x7fff_ffff) as i32
}

fn get_class(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let this = obj(args, 0)?;
    let class = vm.heap.class_of(this)?;
    ret_ref(Some(vm.mirror(class)?))
}

fn hash_code(_vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    ret_int(identity_hash(obj(args, 0)?))
}

fn clone(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let this = obj(args, 0)?;
    let class = vm.heap.class_of(this)?;
    let cloneable = vm.load_class("java/lang/Cloneable")?;
    if !vm.is_assignable(class, cloneable) {
        let name = vm.class(class).java_name();
        return Err(VmError::exception(
            "java/lang/CloneNotSupportedException",
            name,
        ));
    }
    let body = match &vm.heap.get(this)?.body {
        Body::Fields(f) => Body::Fields(f.clone()),
        Body::Array(a) => Body::Array(a.duplicate()),
    };
    ret_ref(Some(vm.heap.alloc(Object { class, body })?))
}

/// Until threads exist (S6) nobody can be waiting, so notify is a no-op.
fn notify(_vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    obj(args, 0)?;
    ret_void()
}

/// With one thread, `wait(0)` could never return; a timed wait just sleeps.
fn wait(_vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    obj(args, 0)?;
    let millis = long(args, 1)?;
    if millis < 0 {
        return Err(VmError::exception(
            "java/lang/IllegalArgumentException",
            "timeout value is negative",
        ));
    }
    if millis == 0 {
        return Err(VmError::exception(
            "java/lang/IllegalMonitorStateException",
            "wait() with no other thread would block forever (threads arrive in S6)",
        ));
    }
    std::thread::sleep(std::time::Duration::from_millis(millis as u64));
    ret_void()
}

fn intern(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let this = obj(args, 0)?;
    ret_ref(Some(vm.intern(this)?))
}
