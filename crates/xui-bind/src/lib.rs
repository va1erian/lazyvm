//! `lazy.ui` on xui (docs/ui.md). This crate knows nothing of the VM: it
//! decodes the op list Java encodes a window into, builds xui widgets from
//! it, runs the loop, and hands each widget message to a dispatch callback,
//! which the natives turn into one upcall to `lazy.ui.Ui.dispatch`.
//!
//! Prototype scope (S9-02 spike): `column`, `row`, `label` and `button`, one
//! window per process, a window backend handed in, or the offscreen host.

mod app;
mod host;
mod table;
mod tree;

use std::path::PathBuf;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};

pub use table::set_text;
pub use tree::{Tree, op};
pub use xui_core::backend::Backend;

/// Where the window goes.
pub enum Host {
    /// A real window on a window system's backend (`lazyvm-win` on Windows).
    Desktop(Rc<dyn Backend>),
    /// No window: build, optionally click, optionally dump a frame.
    Headless(Headless),
}

/// What the headless host does once the window is built.
pub struct Headless {
    /// Writes the last frame there as a binary PPM.
    pub dump: Option<PathBuf>,
    /// Clicks the first clickable button this many times.
    pub clicks: u32,
}

/// Called for every widget message with (widget id, event kind). Returns
/// false when Java threw, which stops the loop.
pub type Dispatch = Box<dyn FnMut(i32, i32) -> bool>;

/// What one run cost, for `-Dlazy.ui.stats=true` (docs/ui.md, "Evidence").
#[derive(Clone, Copy, Debug, Default)]
pub struct Stats {
    /// Microseconds spent decoding the tree and mounting the widgets.
    pub build_us: u64,
    /// Messages handed to Java.
    pub upcalls: u64,
    /// Microseconds spent inside those upcalls, in total and at most.
    pub upcall_us: u64,
    pub upcall_max_us: u64,
    /// Widget changes made from Java.
    pub sets: u64,
    /// Headless only: microseconds for all scripted clicks, layout and paint
    /// included.
    pub clicks_us: u64,
}

/// winit allows one event loop per process, so `lazy.ui` runs once.
static RAN: AtomicBool = AtomicBool::new(false);

/// Opens the window `tree` describes and runs it until it closes.
pub fn run(
    host: &Host,
    title: &str,
    size: (u32, u32),
    tree: Tree,
    dispatch: Dispatch,
) -> Result<Stats, String> {
    if RAN.swap(true, Ordering::SeqCst) {
        return Err("lazy.ui runs once per process".into());
    }
    let result = host::run(host, title, size, tree, dispatch);
    table::clear();
    result
}
