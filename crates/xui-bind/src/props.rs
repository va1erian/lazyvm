//! Changing and reading widgets after they are built: one entry point,
//! dispatched on (widget kind, op) to xui's typed methods (docs/ui.md,
//! "Changing a widget"). Ops are shared with `lazy.ui.Op`.

use xui_core::Ui;
use xui_core::widget::{HasText, MenuId};

use crate::Tree;
use crate::msg::Msg;
use crate::table::{self, Widget};

pub mod op {
    pub const TEXT: i32 = 1;
    pub const GET_TEXT: i32 = 2;
    pub const ENABLED: i32 = 3;
    pub const VISIBLE: i32 = 4;
    pub const FOCUS: i32 = 5;
    pub const CHECKED: i32 = 6;
    pub const GET_CHECKED: i32 = 7;
    pub const SELECT: i32 = 8;
    pub const GET_SELECTED: i32 = 9;
    pub const VALUE: i32 = 10;
    pub const GET_VALUE: i32 = 11;
    pub const PART: i32 = 12;
    pub const ROWS: i32 = 13;
    pub const TOOLTIP: i32 = 15;
    pub const OPEN: i32 = 16;
    pub const CLOSE: i32 = 17;
    pub const TITLE: i32 = 18;
    pub const ITEM_ENABLED: i32 = 19;
    pub const ITEM_CHECKED: i32 = 20;
    pub const DRAWING: i32 = 21;
    pub const BOUNDS: i32 = 23;
    pub const NAME: i32 = 24;
    pub const DIR: i32 = 25;
    pub const MESSAGE: i32 = 27;
}

/// An argument from Java, already copied off the heap.
pub enum Arg {
    None,
    Str(String),
    Strs(Vec<String>),
    Ints(Vec<i32>),
    Bytes(Vec<u8>),
    /// A picture's alt text and file bytes.
    Image(String, Vec<u8>),
    Tree(Tree),
}

/// A result for Java.
#[derive(Debug, PartialEq)]
pub enum Ret {
    None,
    Int(i32),
    Double(f64),
    Str(String),
    Ints(Vec<i32>),
    Bytes(Vec<u8>),
    /// A string and byte arrays (rich text's Markdown with its images).
    Export(String, Vec<Vec<u8>>),
}

fn bad(w: &Widget, op: i32) -> String {
    let name = match w {
        Widget::Inert => "this widget",
        _ => "the widget",
    };
    format!("op {op} does not apply to {name}")
}

fn text(arg: &Arg) -> Result<&str, String> {
    match arg {
        Arg::Str(s) => Ok(s),
        _ => Err("a string is required".into()),
    }
}

fn index(i: i32, len: usize) -> Result<usize, String> {
    usize::try_from(i)
        .ok()
        .filter(|&i| i < len)
        .ok_or_else(|| format!("index {i} out of range 0..{len}"))
}

/// Runs `op` on widget `id`.
pub fn call(id: i32, op: i32, i: i32, d: f64, arg: Arg) -> Result<Ret, String> {
    table::count_set();
    let (w, window) = table::get(id)?;
    let gone = || format!("widget {id} is not built");
    let ui = table::window(window)?.ui.clone();
    #[cfg(feature = "rich-text")]
    if let Widget::Rich(h) = &w
        && (op >= 100 || op == op::FOCUS)
    {
        let editor = h.try_get().ok_or_else(gone)?;
        return crate::rich::call(&editor, op, i, d, arg);
    }
    match op {
        op::ENABLED | op::VISIBLE | op::FOCUS | op::BOUNDS if !matches!(w, Widget::Menu(_)) => {
            let node = w.node().ok_or_else(gone)?;
            return Ok(node_op(&ui, node, op, i != 0));
        }
        op::TOOLTIP => {
            let tip = table::tooltip(id).ok_or("the widget was built without a tooltip")?;
            tip.set_text(text(&arg)?);
            return Ok(Ret::None);
        }
        op::DRAWING => {
            let Widget::Canvas(h) = &w else {
                return Err(bad(&w, op));
            };
            let Arg::Tree(tree) = arg else {
                return Err("a drawing is required".into());
            };
            let mut dec = crate::build::Decoder {
                r: crate::reader::Reader::new(&tree),
                ctx: crate::build::Ctx {
                    window,
                    owner: None,
                },
                ids: Vec::new(),
                pending: Vec::new(),
            };
            let drawing = crate::widgets::drawing(&mut dec)?;
            h.try_get().ok_or_else(gone)?.set(drawing);
            return Ok(Ret::None);
        }
        _ => {}
    }
    widget_op(&w, op, i, d, &arg).ok_or_else(gone)?
}

fn node_op(ui: &Ui<Msg>, node: xui_core::backend::WidgetId, op: i32, on: bool) -> Ret {
    match op {
        op::ENABLED => ui.set_enabled(node, on),
        op::VISIBLE => {
            ui.set_visible(node, on);
            ui.invalidate_layout();
        }
        op::FOCUS => ui.focus(node),
        _ => {
            let r = ui.bounds(node);
            return Ret::Ints(vec![r.left, r.top, r.right, r.bottom, ui.dpi() as i32]);
        }
    }
    Ret::None
}

/// The per-kind ops; `None` when the widget is not built (yet, or any more).
fn widget_op(w: &Widget, op: i32, i: i32, d: f64, arg: &Arg) -> Option<Result<Ret, String>> {
    let ok = |r| Some(Ok(r));
    let none = Some(Ok(Ret::None));
    let err = |e: String| Some(Err(e));
    macro_rules! t {
        ($e:expr) => {
            match $e {
                Ok(v) => v,
                Err(e) => return err(String::from(e)),
            }
        };
    }
    match (w, op) {
        (Widget::Label(h), op::TEXT) => h.try_get()?.set_text(t!(text(arg))),
        (Widget::Button(h), op::TEXT) => h.try_get()?.set_text(t!(text(arg))),
        (Widget::Toggle(h), op::TEXT) => h.try_get()?.set_text(t!(text(arg))),
        (Widget::Check(h), op::TEXT) => h.try_get()?.set_text(t!(text(arg))),
        (Widget::Edit(h), op::TEXT) => h.try_get()?.set_text(t!(text(arg))),
        (Widget::TextArea(h), op::TEXT) => h.try_get()?.set_text(t!(text(arg))),
        (Widget::Edit(h), op::GET_TEXT) => return ok(Ret::Str(h.try_get()?.text())),
        (Widget::TextArea(h), op::GET_TEXT) => return ok(Ret::Str(h.try_get()?.text())),
        (Widget::Toggle(h), op::CHECKED) => h.try_get()?.set_checked(i != 0),
        (Widget::Check(h), op::CHECKED) => h.try_get()?.set_checked(i != 0),
        (Widget::Toggle(h), op::GET_CHECKED) => {
            return ok(Ret::Int(h.try_get()?.is_checked() as i32));
        }
        (Widget::Check(h), op::GET_CHECKED) => {
            return ok(Ret::Int(h.try_get()?.is_checked() as i32));
        }
        (Widget::Combo(h, n), op::SELECT) => h.try_get()?.select(t!(index(i, *n))),
        (Widget::Combo(h, _), op::GET_SELECTED) => {
            return ok(Ret::Int(h.try_get()?.selected() as i32));
        }
        (Widget::List(h), op::SELECT) => {
            let l = h.try_get()?;
            let row = match i {
                -1 => None,
                _ => Some(t!(index(i, l.len()))),
            };
            l.select(row);
        }
        (Widget::List(h), op::GET_SELECTED) => {
            let s = h.try_get()?.selected().map_or(-1, |s| s as i32);
            return ok(Ret::Int(s));
        }
        (Widget::List(h), op::ROWS) => {
            let Arg::Strs(cells) = arg else {
                return err("cells are required".into());
            };
            let columns = t!(usize::try_from(i)
                .ok()
                .filter(|&c| c > 0)
                .ok_or("bad column count"));
            if cells.len() % columns != 0 {
                return err(format!(
                    "{} cells do not fill rows of {columns}",
                    cells.len()
                ));
            }
            let rows: Vec<Vec<String>> = cells.chunks(columns).map(<[String]>::to_vec).collect();
            h.try_get()?.set_model(rows);
        }
        (Widget::Tabs(h), op::SELECT) => {
            let tabs = h.try_get()?;
            tabs.select(t!(index(i, tabs.page_count())));
        }
        (Widget::Tabs(h), op::GET_SELECTED) => return ok(Ret::Int(h.try_get()?.selected() as i32)),
        (Widget::Slider(h), op::VALUE) => h.try_get()?.set_value(d),
        (Widget::Slider(h), op::GET_VALUE) => return ok(Ret::Double(h.try_get()?.value())),
        (Widget::Progress(h), op::VALUE) => {
            let bar = h.try_get()?;
            if !(0..=bar.max()).contains(&i) {
                return err(format!("progress {i} of {}", bar.max()));
            }
            bar.set_value(i);
        }
        (Widget::Status(h), op::PART) => {
            let bar = h.try_get()?;
            bar.set_text(t!(index(i, bar.len())), t!(text(arg)));
        }
        (Widget::Dialog(dg), op::OPEN) => dg.open(),
        (Widget::Dialog(dg), op::CLOSE) => dg.close(),
        (Widget::Dialog(dg), op::TITLE) => dg.set_title(t!(text(arg))),
        (Widget::Dialog(dg), op::MESSAGE) => dg.set_message(t!(text(arg))),
        (Widget::Task(dg), op::OPEN) => dg.open(),
        (Widget::Task(dg), op::CLOSE) => dg.close(),
        (Widget::Task(dg), op::TITLE) => dg.set_title(t!(text(arg))),
        (Widget::Task(dg), op::GET_CHECKED) => return ok(Ret::Int(dg.is_checked() as i32)),
        (Widget::File(dg), op::OPEN) => dg.open(),
        (Widget::File(dg), op::CLOSE) => dg.close(),
        (Widget::File(dg), op::NAME) => dg.set_suggested_name(t!(text(arg))),
        (Widget::File(dg), op::DIR) => dg.set_initial_dir(t!(text(arg))),
        (Widget::Menu(m), op::OPEN) => m.show_context(i, d as i32),
        (Widget::Menu(m), op::CLOSE) => m.close(),
        (Widget::Menu(m), op::ITEM_ENABLED) => m.set_enabled(t!(menu_id(i)), d != 0.0),
        (Widget::Menu(m), op::ITEM_CHECKED) => m.set_checked(t!(menu_id(i)), d != 0.0),
        _ => return err(bad(w, op)),
    }
    none
}

fn menu_id(i: i32) -> Result<MenuId, String> {
    usize::try_from(i)
        .map(MenuId::new)
        .map_err(|_| format!("menu id {i}"))
}
