//! Moving values between the Java heap and `xui-bind`: op lists and
//! arguments are copied off the heap before any xui call, and results are
//! built on it afterwards, so no `Ref` is held across the loop.

use vm::heap::Array;
use vm::{Ref, Value, Vm, VmError, VmResult};
use xui_bind::{Arg, Obj, Ret, Tree};

use natives::obj;

fn verify(what: &str) -> VmError {
    VmError::verify(format!("lazy.ui needs {what}"))
}

fn class_name(vm: &Vm, r: Ref) -> VmResult<std::sync::Arc<str>> {
    Ok(vm.class(vm.heap.class_of(r)?).name.clone())
}

pub fn ints(vm: &Vm, v: Value) -> VmResult<Vec<i32>> {
    match vm.heap.array(obj(&[v], 0)?)? {
        Array::Int(a) => Ok(a.to_vec()),
        _ => Err(verify("an int[]")),
    }
}

fn bytes(a: &Array) -> Option<Vec<u8>> {
    match a {
        Array::Byte(b) => Some(b.iter().map(|&x| x as u8).collect()),
        _ => None,
    }
}

/// The elements of an `Object[]`, or `None` when `r` is not one.
fn refs(vm: &Vm, r: Ref) -> VmResult<Option<Vec<Option<Ref>>>> {
    Ok(match vm.heap.array(r) {
        Ok(Array::Ref(a)) => Some(a.to_vec()),
        _ => None,
    })
}

/// An op list: its `int[]` and its `Object[]` of strings and `int[]`s.
pub fn tree(vm: &Vm, ops: Value, objects: Value) -> VmResult<Tree> {
    let ops = ints(vm, ops)?;
    let list = refs(vm, obj(&[objects], 0)?)?.ok_or_else(|| verify("an Object[]"))?;
    let mut out = Vec::with_capacity(list.len());
    for r in list {
        let r = r.ok_or_else(|| verify("no null in the object table"))?;
        out.push(match vm.heap.array(r) {
            Ok(Array::Int(a)) => Obj::Ints(a.to_vec()),
            Ok(_) => return Err(verify("strings and int[]s in the object table")),
            Err(_) => Obj::Str(vm.string_arg(Value::Ref(Some(r)))?),
        });
    }
    Ok(Tree { ops, objects: out })
}

/// `call0`'s argument: null, a String, an int[], a byte[], a String[], an
/// `{alt, bytes}` picture, or an `{int[], Object[]}` op list.
pub fn arg(vm: &Vm, v: Value) -> VmResult<Arg> {
    let Value::Ref(Some(r)) = v else {
        return Ok(Arg::None);
    };
    if let Ok(a) = vm.heap.array(r) {
        if let Array::Int(i) = a {
            return Ok(Arg::Ints(i.to_vec()));
        }
        if let Some(b) = bytes(a) {
            return Ok(Arg::Bytes(b));
        }
        let list = refs(vm, r)?.ok_or_else(|| verify("a known argument"))?;
        return pair(vm, &list);
    }
    if &*class_name(vm, r)? == "java/lang/String" {
        return Ok(Arg::Str(vm.string_arg(v)?));
    }
    Err(verify("a known argument"))
}

fn pair(vm: &Vm, list: &[Option<Ref>]) -> VmResult<Arg> {
    if let [Some(a), Some(b)] = list {
        if let Ok(Array::Int(_)) = vm.heap.array(*a) {
            let (a, b) = (Value::Ref(Some(*a)), Value::Ref(Some(*b)));
            return Ok(Arg::Tree(tree(vm, a, b)?));
        }
        if let Some(bytes) = vm.heap.array(*b).ok().and_then(bytes) {
            return Ok(Arg::Image(vm.string_arg(Value::Ref(Some(*a)))?, bytes));
        }
    }
    let mut out = Vec::with_capacity(list.len());
    for r in list {
        out.push(vm.string_arg(Value::Ref(*r))?);
    }
    Ok(Arg::Strs(out))
}

fn int_array(vm: &mut Vm, v: &[i32]) -> VmResult<Ref> {
    let len = i32::try_from(v.len()).map_err(|_| VmError::OutOfMemory)?;
    let a = vm.new_array_of("I", len)?;
    if let Array::Int(dst) = vm.heap.array_mut(a)? {
        dst.copy_from_slice(v);
    }
    Ok(a)
}

/// `call0`'s result: null, a boxed-in-an-array int or double, a String, an
/// int[], a byte[], or an `Object[]` of a String and byte[]s.
pub fn ret(vm: &mut Vm, r: Ret) -> VmResult<Option<Value>> {
    let r = match r {
        Ret::None => None,
        Ret::Int(i) => Some(int_array(vm, &[i])?),
        Ret::Double(d) => {
            let a = vm.new_array_of("D", 1)?;
            if let Array::Double(dst) = vm.heap.array_mut(a)? {
                dst[0] = d;
            }
            Some(a)
        }
        Ret::Str(s) => Some(vm.new_string(&s)?),
        Ret::Ints(v) => Some(int_array(vm, &v)?),
        Ret::Bytes(b) => Some(natives::byte_array(vm, &b)?),
        Ret::Export(text, files) => Some(export(vm, &text, &files)?),
    };
    Ok(Some(Value::Ref(r)))
}

fn export(vm: &mut Vm, text: &str, files: &[Vec<u8>]) -> VmResult<Ref> {
    let len = i32::try_from(files.len() + 1).map_err(|_| VmError::OutOfMemory)?;
    let out = vm.new_array_of("Ljava/lang/Object;", len)?;
    vm.with_root(out, |vm| {
        let s = vm.new_string(text)?;
        vm.heap.array_mut(out)?.store(0, Value::Ref(Some(s)), false)?;
        for (i, f) in files.iter().enumerate() {
            let b = natives::byte_array(vm, f)?;
            vm.heap.array_mut(out)?.store(i + 1, Value::Ref(Some(b)), false)?;
        }
        Ok(out)
    })
}
