//! Drawing on 0xAARRGGBB pixels for `lazy.gfx.Surface` (docs/gfx.md): fills,
//! lines, blits and masked blends. Everything is clipped, sizes are checked
//! before any pixel is touched, and coordinates are widened so no input
//! overflows. Pixels are `i32` because they live in a Java `int[]`.

/// Bit flags of [`blit_block`], as in `lazy.gfx.Surface`.
pub const FLIP_X: i32 = 1;
pub const FLIP_Y: i32 = 2;
pub const ALPHA_TEST: i32 = 4;
pub const KEY: i32 = 8;
pub const SOLID: i32 = 16;
const ALL_FLAGS: i32 = FLIP_X | FLIP_Y | ALPHA_TEST | KEY | SOLID;

/// A rejected call: `Arg` becomes `IllegalArgumentException`, `Range`
/// `IndexOutOfBoundsException`, `Memory` `OutOfMemoryError`.
#[derive(Debug, PartialEq, Eq)]
pub enum Bad {
    Arg(String),
    Range(String),
    Memory,
}

/// Checks that `len` pixels hold a `w` by `h` picture.
pub fn check_size(len: usize, w: i32, h: i32) -> Result<(), Bad> {
    if w < 0 || h < 0 || i64::from(w) * i64::from(h) > len as i64 {
        return Err(Bad::Arg(format!("{w}x{h} over {len} pixels")));
    }
    Ok(())
}

/// Checks that `clip` lies inside a `w` by `h` picture.
pub fn check_clip(w: i32, h: i32, clip: Clip) -> Result<(), Bad> {
    let Clip { x0, y0, x1, y1 } = clip;
    if x0 < 0 || y0 < 0 || x0 > x1 || y0 > y1 || x1 > w || y1 > h {
        return Err(Bad::Arg(format!("clip {x0},{y0} to {x1},{y1} on {w}x{h}")));
    }
    Ok(())
}

/// A rectangle `x0..x1` by `y0..y1`, the far edges excluded.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Clip {
    pub x0: i32,
    pub y0: i32,
    pub x1: i32,
    pub y1: i32,
}

/// The pixels being drawn on, `w` pixels to a row, and where drawing may go.
pub struct Target<'a> {
    px: &'a mut [i32],
    w: usize,
    clip: Clip,
}

impl<'a> Target<'a> {
    /// Checks that `px` holds at least `w` by `h` pixels and that `clip` lies
    /// inside them.
    pub fn new(px: &'a mut [i32], w: i32, h: i32, clip: Clip) -> Result<Self, Bad> {
        check_size(px.len(), w, h)?;
        check_clip(w, h, clip)?;
        Ok(Target {
            px,
            w: w as usize,
            clip,
        })
    }

    /// `x0..x1` by `y0..y1` (widened) cut to the clip, or `None` when empty.
    fn cut(&self, x0: i64, y0: i64, x1: i64, y1: i64) -> Option<Clip> {
        let c = self.clip;
        let (x0, y0) = (x0.max(c.x0.into()), y0.max(c.y0.into()));
        let (x1, y1) = (x1.min(c.x1.into()), y1.min(c.y1.into()));
        // Compared before narrowing: only a non-empty result lies in the clip.
        (x0 < x1 && y0 < y1).then_some(Clip {
            x0: x0 as i32,
            y0: y0 as i32,
            x1: x1 as i32,
            y1: y1 as i32,
        })
    }

    fn row(&mut self, y: i32, x0: i32, x1: i32) -> &mut [i32] {
        let start = y as usize * self.w;
        &mut self.px[start + x0 as usize..start + x1 as usize]
    }
}

/// `rgb` drawn over `dst` at opacity `a` (1..=254): source-over on
/// unpremultiplied pixels, each channel rounded to nearest. The destination's
/// colour counts by its own alpha, so paint on transparent pixels keeps its
/// colour; on an opaque destination this is `(s * a + d * (255 - a)) / 255`.
fn over(dst: i32, rgb: i32, a: u32) -> i32 {
    let (d, s) = (dst as u32, rgb as u32);
    let dw = (d >> 24) * (255 - a); // the destination's weight, times 255
    let den = a * 255 + dw;
    let mix = |sh: u32| ((s >> sh & 0xff) * a * 255 + (d >> sh & 0xff) * dw + den / 2) / den;
    let alpha = a + (dw + 127) / 255;
    (alpha << 24 | mix(16) << 16 | mix(8) << 8 | mix(0)) as i32
}

/// One pixel painted with `argb` by its own alpha: 0 leaves it, 255 replaces
/// it, anything between is drawn over it.
fn paint(dst: &mut i32, argb: i32) {
    match argb as u32 >> 24 {
        0 => {}
        255 => *dst = argb,
        a => *dst = over(*dst, argb, a),
    }
}

/// Fills `w` by `h` pixels at (`x`, `y`): blended by `argb`'s alpha, or with
/// `argb` written as it is when `blend` is false (clearing).
pub fn fill(t: &mut Target, x: i32, y: i32, w: i32, h: i32, argb: i32, blend: bool) {
    let (x, y) = (i64::from(x), i64::from(y));
    let Some(r) = t.cut(x, y, x + i64::from(w), y + i64::from(h)) else {
        return;
    };
    for yy in r.y0..r.y1 {
        let row = t.row(yy, r.x0, r.x1);
        if blend {
            row.iter_mut().for_each(|p| paint(p, argb));
        } else {
            row.fill(argb);
        }
    }
}

/// A one-pixel line from (`x0`, `y0`) to (`x1`, `y1`), both ends included,
/// each pixel painted as by [`fill`]. The pixels are Bresenham's: along the
/// longer axis step `k` of `n`, the other axis has moved `round(k * m / n)`
/// with halves rounded up, so the clipped part is found without walking the
/// rest of a long line.
pub fn line(t: &mut Target, x0: i32, y0: i32, x1: i32, y1: i32, argb: i32) {
    let (x0, y0, x1, y1) = (i64::from(x0), i64::from(y0), i64::from(x1), i64::from(y1));
    let ((a0, a1), (b0, b1), x_major) = if (x1 - x0).abs() >= (y1 - y0).abs() {
        ((x0, x1), (y0, y1), true)
    } else {
        ((y0, y1), (x0, x1), false)
    };
    let (n, m) = ((a1 - a0).abs(), (b1 - b0).abs());
    let (sa, sb) = (if a1 < a0 { -1 } else { 1 }, (b1 - b0).signum());
    let c = t.clip;
    if c.x0 == c.x1 || c.y0 == c.y1 {
        return; // Nothing can be drawn, so do not walk the steps.
    }
    let (lo, hi) = if x_major { (c.x0, c.x1) } else { (c.y0, c.y1) };
    // Steps whose major coordinate a0 + sa * k lies in lo..hi.
    let (ka, kb) = ((i64::from(lo) - a0) * sa, (i64::from(hi) - 1 - a0) * sa);
    let (k0, k1) = (ka.min(kb).max(0), ka.max(kb).min(n));
    for k in k0..=k1 {
        let moved = if n == 0 {
            0
        } else {
            ((2 * i128::from(k) * i128::from(m) + i128::from(n)) / (2 * i128::from(n))) as i64
        };
        let (a, b) = (a0 + sa * k, b0 + sb * moved);
        let (x, y) = if x_major { (a, b) } else { (b, a) };
        if x >= c.x0.into() && x < c.x1.into() && y >= c.y0.into() && y < c.y1.into() {
            let i = y as usize * t.w + x as usize;
            paint(&mut t.px[i], argb);
        }
    }
}

/// What a blit reads and where it writes, once clipped: the source block
/// `sx, sy, w, h` (unflipped) lands on `dx, dy` with the same size.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Area {
    pub sx: i32,
    pub sy: i32,
    pub w: i32,
    pub h: i32,
    pub dx: i32,
    pub dy: i32,
    flags: i32,
}

/// Checks a blit of `w` by `h` source pixels at (`sx`, `sy`) of an `sw` by
/// `sh` source to (`dx`, `dy`), and returns the part the clip lets through,
/// or `None` if nothing does. The whole source rectangle must lie inside the
/// source, even where it is clipped away.
pub fn blit_area(
    t: &Target,
    (sw, sh): (i32, i32),
    (sx, sy, w, h): (i32, i32, i32, i32),
    (dx, dy): (i32, i32),
    flags: i32,
) -> Result<Option<Area>, Bad> {
    if flags & !ALL_FLAGS != 0 {
        return Err(Bad::Arg(format!("blit flags {flags:#x}")));
    }
    if w <= 0 || h <= 0 {
        return Ok(None);
    }
    let (fits_x, fits_y) = (
        sx >= 0 && sx as i64 + w as i64 <= sw as i64,
        sy >= 0 && sy as i64 + h as i64 <= sh as i64,
    );
    if !fits_x || !fits_y {
        return Err(Bad::Range(format!("source {sx},{sy} {w}x{h} of {sw}x{sh}")));
    }
    let (dx64, dy64) = (i64::from(dx), i64::from(dy));
    let Some(r) = t.cut(dx64, dy64, dx64 + i64::from(w), dy64 + i64::from(h)) else {
        return Ok(None);
    };
    // Offsets of the clipped part inside the destination rectangle, mirrored
    // into the source when flipped.
    let (ox, oy) = (
        (i64::from(r.x0) - dx64) as i32,
        (i64::from(r.y0) - dy64) as i32,
    );
    let (cw, ch) = (r.x1 - r.x0, r.y1 - r.y0);
    let bx = if flags & FLIP_X != 0 { w - ox - cw } else { ox };
    let by = if flags & FLIP_Y != 0 { h - oy - ch } else { oy };
    Ok(Some(Area {
        sx: sx + bx,
        sy: sy + by,
        w: cw,
        h: ch,
        dx: r.x0,
        dy: r.y0,
        flags,
    }))
}

/// Copies the `w` by `h` block at (`x`, `y`) of `src` (`stride` pixels to a
/// row) into `out`, row by row. The block must lie inside `src`.
pub fn copy_block(
    src: &[i32],
    stride: usize,
    (x, y, w, h): (usize, usize, usize, usize),
    out: &mut Vec<i32>,
) -> Result<(), Bad> {
    out.clear();
    if w == 0 || h == 0 {
        return Ok(()); // Empty: no row to read, however many there are.
    }
    let n = w.checked_mul(h).ok_or(Bad::Memory)?;
    out.try_reserve(n).map_err(|_| Bad::Memory)?;
    for row in y..y.saturating_add(h) {
        let outside = || Bad::Range(format!("block row {row}"));
        let start = row
            .checked_mul(stride)
            .and_then(|s| s.checked_add(x))
            .ok_or_else(outside)?;
        let end = start.checked_add(w).ok_or_else(outside)?;
        out.extend_from_slice(src.get(start..end).ok_or_else(outside)?);
    }
    Ok(())
}

/// The colours of a blit beyond its flags: source pixels equal to `key`
/// become `key_color`, and with `SOLID` every other visible one takes
/// `solid`'s colour.
#[derive(Clone, Copy, Debug)]
pub struct Tint {
    pub key: i32,
    pub key_color: i32,
    pub solid: i32,
}

/// Draws `block` (the source pixels of `area`, as [`copy_block`] gives them)
/// onto the target. For each source pixel `p`: with `KEY`, `p == key` writes
/// `key_color` as it is; otherwise alpha 0 is skipped; the colour is `solid`
/// with `SOLID`, else `p`; `ALPHA_TEST` writes it as it is, and without it
/// alpha 255 writes it opaque and anything between draws it over.
pub fn blit_block(t: &mut Target, area: &Area, block: &[i32], tint: Tint) -> Result<(), Bad> {
    let (w, h) = (area.w as usize, area.h as usize);
    if block.len() != w * h {
        return Err(Bad::Arg(format!(
            "blit block of {} for {w}x{h}",
            block.len()
        )));
    }
    let f = area.flags;
    for y in 0..h {
        let src_row = if f & FLIP_Y != 0 { h - 1 - y } else { y };
        let src = &block[src_row * w..][..w];
        let dst = t.row(area.dy + y as i32, area.dx, area.dx + area.w);
        for (x, d) in dst.iter_mut().enumerate() {
            let p = src[if f & FLIP_X != 0 { w - 1 - x } else { x }];
            if f & KEY != 0 && p == tint.key {
                *d = tint.key_color;
                continue;
            }
            let a = p as u32 >> 24;
            if a == 0 {
                continue;
            }
            let c = if f & SOLID != 0 { tint.solid } else { p };
            *d = match (f & ALPHA_TEST != 0, a) {
                (true, _) => c,
                (false, 255) => c | 0xff00_0000u32 as i32,
                (false, a) => over(*d, c, a),
            };
        }
    }
    Ok(())
}

/// Draws `rgb` over every clipped pixel at opacity `mask * strength / 255`
/// (rounded to nearest; mask values are clamped to 0..=255). `block` holds
/// the mask for exactly the clip rectangle, row by row, as [`copy_block`]
/// gives it.
pub fn blend_mask(t: &mut Target, block: &[i32], rgb: i32, strength: i32) -> Result<(), Bad> {
    if !(0..=255).contains(&strength) {
        return Err(Bad::Arg(format!("strength {strength}")));
    }
    let c = t.clip;
    let w = (c.x1 - c.x0) as usize;
    if block.len() != w * (c.y1 - c.y0) as usize {
        return Err(Bad::Arg(format!("mask block of {}", block.len())));
    }
    if strength == 0 || w == 0 {
        return Ok(());
    }
    let opaque = rgb | 0xff00_0000u32 as i32;
    for (y, masks) in (c.y0..c.y1).zip(block.chunks_exact(w)) {
        for (d, &m) in t.row(y, c.x0, c.x1).iter_mut().zip(masks) {
            match (m.clamp(0, 255) as u32 * strength as u32 + 127) / 255 {
                0 => {}
                255 => *d = opaque,
                a => *d = over(*d, rgb, a),
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod reference_tests;
#[cfg(test)]
mod tests;
