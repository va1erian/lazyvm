//! `lazy.internal.Atomics`: read-modify-write on one variable
//! (docs/design/atomics.md). Each operation is atomic because it finishes
//! without giving up the VM lock: nothing between its read and its write can
//! call `Vm::blocking` or run Java. Checks and class initialization, which
//! may do either, come before the read.
//!
//! A field is named by its `Field` object, never by a raw offset: the checks
//! below hold whatever Java passes, so bad input is an exception.

use vm::class::ClassKind;
use vm::heap::Array;
use vm::{ClassId, Ref, Value, Vm, VmError, VmResult};

use crate::reflect_access::{check_receiver, index as array_index, member_of};
use crate::{arg, int, long, obj, opt, ret_bool, ret_int, ret_ref};

const ATOMICS: &str = "lazy/internal/Atomics";
const IAE: &str = "java/lang/IllegalArgumentException";
const METHOD: &str = "java/lang/reflect/Method";

/// The variable an operation works on, with its checks already done.
#[derive(Clone, Copy)]
enum Cell {
    Instance(Ref, u32),
    Static(ClassId, usize),
    Element(Ref, usize),
}

impl Cell {
    fn read(self, vm: &Vm) -> VmResult<Value> {
        match self {
            Cell::Instance(r, slot) => vm.get_field(r, slot),
            Cell::Static(c, slot) => static_slot(vm.class(c).statics.get(slot)).copied(),
            Cell::Element(a, i) => vm
                .heap
                .array(a)?
                .load(i)
                .ok_or_else(|| VmError::bare("java/lang/ArrayIndexOutOfBoundsException")),
        }
    }

    fn write(self, vm: &mut Vm, v: Value) -> VmResult<()> {
        match self {
            Cell::Instance(r, slot) => vm.set_field(r, slot, v),
            Cell::Static(c, slot) => {
                *static_slot(vm.class_mut(c).statics.get_mut(slot))? = v;
                Ok(())
            }
            Cell::Element(a, i) => vm.heap.array_mut(a)?.store(i, v, false),
        }
    }
}

fn static_slot<T>(slot: Option<T>) -> VmResult<T> {
    slot.ok_or_else(|| VmError::exception(IAE, "bad field slot"))
}

/// What a field must hold for an operation to apply to it.
#[derive(Clone, Copy, PartialEq)]
enum Kind {
    Int,
    Long,
    Reference,
}

impl Kind {
    fn holds(self, descriptor: &str) -> bool {
        match self {
            Kind::Int => descriptor == "I",
            Kind::Long => descriptor == "J",
            Kind::Reference => descriptor.starts_with(['L', '[']),
        }
    }
}

/// The field a `(target, Field, ...)` call names. `args[1]` is the `Field`.
fn field_cell(vm: &mut Vm, args: &[Value], kind: Kind) -> VmResult<Cell> {
    let (class, index) = member_of(vm, obj(args, 1)?)?;
    let f = vm
        .class(class)
        .fields
        .get(index)
        .ok_or_else(|| VmError::exception(IAE, "bad field slot"))?;
    if !kind.holds(&f.descriptor) {
        return Err(VmError::exception(IAE, "wrong field type"));
    }
    if f.access & classfile::ACC_FINAL != 0 {
        return Err(VmError::exception(IAE, "final field"));
    }
    let (is_static, slot) = (f.is_static(), f.slot);
    if is_static {
        // May run Java; the arguments stay rooted on the caller's stack.
        vm.initialize(class)?;
        return Ok(Cell::Static(class, slot as usize));
    }
    Ok(Cell::Instance(
        check_receiver(vm, class, opt(args, 0)?)?,
        slot,
    ))
}

/// Only a value the field's declared type allows may go in a reference field.
fn check_field_value(vm: &mut Vm, args: &[Value], value: Option<Ref>) -> VmResult<()> {
    let Some(r) = value else { return Ok(()) };
    let (class, index) = member_of(vm, obj(args, 1)?)?;
    let f = vm.class(class).fields.get(index);
    let descriptor = static_slot(f)?.descriptor.clone();
    let name = descriptor
        .strip_prefix('L')
        .and_then(|d| d.strip_suffix(';'))
        .unwrap_or(&descriptor);
    let declared = vm.load_class(name)?;
    let from = vm.heap.class_of(r)?;
    if vm.is_assignable(from, declared) {
        return Ok(());
    }
    Err(VmError::exception(
        "java/lang/ClassCastException",
        vm.class(from).java_name(),
    ))
}

/// The element a `(array, i, ...)` call names, in an array of the right kind.
fn element_cell(vm: &Vm, args: &[Value], kind: Kind) -> VmResult<Cell> {
    let a = obj(args, 0)?;
    let right = match vm.heap.array(a)? {
        Array::Int(_) => kind == Kind::Int,
        Array::Long(_) => kind == Kind::Long,
        Array::Ref(_) => kind == Kind::Reference,
        _ => false,
    };
    if !right {
        return Err(VmError::exception(IAE, "wrong array type"));
    }
    Ok(Cell::Element(a, array_index(vm, a, int(args, 1)?)?))
}

/// `aastore`'s rule for a value going into a reference array.
fn check_element_value(vm: &Vm, array: Ref, value: Value) -> VmResult<()> {
    match vm.class(vm.heap.class_of(array)?).kind {
        ClassKind::Array { component, .. } => vm.check_array_store(component, value),
        _ => Err(VmError::exception(IAE, "not an array")),
    }
}

fn compare_and_set(vm: &mut Vm, cell: Cell, expect: Value, update: Value) -> VmResult<bool> {
    if cell.read(vm)? != expect {
        return Ok(false);
    }
    cell.write(vm, update)?;
    Ok(true)
}

fn get_and_set(vm: &mut Vm, cell: Cell, value: Value) -> VmResult<Value> {
    let old = cell.read(vm)?;
    cell.write(vm, value)?;
    Ok(old)
}

fn int_cas(vm: &mut Vm, cell: Cell, a: &[Value]) -> VmResult<Option<Value>> {
    let done = compare_and_set(vm, cell, Value::Int(int(a, 2)?), Value::Int(int(a, 3)?))?;
    ret_bool(done)
}

fn int_swap(vm: &mut Vm, cell: Cell, a: &[Value]) -> VmResult<Option<Value>> {
    Ok(Some(get_and_set(vm, cell, Value::Int(int(a, 2)?))?))
}

fn int_add(vm: &mut Vm, cell: Cell, a: &[Value]) -> VmResult<Option<Value>> {
    let old = cell.read(vm)?.int()?;
    cell.write(vm, Value::Int(old.wrapping_add(int(a, 2)?)))?;
    ret_int(old)
}

fn long_cas(vm: &mut Vm, cell: Cell, a: &[Value]) -> VmResult<Option<Value>> {
    let done = compare_and_set(vm, cell, Value::Long(long(a, 2)?), Value::Long(long(a, 3)?))?;
    ret_bool(done)
}

fn long_swap(vm: &mut Vm, cell: Cell, a: &[Value]) -> VmResult<Option<Value>> {
    Ok(Some(get_and_set(vm, cell, Value::Long(long(a, 2)?))?))
}

fn long_add(vm: &mut Vm, cell: Cell, a: &[Value]) -> VmResult<Option<Value>> {
    let old = cell.read(vm)?.long()?;
    cell.write(vm, Value::Long(old.wrapping_add(long(a, 2)?)))?;
    Ok(Some(Value::Long(old)))
}

fn ref_cas(vm: &mut Vm, cell: Cell, a: &[Value]) -> VmResult<Option<Value>> {
    let (expect, update) = (Value::Ref(opt(a, 2)?), Value::Ref(opt(a, 3)?));
    ret_bool(compare_and_set(vm, cell, expect, update)?)
}

fn ref_swap(vm: &mut Vm, cell: Cell, a: &[Value]) -> VmResult<Option<Value>> {
    Ok(Some(get_and_set(vm, cell, Value::Ref(opt(a, 2)?))?))
}

/// The class of the frame below the one that called the native, looking
/// through `Method.invoke`. A native has no frame of its own.
fn caller_class(vm: &mut Vm, _: &[Value]) -> VmResult<Option<Value>> {
    let mut frames = vm.frames.iter().rev().skip(1);
    let caller = frames
        .find(|f| &*vm.class(f.class).name != METHOD)
        .map(|f| f.class);
    match caller {
        Some(class) => ret_ref(Some(vm.mirror(class)?)),
        None => ret_ref(None),
    }
}

pub fn register(vm: &mut Vm) {
    const OBJ: &str = "Ljava/lang/Object;";
    const FIELD: &str = "Ljava/lang/reflect/Field;";
    let d = |rest: &str| format!("({OBJ}{FIELD}{rest}");
    let e = |array: &str, rest: &str| format!("({array}I{rest}");
    vm.register_native(ATOMICS, "compareAndSetInt", &d("II)Z"), |vm, a| {
        let c = field_cell(vm, a, Kind::Int)?;
        int_cas(vm, c, a)
    });
    vm.register_native(ATOMICS, "getAndSetInt", &d("I)I"), |vm, a| {
        let c = field_cell(vm, a, Kind::Int)?;
        int_swap(vm, c, a)
    });
    vm.register_native(ATOMICS, "getAndAddInt", &d("I)I"), |vm, a| {
        let c = field_cell(vm, a, Kind::Int)?;
        int_add(vm, c, a)
    });
    vm.register_native(ATOMICS, "compareAndSetLong", &d("JJ)Z"), |vm, a| {
        let c = field_cell(vm, a, Kind::Long)?;
        long_cas(vm, c, a)
    });
    vm.register_native(ATOMICS, "getAndSetLong", &d("J)J"), |vm, a| {
        let c = field_cell(vm, a, Kind::Long)?;
        long_swap(vm, c, a)
    });
    vm.register_native(ATOMICS, "getAndAddLong", &d("J)J"), |vm, a| {
        let c = field_cell(vm, a, Kind::Long)?;
        long_add(vm, c, a)
    });
    vm.register_native(
        ATOMICS,
        "compareAndSetRef",
        &d("Ljava/lang/Object;Ljava/lang/Object;)Z"),
        |vm, a| {
            let c = field_cell(vm, a, Kind::Reference)?;
            check_field_value(vm, a, opt(a, 3)?)?;
            ref_cas(vm, c, a)
        },
    );
    vm.register_native(
        ATOMICS,
        "getAndSetRef",
        &d("Ljava/lang/Object;)Ljava/lang/Object;"),
        |vm, a| {
            let c = field_cell(vm, a, Kind::Reference)?;
            check_field_value(vm, a, opt(a, 2)?)?;
            ref_swap(vm, c, a)
        },
    );
    vm.register_native(ATOMICS, "getRef", &d(")Ljava/lang/Object;"), |vm, a| {
        let c = field_cell(vm, a, Kind::Reference)?;
        Ok(Some(c.read(vm)?))
    });
    vm.register_native(ATOMICS, "compareAndSetInt", &e("[I", "II)Z"), |vm, a| {
        let c = element_cell(vm, a, Kind::Int)?;
        int_cas(vm, c, a)
    });
    vm.register_native(ATOMICS, "getAndSetInt", &e("[I", "I)I"), |vm, a| {
        let c = element_cell(vm, a, Kind::Int)?;
        int_swap(vm, c, a)
    });
    vm.register_native(ATOMICS, "getAndAddInt", &e("[I", "I)I"), |vm, a| {
        let c = element_cell(vm, a, Kind::Int)?;
        int_add(vm, c, a)
    });
    vm.register_native(ATOMICS, "compareAndSetLong", &e("[J", "JJ)Z"), |vm, a| {
        let c = element_cell(vm, a, Kind::Long)?;
        long_cas(vm, c, a)
    });
    vm.register_native(ATOMICS, "getAndSetLong", &e("[J", "J)J"), |vm, a| {
        let c = element_cell(vm, a, Kind::Long)?;
        long_swap(vm, c, a)
    });
    vm.register_native(ATOMICS, "getAndAddLong", &e("[J", "J)J"), |vm, a| {
        let c = element_cell(vm, a, Kind::Long)?;
        long_add(vm, c, a)
    });
    vm.register_native(
        ATOMICS,
        "compareAndSetRef",
        &e(
            "[Ljava/lang/Object;",
            "Ljava/lang/Object;Ljava/lang/Object;)Z",
        ),
        |vm, a| {
            let c = element_cell(vm, a, Kind::Reference)?;
            check_element_value(vm, obj(a, 0)?, arg(a, 3)?)?;
            ref_cas(vm, c, a)
        },
    );
    vm.register_native(
        ATOMICS,
        "getAndSetRef",
        &e(
            "[Ljava/lang/Object;",
            "Ljava/lang/Object;)Ljava/lang/Object;",
        ),
        |vm, a| {
            let c = element_cell(vm, a, Kind::Reference)?;
            check_element_value(vm, obj(a, 0)?, arg(a, 2)?)?;
            ref_swap(vm, c, a)
        },
    );
    vm.register_native(ATOMICS, "callerClass", "()Ljava/lang/Class;", caller_class);
}
