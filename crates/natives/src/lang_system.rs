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
    vm.register_native(SYSTEM, "setIn0", "(Ljava/io/InputStream;)V", |vm, a| {
        set_stream(vm, "in", "Ljava/io/InputStream;", a)
    });
    vm.register_native(SYSTEM, "setOut0", "(Ljava/io/PrintStream;)V", |vm, a| {
        set_stream(vm, "out", "Ljava/io/PrintStream;", a)
    });
    vm.register_native(SYSTEM, "setErr0", "(Ljava/io/PrintStream;)V", |vm, a| {
        set_stream(vm, "err", "Ljava/io/PrintStream;", a)
    });
}

/// Stores to one of `System`'s final stream fields, which null may fill too.
fn set_stream(vm: &mut Vm, name: &str, desc: &str, args: &[Value]) -> VmResult<Option<Value>> {
    let class = vm.load_class(SYSTEM)?;
    vm.set_static_by_name(class, name, desc, Value::Ref(opt(args, 0)?))?;
    ret_void()
}

fn arraycopy(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let src = obj(args, 0)?;
    let src_pos = int(args, 1)?;
    let dst = obj(args, 2)?;
    let dst_pos = int(args, 3)?;
    let len = int(args, 4)?;
    let (src_class, dst_class) = (vm.heap.class_of(src)?, vm.heap.class_of(dst)?);
    // Java 8 gives these exceptions no message. The Javadoc's order: a type
    // mismatch is ArrayStoreException before any range is checked.
    let (
        ClassKind::Array {
            component: src_comp,
            ..
        },
        ClassKind::Array {
            component: dst_comp,
            ..
        },
    ) = (
        vm.class(src_class).kind.clone(),
        vm.class(dst_class).kind.clone(),
    )
    else {
        return Err(VmError::bare(ARRAY_STORE));
    };
    let prim = |c| matches!(vm.class(c).kind, ClassKind::Primitive(_));
    let primitive = prim(src_comp) || prim(dst_comp);
    if primitive && src_comp != dst_comp {
        return Err(VmError::bare(ARRAY_STORE));
    }
    let src_len = vm.heap.array(src)?.len();
    let dst_len = vm.heap.array(dst)?.len();
    if !in_range(src_pos, len, src_len) || !in_range(dst_pos, len, dst_len) {
        return Err(VmError::bare("java/lang/ArrayIndexOutOfBoundsException"));
    }
    let (s, d, n) = (src_pos as usize, dst_pos as usize, len as usize);
    if primitive {
        copy_primitive(vm, src, dst, s, d, n)?;
        return ret_void();
    }
    copy_refs(vm, src, dst, dst_comp, s, d, n)
}

fn in_range(pos: i32, len: i32, array_len: usize) -> bool {
    pos >= 0 && len >= 0 && i64::from(pos) + i64::from(len) <= array_len as i64
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
                return Err(VmError::bare(ARRAY_STORE));
            }
        }
        if let Array::Ref(b) = vm.heap.array_mut(dst)? {
            b[d + i] = item;
        }
    }
    ret_void()
}
