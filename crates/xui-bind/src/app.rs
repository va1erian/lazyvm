//! The xui `App` behind every `lazy.ui` window: it forwards each message to
//! Java and stops the loop for good once Java throws (docs/ui.md, "When a
//! callback throws").

use std::cell::Cell;
use std::rc::Rc;
use std::time::Instant;

use xui_core::{App, Ui};

use crate::{Dispatch, Stats};

/// A widget message: which Java widget, and what happened to it.
#[derive(Clone, Copy, Debug)]
pub(crate) struct Msg {
    pub id: i32,
    pub kind: i32,
}

pub(crate) struct JavaApp {
    pub dispatch: Dispatch,
    pub stopped: bool,
    pub stats: Rc<Cell<Stats>>,
}

impl App for JavaApp {
    type Msg = Msg;

    fn update(&mut self, msg: Msg, ui: &mut Ui<Msg>) {
        // xui keeps draining its queue after a quit; no Java runs past a throw.
        if self.stopped {
            return;
        }
        let start = Instant::now();
        let ok = (self.dispatch)(msg.id, msg.kind);
        let us = start.elapsed().as_micros() as u64;
        let mut s = self.stats.get();
        s.upcalls += 1;
        s.upcall_us += us;
        s.upcall_max_us = s.upcall_max_us.max(us);
        self.stats.set(s);
        if !ok {
            self.stopped = true;
            ui.quit();
        }
    }
}
