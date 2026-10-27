//! Native methods of the class library (docs/design/natives.md).
//!
//! Each module registers the natives of one class or family. Natives check
//! their arguments again even when the Java side already did, because the
//! Java side is not trusted to be correct.

mod boxing;
mod char_props;
mod file_attrs;
mod files;
mod floats;
mod hexfloat;
mod internal;
mod lang_char;
mod lang_class;
mod lang_object;
mod lang_string_case;
mod lang_system;
mod lang_throwable;
mod math;
mod reflect;
mod reflect_access;
mod unicode;
mod unicode_decode;

use vm::{Ref, Value, Vm, VmError, VmResult};

/// Registers every native with `vm`.
pub fn register_all(vm: &mut Vm) {
    lang_object::register(vm);
    lang_class::register(vm);
    lang_system::register(vm);
    lang_throwable::register(vm);
    math::register(vm);
    floats::register(vm);
    lang_char::register(vm);
    lang_string_case::register(vm);
    internal::register(vm);
    files::register(vm);
    file_attrs::register(vm);
    reflect::register(vm);
    reflect_access::register(vm);
}

fn arg(args: &[Value], i: usize) -> VmResult<Value> {
    args.get(i)
        .copied()
        .ok_or_else(|| VmError::Internal(format!("native argument {i} missing")))
}

fn int(args: &[Value], i: usize) -> VmResult<i32> {
    arg(args, i)?.int()
}

fn long(args: &[Value], i: usize) -> VmResult<i64> {
    arg(args, i)?.long()
}

fn double(args: &[Value], i: usize) -> VmResult<f64> {
    arg(args, i)?.double()
}

fn float(args: &[Value], i: usize) -> VmResult<f32> {
    arg(args, i)?.float()
}

/// A reference argument that must not be null.
fn obj(args: &[Value], i: usize) -> VmResult<Ref> {
    arg(args, i)?.reference()?.ok_or_else(VmError::npe)
}

fn opt(args: &[Value], i: usize) -> VmResult<Option<Ref>> {
    arg(args, i)?.reference()
}

fn ret_int(v: i32) -> VmResult<Option<Value>> {
    Ok(Some(Value::Int(v)))
}

fn ret_bool(v: bool) -> VmResult<Option<Value>> {
    Ok(Some(Value::Int(i32::from(v))))
}

fn ret_ref(r: Option<Ref>) -> VmResult<Option<Value>> {
    Ok(Some(Value::Ref(r)))
}

fn ret_void() -> VmResult<Option<Value>> {
    Ok(None)
}
