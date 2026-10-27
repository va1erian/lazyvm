//! Reflective access: `Field.get0`/`set0`, `Method.invoke0`,
//! `Constructor.newInstance0`, `java.lang.reflect.Array`, and constant pool
//! reads for annotation parsing (docs/design/reflection.md).
//!
//! Java has already checked receivers, counts and converted arguments to the
//! exact parameter types; these natives check again and fail with
//! `IllegalArgumentException` rather than trusting it.

use classfile::Constant;
use classfile::descriptor::parse_method;
use vm::class::ClassKind;
use vm::heap::Array;
use vm::{ClassId, MethodRef, Ref, Value, Vm, VmError, VmResult};

use crate::boxing::{boxed, unboxed};
use crate::{int, obj, opt, ret_int, ret_ref, ret_void};

const IAE: &str = "java/lang/IllegalArgumentException";

pub fn register(vm: &mut Vm) {
    let field = "java/lang/reflect/Field";
    vm.register_native(
        field,
        "get0",
        "(Ljava/lang/reflect/Field;Ljava/lang/Object;)Ljava/lang/Object;",
        field_get,
    );
    vm.register_native(
        field,
        "set0",
        "(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V",
        field_set,
    );
    vm.register_native(
        "java/lang/reflect/Method",
        "invoke0",
        "(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
        method_invoke,
    );
    vm.register_native(
        "java/lang/reflect/Constructor",
        "newInstance0",
        "(Ljava/lang/reflect/Constructor;[Ljava/lang/Object;)Ljava/lang/Object;",
        constructor_new,
    );
    let array = "java/lang/reflect/Array";
    vm.register_native(
        array,
        "newArray",
        "(Ljava/lang/Class;I)Ljava/lang/Object;",
        array_new,
    );
    vm.register_native(array, "getLength", "(Ljava/lang/Object;)I", |vm, a| {
        let r = obj(a, 0)?;
        ret_int(not_array(vm, r).and_then(|_| Ok(vm.heap.array(r)?.len() as i32))?)
    });
    vm.register_native(
        array,
        "get0",
        "(Ljava/lang/Object;I)Ljava/lang/Object;",
        array_get,
    );
    vm.register_native(
        array,
        "set0",
        "(Ljava/lang/Object;ILjava/lang/Object;)V",
        array_set,
    );
    let vmc = "lazy/internal/VM";
    vm.register_native(
        vmc,
        "poolUtf8",
        "(Ljava/lang/Class;I)Ljava/lang/String;",
        |vm, a| {
            let (c, i) = (vm.class_of_mirror(obj(a, 0)?)?, pool_index(a)?);
            let units = pool(vm, c)?
                .pool
                .utf16(i)
                .map_err(|e| VmError::exception(IAE, e))?;
            ret_ref(Some(vm.new_string_utf16(&units)?))
        },
    );
    vm.register_native(
        vmc,
        "poolInt",
        "(Ljava/lang/Class;I)I",
        |vm, a| match constant(vm, a)? {
            Constant::Integer(v) => ret_int(v),
            _ => Err(VmError::exception(IAE, "not an Integer constant")),
        },
    );
    vm.register_native(
        vmc,
        "poolLong",
        "(Ljava/lang/Class;I)J",
        |vm, a| match constant(vm, a)? {
            Constant::Long(v) => Ok(Some(Value::Long(v))),
            _ => Err(VmError::exception(IAE, "not a Long constant")),
        },
    );
    vm.register_native(
        vmc,
        "poolFloat",
        "(Ljava/lang/Class;I)F",
        |vm, a| match constant(vm, a)? {
            Constant::Float(v) => Ok(Some(Value::Float(v))),
            _ => Err(VmError::exception(IAE, "not a Float constant")),
        },
    );
    vm.register_native(
        vmc,
        "poolDouble",
        "(Ljava/lang/Class;I)D",
        |vm, a| match constant(vm, a)? {
            Constant::Double(v) => Ok(Some(Value::Double(v))),
            _ => Err(VmError::exception(IAE, "not a Double constant")),
        },
    );
    vm.register_native(
        vmc,
        "annotationClass",
        "(Ljava/lang/Class;)Ljava/lang/Class;",
        |vm, a| {
            let annotation = vm.class_of_mirror(obj(a, 0)?)?;
            let generated = vm.annotation_class(annotation)?;
            ret_ref(Some(vm.mirror(generated)?))
        },
    );
}

fn pool_index(args: &[Value]) -> VmResult<u16> {
    u16::try_from(int(args, 1)?).map_err(|_| VmError::exception(IAE, "bad constant pool index"))
}

fn pool(vm: &Vm, c: ClassId) -> VmResult<std::sync::Arc<classfile::ClassFile>> {
    vm.class(c)
        .file
        .clone()
        .ok_or_else(|| VmError::exception(IAE, "class has no constant pool"))
}

fn constant(vm: &mut Vm, args: &[Value]) -> VmResult<Constant> {
    let (c, i) = (vm.class_of_mirror(obj(args, 0)?)?, pool_index(args)?);
    pool(vm, c)?
        .pool
        .get(i)
        .cloned()
        .ok_or_else(|| VmError::exception(IAE, "no such constant"))
}

/// The class and slot of a `Field`, `Method` or `Constructor` object.
fn member_of(vm: &Vm, member: Ref) -> VmResult<(ClassId, usize)> {
    let clazz = vm
        .field_by_name(member, "clazz", "Ljava/lang/Class;")?
        .reference()?;
    let class = vm.class_of_mirror(clazz.ok_or_else(VmError::npe)?)?;
    let slot = vm.field_by_name(member, "slot", "I")?.int()?;
    Ok((
        class,
        usize::try_from(slot).map_err(|_| VmError::exception(IAE, "bad slot"))?,
    ))
}

/// The receiver must be an instance of the member's class.
fn check_receiver(vm: &Vm, class: ClassId, receiver: Option<Ref>) -> VmResult<Ref> {
    let r = receiver.ok_or_else(VmError::npe)?;
    if vm.is_assignable(vm.heap.class_of(r)?, class) {
        Ok(r)
    } else {
        Err(VmError::exception(
            IAE,
            "object is not an instance of declaring class",
        ))
    }
}

fn field_get(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let (class, slot) = member_of(vm, obj(args, 0)?)?;
    let f = vm
        .class(class)
        .fields
        .get(slot)
        .ok_or_else(|| VmError::exception(IAE, "bad field slot"))?;
    let (desc, is_static, vslot) = (f.descriptor.clone(), f.is_static(), f.slot as usize);
    let v = if is_static {
        vm.initialize(class)?;
        vm.class(class).statics[vslot]
    } else {
        let r = check_receiver(vm, class, opt(args, 1)?)?;
        vm.get_field(r, vslot as u32)?
    };
    ret_ref(boxed(vm, &desc, v)?)
}

fn field_set(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let (class, slot) = member_of(vm, obj(args, 0)?)?;
    let f = vm
        .class(class)
        .fields
        .get(slot)
        .ok_or_else(|| VmError::exception(IAE, "bad field slot"))?;
    let (desc, is_static, vslot) = (f.descriptor.clone(), f.is_static(), f.slot as usize);
    if is_static {
        vm.initialize(class)?;
        let v = unboxed(vm, &desc, opt(args, 2)?)?;
        vm.class_mut(class).statics[vslot] = v;
    } else {
        let r = check_receiver(vm, class, opt(args, 1)?)?;
        let v = unboxed(vm, &desc, opt(args, 2)?)?;
        vm.set_field(r, vslot as u32, v)?;
    }
    ret_void()
}

/// The arguments for a call to `m`: each element of `array` unboxed to its
/// parameter type.
fn call_args(vm: &Vm, m: MethodRef, array: Option<Ref>, out: &mut Vec<Value>) -> VmResult<()> {
    let md = parse_method(&vm.method(m).descriptor).map_err(VmError::Internal)?;
    let items: Vec<Option<Ref>> = match array {
        None => Vec::new(),
        Some(a) => match vm.heap.array(a)? {
            Array::Ref(items) => items.to_vec(),
            _ => return Err(VmError::exception(IAE, "arguments must be an Object[]")),
        },
    };
    if items.len() != md.params.len() {
        return Err(VmError::exception(IAE, "wrong number of arguments"));
    }
    for (p, item) in md.params.iter().zip(items) {
        out.push(unboxed(vm, &p.descriptor(), item)?);
    }
    Ok(())
}

fn method_invoke(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let (class, slot) = member_of(vm, obj(args, 0)?)?;
    let index = u16::try_from(slot).map_err(|_| VmError::exception(IAE, "bad method slot"))?;
    let mut m = MethodRef { class, index };
    if vm.class(class).methods.get(slot).is_none() {
        return Err(VmError::exception(IAE, "bad method slot"));
    }
    let mut vals = Vec::new();
    if vm.method(m).is_static() {
        vm.initialize(class)?;
    } else {
        let r = check_receiver(vm, class, opt(args, 1)?)?;
        if !vm.method(m).is_private() {
            m = vm.select(vm.heap.class_of(r)?, m)?;
        }
        vals.push(Value::Ref(Some(r)));
    }
    call_args(vm, m, opt(args, 2)?, &mut vals)?;
    let ret = vm
        .method(m)
        .descriptor
        .rsplit(')')
        .next()
        .unwrap_or("V")
        .to_owned();
    match vm.invoke(m, &vals)? {
        None => ret_ref(None),
        Some(v) => ret_ref(boxed(vm, &ret, v)?),
    }
}

fn constructor_new(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let (class, slot) = member_of(vm, obj(args, 0)?)?;
    let index = u16::try_from(slot).map_err(|_| VmError::exception(IAE, "bad constructor slot"))?;
    let c = vm.class(class);
    if c.is_interface() || c.access & 0x0400 != 0 || !matches!(c.kind, ClassKind::Instance) {
        return Err(VmError::exception(
            "java/lang/InstantiationException",
            c.java_name(),
        ));
    }
    let m = MethodRef { class, index };
    if vm
        .class(class)
        .methods
        .get(slot)
        .is_none_or(|m| &*m.name != "<init>")
    {
        return Err(VmError::exception(IAE, "not a constructor"));
    }
    vm.initialize(class)?;
    let instance = vm.new_object(class)?;
    let mut vals = vec![Value::Ref(Some(instance))];
    call_args(vm, m, opt(args, 1)?, &mut vals)?;
    vm.invoke(m, &vals)?;
    ret_ref(Some(instance))
}

fn not_array(vm: &Vm, r: Ref) -> VmResult<(ClassId, u8)> {
    match vm.class(vm.heap.class_of(r)?).kind {
        ClassKind::Array { component, element } => Ok((component, element)),
        _ => Err(VmError::exception(IAE, "Argument is not an array")),
    }
}

fn array_new(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let comp = vm.class_of_mirror(obj(args, 0)?)?;
    let len = int(args, 1)?;
    let c = vm.class(comp);
    let name = match c.kind {
        ClassKind::Primitive(b'V') => return Err(VmError::exception(IAE, "void array")),
        ClassKind::Primitive(d) => format!("[{}", d as char),
        ClassKind::Array { .. } if c.name.bytes().take_while(|&b| b == b'[').count() >= 255 => {
            return Err(VmError::exception(IAE, "more than 255 dimensions"));
        }
        ClassKind::Array { .. } => format!("[{}", c.name),
        ClassKind::Instance => format!("[L{};", c.name),
    };
    let array_class = vm.load_class(&name)?;
    ret_ref(Some(vm.new_array(array_class, len)?))
}

fn index(vm: &Vm, array: Ref, i: i32) -> VmResult<usize> {
    let len = vm.heap.array(array)?.len();
    usize::try_from(i)
        .ok()
        .filter(|&x| x < len)
        .ok_or_else(|| VmError::bare("java/lang/ArrayIndexOutOfBoundsException"))
}

fn array_get(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let a = obj(args, 0)?;
    let (_, element) = not_array(vm, a)?;
    let i = index(vm, a, int(args, 1)?)?;
    let v = vm
        .heap
        .array(a)?
        .load(i)
        .ok_or_else(|| VmError::bare("java/lang/ArrayIndexOutOfBoundsException"))?;
    ret_ref(boxed(vm, &(element as char).to_string(), v)?)
}

fn array_set(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let a = obj(args, 0)?;
    let (component, element) = not_array(vm, a)?;
    let i = index(vm, a, int(args, 1)?)?;
    let value = opt(args, 2)?;
    let v = if matches!(element, b'L' | b'[') {
        if let Some(r) = value
            && !vm.is_assignable(vm.heap.class_of(r)?, component)
        {
            return Err(VmError::exception(IAE, "array element type mismatch"));
        }
        Value::Ref(value)
    } else {
        unboxed(vm, &(element as char).to_string(), value)
            .map_err(|_| VmError::exception(IAE, "argument type mismatch"))?
    };
    vm.heap.array_mut(a)?.store(i, v, element == b'Z')?;
    ret_void()
}
