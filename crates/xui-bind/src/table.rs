//! The widgets Java can still change, by the id Java gave them.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;

use xui_core::arrange::Handle;
use xui_core::widget::{Button, HasText, Label};

use crate::app::Msg;

pub(crate) enum Widget {
    Label(Handle<Label<Msg>>),
    Button(Handle<Button<Msg>>),
}

thread_local! {
    static WIDGETS: RefCell<HashMap<i32, Widget>> = RefCell::new(HashMap::new());
    static SETS: Cell<u64> = const { Cell::new(0) };
    static CLICK: Cell<Option<i32>> = const { Cell::new(None) };
}

/// Remembers the first button Java listens to, for the headless clicks.
pub(crate) fn listen(id: i32) {
    CLICK.with(|c| c.set(c.get().or(Some(id))));
}

/// The node of the first button Java listens to, once it is built.
pub(crate) fn click_target() -> Option<xui_core::backend::WidgetId> {
    let id = CLICK.with(Cell::get)?;
    WIDGETS.with(|w| match w.borrow().get(&id)? {
        Widget::Button(h) => h.try_get().map(|b| b.id()),
        Widget::Label(_) => None,
    })
}

pub(crate) fn insert(id: i32, widget: Widget) -> Result<(), String> {
    WIDGETS.with(|w| match w.borrow_mut().insert(id, widget) {
        Some(_) => Err(format!("widget id {id} used twice")),
        None => Ok(()),
    })
}

pub(crate) fn clear() {
    WIDGETS.with(|w| w.borrow_mut().clear());
    CLICK.with(|c| c.set(None));
}

pub(crate) fn sets() -> u64 {
    SETS.with(Cell::get)
}

/// Sets a label's or a button's text. An id that names no mounted widget,
/// or a widget not yet built, is an error.
pub fn set_text(id: i32, text: &str) -> Result<(), String> {
    SETS.with(|s| s.set(s.get() + 1));
    WIDGETS.with(|w| {
        let w = w.borrow();
        let missing = || format!("no widget {id}");
        match w.get(&id).ok_or_else(missing)? {
            Widget::Label(h) => h.try_get().ok_or_else(missing)?.set_text(text),
            Widget::Button(h) => h.try_get().ok_or_else(missing)?.set_text(text),
        }
        Ok(())
    })
}
