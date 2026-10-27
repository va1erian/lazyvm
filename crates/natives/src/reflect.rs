//! `Class` reflection natives: members, interfaces, signatures and raw
//! annotations (docs/design/reflection.md). Member objects are built by
//! filling their fields directly, so no Java code runs while they are only
//! held here and nothing needs rooting.

use classfile::Member;
use classfile::descriptor::{FieldType, parse_method};
use vm::{ClassId, Ref, Value, Vm, VmError, VmResult};

use crate::{arg, obj, ret_ref};

const CLASS: &str = "java/lang/Class";
const ACC_PUBLIC: u16 = 0x0001;

pub fn register(vm: &mut Vm) {
    vm.register_native(
        CLASS,
        "getDeclaredFields0",
        "(Z)[Ljava/lang/reflect/Field;",
        fields,
    );
    vm.register_native(
        CLASS,
        "getDeclaredMethods0",
        "(Z)[Ljava/lang/reflect/Method;",
        |vm, a| members(vm, a, false),
    );
    vm.register_native(
        CLASS,
        "getDeclaredConstructors0",
        "(Z)[Ljava/lang/reflect/Constructor;",
        |vm, a| members(vm, a, true),
    );
    vm.register_native(CLASS, "getInterfaces0", "()[Ljava/lang/Class;", interfaces);
    vm.register_native(
        CLASS,
        "getGenericSignature0",
        "()Ljava/lang/String;",
        |vm, a| {
            let c = vm.class_of_mirror(obj(a, 0)?)?;
            let sig = vm.class(c).file.as_ref().and_then(|f| f.signature.clone());
            opt_string(vm, sig.as_deref())
        },
    );
    vm.register_native(CLASS, "getRawAnnotations", "()[B", |vm, a| {
        let c = vm.class_of_mirror(obj(a, 0)?)?;
        let data = vm
            .class(c)
            .file
            .as_ref()
            .and_then(|f| raw(&f.attributes, "RuntimeVisibleAnnotations"));
        opt_bytes(vm, data)
    });
    vm.register_native(
        CLASS,
        "getDeclaringClass0",
        "()Ljava/lang/Class;",
        declaring_class,
    );
}

/// The mirror of the type named by a field descriptor.
pub fn type_mirror(vm: &mut Vm, t: &FieldType) -> VmResult<Ref> {
    let class = match t {
        FieldType::Object(name) => vm.load_class(name)?,
        FieldType::Array(_) => vm.load_class(&t.descriptor())?,
        p => vm.primitive_class(p.descriptor().as_bytes()[0])?,
    };
    vm.mirror(class)
}

fn class_array(vm: &mut Vm, items: &[Ref]) -> VmResult<Ref> {
    let len = i32::try_from(items.len()).map_err(|_| VmError::OutOfMemory)?;
    let array = vm.new_array_of("Ljava/lang/Class;", len)?;
    store_all(vm, array, items)?;
    Ok(array)
}

fn store_all(vm: &mut Vm, array: Ref, items: &[Ref]) -> VmResult<()> {
    for (i, r) in items.iter().enumerate() {
        vm.heap
            .array_mut(array)?
            .store(i, Value::Ref(Some(*r)), false)?;
    }
    Ok(())
}

fn raw(attrs: &[classfile::RawAttribute], name: &str) -> Option<Vec<u8>> {
    attrs
        .iter()
        .find(|a| &*a.name == name)
        .map(|a| a.data.to_vec())
}

fn opt_string(vm: &mut Vm, s: Option<&str>) -> VmResult<Option<Value>> {
    match s {
        Some(s) => ret_ref(Some(vm.new_string(s)?)),
        None => ret_ref(None),
    }
}

fn opt_bytes(vm: &mut Vm, data: Option<Vec<u8>>) -> VmResult<Option<Value>> {
    let Some(data) = data else {
        return ret_ref(None);
    };
    Ok(Some(Value::Ref(Some(byte_array(vm, &data)?))))
}

pub fn byte_array(vm: &mut Vm, data: &[u8]) -> VmResult<Ref> {
    let len = i32::try_from(data.len()).map_err(|_| VmError::OutOfMemory)?;
    let array = vm.new_array_of("B", len)?;
    if let vm::heap::Array::Byte(a) = vm.heap.array_mut(array)? {
        for (d, s) in a.iter_mut().zip(data) {
            *d = *s as i8;
        }
    }
    Ok(array)
}

/// A new instance of a reflection class, with fields set by name.
struct Builder {
    obj: Ref,
}

impl Builder {
    fn new(vm: &mut Vm, class: ClassId) -> VmResult<Builder> {
        Ok(Builder {
            obj: vm.new_object(class)?,
        })
    }

    fn set(&self, vm: &mut Vm, name: &str, desc: &str, v: Value) -> VmResult<()> {
        vm.set_field_by_name(self.obj, name, desc, v)
    }

    fn string(&self, vm: &mut Vm, name: &str, s: Option<&str>) -> VmResult<()> {
        let v = match s {
            Some(s) => Value::Ref(Some(vm.new_string(s)?)),
            None => Value::NULL,
        };
        self.set(vm, name, "Ljava/lang/String;", v)
    }

    fn bytes(&self, vm: &mut Vm, name: &str, data: Option<Vec<u8>>) -> VmResult<()> {
        let v = match data {
            Some(d) => Value::Ref(Some(byte_array(vm, &d)?)),
            None => Value::NULL,
        };
        self.set(vm, name, "[B", v)
    }
}

fn reflect_class(vm: &mut Vm, name: &str) -> VmResult<ClassId> {
    let c = vm.load_class(name)?;
    vm.initialize(c)?;
    Ok(c)
}

fn fields(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let this = obj(args, 0)?;
    let public_only = arg(args, 1)?.boolean()?;
    let field_class = reflect_class(vm, "java/lang/reflect/Field")?;
    let c = vm.class_of_mirror(this)?;
    let Some(file) = vm.class(c).file.clone() else {
        return ret_ref(Some(vm.new_array_of("Ljava/lang/reflect/Field;", 0)?));
    };
    let mut out = Vec::new();
    for (slot, f) in file.fields.iter().enumerate() {
        if public_only && f.access & ACC_PUBLIC == 0 {
            continue;
        }
        let ty = classfile::descriptor::parse_field(&f.descriptor).map_err(VmError::Internal)?;
        let b = Builder::new(vm, field_class)?;
        b.set(vm, "clazz", "Ljava/lang/Class;", Value::Ref(Some(this)))?;
        b.string(vm, "name", Some(&f.name))?;
        let t = type_mirror(vm, &ty)?;
        b.set(vm, "type", "Ljava/lang/Class;", Value::Ref(Some(t)))?;
        b.set(vm, "modifiers", "I", Value::Int(i32::from(f.access)))?;
        b.set(vm, "slot", "I", Value::Int(slot as i32))?;
        b.string(vm, "signature", f.signature.as_deref())?;
        b.bytes(
            vm,
            "annotations",
            raw(&f.attributes, "RuntimeVisibleAnnotations"),
        )?;
        out.push(b.obj);
    }
    let array = vm.new_array_of("Ljava/lang/reflect/Field;", out.len() as i32)?;
    store_all(vm, array, &out)?;
    ret_ref(Some(array))
}

/// Methods (`constructors` false, no `<init>`/`<clinit>`) or constructors.
fn members(vm: &mut Vm, args: &[Value], constructors: bool) -> VmResult<Option<Value>> {
    let this = obj(args, 0)?;
    let public_only = arg(args, 1)?.boolean()?;
    let (kind, array_desc) = if constructors {
        (
            "java/lang/reflect/Constructor",
            "Ljava/lang/reflect/Constructor;",
        )
    } else {
        ("java/lang/reflect/Method", "Ljava/lang/reflect/Method;")
    };
    let member_class = reflect_class(vm, kind)?;
    let c = vm.class_of_mirror(this)?;
    let Some(file) = vm.class(c).file.clone() else {
        return ret_ref(Some(vm.new_array_of(array_desc, 0)?));
    };
    let mut out = Vec::new();
    for (slot, m) in file.methods.iter().enumerate() {
        let is_init = &*m.name == "<init>";
        if is_init != constructors
            || &*m.name == "<clinit>"
            || (public_only && m.access & ACC_PUBLIC == 0)
        {
            continue;
        }
        out.push(member(
            vm,
            member_class,
            this,
            &file.pool,
            m,
            slot,
            constructors,
        )?);
    }
    let array = vm.new_array_of(array_desc, out.len() as i32)?;
    store_all(vm, array, &out)?;
    ret_ref(Some(array))
}

fn member(
    vm: &mut Vm,
    class: ClassId,
    owner: Ref,
    pool: &classfile::ConstantPool,
    m: &Member,
    slot: usize,
    constructor: bool,
) -> VmResult<Ref> {
    let md = parse_method(&m.descriptor).map_err(VmError::Internal)?;
    let b = Builder::new(vm, class)?;
    b.set(vm, "clazz", "Ljava/lang/Class;", Value::Ref(Some(owner)))?;
    let mut params = Vec::with_capacity(md.params.len());
    for p in &md.params {
        params.push(type_mirror(vm, p)?);
    }
    let params = class_array(vm, &params)?;
    b.set(
        vm,
        "parameterTypes",
        "[Ljava/lang/Class;",
        Value::Ref(Some(params)),
    )?;
    let mut thrown = Vec::with_capacity(m.exceptions.len());
    for &e in &m.exceptions {
        let name = pool.class_name(e).map_err(VmError::verify)?.clone();
        let c = vm.load_class(&name)?;
        thrown.push(vm.mirror(c)?);
    }
    let thrown = class_array(vm, &thrown)?;
    b.set(
        vm,
        "exceptionTypes",
        "[Ljava/lang/Class;",
        Value::Ref(Some(thrown)),
    )?;
    b.set(vm, "modifiers", "I", Value::Int(i32::from(m.access)))?;
    b.set(vm, "slot", "I", Value::Int(slot as i32))?;
    b.string(vm, "signature", m.signature.as_deref())?;
    b.bytes(
        vm,
        "annotations",
        raw(&m.attributes, "RuntimeVisibleAnnotations"),
    )?;
    b.bytes(
        vm,
        "parameterAnnotations",
        raw(&m.attributes, "RuntimeVisibleParameterAnnotations"),
    )?;
    if !constructor {
        b.string(vm, "name", Some(&m.name))?;
        let ret = match &md.ret {
            Some(t) => type_mirror(vm, t)?,
            None => {
                let v = vm.primitive_class(b'V')?;
                vm.mirror(v)?
            }
        };
        b.set(vm, "returnType", "Ljava/lang/Class;", Value::Ref(Some(ret)))?;
        b.bytes(
            vm,
            "annotationDefault",
            raw(&m.attributes, "AnnotationDefault"),
        )?;
    }
    Ok(b.obj)
}

fn interfaces(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let c = vm.class_of_mirror(obj(args, 0)?)?;
    let ids = vm.class(c).interfaces.clone();
    let mut mirrors = Vec::with_capacity(ids.len());
    for i in ids {
        mirrors.push(vm.mirror(i)?);
    }
    ret_ref(Some(class_array(vm, &mirrors)?))
}

fn declaring_class(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let c = vm.class_of_mirror(obj(args, 0)?)?;
    let Some(file) = vm.class(c).file.clone() else {
        return ret_ref(None);
    };
    let Some(outer) = file.outer_class() else {
        return ret_ref(None);
    };
    let outer = vm.load_class(&outer)?;
    ret_ref(Some(vm.mirror(outer)?))
}
