//! Boxing and unboxing between VM values and `java.lang` wrapper objects,
//! for reflection. Boxing calls the wrapper's `valueOf`, so its caches and
//! identity rules are the class library's; unboxing reads its `value` field.

use vm::{Ref, Value, Vm, VmError, VmResult};

/// The wrapper class of a primitive descriptor character.
pub fn wrapper(desc: u8) -> Option<&'static str> {
    Some(match desc {
        b'Z' => "java/lang/Boolean",
        b'B' => "java/lang/Byte",
        b'C' => "java/lang/Character",
        b'S' => "java/lang/Short",
        b'I' => "java/lang/Integer",
        b'J' => "java/lang/Long",
        b'F' => "java/lang/Float",
        b'D' => "java/lang/Double",
        _ => return None,
    })
}

/// A value of type `desc` (a field descriptor) as an object: references as
/// they are, primitives boxed. Calls Java (`valueOf`).
pub fn boxed(vm: &mut Vm, desc: &str, v: Value) -> VmResult<Option<Ref>> {
    let c = desc.as_bytes().first().copied().unwrap_or(b'V');
    let Some(w) = wrapper(c) else {
        return v.reference();
    };
    let class = vm.load_class(w)?;
    vm.initialize(class)?;
    let sig = format!("({})L{w};", c as char);
    let value_of = vm
        .find_in_class(class, "valueOf", &sig, false)
        .ok_or_else(|| VmError::Internal(format!("{w}.valueOf{sig} missing")))?;
    match vm.invoke(value_of, &[v])? {
        Some(Value::Ref(r)) => Ok(r),
        _ => Err(VmError::Internal("valueOf returned no object".into())),
    }
}

/// The VM value of `obj` for a parameter or field of type `desc`. Java has
/// already converted the object to the exact wrapper; anything else is an
/// `IllegalArgumentException` (and a null primitive too).
pub fn unboxed(vm: &Vm, desc: &str, obj: Option<Ref>) -> VmResult<Value> {
    let c = desc.as_bytes().first().copied().unwrap_or(b'V');
    let Some(w) = wrapper(c) else {
        if let Some(r) = obj {
            check_type(vm, desc, r)?;
        }
        return Ok(Value::Ref(obj));
    };
    let mismatch = || {
        VmError::exception(
            "java/lang/IllegalArgumentException",
            "argument type mismatch",
        )
    };
    let r = obj.ok_or_else(mismatch)?;
    if &*vm.class(vm.heap.class_of(r)?).name != w {
        return Err(mismatch());
    }
    let field = (c as char).to_string();
    vm.field_by_name(r, "value", &field)
}

/// A reference stored into a slot of type `desc` must be assignable to it.
fn check_type(vm: &Vm, desc: &str, r: Ref) -> VmResult<()> {
    let name = match desc.strip_prefix('L').and_then(|d| d.strip_suffix(';')) {
        Some(n) => n,
        None => desc,
    };
    let Some(target) = vm.loaded_class(name) else {
        // A type nobody has loaded cannot be the class of an existing object.
        return Err(VmError::exception(
            "java/lang/IllegalArgumentException",
            "argument type mismatch",
        ));
    };
    if vm.is_assignable(vm.heap.class_of(r)?, target) {
        Ok(())
    } else {
        Err(VmError::exception(
            "java/lang/IllegalArgumentException",
            "argument type mismatch",
        ))
    }
}
