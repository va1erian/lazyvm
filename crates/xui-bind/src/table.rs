//! The widgets and windows Java can still reach, by the id Java gave them
//! (docs/ui.md, "Identity and lifetime").
//!
//! Entries hold `Rc`s only, and callers clone what they need out of the
//! table before they call into xui: a call that runs a nested loop (a modal
//! window) may come back here, so no borrow is held across one.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use xui_core::Ui;
use xui_core::arrange::Handle;
use xui_core::backend::{TimerId, WidgetId};
use xui_core::widget::{
    Button, CheckBox, ComboBox, Dialog, Edit, FileDialog, GroupBox, Label, ListView, Menu,
    MultilineEdit, Panel, Placeable, ProgressBar, ScrollView, Slider, StatusBar, Tabs, TaskDialog,
    ToggleButton, Toolbar, Tooltip,
};

use crate::msg::Msg;

/// One reachable thing. Every variant is cheap to clone.
#[derive(Clone)]
pub enum Widget {
    Label(Handle<Label<Msg>>),
    Button(Handle<Button<Msg>>),
    Toggle(Handle<ToggleButton<Msg>>),
    Check(Handle<CheckBox<Msg>>),
    Edit(Handle<Edit<Msg>>),
    TextArea(Handle<MultilineEdit<Msg>>),
    /// A combo box and its item count.
    Combo(Handle<ComboBox<Msg>>, usize),
    List(Handle<ListView<Msg>>),
    Slider(Handle<Slider<Msg>>),
    Progress(Handle<ProgressBar<Msg>>),
    Status(Handle<StatusBar<Msg>>),
    Toolbar(Handle<Toolbar<Msg>>),
    Panel(Handle<Panel<Msg>>),
    Tabs(Handle<Tabs<Msg>>),
    Scroll(Handle<ScrollView<Msg>>),
    Group(Handle<GroupBox<Msg>>),
    Canvas(Handle<crate::canvas::Canvas>),
    #[cfg(feature = "rich-text")]
    Rich(Handle<xui_rich_text::RichTextEditor<Msg>>),
    Dialog(Rc<Dialog<Msg>>),
    Task(Rc<TaskDialog<Msg>>),
    File(Rc<FileDialog<Msg>>),
    Menu(Rc<Menu<Msg>>),
    Timer(TimerId),
    /// An arrangement or a spacer: nothing to change once built.
    Inert,
}

impl Widget {
    /// The widget's node, once built.
    pub fn node(&self) -> Option<WidgetId> {
        fn of<W>(h: &Handle<W>, id: impl Fn(&W) -> WidgetId) -> Option<WidgetId> {
            h.try_get().map(|w| id(&w))
        }
        match self {
            Widget::Label(h) => of(h, |w| w.id()),
            Widget::Button(h) => of(h, |w| w.id()),
            Widget::Toggle(h) => of(h, |w| w.id()),
            Widget::Check(h) => of(h, |w| w.id()),
            Widget::Edit(h) => of(h, |w| w.id()),
            Widget::TextArea(h) => of(h, |w| w.id()),
            Widget::Combo(h, _) => of(h, |w| w.id()),
            Widget::List(h) => of(h, |w| w.id()),
            Widget::Slider(h) => of(h, |w| w.id()),
            Widget::Progress(h) => of(h, |w| w.id()),
            Widget::Status(h) => of(h, |w| w.id()),
            Widget::Toolbar(h) => of(h, |w| w.id()),
            Widget::Panel(h) => of(h, |w| w.id()),
            Widget::Tabs(h) => of(h, |w| w.id()),
            Widget::Scroll(h) => of(h, |w| w.id()),
            Widget::Group(h) => of(h, |w| w.id()),
            Widget::Canvas(h) => of(h, |w| w.id()),
            #[cfg(feature = "rich-text")]
            Widget::Rich(h) => of(h, |w| w.id()),
            Widget::Dialog(d) => Some(d.id()),
            Widget::Task(d) => Some(d.id()),
            Widget::File(d) => Some(d.id()),
            Widget::Menu(m) => Menu::id(m),
            Widget::Timer(_) | Widget::Inert => None,
        }
    }
}

struct Slot {
    window: i32,
    /// The panel whose content this widget is, if any: replacing that
    /// content removes it.
    owner: Option<i32>,
    widget: Widget,
    tooltip: Option<Rc<Tooltip<Msg>>>,
}

/// A window Java opened.
pub struct WindowState {
    pub ui: Ui<Msg>,
}

#[derive(Default)]
struct Table {
    slots: HashMap<i32, Slot>,
    windows: HashMap<i32, Rc<WindowState>>,
    timers: HashMap<TimerId, i32>,
}

thread_local! {
    static TABLE: RefCell<Table> = RefCell::new(Table::default());
    static SETS: Cell<u64> = const { Cell::new(0) };
}

pub fn insert(id: i32, window: i32, owner: Option<i32>, widget: Widget) -> Result<(), String> {
    TABLE.with(|t| {
        let mut t = t.borrow_mut();
        if t.slots.contains_key(&id) {
            return Err(format!("widget id {id} used twice"));
        }
        if let Widget::Timer(timer) = &widget {
            t.timers.insert(*timer, id);
        }
        let slot = Slot {
            window,
            owner,
            widget,
            tooltip: None,
        };
        t.slots.insert(id, slot);
        Ok(())
    })
}

pub fn attach_tooltip(id: i32, tip: Tooltip<Msg>) {
    TABLE.with(|t| {
        if let Some(slot) = t.borrow_mut().slots.get_mut(&id) {
            slot.tooltip = Some(Rc::new(tip));
        }
    });
}

pub fn tooltip(id: i32) -> Option<Rc<Tooltip<Msg>>> {
    TABLE.with(|t| t.borrow().slots.get(&id)?.tooltip.clone())
}

/// The widget `id` names and its window's id, or an error Java turns into
/// an exception.
pub fn get(id: i32) -> Result<(Widget, i32), String> {
    TABLE.with(|t| {
        let t = t.borrow();
        let slot = t.slots.get(&id).ok_or_else(|| format!("no widget {id}"))?;
        Ok((slot.widget.clone(), slot.window))
    })
}

pub fn window(id: i32) -> Result<Rc<WindowState>, String> {
    TABLE
        .with(|t| t.borrow().windows.get(&id).cloned())
        .ok_or_else(|| format!("no open window {id}"))
}

pub fn windows() -> Vec<Rc<WindowState>> {
    TABLE.with(|t| t.borrow().windows.values().cloned().collect())
}

pub fn add_window(id: i32, state: WindowState) {
    TABLE.with(|t| t.borrow_mut().windows.insert(id, Rc::new(state)));
}

/// The Java id of the timer xui calls `timer`.
pub fn timer_owner(timer: TimerId) -> Option<i32> {
    TABLE.with(|t| t.borrow().timers.get(&timer).copied())
}

/// Removes `id` and, recursively, every widget a panel among them owned.
/// Returns what was removed, so the caller drops it outside the borrow.
pub fn remove(ids: &[i32]) -> Vec<Widget> {
    let mut out = Vec::new();
    TABLE.with(|t| {
        let mut t = t.borrow_mut();
        let mut queue: Vec<i32> = ids.to_vec();
        while let Some(id) = queue.pop() {
            if let Some(slot) = t.slots.remove(&id) {
                if let Widget::Timer(timer) = &slot.widget {
                    t.timers.remove(timer);
                }
                out.push(slot.widget);
            }
            t.windows.remove(&id);
            queue.extend(owned_by(&t, id));
        }
    });
    out
}

fn owned_by(t: &Table, panel: i32) -> Vec<i32> {
    t.slots
        .iter()
        .filter(|(_, s)| s.owner == Some(panel))
        .map(|(&id, _)| id)
        .collect()
}

/// The widgets a panel's current content holds.
pub fn content_of(panel: i32) -> Vec<i32> {
    TABLE.with(|t| owned_by(&t.borrow(), panel))
}

/// Every widget of window `window`, for its closing.
pub fn of_window(window: i32) -> Vec<i32> {
    TABLE.with(|t| {
        let t = t.borrow();
        let ids = t.slots.iter().filter(|(_, s)| s.window == window);
        ids.map(|(&id, _)| id).collect()
    })
}

pub fn clear() {
    let old = TABLE.with(|t| std::mem::take(&mut *t.borrow_mut()));
    drop(old);
    SETS.with(|s| s.set(0));
}

pub fn count_set() {
    SETS.with(|s| s.set(s.get() + 1));
}

pub fn sets() -> u64 {
    SETS.with(Cell::get)
}
