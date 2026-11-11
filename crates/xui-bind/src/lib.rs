//! `lazy.ui` on xui (docs/ui.md). This crate knows nothing of the VM: it
//! decodes the op lists Java encodes widgets into, builds xui widgets from
//! them, runs the loop, and hands each widget message to a dispatch
//! callback, which the natives turn into one upcall to `lazy.ui.Ui.dispatch`.

mod app;
mod build;
mod canvas;
mod free;
mod headless;
mod host;
mod msg;
#[cfg(feature = "rich-text")]
mod probe;
mod props;
mod reader;
#[cfg(feature = "rich-text")]
mod rich;
mod table;
mod widgets;

use std::cell::RefCell;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

pub use host::WindowSpec;
pub use msg::{Msg, kind};
pub use props::{Arg, Ret};
pub use reader::{Obj, Tree};
pub use xui_core::backend::Backend;

/// Where the windows go.
pub enum Host {
    /// Real windows on a window system's backend (`lazyvm-win` on Windows,
    /// `lazyvm-lazyos` on LazyOS).
    Desktop(Rc<dyn Backend>),
    /// No window system: the offscreen backend, driven by a script.
    Headless(Headless),
}

/// What the headless host runs once the window is built: the Java script
/// (`lazy.internal.ui.Headless`), if one was set.
#[derive(Default)]
pub struct Headless {
    pub script: Rc<RefCell<Option<Script>>>,
}

pub type Script = Box<dyn FnOnce()>;

/// Called for every message. Returns false when Java threw, which stops
/// every window for good.
pub type Dispatch = Rc<dyn Fn(&Msg) -> bool>;

/// What one run cost, for `-Dlazy.ui.stats=true` (docs/ui.md, "Evidence").
#[derive(Clone, Copy, Debug, Default)]
pub struct Stats {
    /// Microseconds from `run` to the main window's widgets mounted.
    pub build_us: u64,
    /// Messages handed to Java.
    pub upcalls: u64,
    /// Microseconds spent inside those upcalls, in total and at most.
    pub upcall_us: u64,
    pub upcall_max_us: u64,
    /// Widget changes and reads made from Java.
    pub sets: u64,
}

/// winit allows one event loop per process, so `lazy.ui` runs once.
static RAN: AtomicBool = AtomicBool::new(false);

/// Opens the main window `spec` describes and runs until the loop ends.
/// `host` is called only after the once-per-process check, because building
/// a window backend twice (winit's event loop) panics inside the toolkit.
pub fn run(
    host: impl FnOnce() -> Result<Host, String>,
    spec: WindowSpec,
    dispatch: Dispatch,
) -> Result<Stats, String> {
    if RAN.swap(true, Ordering::SeqCst) {
        return Err("lazy.ui runs once per process".into());
    }
    let start = Instant::now();
    app::start(dispatch);
    let result = host().and_then(|host| host::run(&host, start, spec));
    let mut stats = app::finish();
    stats.sets = table::sets();
    table::clear();
    headless::clear();
    result.map(|()| stats)
}

/// Makes `bytes` (a font file) the only font text is shaped with, so pixels
/// are the same on every platform; call it before the window is built.
pub fn set_font(bytes: Vec<u8>) {
    xui_canvas::set_default_font(bytes);
}

/// Opens a secondary window from a callback of window `opener`.
pub fn open(opener: i32, spec: WindowSpec) -> Result<(), String> {
    host::open(opener, spec)
}

/// Closes window `id`; the main window's close ends the loop.
pub fn close(id: i32) -> Result<(), String> {
    host::close(id)
}

pub fn quit() {
    host::quit();
}

/// Retitles window `id`.
pub fn set_title(id: i32, title: &str) -> Result<(), String> {
    table::window(id)?.ui.set_window_title(title);
    Ok(())
}

/// The system clipboard's text, through window `id`.
pub fn clipboard(id: i32) -> Result<Option<String>, String> {
    Ok(table::window(id)?.ui.clipboard_text())
}

pub fn set_clipboard(id: i32, text: &str) -> Result<(), String> {
    table::window(id)?.ui.set_clipboard_text(text);
    Ok(())
}

/// Makes a dialog, file picker or menu in window `window` (docs/ui.md).
pub fn make(tree: &Tree, window: i32) -> Result<(), String> {
    free::make(tree, window)
}

/// Replaces panel `panel`'s content with `tree`'s layout. The widgets it
/// held are dropped and their ids forgotten; returns those ids.
pub fn mount(panel: i32, tree: &Tree) -> Result<Vec<i32>, String> {
    let (widget, window) = table::get(panel)?;
    let table::Widget::Panel(handle) = widget else {
        return Err(format!("widget {panel} is not a panel"));
    };
    let p = handle.try_get().ok_or("the panel is not built")?;
    let (old, removed) = table::remove_content(panel);
    let ctx = build::Ctx {
        window,
        owner: Some(panel),
    };
    let (layout, pending) = build::decode(tree, ctx)?;
    p.set_layout(layout)
        .map_err(|e| format!("cannot mount: {e:?}"))?;
    build::apply(pending, &table::window(window)?.ui)?;
    drop(removed);
    Ok(old)
}

/// Forgets widgets Java let go of (a closed window's dialogs, a cancelled
/// timer), killing their timers.
pub fn forget(ids: &[i32]) {
    for &id in ids {
        headless::remove_timer(id);
        if let Ok((table::Widget::Timer(t), w)) = table::get(id)
            && let Ok(window) = table::window(w)
        {
            window.ui.kill_timer(t);
        }
    }
    drop(table::remove(ids));
}

/// Starts timer `id` in window `window`: every `ms` milliseconds until it
/// is forgotten. On the headless host it runs on the manual clock.
pub fn timer(window: i32, id: i32, ms: u32) -> Result<(), String> {
    if ms == 0 {
        return Err("a timer needs a period".into());
    }
    let ui = table::window(window)?.ui.clone();
    if headless::is_active() {
        table::insert(id, window, None, table::Widget::Inert)?;
        headless::add_timer(id, ms.into());
        return Ok(());
    }
    table::insert(id, window, None, table::Widget::Timer(ui.set_timer(ms)))
}

/// Runs `op` on widget `id` (docs/ui.md, "Changing a widget").
pub fn call(id: i32, op: i32, i: i32, d: f64, arg: Arg) -> Result<Ret, String> {
    props::call(id, op, i, d, arg)
}

/// Wakes the UI thread to drain `Ui.executor()`; callable from any thread.
pub fn wake() -> bool {
    host::wake()
}

/// The headless driver's steps (`lazy.internal.ui.Headless`).
pub mod drive {
    pub use crate::headless::{
        advance, click, close, frame, hover, key, scroll, type_text, upcalls,
    };
}
