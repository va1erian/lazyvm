//! Pixel windows for `lazy.gfx` (docs/gfx.md).
//!
//! The VM thread owns every window: it presents frames and polls input, and
//! nothing calls back into Java. Windows are kept in a per-thread table and
//! named by small integer handles, so natives never hold Rust objects in the
//! Java heap.

mod headless;
mod image;
mod scale;

#[cfg(all(windows, feature = "desktop"))]
mod desktop;

use std::cell::RefCell;
use std::path::PathBuf;

pub use image::decode_png;
pub use scale::fit;

/// One input event, mirroring `lazy.gfx.Event`'s types.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Event {
    KeyDown(i32),
    KeyUp(i32),
    Text(u16),
    MouseMove(i32, i32),
    MouseDown(i32, i32, i32),
    MouseUp(i32, i32, i32),
    Focus(bool),
    Resize(i32, i32),
    Close,
}

/// Where windows go.
#[derive(Clone, Debug)]
pub enum Backend {
    /// The platform's real windows.
    Desktop,
    /// No window: frames are kept in memory and, if a path is given, the last
    /// one is written there as a binary PPM when the window closes.
    Headless(Option<PathBuf>),
}

/// What a backend does for one window.
trait Host {
    /// `rgb` holds exactly `w * h` pixels of 0x00RRGGBB.
    fn present(&mut self, rgb: &[u32], w: usize, h: usize) -> Result<(), String>;
    fn poll(&mut self) -> Option<Event>;
    fn is_open(&self) -> bool;
    fn size(&self) -> (u32, u32);
    /// The window's pixels as last presented, row by row, `size()` of them.
    fn capture(&self) -> Vec<u32>;
    fn set_title(&mut self, title: &str);
    fn close(self: Box<Self>) -> Result<(), String>;
}

thread_local! {
    static WINDOWS: RefCell<Vec<Option<Box<dyn Host>>>> = const { RefCell::new(Vec::new()) };
}

/// Opens a window with a client area of `w` by `h` pixels and returns its handle (never 0).
pub fn open(backend: &Backend, title: &str, w: u32, h: u32) -> Result<i32, String> {
    let host: Box<dyn Host> = match backend {
        Backend::Headless(dump) => Box::new(headless::Headless::new(w, h, dump.clone())),
        #[cfg(all(windows, feature = "desktop"))]
        Backend::Desktop => Box::new(desktop::DesktopWindow::open(title, w, h)?),
        #[cfg(not(all(windows, feature = "desktop")))]
        Backend::Desktop => {
            let _ = title;
            return Err("no window system in this build; use -Dlazy.gfx=headless".into());
        }
    };
    WINDOWS.with_borrow_mut(|table| {
        let slot = match table.iter().position(Option::is_none) {
            Some(i) => i,
            None => {
                table.push(None);
                table.len() - 1
            }
        };
        table[slot] = Some(host);
        i32::try_from(slot + 1).map_err(|_| "too many windows".to_string())
    })
}

/// Runs `f` on the window named by `handle`, or fails if there is none.
fn with<T>(handle: i32, f: impl FnOnce(&mut dyn Host) -> T) -> Result<T, String> {
    WINDOWS.with_borrow_mut(|table| {
        let slot = usize::try_from(handle).ok().and_then(|h| h.checked_sub(1));
        match slot.and_then(|i| table.get_mut(i)).and_then(Option::as_mut) {
            Some(host) => Ok(f(host.as_mut())),
            None => Err(format!("no window {handle}")),
        }
    })
}

pub fn present(handle: i32, rgb: &[u32], w: usize, h: usize) -> Result<(), String> {
    with(handle, |host| host.present(rgb, w, h))?
}

pub fn poll(handle: i32) -> Result<Option<Event>, String> {
    with(handle, |host| host.poll())
}

pub fn is_open(handle: i32) -> bool {
    with(handle, |host| host.is_open()).unwrap_or(false)
}

pub fn size(handle: i32) -> Result<(u32, u32), String> {
    with(handle, |host| host.size())
}

pub fn capture(handle: i32) -> Result<Vec<u32>, String> {
    with(handle, |host| host.capture())
}

pub fn set_title(handle: i32, title: &str) -> Result<(), String> {
    with(handle, |host| host.set_title(title))
}

/// Destroys the window; an unknown handle is ignored, so closing twice is harmless.
pub fn close(handle: i32) -> Result<(), String> {
    let host = WINDOWS.with_borrow_mut(|table| {
        let slot = usize::try_from(handle)
            .ok()
            .and_then(|h| h.checked_sub(1))?;
        table.get_mut(slot)?.take()
    });
    host.map_or(Ok(()), Host::close)
}
