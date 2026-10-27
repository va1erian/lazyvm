//! `lazy.internal.VM`, the console streams and `Thread.sleep`.

use platform::Stream;
use vm::heap::Array;
use vm::{Value, Vm, VmError, VmResult};

use crate::{arg, int, long, obj, ret_int, ret_ref, ret_void};

const VM_CLASS: &str = "lazy/internal/VM";
const OUT: &str = "lazy/internal/ConsoleOutputStream";
const IN: &str = "lazy/internal/ConsoleInputStream";

pub fn register(vm: &mut Vm) {
    vm.register_native(
        VM_CLASS,
        "getProperty",
        "(Ljava/lang/String;)Ljava/lang/String;",
        get_property,
    );
    vm.register_native(VM_CLASS, "exit", "(I)V", |_, a| {
        Err(VmError::Exit(int(a, 0)?))
    });
    vm.register_native(VM_CLASS, "gc", "()V", |vm, _| {
        // Safe: a native's caller frames hold every live reference, and
        // Rust code that called into Java has rooted its own (`with_root`).
        vm.collect_garbage();
        ret_void()
    });
    vm.register_native(VM_CLASS, "freeMemory", "()J", |vm, _| {
        Ok(Some(Value::Long(
            vm.heap.limit().saturating_sub(vm.heap.live_bytes()) as i64,
        )))
    });
    vm.register_native(VM_CLASS, "totalMemory", "()J", |vm, _| {
        Ok(Some(Value::Long(vm.heap.limit() as i64)))
    });
    vm.register_native(VM_CLASS, "maxMemory", "()J", |vm, _| {
        Ok(Some(Value::Long(vm.heap.limit() as i64)))
    });
    vm.register_native(VM_CLASS, "availableProcessors", "()I", |_, _| {
        ret_int(platform::available_processors())
    });
    vm.register_native(
        VM_CLASS,
        "getResource",
        "(Ljava/lang/String;)[B",
        get_resource,
    );
    vm.register_native(VM_CLASS, "isBootClass", "(Ljava/lang/Class;)Z", |vm, a| {
        let class = vm.class_of_mirror(obj(a, 0)?)?;
        crate::ret_bool(vm.class(class).from_boot)
    });
    vm.register_native(OUT, "write0", "(I[BII)V", write0);
    vm.register_native(OUT, "flush0", "(I)V", |_, a| {
        let stream = stream(int(a, 0)?)?;
        platform::flush(stream).map_err(io_error)?;
        ret_void()
    });
    vm.register_native(IN, "read0", "(I[BII)I", read0);
    vm.register_native(IN, "available0", "(I)I", |_, _| ret_int(0));
    vm.register_native("java/lang/Thread", "sleep", "(J)V", sleep);
}

fn get_property(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let key = vm.string_arg(arg(args, 0)?)?;
    match vm.properties.get(&key).cloned() {
        Some(v) => ret_ref(Some(vm.new_string_utf16(&v)?)),
        None => ret_ref(None),
    }
}

fn get_resource(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let name = vm.string_arg(arg(args, 0)?)?;
    let Some(bytes) = vm.classpath.find_resource(&name) else {
        return ret_ref(None);
    };
    let len = i32::try_from(bytes.len()).map_err(|_| VmError::OutOfMemory)?;
    let array = vm.new_array_of("B", len)?;
    if let Array::Byte(a) = vm.heap.array_mut(array)? {
        for (dst, src) in a.iter_mut().zip(bytes) {
            *dst = src as i8;
        }
    }
    ret_ref(Some(array))
}

fn stream(fd: i32) -> VmResult<Stream> {
    Stream::from_fd(fd)
        .ok_or_else(|| VmError::exception("java/io/IOException", format!("bad descriptor {fd}")))
}

fn io_error(e: std::io::Error) -> VmError {
    VmError::exception("java/io/IOException", e.to_string())
}

/// Checks `off`/`len` against an array of `array_len` bytes.
fn range(off: i32, len: i32, array_len: usize) -> VmResult<std::ops::Range<usize>> {
    let end = i64::from(off) + i64::from(len);
    if off < 0 || len < 0 || end > array_len as i64 {
        let msg = format!("Range [{off}, {off} + {len}) out of bounds for length {array_len}");
        return Err(VmError::exception(
            "java/lang/IndexOutOfBoundsException",
            msg,
        ));
    }
    Ok(off as usize..end as usize)
}

fn write0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let stream = stream(int(args, 0)?)?;
    let array = obj(args, 1)?;
    let Array::Byte(bytes) = vm.heap.array(array)? else {
        return Err(VmError::verify("write0 needs a byte[]"));
    };
    let r = range(int(args, 2)?, int(args, 3)?, bytes.len())?;
    let data: Vec<u8> = bytes[r].iter().map(|&b| b as u8).collect();
    platform::write(stream, &data).map_err(io_error)?;
    ret_void()
}

fn read0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let stream = stream(int(args, 0)?)?;
    let array = obj(args, 1)?;
    let len = vm.heap.array(array)?.len();
    let r = range(int(args, 2)?, int(args, 3)?, len)?;
    if r.is_empty() {
        return ret_int(0);
    }
    let mut buf = vec![0u8; r.len()];
    let n = platform::read(stream, &mut buf).map_err(io_error)?;
    if n == 0 {
        return ret_int(-1);
    }
    if let Array::Byte(bytes) = vm.heap.array_mut(array)? {
        for (dst, &src) in bytes[r.start..r.start + n].iter_mut().zip(&buf[..n]) {
            *dst = src as i8;
        }
    }
    ret_int(n as i32)
}

fn sleep(_vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let millis = long(args, 0)?;
    if millis < 0 {
        return Err(VmError::exception(
            "java/lang/IllegalArgumentException",
            "timeout value is negative",
        ));
    }
    platform::flush(Stream::Out).map_err(io_error)?;
    std::thread::sleep(std::time::Duration::from_millis(millis as u64));
    ret_void()
}
