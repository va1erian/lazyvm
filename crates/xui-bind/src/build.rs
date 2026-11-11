//! Decoding an op list into xui layouts (docs/ui.md, "Building").
//!
//! The list is the tree in pre-order. Each node starts with a header:
//! `kind id sizing a b flags [tooltip]`, where `sizing` is one of
//! [`sizing`]'s values with its arguments `a` and `b`, and `flags` holds
//! [`flag`] bits; the tooltip string follows when its bit is set. What
//! comes next depends on the kind (see `lazy.ui.Encoder`).

use xui_core::arrange::{
    Build, Entry, Handle, IntoEntry, Layout, LayoutExt, column, group, panel, row, scroll, spacer,
    tabs, wrap,
};
use xui_core::layout::{Align, Insets};
use xui_core::widget::{Placeable, Tooltip};
use xui_core::{Dip, Ui};

use crate::msg::{Msg, kind as ev};
use crate::reader::{MAX_DEPTH, Reader};
use crate::table::{self, Widget};
use crate::widgets;

/// Node kinds, shared with `lazy.ui.Encoder`.
pub mod op {
    pub const COLUMN: i32 = 1;
    pub const ROW: i32 = 2;
    pub const WRAP: i32 = 3;
    pub const LABEL: i32 = 10;
    pub const BUTTON: i32 = 11;
    pub const TOGGLE: i32 = 12;
    pub const CHECK: i32 = 13;
    pub const EDIT: i32 = 14;
    pub const TEXT_AREA: i32 = 15;
    pub const COMBO: i32 = 16;
    pub const LIST: i32 = 17;
    pub const SLIDER: i32 = 18;
    pub const PROGRESS: i32 = 19;
    pub const STATUS: i32 = 20;
    pub const TOOLBAR: i32 = 21;
    pub const SEPARATOR: i32 = 22;
    pub const SPACER: i32 = 23;
    pub const PANEL: i32 = 30;
    pub const SCROLL: i32 = 31;
    pub const TABS: i32 = 32;
    pub const GROUP: i32 = 33;
    pub const CANVAS: i32 = 40;
    #[cfg(feature = "rich-text")]
    pub const RICH: i32 = 50;
}

pub mod sizing {
    pub const AUTO: i32 = 0;
    pub const FILL: i32 = 1;
    pub const FIXED: i32 = 2;
    pub const MIN: i32 = 3;
    pub const WIDTH: i32 = 4;
    pub const HEIGHT: i32 = 5;
    pub const SIZE: i32 = 6;
}

pub mod flag {
    pub const DISABLED: i32 = 1;
    pub const HIDDEN: i32 = 2;
    pub const TOOLTIP: i32 = 4;
}

/// Where the decoded widgets go: their window, and the panel whose content
/// they are, if any.
#[derive(Clone, Copy)]
pub struct Ctx {
    pub window: i32,
    pub owner: Option<i32>,
}

/// A node's header, after its kind.
pub struct Head {
    pub id: i32,
    sizing: (i32, i32, i32),
    disabled: bool,
    hidden: bool,
    tooltip: Option<String>,
}

pub struct Decoder<'a> {
    pub r: Reader<'a>,
    pub ctx: Ctx,
    /// Every id inserted so far, so a failed decode can take them out again.
    pub ids: Vec<i32>,
    /// Containers whose header asks for something only a built node can
    /// take: applied by [`apply`] once the layout is mounted.
    pub pending: Vec<Head>,
}

impl Head {
    fn flagged(&self) -> bool {
        self.disabled || self.hidden || self.tooltip.is_some()
    }

    /// An arrangement or a spacer has no node to disable, hide or tip.
    fn nodeless(self) -> Result<i32, String> {
        match self.flagged() {
            true => Err(
                "a layout or spacer has no node: it cannot be disabled, hidden or tipped".into(),
            ),
            false => Ok(self.id),
        }
    }
}

impl Decoder<'_> {
    fn head(&mut self) -> Result<Head, String> {
        let id = self.r.int()?;
        let kind = self.r.int()?;
        let (a, b) = (self.r.int()?, self.r.int()?);
        let fill_ok = kind == sizing::FILL && (0..=1000).contains(&a);
        let len = |v: i32| (0..=crate::reader::MAX_LENGTH).contains(&v);
        let ok = match kind {
            sizing::AUTO => true,
            sizing::FILL => fill_ok,
            sizing::SIZE => len(a) && len(b),
            sizing::FIXED | sizing::MIN | sizing::WIDTH | sizing::HEIGHT => len(a),
            _ => false,
        };
        if !ok {
            return Err(format!("bad sizing {kind} {a} {b}"));
        }
        let flags = self.r.int()?;
        let tooltip = if flags & flag::TOOLTIP != 0 {
            Some(self.r.string()?)
        } else {
            None
        };
        Ok(Head {
            id,
            sizing: (kind, a, b),
            disabled: flags & flag::DISABLED != 0,
            hidden: flags & flag::HIDDEN != 0,
            tooltip,
        })
    }

    pub fn insert(&mut self, id: i32, widget: Widget) -> Result<(), String> {
        table::insert(id, self.ctx.window, self.ctx.owner, widget)?;
        self.ids.push(id);
        Ok(())
    }

    /// A layout node: the window's root, a panel's content, a page.
    pub fn layout(&mut self, depth: usize) -> Result<Layout<Msg>, String> {
        let kind = self.r.int()?;
        let head = self.head()?;
        if head.sizing.0 != sizing::AUTO {
            return Err("a root layout takes no sizing".into());
        }
        let id = head.nodeless()?;
        self.arrangement(kind, id, depth)
    }

    fn arrangement(&mut self, kind: i32, id: i32, depth: usize) -> Result<Layout<Msg>, String> {
        if depth > MAX_DEPTH {
            return Err("tree nests too deep".into());
        }
        let mut layout = match kind {
            op::COLUMN => column(),
            op::ROW => row(),
            op::WRAP => wrap(),
            _ => return Err(format!("op {kind} is not a layout")),
        };
        self.insert(id, Widget::Inert)?;
        let pad = [
            self.r.length()?,
            self.r.length()?,
            self.r.length()?,
            self.r.length()?,
        ];
        let d = |v: i32| Dip(v as f32);
        layout = layout
            .padding(Insets::new(d(pad[0]), d(pad[1]), d(pad[2]), d(pad[3])))
            .gap(self.r.length()?);
        if let Some(a) = align(self.r.int()?)? {
            layout = layout.align(a);
        }
        if let Some(a) = align(self.r.int()?)? {
            layout = layout.justify(a);
        }
        // Each child takes at least its kind and a five-int header (a spacer).
        let n = self.r.count(6)?;
        let mut children = Vec::with_capacity(n);
        for _ in 0..n {
            children.push(self.entry(depth + 1)?);
        }
        Ok(layout.children(children))
    }

    fn entry(&mut self, depth: usize) -> Result<Entry<Msg>, String> {
        let kind = self.r.int()?;
        let mut head = self.head()?;
        let sizing = head.sizing;
        if matches!(kind, op::PANEL | op::SCROLL | op::GROUP | op::TABS) && head.flagged() {
            // Their builders take no hook: the flags apply once mounted.
            self.pending.push(Head {
                id: head.id,
                sizing,
                disabled: head.disabled,
                hidden: head.hidden,
                tooltip: head.tooltip.take(),
            });
        }
        let entry = match kind {
            op::COLUMN | op::ROW | op::WRAP => {
                let id = head.nodeless()?;
                self.arrangement(kind, id, depth)?.into_entry()
            }
            op::SPACER => {
                let id = head.nodeless()?;
                self.insert(id, Widget::Inert)?;
                spacer()
            }
            op::PANEL => {
                let handle = Handle::new();
                self.insert(head.id, Widget::Panel(handle.clone()))?;
                let plain = self.r.flag()?;
                let inner = Ctx {
                    owner: Some(head.id),
                    ..self.ctx
                };
                let content = self.nested(inner, depth)?;
                let p = panel(content).bind(&handle);
                if plain { p.plain() } else { p }.into_entry()
            }
            op::SCROLL => {
                let handle = Handle::new();
                self.insert(head.id, Widget::Scroll(handle.clone()))?;
                scroll(self.layout(depth + 1)?).bind(&handle).into_entry()
            }
            op::GROUP => {
                let handle = Handle::new();
                self.insert(head.id, Widget::Group(handle.clone()))?;
                let title = self.r.string()?;
                group(title, self.layout(depth + 1)?)
                    .bind(&handle)
                    .into_entry()
            }
            op::TABS => self.tabs(head.id, depth)?,
            _ => widgets::leaf(self, kind, &head)?,
        };
        Ok(size(entry, sizing))
    }

    /// A layout decoded with another context (a panel's content).
    fn nested(&mut self, ctx: Ctx, depth: usize) -> Result<Layout<Msg>, String> {
        let outer = self.ctx;
        self.ctx = ctx;
        let layout = self.layout(depth + 1);
        self.ctx = outer;
        layout
    }

    fn tabs(&mut self, id: i32, depth: usize) -> Result<Entry<Msg>, String> {
        let handle = Handle::new();
        self.insert(id, Widget::Tabs(handle.clone()))?;
        let n = self.r.count(8)?;
        let mut t = tabs();
        for _ in 0..n {
            let title = self.r.string()?;
            t = t.page(title, self.layout(depth + 1)?);
        }
        if self.r.flag()? {
            t = t.on_change(move |page| Msg::int(id, ev::SELECT, page as i32));
        }
        Ok(t.bind(&handle).into_entry())
    }
}

/// Applies the header's enabled, visible and tooltip options once `b` is
/// built, and records it in the table.
pub fn finish<W: Placeable<Msg> + 'static>(
    d: &mut Decoder,
    head: &Head,
    widget: Widget,
    b: Build<W, Msg>,
) -> Result<Entry<Msg>, String> {
    d.insert(head.id, widget)?;
    let (id, disabled, hidden) = (head.id, head.disabled, head.hidden);
    let tip = head.tooltip.clone();
    let b = b.then_with(move |w, ui: &Ui<Msg>| {
        if disabled {
            ui.set_enabled(w.id(), false);
        }
        if hidden {
            ui.set_visible(w.id(), false);
        }
        if let Some(tip) = tip {
            table::attach_tooltip(id, Tooltip::attach(ui, w.id(), &tip)?);
        }
        Ok(w)
    });
    Ok(b.into_entry())
}

fn size(entry: Entry<Msg>, (kind, a, b): (i32, i32, i32)) -> Entry<Msg> {
    let d = |v: i32| Dip(v as f32);
    match kind {
        sizing::FILL => entry.fill(a as u32),
        sizing::FIXED => entry.fixed(d(a)),
        sizing::MIN => entry.min(d(a)),
        sizing::WIDTH => entry.width(d(a)),
        sizing::HEIGHT => entry.height(d(a)),
        sizing::SIZE => entry.size(d(a), d(b)),
        _ => entry,
    }
}

fn align(v: i32) -> Result<Option<Align>, String> {
    Ok(Some(match v {
        -1 => return Ok(None),
        0 => Align::Start,
        1 => Align::Center,
        2 => Align::End,
        3 => Align::Stretch,
        _ => return Err(format!("bad alignment {v}")),
    }))
}

/// Applies the containers' flags once their layout is mounted.
pub fn apply(pending: Vec<Head>, ui: &Ui<Msg>) -> Result<(), String> {
    for head in pending {
        let (widget, _) = table::get(head.id)?;
        let node = widget
            .node()
            .ok_or_else(|| format!("widget {} is not built", head.id))?;
        if head.disabled {
            ui.set_enabled(node, false);
        }
        if head.hidden {
            ui.set_visible(node, false);
        }
        if let Some(tip) = head.tooltip {
            let tip = Tooltip::attach(ui, node, &tip).map_err(|e| format!("{e:?}"))?;
            table::attach_tooltip(head.id, tip);
        }
    }
    Ok(())
}

/// The root layout of `tree` for `ctx`, and the flags to [`apply`] once it
/// is mounted; the list must hold exactly one layout. On an error nothing
/// stays in the table.
pub fn decode(tree: &crate::Tree, ctx: Ctx) -> Result<(Layout<Msg>, Vec<Head>), String> {
    let mut d = Decoder {
        r: Reader::new(tree),
        ctx,
        ids: Vec::new(),
        pending: Vec::new(),
    };
    let root = d.layout(1).and_then(|root| match d.r.done() {
        true => Ok(root),
        false => Err("op list has trailing ops".to_string()),
    });
    if root.is_err() {
        drop(table::remove(&d.ids));
    }
    root.map(|root| (root, d.pending))
}
