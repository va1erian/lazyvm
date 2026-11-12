//! Widgets that live outside the layout: dialogs, file pickers and context
//! menus. Java makes each on its first `open`, in the window it belongs to:
//! `kind id` and then the fields below, as `lazy.ui.Encoder` writes them.

use std::path::PathBuf;
use std::rc::Rc;

use xui_core::Ui;
use xui_core::widget::{
    Dialog, DialogAction, FileDialog, Menu, MenuId, TaskDialog, TaskDialogAction, TaskDialogIcon,
};

use crate::msg::{Msg, kind as ev};
use crate::reader::Reader;
use crate::table::{self, Widget};

pub mod op {
    /// `style title message initial? accept? cancel?`; style 0 message,
    /// 1 confirm, 2 prompt.
    pub const DIALOG: i32 = 60;
    /// `title message icon commands verification?`.
    pub const TASK: i32 = 61;
    /// `save title dir? name? existing filters` with each filter
    /// `label extensions`.
    pub const FILE: i32 = 62;
    /// `entries` with each `type id text checked icon?`; type 0 item,
    /// 1 check, 2 separator, 3 submenu, 4 radio.
    pub const MENU: i32 = 63;
}

fn xe(e: impl std::fmt::Debug) -> String {
    format!("{e:?}")
}

/// Decodes one popup into window `window`.
pub fn make(tree: &crate::Tree, window: i32) -> Result<(), String> {
    let ui = table::window(window)?.ui.clone();
    let mut r = Reader::new(tree);
    let kind = r.int()?;
    let id = r.int()?;
    let widget = match kind {
        op::DIALOG => Widget::Dialog(Rc::new(dialog(&mut r, &ui, id)?)),
        op::TASK => Widget::Task(Rc::new(task(&mut r, &ui, id)?)),
        op::FILE => Widget::File(Rc::new(file(&mut r, &ui, id)?)),
        op::MENU => Widget::Menu(Rc::new(menu(&mut r, &ui, id)?)),
        _ => return Err(format!("op {kind} is not a dialog or menu")),
    };
    if !r.done() {
        return Err("op list has trailing ops".into());
    }
    table::insert(id, window, None, widget)
}

fn dialog(r: &mut Reader, ui: &Ui<Msg>, id: i32) -> Result<Dialog<Msg>, String> {
    let style = r.int()?;
    let (title, message) = (r.string()?, r.string()?);
    let initial = r.opt_string()?.unwrap_or_default();
    let d = match style {
        0 => Dialog::message(ui, &title, &message),
        1 => Dialog::confirm(ui, &title, &message),
        2 => Dialog::prompt(ui, &title, &message, &initial),
        _ => return Err(format!("dialog style {style}")),
    };
    let mut d = d.map_err(xe)?;
    if let Some(label) = r.opt_string()? {
        d = d.accept_label(&label);
    }
    if let Some(label) = r.opt_string()? {
        d = d.cancel_label(&label);
    }
    Ok(d.on_action(move |action| {
        Some(match action {
            DialogAction::Accept(text) => Msg::text(id, ev::DIALOG, text),
            DialogAction::Cancel => Msg::int(id, ev::DIALOG, -1),
        })
    }))
}

fn task(r: &mut Reader, ui: &Ui<Msg>, id: i32) -> Result<TaskDialog<Msg>, String> {
    let (title, message) = (r.string()?, r.string()?);
    let icon = match r.int()? {
        0 => TaskDialogIcon::None,
        1 => TaskDialogIcon::Info,
        2 => TaskDialogIcon::Warning,
        3 => TaskDialogIcon::Error,
        v => return Err(format!("task dialog icon {v}")),
    };
    let mut d = TaskDialog::new(ui, &title, &message)
        .map_err(xe)?
        .icon(icon);
    for command in r.strings()? {
        d = d.command(&command).map_err(xe)?;
    }
    if let Some(label) = r.opt_string()? {
        d = d.verification(&label).map_err(xe)?;
    }
    Ok(d.on_action(move |action| {
        let i = match action {
            TaskDialogAction::Command(i) => i as i32,
            TaskDialogAction::Cancel => -1,
        };
        Some(Msg::int(id, ev::DIALOG, i))
    }))
}

fn file(r: &mut Reader, ui: &Ui<Msg>, id: i32) -> Result<FileDialog<Msg>, String> {
    let save = r.flag()?;
    let title = r.string()?;
    let mut d = match save {
        true => FileDialog::save_file(ui, &title),
        false => FileDialog::open_file(ui, &title),
    }
    .map_err(xe)?;
    if let Some(dir) = r.opt_string()? {
        d = d.initial_dir(PathBuf::from(dir));
    }
    if let Some(name) = r.opt_string()? {
        d = d.suggested_name(&name);
    }
    d = d.require_existing(r.flag()?);
    let n = r.count(2)?;
    for _ in 0..n {
        let label = r.string()?;
        let exts = r.strings()?;
        let exts: Vec<&str> = exts.iter().map(String::as_str).collect();
        d = d.filter(&label, &exts);
    }
    let path = move |p: PathBuf| Some(Msg::text(id, ev::DIALOG, p.to_string_lossy().into()));
    Ok(d.on_accept(path)
        .on_cancel(move || Some(Msg::int(id, ev::DIALOG, -1))))
}

/// One menu entry: an item, a check, a separator, or a submenu of `children`.
struct Item {
    kind: i32,
    id: usize,
    text: String,
    checked: bool,
    icon: Option<xui_core::widget::Lucide>,
    children: Vec<Item>,
}

const MAX_MENU_DEPTH: usize = 4;

/// `n` entries, each `type id text checked icon?`; a submenu (type 3) uses
/// `checked` as its child count, and its children follow it.
fn items(
    r: &mut Reader,
    n: usize,
    depth: usize,
    ids: &mut Vec<usize>,
) -> Result<Vec<Item>, String> {
    if depth > MAX_MENU_DEPTH {
        return Err("menus nest too deep".into());
    }
    let mut out = Vec::with_capacity(n);
    for _ in 0..n {
        let kind = r.int()?;
        let id = usize::try_from(r.int()?).map_err(|_| "negative menu id".to_string())?;
        let text = r.string()?;
        let extra = r.int()?;
        let icon = r
            .opt_string()?
            .map(|s| crate::widgets::icon(&s))
            .transpose()?;
        if ids.contains(&id) {
            return Err(format!("menu id {id} used twice"));
        }
        ids.push(id);
        let children = match kind {
            0..=2 | 4 => Vec::new(),
            3 => {
                // Each child takes at least five ints.
                let count = usize::try_from(extra)
                    .ok()
                    .filter(|&c| c <= r.remaining() / 5);
                let count = count.ok_or_else(|| "bad submenu size".to_string())?;
                items(r, count, depth + 1, ids)?
            }
            _ => return Err(format!("menu entry type {kind}")),
        };
        out.push(Item {
            kind,
            id,
            text,
            checked: extra != 0,
            icon,
            children,
        });
    }
    Ok(out)
}

fn fill(scope: &mut xui_core::widget::MenuScope<'_>, items: &[Item]) {
    for item in items {
        let id = MenuId::new(item.id);
        let entry = match item.kind {
            0 => scope.item(id, &item.text),
            1 => scope.check(id, &item.text, item.checked),
            3 => scope.submenu(id, &item.text, |m| fill(m, &item.children)),
            4 => scope.radio(id, &item.text, item.checked),
            _ => {
                scope.separator();
                continue;
            }
        };
        if let Some(icon) = item.icon {
            entry.icon(icon);
        }
    }
}

/// A context menu. Its messages carry the entry's id, as Java gave it.
fn menu(r: &mut Reader, ui: &Ui<Msg>, id: i32) -> Result<Menu<Msg>, String> {
    let n = r.count(5)?;
    let mut ids = Vec::new();
    let items = items(r, n, 0, &mut ids)?;
    let ids = Rc::new(ids);
    let of = move |ids: &[usize], m: MenuId| ids.iter().copied().find(|&i| MenuId::new(i) == m);
    let toggled = Rc::clone(&ids);
    let menu = Menu::context(ui).build(move |scope| fill(scope, &items));
    Ok(menu
        .on_select(move |m| of(&ids, m).map(|i| Msg::int(id, ev::CLICK, i as i32)))
        .on_toggle(move |m, on| {
            of(&toggled, m).map(|i| Msg {
                d: if on { 1.0 } else { 0.0 },
                ..Msg::int(id, ev::TOGGLE, i as i32)
            })
        }))
}
