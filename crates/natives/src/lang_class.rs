//! `java.lang.Class`.

use vm::class::ClassKind;
use vm::{ClassId, Value, Vm, VmError, VmResult};

use crate::{arg, obj, opt, ret_bool, ret_int, ret_ref};

const CLASS: &str = "java/lang/Class";

pub fn register(vm: &mut Vm) {
    vm.register_native(CLASS, "getName", "()Ljava/lang/String;", get_name);
    vm.register_native(CLASS, "isInstance", "(Ljava/lang/Object;)Z", is_instance);
    vm.register_native(
        CLASS,
        "isAssignableFrom",
        "(Ljava/lang/Class;)Z",
        is_assignable_from,
    );
    vm.register_native(CLASS, "isInterface", "()Z", is_interface);
    vm.register_native(CLASS, "isArray", "()Z", is_array);
    vm.register_native(CLASS, "isPrimitive", "()Z", is_primitive);
    vm.register_native(
        CLASS,
        "getSuperclass",
        "()Ljava/lang/Class;",
        get_superclass,
    );
    vm.register_native(
        CLASS,
        "getComponentType",
        "()Ljava/lang/Class;",
        get_component_type,
    );
    vm.register_native(CLASS, "getModifiers", "()I", get_modifiers);
    vm.register_native(
        CLASS,
        "getPrimitiveClass",
        "(Ljava/lang/String;)Ljava/lang/Class;",
        get_primitive_class,
    );
    vm.register_native(
        CLASS,
        "forName0",
        "(Ljava/lang/String;Z)Ljava/lang/Class;",
        for_name0,
    );
    vm.register_native(CLASS, "desiredAssertionStatus", "()Z", |vm, args| {
        let c = this_class(vm, args)?;
        ret_bool(vm.options.assertions && !vm.class(c).from_boot)
    });
    vm.register_native(
        CLASS,
        "getEnumConstants0",
        "()[Ljava/lang/Object;",
        enum_constants,
    );
}

fn this_class(vm: &Vm, args: &[Value]) -> VmResult<ClassId> {
    vm.class_of_mirror(obj(args, 0)?)
}

fn get_name(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let name = vm.class(this_class(vm, args)?).java_name();
    ret_ref(Some(vm.new_string(&name)?))
}

fn is_instance(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let c = this_class(vm, args)?;
    let Some(o) = opt(args, 1)? else {
        return ret_bool(false);
    };
    let oc = vm.heap.class_of(o)?;
    ret_bool(vm.is_assignable(oc, c))
}

fn is_assignable_from(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let c = this_class(vm, args)?;
    let other = vm.class_of_mirror(obj(args, 1)?)?;
    ret_bool(vm.is_assignable(other, c))
}

fn is_interface(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    ret_bool(vm.class(this_class(vm, args)?).is_interface())
}

fn is_array(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    ret_bool(vm.class(this_class(vm, args)?).is_array())
}

fn is_primitive(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let c = this_class(vm, args)?;
    ret_bool(matches!(vm.class(c).kind, ClassKind::Primitive(_)))
}

fn get_superclass(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let c = vm.class(this_class(vm, args)?);
    if c.is_interface() {
        return ret_ref(None);
    }
    match c.super_class {
        Some(s) => ret_ref(Some(vm.mirror(s)?)),
        None => ret_ref(None),
    }
}

fn get_component_type(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    match vm.class(this_class(vm, args)?).kind {
        ClassKind::Array { component, .. } => ret_ref(Some(vm.mirror(component)?)),
        _ => ret_ref(None),
    }
}

/// Class modifiers as `Class.getModifiers` reports them: no `ACC_SUPER`.
fn get_modifiers(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    ret_int(i32::from(modifiers(vm, this_class(vm, args)?)))
}

const ACC_SUPER: u16 = 0x0020;
const VISIBILITY: u16 = 0x0007;
const FINAL_ABSTRACT: u16 = 0x0410;

/// `Class.getModifiers`: nested classes take their flags from
/// `InnerClasses`; arrays take the component's visibility, final and abstract;
/// `ACC_SUPER` is never reported.
fn modifiers(vm: &Vm, class: ClassId) -> u16 {
    let c = vm.class(class);
    match c.kind {
        ClassKind::Array { component, .. } => {
            (modifiers(vm, component) & VISIBILITY) | FINAL_ABSTRACT
        }
        ClassKind::Primitive(_) => c.access,
        ClassKind::Instance => {
            let flags = c
                .file
                .as_ref()
                .and_then(|f| f.inner_class_flags())
                .unwrap_or(c.access);
            flags & !ACC_SUPER
        }
    }
}

fn get_primitive_class(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let name = vm.string_arg(arg(args, 0)?)?;
    let desc = match name.as_str() {
        "boolean" => b'Z',
        "byte" => b'B',
        "char" => b'C',
        "short" => b'S',
        "int" => b'I',
        "long" => b'J',
        "float" => b'F',
        "double" => b'D',
        "void" => b'V',
        _ => {
            return Err(VmError::exception(
                "java/lang/IllegalArgumentException",
                name,
            ));
        }
    };
    let c = vm.primitive_class(desc)?;
    ret_ref(Some(vm.mirror(c)?))
}

/// Calls the enum class's own `values()`, initializing it first.
fn enum_constants(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let c = this_class(vm, args)?;
    let desc = format!("()[L{};", vm.class(c).name);
    let Some(index) = vm.class(c).find_method("values", &desc) else {
        return ret_ref(None);
    };
    vm.initialize(c)?;
    let values = vm.invoke(vm::MethodRef { class: c, index }, &[])?;
    match values {
        Some(Value::Ref(r)) => ret_ref(r),
        _ => ret_ref(None),
    }
}

/// `Class.forName`: binary names with dots; a missing class is
/// `ClassNotFoundException`, not `NoClassDefFoundError`.
fn for_name0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let name = vm.string_arg(arg(args, 0)?)?;
    let initialize = arg(args, 1)?.boolean()?;
    let not_found = || VmError::exception("java/lang/ClassNotFoundException", name.clone());
    if name.contains('/') || name.is_empty() {
        return Err(not_found());
    }
    let internal = name.replace('.', "/");
    let class = match vm.load_class(&internal) {
        Ok(c) => c,
        Err(VmError::Pending {
            class: "java/lang/NoClassDefFoundError",
            ..
        }) => return Err(not_found()),
        Err(e) => return Err(e),
    };
    if matches!(vm.class(class).kind, ClassKind::Primitive(_)) {
        return Err(not_found());
    }
    if initialize {
        vm.initialize(class)?;
    }
    ret_ref(Some(vm.mirror(class)?))
}
