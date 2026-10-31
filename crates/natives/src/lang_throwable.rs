//! `java.lang.Throwable`: recording and reading stack traces.

use vm::{Value, Vm, VmError, VmResult};

use crate::{obj, ret_ref, ret_void};

const THROWABLE: &str = "java/lang/Throwable";
const ELEMENT: &str = "java/lang/StackTraceElement";

pub fn register(vm: &mut Vm) {
    vm.register_native(THROWABLE, "fillInStackTrace0", "()V", fill_in);
    vm.register_native(
        THROWABLE,
        "getStackTrace0",
        "()[Ljava/lang/StackTraceElement;",
        get_stack_trace,
    );
}

fn fill_in(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let this = obj(args, 0)?;
    let frames = vm.capture_backtrace(this)?;
    // With no room for a trace the exception goes without: a throwable must
    // not fail because of how much it would like to report.
    let encoded = match vm.encode_backtrace(&frames) {
        Ok(array) => Some(array),
        Err(VmError::OutOfMemory) => None,
        Err(e) => return Err(e),
    };
    vm.set_field(this, vm.wk.throwable_backtrace, Value::Ref(encoded))?;
    ret_void()
}

/// Builds the elements without calling the constructor, so no Java code
/// runs while the new objects are only held here.
fn get_stack_trace(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let this = obj(args, 0)?;
    let class = vm.load_class(ELEMENT)?;
    vm.initialize(class)?;
    let frames = match vm.get_field(this, vm.wk.throwable_backtrace)?.reference()? {
        Some(bt) => vm.decode_backtrace(bt)?,
        None => Vec::new(),
    };
    let len = i32::try_from(frames.len()).map_err(|_| VmError::OutOfMemory)?;
    let array = vm.new_array_of("Ljava/lang/StackTraceElement;", len)?;
    for (i, &f) in frames.iter().enumerate() {
        let (class_name, method, file, line) = vm.describe_frame(f);
        let element = vm.new_object(class)?;
        let class_str = vm.new_string(&class_name)?;
        let method_str = vm.new_string(&method)?;
        let file_str = match file {
            Some(f) => Some(vm.new_string(&f)?),
            None => None,
        };
        set(vm, element, "declaringClass", Value::Ref(Some(class_str)))?;
        set(vm, element, "methodName", Value::Ref(Some(method_str)))?;
        set(vm, element, "fileName", Value::Ref(file_str))?;
        vm.set_field_by_name(element, "lineNumber", "I", Value::Int(line))?;
        vm.heap
            .array_mut(array)?
            .store(i, Value::Ref(Some(element)), false)?;
    }
    ret_ref(Some(array))
}

fn set(vm: &mut Vm, obj: vm::Ref, name: &str, v: Value) -> VmResult<()> {
    vm.set_field_by_name(obj, name, "Ljava/lang/String;", v)
}
