//! `RichText`: xui's rich-text editor (`xui-rich-text`), the widget a word
//! processor is built on. The document stays in Rust; Java runs commands on
//! it, hears about changes, and loads and saves it whole.

use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;

use xui_core::arrange::{Entry, Handle, build};
use xui_core::geometry::Rect;
use xui_core::image::Image;
use xui_core::Dip;
use xui_rich_text::edit::Command;
use xui_rich_text::format::{ImageExport, from_json, to_json, to_markdown};
use xui_rich_text::model::{
    Align, BlockKind, CharStylePatch, Document, InlineImage, ListKind, PageSetup, Selection,
    Side, StyleSummary, Tri, Wrap, mm,
};
use xui_rich_text::{RichTextEditor, ViewMode};

use crate::build::{Decoder, Head, finish};
use crate::msg::{Msg, kind as ev};
use crate::props::{Arg, Ret};
use crate::table::Widget;

/// Rich-text ops, shared with `lazy.ui.RichText`.
pub mod op {
    pub const EXEC: i32 = 100;
    pub const BLOCK: i32 = 101;
    pub const FAMILY: i32 = 102;
    pub const SIZE: i32 = 103;
    pub const LINK: i32 = 104;
    pub const ALIGN: i32 = 105;
    pub const LIST: i32 = 106;
    pub const WRAP: i32 = 107;
    pub const PAGE_VIEW: i32 = 108;
    pub const PAGE: i32 = 109;
    pub const GET_PAGE: i32 = 110;
    pub const PAGE_INFO: i32 = 111;
    pub const TABLE_CURSOR: i32 = 112;
    pub const INSERT_TABLE: i32 = 113;
    pub const TABLE_SWITCH: i32 = 114;
    pub const GET_JSON: i32 = 115;
    pub const SET_JSON: i32 = 116;
    pub const SET_PLAIN: i32 = 117;
    pub const MARKDOWN: i32 = 118;
    pub const INSERT_IMAGE: i32 = 119;
    pub const WORDS: i32 = 121;
    pub const GET_WRAP: i32 = 122;
}

/// `EXEC`'s argument-free commands, in `lazy.ui.RichText`'s order.
const COMMANDS: [Command; 20] = [
    Command::Undo,
    Command::Redo,
    Command::Cut,
    Command::Copy,
    Command::Paste,
    Command::SelectAll,
    Command::ToggleBold,
    Command::ToggleItalic,
    Command::ToggleUnderline,
    Command::ToggleStrike,
    Command::Indent,
    Command::Outdent,
    Command::InsertPageBreak,
    Command::NextCell,
    Command::PrevCell,
    Command::InsertRow { below: false },
    Command::InsertRow { below: true },
    Command::InsertColumn { right: false },
    Command::InsertColumn { right: true },
    Command::DeleteRows,
];

/// `RICH id ... page listens`: page view on or off; `listens` bits 1
/// changes, 2 the selection's style, 4 links.
pub fn decode(d: &mut Decoder, head: &Head) -> Result<Entry<Msg>, String> {
    let id = head.id;
    let page = d.r.flag()?;
    let listens = d.r.int()?;
    let h = Handle::new();
    let b = build(move |ui| {
        let mut e = RichTextEditor::new(ui, Rect::default())?;
        if listens & 1 != 0 {
            e = e.on_change(move |doc| Some(Msg::int(id, ev::CHANGE, words(doc))));
        }
        if listens & 2 != 0 {
            e = e.on_selection(move |s| Some(style(id, s)));
        }
        if listens & 4 != 0 {
            e = e.on_link(move |url| Some(Msg::text(id, ev::LINK, url.to_owned())));
        }
        let mode = if page { ViewMode::Page } else { ViewMode::Draft };
        Ok(e.view_mode(mode))
    })
    .bind(&h);
    finish(d, head, Widget::Rich(h), b)
}

fn words(doc: &Document) -> i32 {
    doc.to_plain_text().split_whitespace().count().min(i32::MAX as usize) as i32
}

fn tri(t: &Tri<bool>) -> i32 {
    match t {
        Tri::Uniform(false) => 0,
        Tri::Uniform(true) => 1,
        Tri::Mixed => 2,
    }
}

/// The selection's style packed into one message: `i` holds the bits
/// `lazy.ui.RichText.Style` unpacks, `d` the size, `s` the family.
fn style(id: i32, s: &StyleSummary) -> Msg {
    let align = match s.align {
        Tri::Uniform(Align::Left) => 0,
        Tri::Uniform(Align::Center) => 1,
        Tri::Uniform(Align::Right) => 2,
        Tri::Uniform(Align::Justify) => 3,
        _ => 7,
    };
    let list = match &s.list {
        Tri::Uniform(None) => 0,
        Tri::Uniform(Some(item)) if item.kind == ListKind::Bullet => 1,
        Tri::Uniform(Some(_)) => 2,
        Tri::Mixed => 3,
    };
    let kind = match s.kind {
        Tri::Uniform(BlockKind::Body) => 0,
        Tri::Uniform(BlockKind::Heading(n)) => i32::from(n.clamp(1, 3)),
        Tri::Uniform(BlockKind::Quote) => 4,
        Tri::Mixed => 7,
    };
    let (size, size_mixed) = match s.size {
        Tri::Uniform(Dip(v)) => (f64::from(v), 0),
        Tri::Mixed => (0.0, 1),
    };
    let (family, family_mixed) = match &s.family {
        Tri::Uniform(f) => (f.clone(), 0),
        Tri::Mixed => (None, 1),
    };
    let bits = tri(&s.bold)
        | tri(&s.italic) << 2
        | tri(&s.underline) << 4
        | tri(&s.strike) << 6
        | align << 8
        | list << 11
        | kind << 13
        | size_mixed << 16
        | family_mixed << 17;
    Msg {
        id,
        kind: ev::STYLE,
        i: bits,
        d: size,
        s: family,
    }
}

fn wrap_at(index: i32) -> Result<Wrap, String> {
    Ok(match index {
        0 => Wrap::Inline,
        1 => Wrap::square(Side::Left),
        2 => Wrap::square(Side::Right),
        3 => Wrap::TopAndBottom { margin: Dip(8.0) },
        _ => return Err(format!("image wrap {index}")),
    })
}

fn wrap_index(w: Wrap) -> i32 {
    match w {
        Wrap::Inline => 1,
        Wrap::Square { side: Side::Left, .. } => 2,
        Wrap::Square { .. } => 3,
        Wrap::TopAndBottom { .. } => 4,
    }
}

/// A page in hundredths of a millimetre: width, height, left, top, right,
/// bottom.
fn page_ints(p: &PageSetup) -> Vec<i32> {
    let per_mm = mm(1.0).0;
    [p.width, p.height, p.left, p.top, p.right, p.bottom]
        .iter()
        .map(|d| (d.0 / per_mm * 100.0).round() as i32)
        .collect()
}

fn page_of(v: &[i32]) -> Result<PageSetup, String> {
    let ok = v.len() == 6 && v.iter().all(|&x| (0..=200_000).contains(&x));
    let d = |i: usize| mm(v[i] as f32 / 100.0);
    match ok && v[0] > v[2] + v[4] && v[1] > v[3] + v[5] {
        true => Ok(PageSetup {
            width: d(0),
            height: d(1),
            left: d(2),
            top: d(3),
            right: d(4),
            bottom: d(5),
        }),
        false => Err(format!("bad page setup {v:?}")),
    }
}

fn text(arg: Arg) -> Result<String, String> {
    match arg {
        Arg::Str(s) => Ok(s),
        _ => Err("a string is required".into()),
    }
}

/// The largest picture, in pixels a side (as LazyWriter's cap).
const MAX_IMAGE_SIDE: u32 = 4096;
/// The widest an inserted picture is shown, in design units.
const MAX_IMAGE_WIDTH: f32 = 360.0;

fn image(bytes: &[u8], alt: String) -> Result<InlineImage, String> {
    let image = Image::decode(bytes).map_err(|e| format!("not a PNG or JPEG picture: {e:?}"))?;
    let (w, h) = (image.width(), image.height());
    if w > MAX_IMAGE_SIDE || h > MAX_IMAGE_SIDE {
        return Err(format!("the picture is {w} x {h} pixels; the largest is 4096 x 4096"));
    }
    let scale = (MAX_IMAGE_WIDTH / w as f32).min(1.0);
    Ok(InlineImage {
        image: Arc::new(image),
        size: (Dip(w as f32 * scale), Dip(h as f32 * scale)),
        wrap: Wrap::Inline,
        alt,
    })
}

/// Markdown, with each picture written as `<prefix><n>.png` and handed back.
fn markdown(doc: &Document, prefix: &str) -> Result<Ret, String> {
    type Pngs = Vec<Result<Vec<u8>, String>>;
    let pngs: Rc<RefCell<Pngs>> = Rc::default();
    let out = Rc::clone(&pngs);
    let prefix = prefix.to_owned();
    let export = ImageExport::Callback(Box::new(move |image: &Image, _alt: &str| {
        let mut out = out.borrow_mut();
        out.push(image.encode_png().map_err(|e| format!("{e:?}")));
        format!("{prefix}{}.png", out.len())
    }));
    let md = to_markdown(doc, &export);
    drop(export);
    let pngs = Rc::try_unwrap(pngs).map_err(|_| "export still running")?;
    let pngs = pngs.into_inner().into_iter().collect::<Result<Vec<_>, _>>()?;
    Ok(Ret::Export(md, pngs))
}

pub fn call(e: &RichTextEditor<Msg>, op: i32, i: i32, d: f64, arg: Arg) -> Result<Ret, String> {
    let exec = |c: Command| {
        e.exec(c);
        Ok(Ret::None)
    };
    let patch = |p: CharStylePatch| exec(Command::SetCharStyle(p));
    match op {
        op::EXEC => {
            let c = usize::try_from(i).ok().and_then(|i| COMMANDS.get(i));
            exec(c.ok_or_else(|| format!("rich text command {i}"))?.clone())
        }
        op::BLOCK => exec(Command::SetBlockKind(match i {
            0 => BlockKind::Body,
            1..=3 => BlockKind::Heading(i as u8),
            4 => BlockKind::Quote,
            _ => return Err(format!("block kind {i}")),
        })),
        op::FAMILY => patch(CharStylePatch {
            family: Some(match arg {
                Arg::Str(s) => Some(s),
                _ => None,
            }),
            ..CharStylePatch::default()
        }),
        op::SIZE if (1.0..=1000.0).contains(&d) => patch(CharStylePatch {
            size: Some(Dip(d as f32)),
            ..CharStylePatch::default()
        }),
        op::LINK => {
            let url = text(arg)?;
            let url = url.trim();
            patch(CharStylePatch {
                link: Some((!url.is_empty()).then(|| url.to_owned())),
                ..CharStylePatch::default()
            })
        }
        op::ALIGN => exec(Command::SetAlign(match i {
            0 => Align::Left,
            1 => Align::Center,
            2 => Align::Right,
            3 => Align::Justify,
            _ => return Err(format!("alignment {i}")),
        })),
        op::LIST => exec(Command::ToggleList(match i {
            0 => ListKind::Bullet,
            1 => ListKind::Numbered,
            _ => return Err(format!("list kind {i}")),
        })),
        op::WRAP => match e.selection() {
            Selection::Object(id) => exec(Command::SetWrap { id, wrap: wrap_at(i)? }),
            Selection::Text { .. } => Ok(Ret::None),
        },
        op::GET_WRAP => Ok(Ret::Int(match e.selection() {
            Selection::Object(id) => e
                .with_document(|doc| doc.objects().get(id).map(|o| wrap_index(o.wrap)))
                .unwrap_or(0),
            Selection::Text { .. } => 0,
        })),
        op::PAGE_VIEW => {
            e.set_view_mode(if i != 0 { ViewMode::Page } else { ViewMode::Draft });
            Ok(Ret::None)
        }
        op::PAGE => match arg {
            Arg::Ints(v) => exec(Command::SetPageSetup(page_of(&v)?)),
            _ => Err("a page setup is required".into()),
        },
        op::GET_PAGE => Ok(Ret::Ints(e.with_document(|doc| page_ints(doc.page())))),
        op::PAGE_INFO => {
            let (page, count) = e.page_info();
            Ok(Ret::Ints(vec![page as i32, count as i32]))
        }
        op::TABLE_CURSOR => Ok(Ret::Ints(match e.table_cursor() {
            Some(c) => vec![
                c.row as i32,
                c.column as i32,
                c.rows as i32,
                c.table.columns.len() as i32,
                c.table.header as i32,
                c.table.border as i32,
            ],
            None => Vec::new(),
        })),
        op::INSERT_TABLE => {
            let (rows, columns) = (i, d as i32);
            if !(1..=64).contains(&rows) || !(1..=64).contains(&columns) {
                return Err(format!("table {rows} x {columns}"));
            }
            exec(Command::InsertTable {
                rows: rows as usize,
                columns: columns as usize,
            })
        }
        op::TABLE_SWITCH => {
            let Some(c) = e.table_cursor() else {
                return Ok(Ret::None);
            };
            let mut table = c.table;
            match i {
                0 => table.header = d != 0.0,
                1 => table.border = d != 0.0,
                2 => return exec(Command::DeleteColumns),
                3 => return exec(Command::DeleteTable),
                _ => return Err(format!("table switch {i}")),
            }
            exec(Command::SetTable { id: c.id, table })
        }
        op::GET_JSON => Ok(Ret::Str(e.with_document(to_json))),
        op::SET_JSON => {
            let doc = from_json(&text(arg)?).map_err(|e| e.to_string())?;
            e.set_document(doc);
            Ok(Ret::None)
        }
        op::SET_PLAIN => {
            e.set_document(Document::from_plain_text(&text(arg)?));
            Ok(Ret::None)
        }
        op::MARKDOWN => {
            let prefix = text(arg)?;
            e.with_document(|doc| markdown(doc, &prefix))
        }
        op::INSERT_IMAGE => match arg {
            Arg::Image(alt, bytes) => exec(Command::InsertImage(image(&bytes, alt)?)),
            _ => Err("picture bytes are required".into()),
        },
        op::WORDS => Ok(Ret::Int(e.with_document(words))),
        crate::props::op::FOCUS => {
            e.focus();
            Ok(Ret::None)
        }
        _ => Err(format!("op {op} does not apply to rich text")),
    }
}
