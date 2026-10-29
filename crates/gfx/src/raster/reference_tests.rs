//! Fills, masks and blending against references worked out another way:
//! floating-point source-over, and pixel-by-pixel loops over whole rectangles.

use super::tests::{H, Lcg, W, in_clip, whole};
use super::*;

/// Source-over on unpremultiplied pixels in floating point.
fn exact_over(dst: u32, rgb: u32, a: u32) -> [f64; 4] {
    let (sa, da) = (a as f64 / 255.0, (dst >> 24) as f64 / 255.0);
    let out = sa + da * (1.0 - sa);
    let ch = |sh: u32| {
        let (s, d) = ((rgb >> sh & 0xff) as f64, (dst >> sh & 0xff) as f64);
        (s * sa + d * da * (1.0 - sa)) / out
    };
    [out * 255.0, ch(16), ch(8), ch(0)]
}

#[test]
fn over_is_source_over_rounded_to_nearest() {
    let mut rng = Lcg(3);
    for _ in 0..200_000 {
        let (a, dst, rgb) = (
            rng.next(1, 254) as u32,
            (rng.next(0, 0xffff) << 16 | rng.next(0, 0xffff)) as u32,
            rng.next(0, 0x00ff_ffff) as u32,
        );
        let got = over(dst as i32, rgb as i32, a) as u32;
        let want = exact_over(dst, rgb, a);
        for (i, sh) in [24, 16, 8, 0].into_iter().enumerate() {
            let g = (got >> sh & 0xff) as f64;
            assert!(
                (g - want[i]).abs() <= 0.5 + 1e-9, // rounded to nearest
                "{dst:08x} {rgb:06x} at {a}: {got:08x}, channel {i} wants {}",
                want[i]
            );
        }
    }
    // An opaque destination stays opaque, and its colours are the plain mix.
    for a in 1..255u32 {
        let got = over(0xff20_4060u32 as i32, 0x00c0_a080, a) as u32;
        let mix = |s: u32, d: u32| (s * a + d * (255 - a) + 127) / 255;
        assert_eq!(
            got,
            0xff00_0000 | mix(0xc0, 0x20) << 16 | mix(0xa0, 0x40) << 8 | mix(0x80, 0x60)
        );
    }
}

#[test]
fn fills_match_the_slow_way() {
    let mut rng = Lcg(11);
    let colours = [
        0x0012_3456,
        0x0112_3456,
        0x80ab_cdefu32 as i32,
        0xff65_4321u32 as i32,
        0x3300_ff00,
    ];
    for _ in 0..4000 {
        let clip = Clip {
            x0: rng.next(0, 3),
            y0: rng.next(0, 2),
            x1: rng.next(3, W),
            y1: rng.next(2, H),
        };
        let (x, y, w, h) = (
            rng.next(-9, 9),
            rng.next(-7, 7),
            rng.next(-2, 12),
            rng.next(-2, 9),
        );
        let (argb, blend) = (colours[rng.next(0, 4) as usize], rng.next(0, 1) == 1);
        let base: Vec<i32> = (0..W * H)
            .map(|i| i.wrapping_mul(0x0507_0b0d) ^ 0x2a00_0000)
            .collect();
        let mut want = base.clone();
        for yy in y..y + h.max(0) {
            for xx in x..x + w.max(0) {
                if in_clip(clip, xx.into(), yy.into()) {
                    let p = &mut want[(yy * W + xx) as usize];
                    *p = match (blend, argb as u32 >> 24) {
                        (false, _) => argb,
                        (true, 0) => *p,
                        (true, 255) => argb,
                        (true, a) => over(*p, argb, a),
                    };
                }
            }
        }
        let mut got = base;
        let mut t = Target::new(&mut got, W, H, clip).expect("valid arguments");
        fill(&mut t, x, y, w, h, argb, blend);
        assert!(
            got == want,
            "{x},{y} {w}x{h} {argb:08x} blend {blend} clip {clip:?}"
        );
    }
}

#[test]
fn masks_match_the_slow_way() {
    let mut rng = Lcg(19);
    for _ in 0..3000 {
        let clip = Clip {
            x0: rng.next(0, 3),
            y0: rng.next(0, 2),
            x1: rng.next(3, W),
            y1: rng.next(2, H),
        };
        let mask: Vec<i32> = (0..W * H).map(|_| rng.next(-20, 280)).collect();
        let (rgb, strength) = (rng.next(0, 0x00ff_ffff), rng.next(0, 255));
        let base: Vec<i32> = (0..W * H)
            .map(|i| i.wrapping_mul(0x0305_0709) ^ 0x7f00_0000)
            .collect();
        let mut want = base.clone();
        for y in 0..H {
            for x in 0..W {
                let i = (y * W + x) as usize;
                let a = (mask[i].clamp(0, 255) as u32 * strength as u32 + 127) / 255;
                if in_clip(clip, x.into(), y.into()) && a > 0 {
                    want[i] = if a == 255 {
                        rgb | 0xff00_0000u32 as i32
                    } else {
                        over(want[i], rgb, a)
                    };
                }
            }
        }
        let rect = (
            clip.x0 as usize,
            clip.y0 as usize,
            (clip.x1 - clip.x0) as usize,
            (clip.y1 - clip.y0) as usize,
        );
        let mut block = Vec::new();
        copy_block(&mask, W as usize, rect, &mut block).expect("valid arguments");
        let mut got = base;
        let mut t = Target::new(&mut got, W, H, clip).expect("valid arguments");
        blend_mask(&mut t, &block, rgb, strength).expect("valid arguments");
        assert!(got == want, "clip {clip:?} strength {strength}");
    }
}

#[test]
fn empty_surfaces_return_at_once() {
    // A 0 by 2^31-1 surface has no pixels: nothing may walk its rows or steps.
    let mut px: Vec<i32> = Vec::new();
    let mut t = Target::new(&mut px, 0, i32::MAX, whole(0, i32::MAX)).expect("valid arguments");
    line(&mut t, 0, i32::MIN, 0, i32::MAX, -1);
    let mut block = vec![1];
    copy_block(&[], 0, (0, 0, 0, i32::MAX as usize), &mut block).expect("valid arguments");
    assert!(block.is_empty());
    blend_mask(&mut t, &block, 0, 255).expect("valid arguments");
    let mut t = Target::new(&mut px, i32::MAX, 0, whole(i32::MAX, 0)).expect("valid arguments");
    line(&mut t, i32::MIN, 0, i32::MAX, 0, -1);
}
