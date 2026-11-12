//! The xui `App` behind every `lazy.ui` window: it forwards each message to
//! Java and stops for good once Java throws (docs/ui.md, "When a callback
//! throws").

use std::cell::{Cell, RefCell};
use std::time::Instant;

use xui_core::{App, Ui};

use crate::msg::Msg;
use crate::{Dispatch, Stats};

thread_local! {
    /// Set once Java threw: no further callback starts, in any window.
    static STOPPED: Cell<bool> = const { Cell::new(false) };
    static STATS: RefCell<Stats> = RefCell::new(Stats::default());
    static DISPATCH: RefCell<Option<Dispatch>> = const { RefCell::new(None) };
}

pub fn start(dispatch: Dispatch) {
    STOPPED.with(|s| s.set(false));
    STATS.with(|s| *s.borrow_mut() = Stats::default());
    DISPATCH.with(|d| *d.borrow_mut() = Some(dispatch));
}

pub fn finish() -> Stats {
    DISPATCH.with(|d| d.borrow_mut().take());
    STATS.with(|s| std::mem::take(&mut *s.borrow_mut()))
}

pub fn stopped() -> bool {
    STOPPED.with(Cell::get)
}

pub fn with_stats(f: impl FnOnce(&mut Stats)) {
    STATS.with(|s| f(&mut s.borrow_mut()));
}

/// Hands `msg` to Java, unless Java already threw. False when Java threw,
/// now or before.
pub fn deliver(msg: &Msg) -> bool {
    if stopped() {
        return false;
    }
    let Some(dispatch) = DISPATCH.with(|d| d.borrow().clone()) else {
        return false;
    };
    let start = Instant::now();
    let ok = dispatch(msg);
    let us = start.elapsed().as_micros() as u64;
    with_stats(|s| {
        s.upcalls += 1;
        s.upcall_us += us;
        s.upcall_max_us = s.upcall_max_us.max(us);
    });
    if !ok {
        STOPPED.with(|s| s.set(true));
    }
    ok
}

pub struct JavaApp;

impl App for JavaApp {
    type Msg = Msg;

    fn update(&mut self, msg: Msg, ui: &mut Ui<Msg>) {
        // xui keeps draining its queue after a quit; no Java runs past a throw.
        if !deliver(&msg) {
            ui.quit();
        }
    }
}
