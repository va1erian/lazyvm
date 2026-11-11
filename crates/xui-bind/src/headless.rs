//! The headless host: xui's offscreen backend, which has no loop, timers or
//! wakes of its own, driven by a Java script (`lazy.internal.ui.Headless`)
//! that the run hook calls once the window is built (docs/ui.md,
//! "Evidence and tests").

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use xui_canvas::OffscreenBackend;
use xui_core::backend::{Backend, Event, WindowId};
use xui_core::{Key, Modifiers, MouseButton};

use crate::msg::{Msg, kind as ev};
use crate::table;
use crate::{Headless, host};

/// A timer on the manual clock: its Java id, period and next due time.
struct Timer {
    id: i32,
    period: u64,
    due: u64,
}

#[derive(Default)]
struct Clock {
    now: u64,
    timers: Vec<Timer>,
}

thread_local! {
    static BACKEND: RefCell<Option<Rc<OffscreenBackend>>> = const { RefCell::new(None) };
    static MAIN: Cell<Option<WindowId>> = const { Cell::new(None) };
    static CLOCK: RefCell<Clock> = RefCell::new(Clock::default());
}

/// The offscreen backend, whose run hook calls `script` and returns.
pub fn backend(script: &Headless) -> Rc<dyn Backend> {
    let backend = Rc::new(OffscreenBackend::new());
    BACKEND.with(|b| *b.borrow_mut() = Some(Rc::clone(&backend)));
    CLOCK.with(|c| *c.borrow_mut() = Clock::default());
    let script = Rc::clone(&script.script);
    backend.set_run_hook(move || {
        if let Some(script) = script.borrow_mut().take() {
            script();
        }
    });
    backend
}

/// Records the main window, once `run_app` has opened it.
pub fn bind(window: WindowId) {
    MAIN.with(|m| m.set(Some(window)));
}

pub fn clear() {
    BACKEND.with(|b| b.borrow_mut().take());
    MAIN.with(|m| m.set(None));
    CLOCK.with(|c| *c.borrow_mut() = Clock::default());
}

fn active() -> Result<(Rc<OffscreenBackend>, WindowId), String> {
    let backend = BACKEND.with(|b| b.borrow().clone());
    match (backend, MAIN.with(Cell::get)) {
        (Some(b), Some(w)) => Ok((b, w)),
        _ => Err("no headless window is running".into()),
    }
}

pub fn is_active() -> bool {
    active().is_ok()
}

/// A point of widget `id` and the window it is in: its centre, or `at`
/// pixels from its top-left corner.
fn point(
    id: i32,
    at: Option<(i32, i32)>,
) -> Result<(Rc<OffscreenBackend>, WindowId, i32, i32), String> {
    let (b, _) = active()?;
    let (w, window) = table::get(id)?;
    let node = w.node().ok_or_else(|| format!("widget {id} has no node"))?;
    let window = table::window(window)?.ui.window();
    let r = b.bounds(node);
    let (x, y) = match at {
        Some((x, y)) => (r.left.saturating_add(x), r.top.saturating_add(y)),
        None => ((r.left + r.right) / 2, (r.top + r.bottom) / 2),
    };
    Ok((b, window, x, y))
}

fn centre(id: i32) -> Result<(Rc<OffscreenBackend>, WindowId, i32, i32), String> {
    point(id, None)
}

fn pump(b: &OffscreenBackend, window: WindowId) {
    b.pump(window);
    b.render(window);
}

/// A left click in the middle of widget `id`, or `at` pixels from its
/// top-left corner.
pub fn click(id: i32, at: Option<(i32, i32)>) -> Result<(), String> {
    let (b, window, x, y) = point(id, at)?;
    let (button, modifiers) = (MouseButton::Left, Modifiers::default());
    b.inject(window, Event::MouseMove { x, y, modifiers });
    b.inject(
        window,
        Event::MouseDown {
            x,
            y,
            button,
            modifiers,
        },
    );
    b.inject(
        window,
        Event::MouseUp {
            x,
            y,
            button,
            modifiers,
        },
    );
    pump(&b, window);
    Ok(())
}

/// The pointer moved over widget `id`.
pub fn hover(id: i32) -> Result<(), String> {
    let (b, window, x, y) = centre(id)?;
    let modifiers = Modifiers::default();
    b.inject(window, Event::MouseMove { x, y, modifiers });
    pump(&b, window);
    Ok(())
}

/// The wheel turned by `delta` (120 a notch) over widget `id`.
pub fn scroll(id: i32, delta: i32) -> Result<(), String> {
    let (b, window, x, y) = centre(id)?;
    let delta = i16::try_from(delta).map_err(|_| format!("wheel delta {delta}"))?;
    let modifiers = Modifiers::default();
    let horizontal = false;
    b.inject(
        window,
        Event::MouseWheel {
            delta,
            horizontal,
            x,
            y,
            modifiers,
        },
    );
    pump(&b, window);
    Ok(())
}

/// A key pressed and released with `mods` (`host::modifier` bits), in the
/// main window.
pub fn key(code: i32, mods: i32) -> Result<(), String> {
    let (b, window) = active()?;
    let key = Key::from_code(u16::try_from(code).map_err(|_| format!("key code {code}"))?);
    let modifiers = Modifiers {
        ctrl: mods & i32::from(host::modifier::CTRL) != 0,
        shift: mods & i32::from(host::modifier::SHIFT) != 0,
        alt: mods & i32::from(host::modifier::ALT) != 0,
        ..Modifiers::default()
    };
    b.inject(
        window,
        Event::KeyDown {
            key,
            modifiers,
            repeat: 0,
            system: false,
        },
    );
    b.inject(
        window,
        Event::KeyUp {
            key,
            modifiers,
            system: false,
        },
    );
    pump(&b, window);
    Ok(())
}

/// `text` typed into whatever has the focus, one character at a time.
pub fn type_text(text: &str) -> Result<(), String> {
    let (b, window) = active()?;
    for c in text.chars() {
        b.inject(window, Event::Char(c));
        b.pump(window);
    }
    b.render(window);
    Ok(())
}

/// Puts a timer on the manual clock; `period` 0 is refused.
pub fn add_timer(id: i32, period: u64) {
    CLOCK.with(|c| {
        let mut c = c.borrow_mut();
        let due = c.now + period;
        c.timers.push(Timer { id, period, due });
    });
}

pub fn remove_timer(id: i32) {
    CLOCK.with(|c| c.borrow_mut().timers.retain(|t| t.id != id));
}

/// Moves the manual clock on by `ms`, firing each timer as it falls due, in
/// time order.
pub fn advance(ms: u64) -> Result<(), String> {
    let (b, window) = active()?;
    let end = CLOCK.with(|c| c.borrow().now) + ms;
    loop {
        let next = CLOCK.with(|c| {
            let mut c = c.borrow_mut();
            let t = c
                .timers
                .iter_mut()
                .filter(|t| t.due <= end)
                .min_by_key(|t| t.due)?;
            let fired = (t.id, t.due);
            t.due += t.period;
            c.now = fired.1;
            Some(fired.0)
        });
        let Some(id) = next else { break };
        let (_, w) = table::get(id)?;
        table::window(w)?.ui.emit(Msg::new(id, ev::TIMER));
        b.pump(window);
    }
    CLOCK.with(|c| c.borrow_mut().now = end);
    b.render(window);
    Ok(())
}

/// The main window's close button.
pub fn close() -> Result<(), String> {
    let (b, window) = active()?;
    b.inject(window, Event::Close);
    pump(&b, window);
    Ok(())
}

/// Messages handed to Java so far in this run.
pub fn upcalls() -> u64 {
    let mut n = 0;
    crate::app::with_stats(|s| n = s.upcalls);
    n
}

/// The main window's pixels as (width, height, RGBA), freshly rendered.
pub fn frame() -> Result<(u32, u32, Vec<u8>), String> {
    let (b, window) = active()?;
    let f = b.render(window).ok_or("nothing rendered")?;
    Ok((f.width, f.height, f.pixels.clone()))
}
