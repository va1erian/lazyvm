//! Windows: the main one `Ui.run` opens, the secondary ones a callback
//! opens, their close button, shortcuts and timers, and the executor's wake
//! (docs/ui.md, "The loop and the thread").

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use xui_core::app::{Proxy, WindowHandle};
use xui_core::backend::{Backend, PlatformSpec};
use xui_core::{Dip, Key, Modifiers, Ui, run_app};

use crate::app::{self, JavaApp};
use crate::build::{self, Ctx};
use crate::msg::{Msg, kind as ev};
use crate::table::{self, WindowState};
use crate::{Host, Tree};

/// What Java hands over to open a window.
pub struct WindowSpec {
    /// The Java id of the `Window`.
    pub id: i32,
    pub title: String,
    pub size: (u32, u32),
    pub tree: Tree,
    /// Shortcuts as (key code, modifier mask) pairs, flattened.
    pub keys: Vec<i32>,
}

/// Modifier bits in a shortcut, shared with `lazy.ui.Window`.
pub mod modifier {
    pub const CTRL: u8 = 1;
    pub const SHIFT: u8 = 2;
    pub const ALT: u8 = 4;
}

thread_local! {
    /// Secondary windows, kept open by their handle.
    static SECONDARY: RefCell<HashMap<i32, WindowHandle<Msg>>> = RefCell::new(HashMap::new());
    /// The main window's id, which closing quits the loop.
    static MAIN: std::cell::Cell<i32> = const { std::cell::Cell::new(0) };
}

/// The executor's wake: any thread may send it (docs/ui.md, "Threads").
static WAKE: Mutex<Option<Proxy<Msg>>> = Mutex::new(None);
/// A wake asked for before the loop could take it: sent once it can.
static EARLY: AtomicBool = AtomicBool::new(false);

fn shortcuts(keys: &[i32]) -> Result<Vec<(u16, u8)>, String> {
    if !keys.len().is_multiple_of(2) {
        return Err("shortcuts come in pairs".into());
    }
    keys.chunks(2)
        .map(|k| match (u16::try_from(k[0]), u8::try_from(k[1])) {
            (Ok(code), Ok(mods)) if mods < 8 => Ok((code, mods)),
            _ => Err(format!("bad shortcut {} {}", k[0], k[1])),
        })
        .collect()
}

fn mask(m: Modifiers) -> u8 {
    let bit = |on: bool, b: u8| if on { b } else { 0 };
    bit(m.ctrl || m.win, modifier::CTRL) | bit(m.shift, modifier::SHIFT) | bit(m.alt, modifier::ALT)
}

/// Builds `spec`'s widgets in `ui` and wires its window-level events.
fn setup(ui: &mut Ui<Msg>, spec: &WindowSpec) -> Result<(), String> {
    let keys = shortcuts(&spec.keys)?;
    let id = spec.id;
    let state = WindowState { ui: ui.clone() };
    table::add_window(id, state);
    let ctx = Ctx {
        window: id,
        owner: None,
    };
    let layout = build::decode(&spec.tree, ctx)?;
    ui.root(layout)
        .map_err(|e| format!("cannot build the window: {e:?}"))?;
    ui.on_close(move || Some(Msg::new(id, ev::CLOSE)));
    ui.on_timer(|t| table::timer_owner(t).map(|j| Msg::new(j, ev::TIMER)));
    ui.on_key(move |key: Key, m: Modifiers| {
        let found = keys
            .iter()
            .position(|&(code, mods)| Key::from_code(code) == key && mods == mask(m));
        found.map(|i| Msg::int(id, ev::KEY, i as i32))
    });
    Ok(())
}

/// Opens the main window and runs the loop until it ends.
pub fn run(host: &Host, start: Instant, spec: WindowSpec) -> Result<(), String> {
    let error = RefCell::new(None);
    let backend: Rc<dyn Backend> = match host {
        Host::Desktop(backend) => Rc::clone(backend),
        Host::Headless(script) => crate::headless::backend(script),
    };
    MAIN.with(|m| m.set(spec.id));
    let platform =
        PlatformSpec::new(&spec.title).size(Dip(spec.size.0 as f32), Dip(spec.size.1 as f32));
    let made = run_app(backend, platform, |ui: &mut Ui<Msg>| {
        crate::headless::bind(ui.window());
        if let Err(e) = setup(ui, &spec) {
            *error.borrow_mut() = Some(e);
            ui.quit();
        }
        // Under the lock `wake` takes, so no early wake is lost in between.
        let proxy = ui.proxy();
        let mut slot = WAKE.lock().unwrap_or_else(|p| p.into_inner());
        if EARLY.swap(false, Ordering::SeqCst) {
            let _ = proxy.send(Msg::new(0, ev::WAKE));
        }
        *slot = Some(proxy);
        app::with_stats(|s| s.build_us = start.elapsed().as_micros() as u64);
        JavaApp
    });
    *WAKE.lock().unwrap_or_else(|p| p.into_inner()) = None;
    SECONDARY.with(|s| s.borrow_mut().clear());
    made.map_err(|e| format!("cannot open the window: {e:?}"))?;
    match error.into_inner() {
        Some(e) => Err(e),
        None => Ok(()),
    }
}

/// Opens a secondary window from a callback of window `opener`.
pub fn open(opener: i32, spec: WindowSpec) -> Result<(), String> {
    let ui = table::window(opener)?.ui.clone();
    let id = spec.id;
    let platform =
        PlatformSpec::new(&spec.title).size(Dip(spec.size.0 as f32), Dip(spec.size.1 as f32));
    let error = RefCell::new(None);
    let handle = ui
        .open_window(platform, |ui: &mut Ui<Msg>| {
            if let Err(e) = setup(ui, &spec) {
                *error.borrow_mut() = Some(e);
            }
            JavaApp
        })
        .map_err(|e| format!("cannot open the window: {e:?}"))?;
    if let Some(e) = error.into_inner() {
        handle.close();
        drop(table::remove(&table::of_window(id)));
        return Err(e);
    }
    SECONDARY.with(|s| s.borrow_mut().insert(id, handle));
    Ok(())
}

/// Closes window `id` and drops its widgets; closing the main window, or
/// the last one, ends the loop.
pub fn close(id: i32) -> Result<(), String> {
    let state = table::window(id)?;
    let handle = SECONDARY.with(|s| s.borrow_mut().remove(&id));
    match handle {
        Some(h) => h.close(),
        None => state.ui.close(),
    }
    let mut ids = table::of_window(id);
    ids.push(id);
    let removed = table::remove(&ids);
    let main = MAIN.with(std::cell::Cell::get);
    if id == main || table::windows().is_empty() {
        state.ui.quit();
    }
    drop(removed);
    Ok(())
}

/// Asks the UI thread to drain `Ui.executor()`'s queue. Callable from any
/// thread. Before the loop runs, the wake is kept and sent once it does.
pub fn wake() -> bool {
    let guard = WAKE.lock().unwrap_or_else(|p| p.into_inner());
    match guard.as_ref() {
        Some(p) => p.send(Msg::new(0, ev::WAKE)).is_ok(),
        None => {
            EARLY.store(true, Ordering::SeqCst);
            true
        }
    }
}

/// Ends the loop: `Ui.quit()`.
pub fn quit() {
    for w in table::windows() {
        w.ui.quit();
    }
}
