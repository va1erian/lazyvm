//! The leaf widgets of an op list: what follows each kind's header
//! (`lazy.ui.Encoder` writes the same order).

use xui_core::Dip;
use xui_core::arrange::{
    Entry, Handle, build, button, checkbox, combo_box, edit, label, list, multiline_edit, progress,
    separator, slider, status_bar, toggle_button, toolbar, vertical_separator,
};
use xui_core::icon::IconRef;
use xui_core::widget::{ColumnWidth, HasText, Lucide};

use crate::build::{Decoder, Head, finish, op};
use crate::canvas::{Canvas, Drawing};
use crate::msg::{Msg, kind as ev};
use crate::table::Widget;

/// The Lucide icon Java names (`"FilePlus"`), as the enum spells it.
pub fn icon(name: &str) -> Result<Lucide, String> {
    Lucide::ALL
        .iter()
        .copied()
        .find(|l| format!("{l:?}") == name)
        .ok_or_else(|| format!("no icon {name}"))
}

fn opt_icon(d: &mut Decoder) -> Result<Option<IconRef>, String> {
    d.r.opt_string()?
        .map(|name| icon(&name).map(IconRef::from))
        .transpose()
}

pub fn leaf(d: &mut Decoder, kind: i32, head: &Head) -> Result<Entry<Msg>, String> {
    let id = head.id;
    match kind {
        op::LABEL => {
            let h = Handle::new();
            let mut b = label(d.r.string()?).bind(&h);
            b = match d.r.int()? {
                1 => b.title(),
                2 => b.caption(),
                _ => b,
            };
            finish(d, head, Widget::Label(h), b)
        }
        op::BUTTON => {
            let h = Handle::new();
            let mut b = button(d.r.string()?).bind(&h);
            if let Some(i) = opt_icon(d)? {
                b = b.icon(i);
            }
            if d.r.flag()? {
                b = b.primary();
            }
            if d.r.flag()? {
                b = b.on_click(Msg::new(id, ev::CLICK));
            }
            finish(d, head, Widget::Button(h), b)
        }
        op::TOGGLE => {
            let h = Handle::new();
            let mut b = toggle_button(d.r.string()?).bind(&h);
            if let Some(i) = opt_icon(d)? {
                b = b.icon(i);
            }
            b = b.checked(d.r.flag()?);
            if d.r.flag()? {
                b = b.on_toggle(move |on| Msg::int(id, ev::TOGGLE, on as i32));
            }
            finish(d, head, Widget::Toggle(h), b)
        }
        op::CHECK => {
            let h = Handle::new();
            let b = checkbox(d.r.string()?).bind(&h).checked(d.r.flag()?);
            let b = match d.r.flag()? {
                true => b.on_toggle(move |on| Msg::int(id, ev::TOGGLE, on as i32)),
                false => b,
            };
            finish(d, head, Widget::Check(h), b)
        }
        op::EDIT => {
            let h = Handle::new();
            let mut b = edit().bind(&h).text(d.r.string()?);
            if let Some(cue) = d.r.opt_string()? {
                b = b.placeholder(cue);
            }
            if d.r.flag()? {
                b = b.password();
            }
            if d.r.flag()? {
                b = b.on_change(move |s| Msg::text(id, ev::CHANGE, s));
            }
            finish(d, head, Widget::Edit(h), b)
        }
        op::TEXT_AREA => {
            let h = Handle::new();
            let text = d.r.string()?;
            let mut b = multiline_edit().bind(&h).then(move |e| {
                e.set_text(&text);
                e
            });
            if d.r.flag()? {
                b = b.on_change(move |s| Msg::text(id, ev::CHANGE, s));
            }
            finish(d, head, Widget::TextArea(h), b)
        }
        op::COMBO => combo(d, head),
        op::LIST => list_view(d, head),
        op::SLIDER => {
            let h = Handle::new();
            let (min, max, value) = (d.r.int()?, d.r.int()?, d.r.int()?);
            if min > max || !(min..=max).contains(&value) {
                return Err(format!("slider {min}..{max} at {value}"));
            }
            let mut b = slider(min.into(), max.into()).bind(&h).then(move |s| {
                s.set_value(value.into());
                s
            });
            if d.r.flag()? {
                b = b.on_change(move |v| Msg {
                    d: v,
                    ..Msg::new(id, ev::SLIDE)
                });
            }
            finish(d, head, Widget::Slider(h), b)
        }
        op::PROGRESS => {
            let h = Handle::new();
            let (max, value) = (d.r.int()?, d.r.int()?);
            if max < 0 || !(0..=max).contains(&value) {
                return Err(format!("progress {value} of {max}"));
            }
            let b = progress(max).bind(&h).value(value);
            finish(d, head, Widget::Progress(h), b)
        }
        op::STATUS => {
            let h = Handle::new();
            let parts = d.r.strings()?;
            let parts: Vec<&str> = parts.iter().map(String::as_str).collect();
            finish(
                d,
                head,
                Widget::Status(h.clone()),
                status_bar(&parts).bind(&h),
            )
        }
        op::TOOLBAR => tool_bar(d, head),
        op::SEPARATOR => {
            let b = if d.r.flag()? {
                vertical_separator()
            } else {
                separator()
            };
            finish(d, head, Widget::Inert, b)
        }
        op::CANVAS => {
            let h = Handle::new();
            let (w, ht) = (d.r.length()?, d.r.length()?);
            let drawing = drawing(d)?;
            let b = build(move |ui| Canvas::new(ui, (w, ht), drawing)).bind(&h);
            finish(d, head, Widget::Canvas(h), b)
        }
        #[cfg(feature = "rich-text")]
        op::RICH => crate::rich::decode(d, head),
        _ => Err(format!("unknown op {kind}")),
    }
}

/// A drawing inline in the op list: its commands (an `int[]`), its strings,
/// and its images as (width, height, `int[]` of ARGB pixels).
pub fn drawing(d: &mut Decoder) -> Result<Drawing, String> {
    let ops = d.r.ints()?;
    let strings = d.r.strings()?;
    let n = d.r.count(3)?;
    let mut images = Vec::with_capacity(n);
    for _ in 0..n {
        let (w, h) = (d.r.length()?, d.r.length()?);
        images.push((w as u32, h as u32, d.r.ints()?));
    }
    Drawing::decode(&ops, &strings, &images)
}

fn combo(d: &mut Decoder, head: &Head) -> Result<Entry<Msg>, String> {
    let id = head.id;
    let h = Handle::new();
    let n = d.r.count(2)?;
    let mut items = Vec::with_capacity(n);
    let mut icons = Vec::with_capacity(n);
    for _ in 0..n {
        items.push(d.r.string()?);
        icons.push(opt_icon(d)?);
    }
    let selected = d.r.int()?;
    if selected < -1 || selected >= n as i32 {
        return Err(format!("combo selection {selected} of {n}"));
    }
    let refs: Vec<&str> = items.iter().map(String::as_str).collect();
    let mut b = combo_box(&refs).bind(&h).then(move |c| {
        for (i, icon) in icons.into_iter().enumerate() {
            if icon.is_some() {
                c.set_item_icon(i, icon);
            }
        }
        if selected >= 0 {
            c.select(selected as usize);
        }
        c
    });
    if d.r.flag()? {
        b = b.on_select(move |i| Msg::int(id, ev::SELECT, i as i32));
    }
    finish(d, head, Widget::Combo(h, n), b)
}

fn list_view(d: &mut Decoder, head: &Head) -> Result<Entry<Msg>, String> {
    let id = head.id;
    let h = Handle::new();
    let ncols = d.r.count(2)?;
    let mut b = list().bind(&h);
    for _ in 0..ncols {
        let title = d.r.string()?;
        let width = match d.r.length()? {
            0 => ColumnWidth::Fill,
            w => ColumnWidth::Fixed(Dip(w as f32)),
        };
        b = b.column(title, width);
    }
    let rows = rows(d, ncols.max(1))?;
    let selected = d.r.int()?;
    if selected < -1 || selected >= rows.len() as i32 {
        return Err(format!("list selection {selected} of {}", rows.len()));
    }
    b = b.then(move |l| {
        l.set_model(rows);
        l.select(usize::try_from(selected).ok());
        l
    });
    if d.r.flag()? {
        b = b.on_select(move |i| Msg::int(id, ev::SELECT, i as i32));
    }
    if d.r.flag()? {
        b = b.on_activate(move |i| Msg::int(id, ev::ACTIVATE, i as i32));
    }
    finish(d, head, Widget::List(h), b)
}

/// Rows of `columns` cells each, as a list of strings.
pub fn rows(d: &mut Decoder, columns: usize) -> Result<Vec<Vec<String>>, String> {
    let cells = d.r.strings()?;
    if cells.len() % columns != 0 {
        return Err(format!(
            "{} cells do not fill rows of {columns}",
            cells.len()
        ));
    }
    Ok(cells.chunks(columns).map(<[String]>::to_vec).collect())
}

fn tool_bar(d: &mut Decoder, head: &Head) -> Result<Entry<Msg>, String> {
    let id = head.id;
    let h = Handle::new();
    let mut b = toolbar().bind(&h);
    let n = d.r.count(3)?;
    for _ in 0..n {
        let name = d.r.opt_string()?;
        let tip = d.r.string()?;
        let text = d.r.opt_string()?;
        b = match (name, text) {
            (None, _) => b.separator(),
            (Some(name), None) => b.item(icon(&name)?, &tip),
            (Some(name), Some(text)) => b.item_with_text(icon(&name)?, &tip, &text),
        };
    }
    if d.r.flag()? {
        b = b.on_click(move |i| Msg::int(id, ev::CLICK, i as i32));
    }
    finish(d, head, Widget::Toolbar(h), b)
}
