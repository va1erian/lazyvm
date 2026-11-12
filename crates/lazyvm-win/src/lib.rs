//! Windows-only parts of LazyVM (decisions.md D10): the window backend
//! `lazy.ui` runs on (docs/ui.md, "Backends"), and the message box an
//! uncaught exception shows once a `lazy.ui` window was open (docs/ui.md,
//! "When a callback throws").

#![cfg(windows)]

use std::rc::Rc;

use xui_core::backend::Backend;

/// xui's winit backend. winit makes one event loop per process, on the main
/// thread; `xui_bind::run` enforces the once, the caller the thread.
pub fn ui_backend() -> Rc<dyn Backend> {
    Rc::new(xui_canvas::WinitBackend::new())
}

/// Shows `text` in a modal system message box titled `title`, with an error
/// icon, and waits for OK. By the time it is needed the process's one winit
/// loop is gone, so it cannot be an xui dialog.
#[allow(unsafe_code)]
pub fn error_box(title: &str, text: &str) {
    use windows_sys::Win32::UI::WindowsAndMessaging::{MB_ICONERROR, MB_OK, MessageBoxW};
    let wide = |s: &str| -> Vec<u16> { s.encode_utf16().chain(Some(0)).collect() };
    let (title, text) = (wide(title), wide(text));
    // SAFETY: both pointers are NUL-terminated UTF-16 buffers that live until
    // the call returns; no owner window (0) is allowed and MessageBoxW keeps
    // neither pointer.
    unsafe {
        MessageBoxW(0, text.as_ptr(), title.as_ptr(), MB_OK | MB_ICONERROR);
    }
}
