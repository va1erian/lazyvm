//! `Canvas`: a widget that replays a recorded `Drawing` on every paint, so a
//! repaint the window causes runs no bytecode (docs/ui.md, "Drawing").
//!
//! A drawing is an `int[]` of commands in design units, plus the strings
//! and pixel buffers they index. It is checked once, when Java hands it
//! over; replaying a checked drawing cannot fail.

use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;

use xui_core::{Dip, Ui};
use xui_core::backend::{NodeKind, NodeSpec, Result as XuiResult, TextStyle, WidgetId};
use xui_core::color::Color;
use xui_core::geometry::{Point, Rect, Size};
use xui_core::image::Image;
use xui_core::layout::Constraints;
use xui_core::widget::Placeable;

use crate::msg::Msg;

/// Drawing opcodes, shared with `lazy.ui.Drawing`.
pub mod op {
    pub const CLEAR: i32 = 1; // color
    pub const FILL_RECT: i32 = 2; // x y w h color
    pub const STROKE_RECT: i32 = 3; // x y w h color width
    pub const LINE: i32 = 4; // x1 y1 x2 y2 color width
    pub const FILL_ELLIPSE: i32 = 5; // x y w h color
    pub const FILL_ROUNDED: i32 = 6; // x y w h radius color
    pub const TEXT: i32 = 7; // x y w h color size string
    pub const IMAGE: i32 = 8; // x y w h image
}

enum Cmd {
    Clear(Color),
    FillRect(Rect, Color),
    StrokeRect(Rect, Color, f32),
    Line(Point, Point, Color, f32),
    FillEllipse(Rect, Color),
    FillRounded(Rect, f32, Color),
    Text(Rect, Color, f32, String),
    Image(Rect, Arc<Image>),
}

/// A checked drawing.
#[derive(Default)]
pub struct Drawing(Vec<Cmd>);

/// Colours are `0xAARRGGBB`; xui's are opaque, so the alpha byte is ignored.
fn color(argb: i32) -> Color {
    Color::hex(argb as u32 & 0x00ff_ffff)
}

/// The largest coordinate a drawing may use, in design units.
const MAX_COORD: i32 = 1 << 16;

impl Drawing {
    /// Checks and converts `ops`; `strings` and `images` are what its TEXT
    /// and IMAGE commands index. An image is `(width, height, ARGB pixels)`.
    pub fn decode(
        ops: &[i32],
        strings: &[String],
        images: &[(u32, u32, Vec<i32>)],
    ) -> Result<Drawing, String> {
        let mut cmds = Vec::new();
        let mut at = 0usize;
        let mut take = |n: usize| -> Result<Vec<i32>, String> {
            let args = ops.get(at..at + n).ok_or("drawing ends early")?.to_vec();
            at += n;
            Ok(args)
        };
        let mut images_out: Vec<Option<Arc<Image>>> = vec![None; images.len()];
        while let Ok(code) = take(1) {
            let cmd = match code[0] {
                op::CLEAR => Cmd::Clear(color(take(1)?[0])),
                op::FILL_RECT => {
                    let a = take(5)?;
                    Cmd::FillRect(rect(&a)?, color(a[4]))
                }
                op::STROKE_RECT => {
                    let a = take(6)?;
                    Cmd::StrokeRect(rect(&a)?, color(a[4]), width(a[5])?)
                }
                op::LINE => {
                    let a = take(6)?;
                    let (p, q) = (point(a[0], a[1])?, point(a[2], a[3])?);
                    Cmd::Line(p, q, color(a[4]), width(a[5])?)
                }
                op::FILL_ELLIPSE => {
                    let a = take(5)?;
                    Cmd::FillEllipse(rect(&a)?, color(a[4]))
                }
                op::FILL_ROUNDED => {
                    let a = take(6)?;
                    Cmd::FillRounded(rect(&a)?, width(a[4])?, color(a[5]))
                }
                op::TEXT => {
                    let a = take(7)?;
                    let s = usize::try_from(a[6]).ok().and_then(|i| strings.get(i));
                    let s = s.ok_or("drawing text index out of range")?.clone();
                    Cmd::Text(rect(&a)?, color(a[4]), width(a[5])?, s)
                }
                op::IMAGE => {
                    let a = take(5)?;
                    let i = usize::try_from(a[4]).ok().filter(|&i| i < images.len());
                    let i = i.ok_or("drawing image index out of range")?;
                    let image = match &images_out[i] {
                        Some(image) => Arc::clone(image),
                        None => {
                            let image = Arc::new(argb_image(&images[i])?);
                            images_out[i] = Some(Arc::clone(&image));
                            image
                        }
                    };
                    Cmd::Image(rect(&a)?, image)
                }
                other => return Err(format!("unknown drawing op {other}")),
            };
            cmds.push(cmd);
        }
        Ok(Drawing(cmds))
    }
}

fn coord(v: i32) -> Result<i32, String> {
    if (-MAX_COORD..=MAX_COORD).contains(&v) {
        Ok(v)
    } else {
        Err(format!("drawing coordinate {v} out of range"))
    }
}

fn point(x: i32, y: i32) -> Result<Point, String> {
    Ok(Point::new(coord(x)?, coord(y)?))
}

fn rect(a: &[i32]) -> Result<Rect, String> {
    let (x, y) = (coord(a[0])?, coord(a[1])?);
    let (w, h) = (coord(a[2])?, coord(a[3])?);
    if w < 0 || h < 0 {
        return Err(format!("drawing size {w}x{h}"));
    }
    Ok(Rect::new(x, y, x + w, y + h))
}

fn width(v: i32) -> Result<f32, String> {
    if (0..=4096).contains(&v) {
        Ok(v as f32)
    } else {
        Err(format!("drawing width {v} out of range"))
    }
}

fn argb_image((w, h, px): &(u32, u32, Vec<i32>)) -> Result<Image, String> {
    if u64::from(*w) * u64::from(*h) != px.len() as u64 {
        return Err("image pixel count does not match its size".into());
    }
    let mut rgba = Vec::with_capacity(px.len() * 4);
    for &p in px {
        let p = p as u32;
        rgba.extend_from_slice(&[(p >> 16) as u8, (p >> 8) as u8, p as u8, (p >> 24) as u8]);
    }
    Image::from_rgba(*w, *h, rgba).map_err(|e| format!("{e:?}"))
}

/// The widget: a plain node whose painter replays the current drawing.
pub struct Canvas {
    id: WidgetId,
    drawing: Rc<RefCell<Rc<Drawing>>>,
    ui: Ui<Msg>,
    size: (i32, i32),
}

impl Canvas {
    pub fn new(ui: &Ui<Msg>, size: (i32, i32), drawing: Drawing) -> XuiResult<Canvas> {
        let id = ui.create_node(&NodeSpec::new(NodeKind::Container, Rect::default()))?;
        let drawing = Rc::new(RefCell::new(Rc::new(drawing)));
        let shown = Rc::clone(&drawing);
        let bounds_ui = ui.clone();
        ui.set_painter(
            id,
            Rc::new(move |canvas| {
                let d = Rc::clone(&shown.borrow());
                let b = bounds_ui.bounds(id);
                let dpi = canvas.dpi();
                replay(canvas, &d, Point::new(b.left, b.top), dpi);
            }),
        );
        Ok(Canvas {
            id,
            drawing,
            ui: ui.clone(),
            size,
        })
    }

    /// Shows `drawing` from the next paint on.
    pub fn set(&self, drawing: Drawing) {
        *self.drawing.borrow_mut() = Rc::new(drawing);
        self.ui.invalidate(self.id);
    }
}

impl Drop for Canvas {
    fn drop(&mut self) {
        self.ui.destroy(self.id);
    }
}

impl Placeable<Msg> for Canvas {
    fn id(&self) -> WidgetId {
        self.id
    }

    fn measure(&self, ui: &Ui<Msg>, _constraints: Constraints) -> Size {
        let scale = ui.dpi() as f32 / 96.0;
        let px = |v: i32| (v as f32 * scale).round() as i32;
        Size::new(px(self.size.0), px(self.size.1))
    }
}

fn replay(canvas: &mut dyn xui_core::backend::Canvas, d: &Drawing, origin: Point, dpi: u32) {
    let s = dpi as f32 / 96.0;
    let px = |v: i32| (v as f32 * s).round() as i32;
    let r = |r: &Rect| {
        Rect::new(
            origin.x + px(r.left),
            origin.y + px(r.top),
            origin.x + px(r.right),
            origin.y + px(r.bottom),
        )
    };
    let p = |p: &Point| Point::new(origin.x + px(p.x), origin.y + px(p.y));
    for cmd in &d.0 {
        match cmd {
            Cmd::Clear(c) => canvas.fill_rect(canvas.bounds(), *c),
            Cmd::FillRect(rc, c) => canvas.fill_rect(r(rc), *c),
            Cmd::StrokeRect(rc, c, w) => canvas.stroke_rect(r(rc), *c, w * s),
            Cmd::Line(a, b, c, w) => canvas.draw_line(p(a), p(b), *c, w * s),
            Cmd::FillEllipse(rc, c) => {
                let rc = r(rc);
                let centre = Point::new((rc.left + rc.right) / 2, (rc.top + rc.bottom) / 2);
                let (rx, ry) = (rc.width() as f32 / 2.0, rc.height() as f32 / 2.0);
                canvas.fill_ellipse(centre, rx, ry, *c);
            }
            Cmd::FillRounded(rc, radius, c) => canvas.fill_rounded_rect(r(rc), radius * s, *c),
            Cmd::Text(rc, c, size, text) => {
                let style = TextStyle::new(*c, Dip(*size));
                canvas.draw_text(text, r(rc), &style);
            }
            Cmd::Image(rc, image) => canvas.draw_image(image, r(rc)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn malformed_drawings_are_errors() {
        let none: [String; 0] = [];
        assert!(Drawing::decode(&[op::FILL_RECT, 0, 0, 1], &none, &[]).is_err());
        assert!(Drawing::decode(&[99], &none, &[]).is_err());
        assert!(Drawing::decode(&[op::FILL_RECT, 0, 0, -1, 1, 0], &none, &[]).is_err());
        assert!(Drawing::decode(&[op::TEXT, 0, 0, 1, 1, 0, 12, 0], &none, &[]).is_err());
        assert!(Drawing::decode(&[op::IMAGE, 0, 0, 1, 1, 0], &none, &[(2, 2, vec![0])]).is_err());
        let ok = Drawing::decode(&[op::CLEAR, -1, op::LINE, 0, 0, 5, 5, 0, 1], &none, &[]);
        assert_eq!(ok.map(|d| d.0.len()), Ok(2));
    }
}
