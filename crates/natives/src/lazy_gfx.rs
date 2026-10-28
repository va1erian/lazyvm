//! `lazy.gfx`: windows, input events and PNG decoding (docs/gfx.md). The
//! windows themselves live in the `gfx` crate; this module checks arguments
//! and moves values between the heap and it.

use std::path::PathBuf;

use gfx::{Backend, Event};
use vm::heap::Array;
use vm::{Value, Vm, VmError, VmResult};

use crate::{arg, int, obj, ret_bool, ret_int, ret_ref, ret_void};

const WINDOW: &str = "lazy/gfx/Window";
const EVENT: &str = "lazy/gfx/Event";
const IMAGE: &str = "lazy/gfx/Image";

pub fn register(vm: &mut Vm) {
    vm.register_native(WINDOW, "open0", "(Ljava/lang/String;II)I", open0);
    vm.register_native(WINDOW, "present0", "(I[III)V", present0);
    vm.register_native(WINDOW, "poll0", "(ILlazy/gfx/Event;)Z", poll0);
    vm.register_native(WINDOW, "isOpen0", "(I)Z", |_, a| {
        ret_bool(gfx::is_open(int(a, 0)?))
    });
    vm.register_native(WINDOW, "size0", "(II)I", |_, a| {
        let (w, h) = gfx::size(int(a, 0)?).map_err(state)?;
        ret_int(if int(a, 1)? == 0 { w } else { h } as i32)
    });
    vm.register_native(WINDOW, "capture0", "(I)[I", capture0);
    vm.register_native(WINDOW, "setTitle0", "(ILjava/lang/String;)V", |vm, a| {
        let title = vm.string_arg(arg(a, 1)?)?;
        gfx::set_title(int(a, 0)?, &title).map_err(state)?;
        ret_void()
    });
    vm.register_native(WINDOW, "close0", "(I)V", |_, a| {
        gfx::close(int(a, 0)?).map_err(state)?;
        ret_void()
    });
    vm.register_native(IMAGE, "decodePng0", "([B[I)[I", decode_png0);
}

fn state(msg: String) -> VmError {
    VmError::exception("java/lang/IllegalStateException", msg)
}

/// `-Dlazy.gfx=headless` hides windows; `-Dlazy.gfx.dump=<file>` keeps the last frame.
fn backend(vm: &Vm) -> Backend {
    let prop = |k: &str| vm.properties.get(k).map(|v| String::from_utf16_lossy(v));
    match prop("lazy.gfx").as_deref() {
        Some("headless") => Backend::Headless(prop("lazy.gfx.dump").map(PathBuf::from)),
        _ => Backend::Desktop,
    }
}

fn open0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let title = vm.string_arg(arg(args, 0)?)?;
    let (w, h) = (int(args, 1)?, int(args, 2)?);
    let area = i64::from(w) * i64::from(h);
    if w <= 0 || h <= 0 || area as u64 > gfx::MAX_PIXELS {
        return Err(VmError::exception(
            "java/lang/IllegalArgumentException",
            format!("window size {w}x{h}"),
        ));
    }
    let handle = gfx::open(&backend(vm), &title, w as u32, h as u32).map_err(state)?;
    ret_int(handle)
}

fn present0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let handle = int(args, 0)?;
    let (w, h) = (int(args, 2)?, int(args, 3)?);
    let Array::Int(rgb) = vm.heap.array(obj(args, 1)?)? else {
        return Err(VmError::verify("present0 needs an int[]"));
    };
    let n = i64::from(w) * i64::from(h);
    if w <= 0 || h <= 0 || n > rgb.len() as i64 {
        return Err(VmError::exception(
            "java/lang/IllegalArgumentException",
            format!("size {w}x{h} for {} pixels", rgb.len()),
        ));
    }
    // Read in place: no copy of the frame outside the heap.
    gfx::present(handle, &rgb[..n as usize], w as usize, h as usize).map_err(state)?;
    ret_void()
}

/// The array comes from the heap first, so its size is charged to `-Xmx`
/// before anything is copied; frames not yet presented read as black.
fn capture0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let handle = int(args, 0)?;
    let (w, h) = gfx::size(handle).map_err(state)?;
    let len = i32::try_from(u64::from(w) * u64::from(h)).map_err(|_| VmError::OutOfMemory)?;
    let out = vm.new_array_of("I", len)?;
    let Array::Int(dst) = vm.heap.array_mut(out)? else {
        return Err(VmError::Internal("int[] is not an int array".into()));
    };
    gfx::capture(handle, |pixels| {
        for (d, &p) in dst.iter_mut().zip(pixels) {
            *d = p as i32;
        }
    })
    .map_err(state)?;
    ret_ref(Some(out))
}

fn poll0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let Some(event) = gfx::poll(int(args, 0)?).map_err(state)? else {
        return ret_bool(false);
    };
    let target = obj(args, 1)?;
    // (type, key, ch, x, y, button), as in lazy.gfx.Event.
    let (ty, key, ch, x, y, button) = match event {
        Event::KeyDown(k) => (1, k, 0, 0, 0, 0),
        Event::KeyUp(k) => (2, k, 0, 0, 0, 0),
        Event::Text(c) => (3, 0, c, 0, 0, 0),
        Event::MouseMove(x, y) => (4, 0, 0, x, y, 0),
        Event::MouseDown(x, y, b) => (5, 0, 0, x, y, b),
        Event::MouseUp(x, y, b) => (6, 0, 0, x, y, b),
        Event::Focus(f) => (7, 0, 0, i32::from(f), 0, 0),
        Event::Resize(w, h) => (8, 0, 0, w, h, 0),
        Event::Close => (9, 0, 0, 0, 0, 0),
    };
    let class = vm.load_class(EVENT)?;
    let ints = [
        ("type", ty),
        ("key", key),
        ("x", x),
        ("y", y),
        ("button", button),
    ];
    for (name, v) in ints {
        let slot = vm.field_slot(class, name, "I")? as usize;
        vm.heap.fields_mut(target)?[slot] = Value::Int(v);
    }
    let slot = vm.field_slot(class, "ch", "C")? as usize;
    vm.heap.fields_mut(target)?[slot] = Value::Int(i32::from(ch));
    ret_bool(true)
}

/// The result array is allocated from the header's size before decoding, so
/// an image the heap cannot hold is an OutOfMemoryError before the decoder
/// allocates anything; the decoder's own buffers are bounded by its caps.
fn decode_png0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let Array::Byte(data) = vm.heap.array(obj(args, 0)?)? else {
        return Err(VmError::verify("decodePng0 needs a byte[]"));
    };
    let bytes: Vec<u8> = data.iter().map(|&b| b as u8).collect();
    let size = obj(args, 1)?;
    let bad = |e: String| VmError::exception("java/io/IOException", format!("bad PNG: {e}"));
    let (w, h) = gfx::png_size(&bytes).map_err(bad)?;
    let len = i32::try_from(u64::from(w) * u64::from(h)).map_err(|_| VmError::OutOfMemory)?;
    let out = vm.with_root(size, |vm| vm.new_array_of("I", len))?;
    // No allocation from here on, so neither `out` nor `size` can move or die.
    let (dw, dh, pixels) = gfx::decode_png(&bytes).map_err(bad)?;
    if (dw, dh) != (w, h) {
        return Err(bad(format!("frame is {dw}x{dh}, header said {w}x{h}")));
    }
    if let Array::Int(dst) = vm.heap.array_mut(out)? {
        for (d, p) in dst.iter_mut().zip(pixels) {
            *d = p as i32;
        }
    }
    match vm.heap.array_mut(size)? {
        Array::Int(s) if s.len() >= 2 => {
            s[0] = w as i32;
            s[1] = h as i32;
        }
        _ => return Err(VmError::verify("decodePng0 needs an int[2]")),
    }
    ret_ref(Some(out))
}
