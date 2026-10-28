//! Fitting a frame into a window: the largest nearest-neighbour scale that
//! keeps the aspect ratio, centred, with black bars.

/// Fills `dst` (`dw` by `dh`) with `src` (`sw` by `sh`) scaled to fit.
/// Pixels are 0x00RRGGBB; the top byte of `src` is ignored. `src` is `i32`
/// because it is a Java `int[]`, read in place.
pub fn fit(src: &[i32], sw: usize, sh: usize, dst: &mut [u32], dw: usize, dh: usize) {
    dst.fill(0);
    if sw == 0 || sh == 0 || dw == 0 || dh == 0 || src.len() < sw * sh || dst.len() < dw * dh {
        return;
    }
    // Compare dw/sw with dh/sh without division: the narrower side limits.
    let (tw, th) = if dw * sh <= dh * sw {
        (dw, (sh * dw / sw).max(1))
    } else {
        ((sw * dh / sh).max(1), dh)
    };
    let (ox, oy) = ((dw - tw) / 2, (dh - th) / 2);
    // One source column per window column; out of memory leaves the frame black.
    let mut columns = Vec::new();
    if columns.try_reserve_exact(tw).is_err() {
        return;
    }
    columns.extend((0..tw).map(|x| x * sw / tw));
    for y in 0..th {
        let row = &src[(y * sh / th) * sw..][..sw];
        let out = &mut dst[(oy + y) * dw + ox..][..tw];
        for (o, &sx) in out.iter_mut().zip(&columns) {
            *o = row[sx] as u32 & 0x00ff_ffff;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::fit;

    #[test]
    fn doubles_exactly() {
        let src = [1, 2, 3, 4];
        let mut dst = [9; 16];
        fit(&src, 2, 2, &mut dst, 4, 4);
        assert_eq!(dst, [1, 1, 2, 2, 1, 1, 2, 2, 3, 3, 4, 4, 3, 3, 4, 4]);
    }

    #[test]
    fn letterboxes_a_wide_window() {
        let src = [0x00ff_ffff];
        let mut dst = [7; 3];
        fit(&src, 1, 1, &mut dst, 3, 1);
        assert_eq!(dst, [0, 0x00ff_ffff, 0]);
    }

    #[test]
    fn ignores_short_buffers() {
        let mut dst = [7; 4];
        fit(&[1], 2, 2, &mut dst, 2, 2);
        assert_eq!(dst, [0; 4]);
    }
}
