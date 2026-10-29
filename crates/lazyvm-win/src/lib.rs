//! Windows-only parts of LazyVM (decisions.md D10). Today: the window
//! backend `lazy.ui` runs on (docs/ui.md, "Backends").

use std::rc::Rc;

use xui_core::backend::Backend;

/// xui's winit backend. winit makes one event loop per process, on the main
/// thread; `xui_bind::run` enforces the once, the caller the thread.
pub fn ui_backend() -> Rc<dyn Backend> {
    Rc::new(xui_canvas::WinitBackend::new())
}
