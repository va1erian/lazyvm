//! Rust functions compiled code calls for heap access. Each takes the heap,
//! a pointer to the lowest stack entry the instruction reads, and one
//! argument; it returns 1 when it did the instruction (result in that
//! entry), 0 when it left everything as it was for the interpreter to raise
//! the error (null, stale reference, wrong kind, index out of range). They
//! check what the fast loop checks (`fast.rs`, `fast_field.rs`) and must not
//! panic: they are called from machine code.

use crate::heap::Heap;
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
