//! `java.lang.Object` and `java.lang.String.intern`; the monitor methods
//! are in `lang_thread`.

use vm::heap::{Body, Object};
use vm::{Value, Vm, VmError, VmResult};

use crate::{obj, ret_int, ret_ref};

const OBJECT: &str = "java/lang/Object";

pub fn register(vm: &mut Vm) {
    vm.register_native(OBJECT, "getClass", "()Ljava/lang/Class;", get_class);
    vm.register_native(OBJECT, "hashCode", "()I", hash_code);
    vm.register_native(OBJECT, "clone", "()Ljava/lang/Object;", clone);
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

fn intern(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let this = obj(args, 0)?;
    ret_ref(Some(vm.intern(this)?))
}
