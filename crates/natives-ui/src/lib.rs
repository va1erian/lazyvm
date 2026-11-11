//! `lazy.ui`: Java widget trees on xui (docs/ui.md), registered only by the
//! `lazyvm-ui` launcher (`crates/launcher-ui`). The `xui-bind` crate builds
//! and runs the windows; this crate moves op lists off the heap and turns
//! each widget message into one upcall to `Ui.dispatch`, through the VM lent
//! for the length of the loop (`vm::thread::with_lent`).

mod convert;
mod headless;

use std::cell::RefCell;
use std::rc::Rc;

use vm::thread::with_lent;
use vm::{MethodRef, Value, Vm, VmError, VmResult};
use xui_bind::{Headless, Msg, Stats, WindowSpec};
pub use xui_bind::Host;

use natives::{arg, int, ret_void};

const UI: &str = "lazy/ui/Ui";

/// The window host a build without one stands in with.
pub type Desktop = fn() -> Result<Host, String>;

/// Registers the `lazy.ui` natives; `launcher-ui` calls it after
/// `natives::register_all`. `desktop` makes the platform's window host.
pub fn register(vm: &mut Vm, desktop: Desktop) {
    DESKTOP.with(|d| d.set(Some(desktop)));
    let window = "(ILjava/lang/String;II[I[Ljava/lang/Object;[I)V";
    vm.register_native(UI, "run0", window, run0);
    let open = "(IILjava/lang/String;II[I[Ljava/lang/Object;[I)V";
    vm.register_native(UI, "open0", open, |vm, a| {
        let spec = spec(vm, &a[1..])?;
        xui_bind::open(int(a, 0)?, spec).map_err(state)?;
        ret_void()
    });
    vm.register_native(UI, "close0", "(I)V", |_, a| {
        xui_bind::close(int(a, 0)?).map_err(state)?;
        ret_void()
    });
    vm.register_native(UI, "quit0", "()V", |_, _| {
        xui_bind::quit();
        ret_void()
    });
    vm.register_native(UI, "title0", "(ILjava/lang/String;)V", |vm, a| {
        let title = vm.string_arg(arg(a, 1)?)?;
        xui_bind::set_title(int(a, 0)?, &title).map_err(state)?;
        ret_void()
    });
    let make = "(I[I[Ljava/lang/Object;)V";
    vm.register_native(UI, "make0", make, |vm, a| {
        let tree = convert::tree(vm, arg(a, 1)?, arg(a, 2)?)?;
        xui_bind::make(&tree, int(a, 0)?).map_err(state)?;
        ret_void()
    });
    vm.register_native(UI, "mount0", "(I[I[Ljava/lang/Object;)V", |vm, a| {
        let tree = convert::tree(vm, arg(a, 1)?, arg(a, 2)?)?;
        xui_bind::mount(int(a, 0)?, &tree).map_err(state)?;
        ret_void()
    });
    vm.register_native(UI, "forget0", "([I)V", |vm, a| {
        xui_bind::forget(&convert::ints(vm, arg(a, 0)?)?);
        ret_void()
    });
    vm.register_native(UI, "timer0", "(III)V", |_, a| {
        let ms = u32::try_from(int(a, 2)?).map_err(|_| illegal("negative period"))?;
        xui_bind::timer(int(a, 0)?, int(a, 1)?, ms).map_err(state)?;
        ret_void()
    });
    let call = "(IIIDLjava/lang/Object;)Ljava/lang/Object;";
    vm.register_native(UI, "call0", call, |vm, a| {
        let Value::Double(d) = arg(a, 3)? else {
            return Err(VmError::verify("call0 needs a double"));
        };
        let o = convert::arg(vm, arg(a, 4)?)?;
        let r = xui_bind::call(int(a, 0)?, int(a, 1)?, int(a, 2)?, d, o).map_err(state)?;
        convert::ret(vm, r)
    });
    let clip = "(ILjava/lang/String;)Ljava/lang/String;";
    vm.register_native(UI, "clipboard0", clip, |vm, a| {
        let window = int(a, 0)?;
        if let Value::Ref(Some(_)) = arg(a, 1)? {
            let text = vm.string_arg(arg(a, 1)?)?;
            xui_bind::set_clipboard(window, &text).map_err(state)?;
            return Ok(Some(Value::Ref(None)));
        }
        let text = xui_bind::clipboard(window).map_err(state)?;
        let r = text.map(|t| vm.new_string(&t)).transpose()?;
        Ok(Some(Value::Ref(r)))
    });
    vm.register_native(UI, "wake0", "()Z", |_, _| {
        Ok(Some(Value::Int(xui_bind::wake() as i32)))
    });
    headless::register(vm);
}

thread_local! {
    static DESKTOP: std::cell::Cell<Option<Desktop>> = const { std::cell::Cell::new(None) };
}

fn state(msg: String) -> VmError {
    VmError::exception("java/lang/IllegalStateException", msg)
}

fn illegal(msg: &str) -> VmError {
    VmError::exception("java/lang/IllegalArgumentException", msg.to_string())
}

/// A window's spec from `(id, title, width, height, ops, objects, keys)`.
fn spec(vm: &Vm, a: &[Value]) -> VmResult<WindowSpec> {
    let title = vm.string_arg(arg(a, 1)?)?;
    let (w, h) = (int(a, 2)?, int(a, 3)?);
    let size = match (u32::try_from(w), u32::try_from(h)) {
        (Ok(w @ 1..=8192), Ok(h @ 1..=8192)) => (w, h),
        _ => return Err(illegal(&format!("window size {w}x{h}"))),
    };
    Ok(WindowSpec {
        id: int(a, 0)?,
        title,
        size,
        tree: convert::tree(vm, arg(a, 4)?, arg(a, 5)?)?,
        keys: convert::ints(vm, arg(a, 6)?)?,
    })
}

fn method(vm: &mut Vm, class: &str, name: &str, desc: &str) -> VmResult<MethodRef> {
    let c = vm.load_class(class)?;
    let index = vm.class(c).find_method(name, desc);
    let index = index.ok_or_else(|| VmError::Internal(format!("no {class}.{name}")))?;
    Ok(MethodRef { class: c, index })
}

/// The one upcall: `Ui.dispatch` catches what a callback throws and keeps
/// it for `Ui.run`, so a VM error here is the VM's own and stops the loop.
fn upcall(dispatch: MethodRef, failed: &RefCell<Option<VmError>>, m: &Msg) -> bool {
    let r = with_lent(|vm| {
        let s = match &m.s {
            Some(s) => Some(vm.new_string(s)?),
            None => None,
        };
        let args = [
            Value::Int(m.id),
            Value::Int(m.kind),
            Value::Int(m.i),
            Value::Double(m.d),
            Value::Ref(s),
        ];
        vm.invoke(dispatch, &args)
    });
    match r {
        Some(Ok(Some(Value::Int(ok)))) => ok != 0,
        Some(Err(e)) => {
            // A `Ref` must not outlive the lend: another thread may collect.
            let e = match e {
                VmError::Throw(_) => VmError::Internal("lazy.ui: an upcall threw".into()),
                e => e,
            };
            *failed.borrow_mut() = Some(e);
            false
        }
        _ => false,
    }
}

fn run0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let spec = spec(vm, args)?;
    let headless = vm.property_text("lazy.ui").as_deref() == Some("headless");
    let dispatch = method(vm, UI, "dispatch", "(IIIDLjava/lang/String;)Z")?;
    let script = method(vm, headless::HEADLESS, "script", "()V")?;
    let failed = Rc::new(RefCell::new(None));
    let seen = Rc::clone(&failed);
    let up: xui_bind::Dispatch = Rc::new(move |m: &Msg| upcall(dispatch, &seen, m));
    let host = move || match headless {
        true => {
            let h = Headless::default();
            *h.script.borrow_mut() = Some(Box::new(move || {
                with_lent(|vm| vm.invoke(script, &[]).map(|_| ()));
            }) as xui_bind::Script);
            Ok(Host::Headless(h))
        }
        false => DESKTOP.with(std::cell::Cell::get).map_or_else(
            || Err("no window host in this build; use -Dlazy.ui=headless".into()),
            |make| make(),
        ),
    };
    let stats = vm.lend(|| xui_bind::run(host, spec, up)).map_err(state)?;
    if vm.property_text("lazy.ui.stats").as_deref() == Some("true") {
        print_stats(&stats);
    }
    match failed.borrow_mut().take() {
        Some(e) => Err(e),
        None => ret_void(),
    }
}

fn print_stats(s: &Stats) {
    eprintln!(
        "lazy.ui stats: build {} us, {} upcalls ({} us total, {} us max), {} sets",
        s.build_us, s.upcalls, s.upcall_us, s.upcall_max_us, s.sets
    );
}
