use super::*;

pub(super) const W: i32 = 7;
pub(super) const H: i32 = 5;

pub(super) fn whole(w: i32, h: i32) -> Clip {
    Clip {
        x0: 0,
        y0: 0,
        x1: w,
        y1: h,
    }
}

/// A small deterministic generator, so the property tests need no crate.
pub(super) struct Lcg(pub(super) u64);

impl Lcg {
    pub(super) fn next(&mut self, lo: i32, hi: i32) -> i32 {
        self.0 = self
            .0
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        lo + ((self.0 >> 33) % (hi - lo + 1) as u64) as i32
    }
}

/// Bresenham as the port and AWT draw it: one error term for all octants.
fn classic_line(x0: i32, y0: i32, x1: i32, y1: i32) -> Vec<(i32, i32)> {
    let (dx, dy) = ((x1 - x0).abs(), -(y1 - y0).abs());
    let (sx, sy) = (if x0 < x1 { 1 } else { -1 }, if y0 < y1 { 1 } else { -1 });
    let (mut err, mut x, mut y, mut out) = (dx + dy, x0, y0, Vec::new());
    loop {
        out.push((x, y));
        if x == x1 && y == y1 {
            return out;
        }
        let e2 = 2 * err;
        if e2 >= dy {
            err += dy;
            x += sx;
        }
        if e2 <= dx {
            err += dx;
            y += sy;
        }
    }
}

pub(super) fn in_clip(c: Clip, x: i64, y: i64) -> bool {
    x >= c.x0.into() && x < c.x1.into() && y >= c.y0.into() && y < c.y1.into()
}

#[test]
fn over_rounds_each_channel() {
    // 255 * 128 + 127 over 255 is 128.49: 128. Alpha: 128 + 127.5 rounds to 255.
    assert_eq!(
        over(0xff00_0000u32 as i32, 0x00ff_ffff, 128),
        0xff80_8080u32 as i32
    );
    // Over a transparent pixel the colour stays; only the alpha is the paint's.
    assert_eq!(over(0x0000_0000, 0x0010_2030, 51), 0x3310_2030);
}

#[test]
fn fill_paints_by_alpha_and_clips() {
    let mut px = vec![0x0101_0101; (W * H) as usize];
    let mut t = Target::new(
        &mut px,
        W,
        H,
        Clip {
            x0: 1,
            y0: 1,
            x1: 6,
            y1: 4,
        },
    )
    .expect("valid arguments");
    fill(&mut t, -3, -3, 5, 5, 0xff12_3456u32 as i32, true); // covers 1..2 by 1..2
    fill(&mut t, 0, 0, W, H, 0x00ff_ffff, true); // alpha 0: nothing
    fill(&mut t, 5, 3, 9, 9, 0x2a00_0000, false); // clearing writes it as it is
    assert_eq!(px[8], 0xff12_3456u32 as i32);
    assert_eq!(px[0], 0x0101_0101); // outside the clip
    assert_eq!(px[3 * 7 + 5], 0x2a00_0000);
    assert_eq!(px[3 * 7 + 6], 0x0101_0101);
}

#[test]
fn rectangles_far_away_draw_nothing() {
    let mut px = vec![0; (W * H) as usize];
    let mut t = Target::new(&mut px, W, H, whole(W, H)).expect("valid arguments");
    for (x, y, w, h) in [
        (i32::MAX, 0, i32::MAX, 3),
        (i32::MIN, 0, i32::MAX, 3),
        (0, i32::MIN, 3, -5),
        (2, 2, 0, 9),
        (i32::MAX - 1, i32::MAX - 1, i32::MAX, i32::MAX),
    ] {
        fill(&mut t, x, y, w, h, -1, true);
    }
    assert!(px.iter().all(|&p| p == 0));
}

#[test]
fn line_is_classic_bresenham() {
    let mut px = vec![0; 81 * 81];
    for dx in -40..=40 {
        for dy in -40..=40 {
            px.fill(0);
            let mut t = Target::new(&mut px, 81, 81, whole(81, 81)).expect("valid arguments");
            line(&mut t, 40, 40, 40 + dx, 40 + dy, -1);
            let mut want = vec![0; 81 * 81];
            for (x, y) in classic_line(40, 40, 40 + dx, 40 + dy) {
                want[(y * 81 + x) as usize] = -1;
            }
            assert!(px == want, "line to {dx},{dy}");
        }
    }
}

#[test]
fn clipped_lines_keep_their_pixels() {
    let mut rng = Lcg(7);
    for _ in 0..3000 {
        let c = Clip {
            x0: rng.next(0, 3),
            y0: rng.next(0, 2),
            x1: rng.next(3, W),
            y1: rng.next(2, H),
        };
        let (x0, y0, x1, y1) = (
            rng.next(-30, 30),
            rng.next(-30, 30),
            rng.next(-30, 30),
            rng.next(-30, 30),
        );
        let mut px = vec![0; (W * H) as usize];
        let mut t = Target::new(&mut px, W, H, c).expect("valid arguments");
        line(&mut t, x0, y0, x1, y1, -1);
        let mut want = vec![0; (W * H) as usize];
        for (x, y) in classic_line(x0, y0, x1, y1) {
            if in_clip(c, x.into(), y.into()) {
                want[(y * W + x) as usize] = -1;
            }
        }
        assert!(px == want, "{x0},{y0} to {x1},{y1} in {c:?}");
    }
}

#[test]
fn a_huge_line_only_walks_the_clip() {
    let mut px = vec![0; 100];
    let mut t = Target::new(&mut px, 10, 10, whole(10, 10)).expect("valid arguments");
    line(
        &mut t,
        -2_000_000_000,
        -2_000_000_000,
        2_000_000_000,
        2_000_000_000,
        -1,
    );
    line(&mut t, i32::MIN, 3, i32::MAX, 3, -1);
    for i in 0..10 {
        assert_eq!(px[i * 10 + i], -1);
        assert_eq!(px[30 + i], -1);
    }
    assert_eq!(px.iter().filter(|&&p| p == -1).count(), 19);
}

/// One blit: the clip, the source's size, the source rectangle, where it
/// lands, the flags and the colours.
#[derive(Clone, Copy, Debug)]
struct Case {
    clip: Clip,
    src_size: (i32, i32),
    r: (i32, i32, i32, i32),
    d: (i32, i32),
    flags: i32,
    tint: Tint,
}

const NO_TINT: Tint = Tint {
    key: 0,
    key_color: 0,
    solid: 0,
};

/// A blit done the slow way: every destination pixel of the whole rectangle,
/// kept if the clip allows, its source found by flipping the offset.
fn naive_blit(dst: &mut [i32], src: &[i32], c: &Case) {
    let ((sx, sy, w, h), f, tint) = (c.r, c.flags, c.tint);
    for oy in 0..h {
        for ox in 0..w {
            let (x, y) = (c.d.0 as i64 + ox as i64, c.d.1 as i64 + oy as i64);
            if !in_clip(c.clip, x, y) {
                continue;
            }
            let fx = if f & FLIP_X != 0 { w - 1 - ox } else { ox };
            let fy = if f & FLIP_Y != 0 { h - 1 - oy } else { oy };
            let p = src[((sy + fy) * c.src_size.0 + sx + fx) as usize];
            let out = &mut dst[(y * W as i64 + x) as usize];
            let a = p as u32 >> 24;
            if f & KEY != 0 && p == tint.key {
                *out = tint.key_color;
            } else if a != 0 {
                let col = if f & SOLID != 0 { tint.solid } else { p };
                *out = if f & ALPHA_TEST != 0 {
                    col
                } else if a == 255 {
                    col | 0xff00_0000u32 as i32
                } else {
                    over(*out, col, a)
                };
            }
        }
    }
}

/// The blit as the natives do it: area, block copy, then drawing.
fn blit(dst: &mut [i32], src: &[i32], c: &Case) -> Result<(), Bad> {
    let mut t = Target::new(dst, W, H, c.clip)?;
    if let Some(a) = blit_area(&t, c.src_size, c.r, c.d, c.flags)? {
        let mut block = Vec::new();
        let rect = (a.sx as usize, a.sy as usize, a.w as usize, a.h as usize);
        copy_block(src, c.src_size.0 as usize, rect, &mut block)?;
        blit_block(&mut t, &a, &block, c.tint)?;
    }
    Ok(())
}

#[test]
fn blits_match_the_slow_way() {
    let mut rng = Lcg(42);
    let (sw, sh) = (6, 4);
    // Alphas 0, 1 (the port's 25-bit "opaque"), 128 and 255, and a key colour.
    let alphas = [0x0000_0000u32, 0x0100_0000, 0x8000_0000, 0xff00_0000];
    let mut src: Vec<i32> = (0..sw * sh)
        .map(|i| (alphas[(i % 4) as usize] | ((i as u32 * 0x0010_2030) & 0x00ff_ffff)) as i32)
        .collect();
    src[5] = 0x01ff_ffff;
    let tint = Tint {
        key: 0x01ff_ffff,
        key_color: 0x0012_3456,
        solid: 0x7f65_4321,
    };
    for _ in 0..4000 {
        let clip = Clip {
            x0: rng.next(0, 3),
            y0: rng.next(0, 2),
            x1: rng.next(3, W),
            y1: rng.next(2, H),
        };
        let (w, h) = (rng.next(0, sw), rng.next(0, sh));
        let r = (rng.next(0, sw - w), rng.next(0, sh - h), w, h);
        let d = (rng.next(-8, 9), rng.next(-6, 7));
        let flags = rng.next(0, ALL_FLAGS);
        let case = Case {
            clip,
            src_size: (sw, sh),
            r,
            d,
            flags,
            tint,
        };
        let base: Vec<i32> = (0..W * H)
            .map(|i| (0xff00_0000u32 as i32) | (i * 0x0003_0507))
            .collect();
        let (mut got, mut want) = (base.clone(), base);
        blit(&mut got, &src, &case).expect("valid arguments");
        naive_blit(&mut want, &src, &case);
        assert!(got == want, "{case:?}");
    }
}

#[test]
fn blit_checks_its_arguments() {
    let src = vec![0; 12];
    let at = |r, d, flags| {
        let case = Case {
            clip: whole(W, H),
            src_size: (4, 3),
            r,
            d,
            flags,
            tint: NO_TINT,
        };
        blit(&mut vec![0; (W * H) as usize], &src, &case)
    };
    assert!(matches!(at((1, 0, 4, 1), (0, 0), 0), Err(Bad::Range(_))));
    assert!(matches!(at((-1, 0, 1, 1), (0, 0), 0), Err(Bad::Range(_))));
    assert!(matches!(at((0, 2, 1, 2), (0, 0), 0), Err(Bad::Range(_))));
    assert_eq!(at((9, 9, 0, 3), (0, 0), 0), Ok(())); // empty: nothing is read
    // The source must fit even where the clip hides it.
    assert!(matches!(
        at((0, 0, 5, 1), (100, 100), 0),
        Err(Bad::Range(_))
    ));
    assert!(matches!(at((0, 0, 1, 1), (0, 0), 32), Err(Bad::Arg(_))));
}

#[test]
fn targets_check_their_size_and_clip() {
    let mut px = vec![0; 6];
    assert!(Target::new(&mut px, 3, 2, whole(3, 2)).is_ok());
    assert!(Target::new(&mut px, 4, 2, whole(4, 2)).is_err());
    assert!(Target::new(&mut px, -1, 2, whole(0, 0)).is_err());
    assert!(
        Target::new(
            &mut px,
            3,
            2,
            Clip {
                x0: 2,
                y0: 0,
                x1: 1,
                y1: 2
            }
        )
        .is_err()
    );
    assert!(
        Target::new(
            &mut px,
            3,
            2,
            Clip {
                x0: 0,
                y0: 0,
                x1: 3,
                y1: 3
            }
        )
        .is_err()
    );
    assert!(
        Target::new(
            &mut px,
            3,
            2,
            Clip {
                x0: -1,
                y0: 0,
                x1: 3,
                y1: 2
            }
        )
        .is_err()
    );
}

#[test]
fn mask_blends_by_mask_and_strength() {
    let mut px = vec![0xff40_4040u32 as i32; 4];
    let mut t = Target::new(&mut px, 2, 2, whole(2, 2)).expect("valid arguments");
    // Clamped to 0 and 255; 255 at full strength writes the colour opaque.
    blend_mask(&mut t, &[-7, 999, 128, 0], 0x0000_0000, 255).expect("valid arguments");
    // 0x40 * 127 + 127 over 255 is 32.37: 32; alpha 128 + 127 rounds to 255.
    assert_eq!(
        px,
        [
            0xff40_4040u32 as i32,
            0xff00_0000u32 as i32,
            0xff20_2020u32 as i32,
            0xff40_4040u32 as i32
        ]
    );
    let mut t = Target::new(&mut px, 2, 2, whole(2, 2)).expect("valid arguments");
    assert!(matches!(
        blend_mask(&mut t, &[0; 4], 0, 256),
        Err(Bad::Arg(_))
    ));
    assert!(matches!(
        blend_mask(&mut t, &[0; 3], 0, 10),
        Err(Bad::Arg(_))
    ));
}

#[test]
fn mask_covers_only_the_clip() {
    let mut px = vec![0; 9];
    let mut t = Target::new(
        &mut px,
        3,
        3,
        Clip {
            x0: 1,
            y0: 1,
            x1: 3,
            y1: 2,
        },
    )
    .expect("valid arguments");
    blend_mask(&mut t, &[255, 255], 0x00ab_cdef, 255).expect("valid arguments");
    assert_eq!(
        px,
        [
            0,
            0,
            0,
            0,
            0xffab_cdefu32 as i32,
            0xffab_cdefu32 as i32,
            0,
            0,
            0
        ]
    );
}

#[test]
fn copy_block_refuses_rows_outside() {
    let mut out = Vec::new();
    assert!(copy_block(&[1, 2, 3, 4], 2, (1, 1, 1, 1), &mut out).is_ok());
    assert_eq!(out, [4]);
    assert!(matches!(
        copy_block(&[1, 2, 3, 4], 2, (1, 1, 2, 1), &mut out),
        Err(Bad::Range(_))
    ));
    assert!(matches!(
        copy_block(&[1], usize::MAX, (0, 2, 1, 1), &mut out),
        Err(Bad::Range(_))
    ));
}
