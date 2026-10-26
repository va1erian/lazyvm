//! `java.lang.System`: `arraycopy`, clocks and the identity hash.

use vm::class::ClassKind;
use vm::heap::Array;
use vm::{Ref, Value, Vm, VmError, VmResult};

use crate::lang_object::identity_hash;
use crate::{int, obj, opt, ret_int, ret_void};

const SYSTEM: &str = "java/lang/System";
const ARRAY_STORE: &str = "java/lang/ArrayStoreException";

pub fn register(vm: &mut Vm) {
    vm.register_native(
        SYSTEM,
        "arraycopy",
        "(Ljava/lang/Object;ILjava/lang/Object;II)V",
        arraycopy,
    );
    vm.register_native(SYSTEM, "currentTimeMillis", "()J", |_, _| {
        Ok(Some(Value::Long(platform::current_time_millis())))
    });
    vm.register_native(SYSTEM, "nanoTime", "()J", |_, _| {
        Ok(Some(Value::Long(platform::nano_time())))
    });
    vm.register_native(
        SYSTEM,
        "identityHashCode",
        "(Ljava/lang/Object;)I",
        |_, args| ret_int(opt(args, 0)?.map_or(0, identity_hash)),
    );
}

fn arraycopy(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let src = obj(args, 0)?;
    let src_pos = int(args, 1)?;
    let dst = obj(args, 2)?;
    let dst_pos = int(args, 3)?;
    let len = int(args, 4)?;
    let (src_class, dst_class) = (vm.heap.class_of(src)?, vm.heap.class_of(dst)?);
    let (src_comp, dst_comp) = match (&vm.class(src_class).kind, &vm.class(dst_class).kind) {
        (ClassKind::Array { component: s, .. }, ClassKind::Array { component: d, .. }) => (*s, *d),
        (ClassKind::Array { .. }, _) => return Err(not_array(vm, "destination", dst_class)),
        _ => return Err(not_array(vm, "source", src_class)),
    };
    let src_len = vm.heap.array(src)?.len();
    let dst_len = vm.heap.array(dst)?.len();
    check_range("source", src_pos, len, src_len)?;
    check_range("destination", dst_pos, len, dst_len)?;
    let (s, d, n) = (src_pos as usize, dst_pos as usize, len as usize);
    let prim = |c| matches!(vm.class(c).kind, ClassKind::Primitive(_));
    if prim(src_comp) || prim(dst_comp) {
        if src_comp != dst_comp {
            let msg = format!(
                "arraycopy: type mismatch: can not copy {}[] into {}[]",
                vm.class(src_comp).java_name(),
                vm.class(dst_comp).java_name()
            );
            return Err(VmError::exception(ARRAY_STORE, msg));
        }
        copy_primitive(vm, src, dst, s, d, n)?;
        return ret_void();
    }
    copy_refs(vm, src, dst, dst_comp, s, d, n)
}

fn not_array(vm: &Vm, which: &str, class: vm::ClassId) -> VmError {
    let msg = format!(
        "arraycopy: {which} type {} is not an array",
        vm.class(class).java_name()
    );
    VmError::exception(ARRAY_STORE, msg)
}

fn check_range(which: &str, pos: i32, len: i32, array_len: usize) -> VmResult<()> {
    let oob = |msg: String| VmError::exception("java/lang/ArrayIndexOutOfBoundsException", msg);
    if pos < 0 {
        return Err(oob(format!(
            "arraycopy: {which} index {pos} out of bounds for length {array_len}"
        )));
    }
    if len < 0 {
        return Err(oob(format!("arraycopy: length {len} is negative")));
    }
    let end = i64::from(pos) + i64::from(len);
    if end > array_len as i64 {
        return Err(oob(format!(
            "arraycopy: last {which} index {end} out of bounds for length {array_len}"
        )));
    }
    Ok(())
}

/// Copies through a temporary, so overlapping ranges in one array work.
fn copy_primitive(vm: &mut Vm, src: Ref, dst: Ref, s: usize, d: usize, n: usize) -> VmResult<()> {
    macro_rules! copy {
        ($variant:ident) => {{
            let tmp = match vm.heap.array(src)? {
                Array::$variant(a) => a[s..s + n].to_vec(),
                _ => return Err(VmError::Internal("arraycopy kind".into())),
            };
            if let Array::$variant(b) = vm.heap.array_mut(dst)? {
                b[d..d + n].copy_from_slice(&tmp);
            }
        }};
    }
    match vm.heap.array(src)? {
        Array::Byte(_) => copy!(Byte),
        Array::Char(_) => copy!(Char),
        Array::Short(_) => copy!(Short),
        Array::Int(_) => copy!(Int),
        Array::Long(_) => copy!(Long),
        Array::Float(_) => copy!(Float),
        Array::Double(_) => copy!(Double),
        Array::Ref(_) => return Err(VmError::Internal("arraycopy kind".into())),
    }
    Ok(())
}

/// Reference copies check each element against the destination component
/// type; on a mismatch the elements before it stay copied (JLS/Javadoc).
fn copy_refs(
    vm: &mut Vm,
    src: Ref,
    dst: Ref,
    dst_comp: vm::ClassId,
    s: usize,
    d: usize,
    n: usize,
) -> VmResult<Option<Value>> {
    let items = match vm.heap.array(src)? {
        Array::Ref(a) => a[s..s + n].to_vec(),
        _ => return Err(VmError::Internal("arraycopy kind".into())),
    };
    for (i, item) in items.into_iter().enumerate() {
        if let Some(r) = item {
            let c = vm.heap.class_of(r)?;
            if !vm.is_assignable(c, dst_comp) {
                let msg = format!(
                    "arraycopy: element type mismatch: can not cast one of the elements of {}[] to the type of the destination array, {}",
                    vm.class(c).java_name(),
                    vm.class(dst_comp).java_name()
                );
                return Err(VmError::exception(ARRAY_STORE, msg));
            }
        }
        if let Array::Ref(b) = vm.heap.array_mut(dst)? {
            b[d + i] = item;
        }
    }
    ret_void()
}
