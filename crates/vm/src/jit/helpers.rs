//! Rust functions compiled code calls for heap access. Each takes the heap,
//! a pointer to the lowest stack entry the instruction reads, and one
//! argument; it returns 1 when it did the instruction (result in that
//! entry), 0 when it left everything as it was for the interpreter to raise
//! the error (null, stale reference, wrong kind, index out of range). They
//! check what the fast loop checks (`fast.rs`, `fast_field.rs`) and must not
//! panic: they are called from machine code.

use crate::Vm;
use crate::class::{ClassId, ClassKind};
use crate::heap::Heap;
use crate::heap::array::ElemKind;
use crate::interp::kind_matches;
use crate::value::Value;

/// The entries the instruction reads, and the heap.
///
/// SAFETY: callers pass the VM's heap as `heap`, and `at` points at the
/// first of `n` initialised stack entries; compiled code passes them from
/// its context while the VM, and so both, are borrowed by `jit::run` alone.
unsafe fn entries<'a>(
    heap: *mut Heap,
    at: *mut Value,
    n: usize,
) -> (&'a mut Heap, &'a mut [Value]) {
    // SAFETY: as the function's doc says.
    unsafe { (&mut *heap, std::slice::from_raw_parts_mut(at, n)) }
}

/// `getfield`: [obj] -> [value].
pub(super) extern "C" fn get_field(heap: *mut Heap, at: *mut Value, slot: u32) -> u32 {
    // SAFETY: compiled code passes one stack entry (`frame::Gen::helper`).
    let (heap, e) = unsafe { entries(heap, at, 1) };
    let Value::Ref(Some(r)) = e[0] else { return 0 };
    match heap.fields(r).ok().and_then(|f| f.get(slot as usize)) {
        Some(&v) => {
            e[0] = v;
            1
        }
        None => 0,
    }
}

/// `putfield`: [obj, value] -> [].
pub(super) extern "C" fn put_field(heap: *mut Heap, at: *mut Value, slot: u32) -> u32 {
    // SAFETY: compiled code passes two stack entries.
    let (heap, e) = unsafe { entries(heap, at, 2) };
    let (Value::Ref(Some(r)), v) = (e[0], e[1]) else {
        return 0;
    };
    match heap
        .fields_mut(r)
        .ok()
        .and_then(|f| f.get_mut(slot as usize))
    {
        Some(field) if field.same_kind(v) => {
            *field = v;
            1
        }
        _ => 0,
    }
}

/// `*aload` (`kind` as `ops_array` numbers them): [array, index] -> [value].
pub(super) extern "C" fn array_load(heap: *mut Heap, at: *mut Value, kind: u32) -> u32 {
    // SAFETY: compiled code passes two stack entries.
    let (heap, e) = unsafe { entries(heap, at, 2) };
    let (Value::Ref(Some(r)), Value::Int(i)) = (e[0], e[1]) else {
        return 0;
    };
    let Ok(a) = heap.array(r) else { return 0 };
    if !kind_matches(kind as u8, a) {
        return 0;
    }
    match usize::try_from(i).ok().and_then(|i| a.load(i)) {
        Some(v) => {
            e[0] = v;
            1
        }
        None => 0,
    }
}

/// `*astore` but `aastore` and `bastore`: [array, index, value] -> [].
pub(super) extern "C" fn array_store(heap: *mut Heap, at: *mut Value, kind: u32) -> u32 {
    // SAFETY: compiled code passes three stack entries.
    let (heap, e) = unsafe { entries(heap, at, 3) };
    let (Value::Ref(Some(r)), Value::Int(i), v) = (e[0], e[1], e[2]) else {
        return 0;
    };
    let Ok(a) = heap.array_mut(r) else { return 0 };
    let Ok(i) = usize::try_from(i) else { return 0 };
    if !kind_matches(kind as u8, a) || i >= a.len() || a.store(i, v, false).is_err() {
        return 0;
    }
    1
}

/// `arraylength`: [array] -> [length].
pub(super) extern "C" fn array_length(heap: *mut Heap, at: *mut Value, _: u32) -> u32 {
    // SAFETY: compiled code passes one stack entry.
    let (heap, e) = unsafe { entries(heap, at, 1) };
    let Value::Ref(Some(r)) = e[0] else { return 0 };
    match heap.array(r) {
        Ok(a) => {
            e[0] = Value::Int(a.len() as i32);
            1
        }
        Err(_) => 0,
    }
}

/// The guard of an inlined virtual call: is the receiver of class `class`?
pub(super) extern "C" fn is_class(heap: *mut Heap, at: *mut Value, class: u32) -> u32 {
    // SAFETY: compiled code passes one stack entry.
    let (heap, e) = unsafe { entries(heap, at, 1) };
    let Value::Ref(Some(r)) = e[0] else { return 0 };
    u32::from(heap.class_of(r).is_ok_and(|c| c.0 == class))
}

/// Set beside the class in `type_check`'s argument for `instanceof`.
pub(super) const INSTANCEOF: u32 = 1 << 31;

/// `checkcast` to class `arg` ([obj] -> [obj]), or `instanceof` with
/// `INSTANCEOF` set ([obj] -> [int]). A failed cast is the interpreter's.
pub(super) extern "C" fn type_check(vm: *mut Vm, at: *mut Value, arg: u32) -> u32 {
    // SAFETY: compiled code passes the VM and one stack entry
    // (`frame::Gen::vm_helper`); the entry is read before the VM is borrowed.
    let v = unsafe { at.read() };
    // SAFETY: as above; nothing else uses the VM during the call.
    let vm = unsafe { &*vm };
    let fits = match v {
        Value::Ref(None) => None,
        Value::Ref(Some(r)) => match vm.heap.class_of(r) {
            Ok(c) => Some(vm.is_assignable(c, ClassId(arg & !INSTANCEOF))),
            Err(_) => return 0,
        },
        _ => return 0,
    };
    if arg & INSTANCEOF == 0 {
        return u32::from(fits != Some(false));
    }
    let result = Value::Int(i32::from(fits == Some(true)));
    // SAFETY: the same entry, written after the VM's last use.
    unsafe { at.write(result) };
    1
}

/// `aastore` (kind 4, with the store check) or `bastore` (kind 5, which
/// keeps one bit in a `boolean[]`): [array, index, value] -> [].
pub(super) extern "C" fn array_store_checked(vm: *mut Vm, at: *mut Value, kind: u32) -> u32 {
    // SAFETY: compiled code passes the VM and three stack entries, read
    // before the VM is borrowed; nothing else uses the VM during the call.
    let (e, vm) = unsafe { ([at.read(), at.add(1).read(), at.add(2).read()], &mut *vm) };
    let (Value::Ref(Some(r)), Value::Int(i), v) = (e[0], e[1], e[2]) else {
        return 0;
    };
    let Ok(class) = vm.heap.class_of(r) else {
        return 0;
    };
    let ClassKind::Array { component, element } = vm.class(class).kind else {
        return 0;
    };
    let (Ok(a), Ok(i)) = (vm.heap.array(r), usize::try_from(i)) else {
        return 0;
    };
    if !kind_matches(kind as u8, a) || i >= a.len() {
        return 0;
    }
    if kind == 4 && vm.check_array_store(component, v).is_err() {
        return 0;
    }
    let boolean = element == b'Z';
    u32::from(
        vm.heap
            .array_mut(r)
            .is_ok_and(|a| a.store(i, v, boolean).is_ok()),
    )
}

/// Whether compiled code may allocate `bytes`: no collection is due and the
/// heap has room. Else the interpreter does the instruction, collecting
/// first; helpers never collect, as inlined frames hold references in the
/// context's scratch area, where the collector does not look.
fn may_allocate(vm: &Vm, bytes: usize) -> bool {
    !vm.heap.wants_gc() && !vm.heap.would_exceed(bytes)
}

/// `new` of `class`, initialised and instantiable ([] -> [obj]; `at` is the
/// entry the reference goes to).
pub(super) extern "C" fn new_object(vm: *mut Vm, at: *mut Value, class: u32) -> u32 {
    // SAFETY: compiled code passes the VM, and `at` is one stack entry, in
    // room the frame has, written after the VM's last use.
    let vm = unsafe { &mut *vm };
    let class = ClassId(class);
    let bytes = 16 + vm.class(class).instance_template.len() * 8;
    if !may_allocate(vm, bytes) {
        return 0;
    }
    let Ok(r) = vm.new_object(class) else {
        return 0;
    };
    // SAFETY: as above.
    unsafe { at.write(Value::Ref(Some(r))) };
    1
}

/// `newarray` or `anewarray` of array class `class`: [count] -> [array].
pub(super) extern "C" fn new_array(vm: *mut Vm, at: *mut Value, class: u32) -> u32 {
    // SAFETY: compiled code passes the VM and one stack entry, read before
    // the VM is borrowed and written after its last use.
    let (count, vm) = unsafe { (at.read(), &mut *vm) };
    let Value::Int(n) = count else { return 0 };
    let class = ClassId(class);
    let ClassKind::Array { element, .. } = vm.class(class).kind else {
        return 0;
    };
    let Ok(len) = usize::try_from(n) else {
        return 0;
    };
    let bytes = len.saturating_mul(ElemKind::from_descriptor(element).bytes());
    if len > crate::objects::MAX_ARRAY || !may_allocate(vm, bytes) {
        return 0;
    }
    let Ok(r) = vm.new_array(class, n) else {
        return 0;
    };
    // SAFETY: as above.
    unsafe { at.write(Value::Ref(Some(r))) };
    1
}
