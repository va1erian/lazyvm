//! `lazy.ui`: Java widget trees on xui (docs/ui.md), registered only by the
//! `lazyvm-ui` launcher (`crates/launcher-ui`). The `xui-bind` crate
//! builds and runs the window; this module moves the op list off the heap
//! and turns each widget message into one upcall to `Ui.dispatch`, through
//! the VM lent for the length of the loop.

use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;

use vm::heap::Array;
use vm::interp::with_lent;
use vm::{MethodRef, Value, Vm, VmError, VmResult};
use xui_bind::{Headless, Host, Stats, Tree};

use natives::{arg, int, obj, ret_void};

const UI: &str = "lazy/ui/Ui";

/// Registers the `lazy.ui` natives; `launcher-ui` calls it after `natives::register_all`.
pub fn register(vm: &mut Vm) {
    vm.register_native(
        UI,
        "run0",
        "(Ljava/lang/String;II[I[Ljava/lang/Object;)V",
        run0,
    );
    vm.register_native(UI, "setText0", "(ILjava/lang/String;)V", |vm, a| {
        let text = vm.string_arg(arg(a, 1)?)?;
        xui_bind::set_text(int(a, 0)?, &text).map_err(state)?;
        ret_void()
    });
}

fn state(msg: String) -> VmError {
    VmError::exception("java/lang/IllegalStateException", msg)
}

fn prop(vm: &Vm, key: &str) -> Option<String> {
    vm.properties.get(key).map(|v| String::from_utf16_lossy(v))
}

/// `-Dlazy.ui=headless` hides the window; `lazy.ui.dump` and `lazy.ui.clicks`
/// script it (prototype; S9-02 moves the script to `lazy.internal.ui.Headless`).
/// A window host is made later, by `xui_bind::run`, through `desktop`.
fn headless(vm: &Vm) -> Option<Headless> {
    if prop(vm, "lazy.ui").as_deref() != Some("headless") {
        return None;
    }
    let clicks = prop(vm, "lazy.ui.clicks").and_then(|c| c.parse().ok());
    let dump = prop(vm, "lazy.ui.dump").map(PathBuf::from);
    Some(Headless {
        dump,
        clicks: clicks.unwrap_or(0),
    })
}

#[cfg(all(windows, feature = "desktop"))]
fn desktop() -> Result<Host, String> {
    Ok(Host::Desktop(lazyvm_win::ui_backend()))
}

#[cfg(not(all(windows, feature = "desktop")))]
fn desktop() -> Result<Host, String> {
    Err("no window host in this build; use -Dlazy.ui=headless".into())
}

/// The op list and its strings, copied off the heap before the loop runs.
fn tree(vm: &Vm, ops: Value, strings: Value) -> VmResult<Tree> {
    let Array::Int(ops) = vm.heap.array(obj(&[ops], 0)?)? else {
        return Err(VmError::verify("run0 needs an int[]"));
    };
    let ops = ops.to_vec();
    let Array::Ref(refs) = vm.heap.array(obj(&[strings], 0)?)? else {
        return Err(VmError::verify("run0 needs an Object[]"));
    };
    let mut out = Vec::with_capacity(refs.len());
    for r in refs.iter() {
        out.push(vm.string_arg(Value::Ref(*r))?);
    }
    Ok(Tree { ops, strings: out })
}

fn run0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let title = vm.string_arg(arg(args, 0)?)?;
    let (w, h) = (int(args, 1)?, int(args, 2)?);
    let size = match (u32::try_from(w), u32::try_from(h)) {
        (Ok(w @ 1..=8192), Ok(h @ 1..=8192)) => (w, h),
        _ => {
            return Err(VmError::exception(
                "java/lang/IllegalArgumentException",
                format!("window size {w}x{h}"),
            ));
        }
    };
    let tree = tree(vm, arg(args, 3)?, arg(args, 4)?)?;
    let headless = headless(vm);
    let ui = vm.load_class(UI)?;
    let index = vm.class(ui).find_method("dispatch", "(II)V");
    let dispatch = MethodRef {
        class: ui,
        index: index.ok_or_else(|| VmError::Internal("no Ui.dispatch".into()))?,
    };
    let thrown = Rc::new(RefCell::new(None));
    let caught = Rc::clone(&thrown);
    let upcall = Box::new(move |id, kind| {
        let r = with_lent(|vm| vm.invoke(dispatch, &[Value::Int(id), Value::Int(kind)]));
        match r {
            Some(Ok(_)) => true,
            Some(Err(e)) => {
                *caught.borrow_mut() = Some(e);
                false
            }
            None => false,
        }
    });
    let host = move || match headless {
        Some(script) => Ok(Host::Headless(script)),
        None => desktop(),
    };
    let stats = vm
        .lend(|| xui_bind::run(host, &title, size, tree, upcall))
        .map_err(state)?;
    if prop(vm, "lazy.ui.stats").as_deref() == Some("true") {
        print_stats(&stats);
    }
    // No Java ran after the throw, so the exception's `Ref` is still live.
    match thrown.borrow_mut().take() {
        Some(e) => Err(e),
        None => ret_void(),
    }
}

fn print_stats(s: &Stats) {
    eprintln!(
        "lazy.ui stats: build {} us, {} upcalls ({} us total, {} us max), {} sets, clicks {} us",
        s.build_us, s.upcalls, s.upcall_us, s.upcall_max_us, s.sets, s.clicks_us
    );
}
