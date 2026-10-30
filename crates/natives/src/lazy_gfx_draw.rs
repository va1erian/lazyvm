//! `lazy.gfx.Surface`: drawing natives over `gfx::raster` (docs/gfx.md). Each
//! call gets the surface's array, size and clip from Java, has them checked
//! again, and draws in place. The heap lends one array at a time, so a blit's
//! source and a mask are first copied out, only the part the clip lets
//! through, into a buffer kept for the next call.

use std::cell::RefCell;

use gfx::raster::{self, Bad, Clip, Target, Tint};
use vm::heap::Array;
use vm::{Ref, Value, Vm, VmError, VmResult};

use crate::{int, obj, ret_void};

const SURFACE: &str = "lazy/gfx/Surface";

thread_local! {
    /// Blit sources and masks, reused so that drawing a sprite allocates nothing.
    static SCRATCH: RefCell<Vec<i32>> = const { RefCell::new(Vec::new()) };
}

pub fn register(vm: &mut Vm) {
    vm.register_native(SURFACE, "fill0", "([IIIIIIIIIIIIZ)V", fill0);
    vm.register_native(SURFACE, "line0", "([IIIIIIIIIIII)V", line0);
    vm.register_native(SURFACE, "blit0", "([IIIIIII[IIIIIIIIIIIII)V", blit0);
    vm.register_native(SURFACE, "blendMask0", "([IIIIIII[III)V", blend_mask0);
}

fn bad(b: Bad) -> VmError {
    match b {
        Bad::Arg(m) => VmError::exception("java/lang/IllegalArgumentException", m),
        Bad::Range(m) => VmError::exception("java/lang/IndexOutOfBoundsException", m),
        Bad::Memory => VmError::OutOfMemory,
    }
}

fn ints(vm: &Vm, r: Ref) -> VmResult<&[i32]> {
    match vm.heap.array(r)? {
        Array::Int(a) => Ok(a),
        _ => Err(VmError::verify("lazy.gfx.Surface needs an int[]")),
    }
}

/// The surface's size and clip, from arguments 1 to 6 (0 is its array).
fn shape(args: &[Value]) -> VmResult<(i32, i32, Clip)> {
    let clip = Clip {
        x0: int(args, 3)?,
        y0: int(args, 4)?,
        x1: int(args, 5)?,
        y1: int(args, 6)?,
    };
    Ok((int(args, 1)?, int(args, 2)?, clip))
}

/// Runs `draw` on the surface named by arguments 0 to 6.
fn on_surface(
    vm: &mut Vm,
    args: &[Value],
    draw: impl FnOnce(&mut Target) -> Result<(), Bad>,
) -> VmResult<Option<Value>> {
    let (w, h, clip) = shape(args)?;
    let Array::Int(px) = vm.heap.array_mut(obj(args, 0)?)? else {
        return Err(VmError::verify("lazy.gfx.Surface needs an int[]"));
    };
    let mut target = Target::new(px, w, h, clip).map_err(bad)?;
    draw(&mut target).map_err(bad)?;
    ret_void()
}

fn fill0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let (x, y, w, h) = (int(args, 7)?, int(args, 8)?, int(args, 9)?, int(args, 10)?);
    let (argb, blend) = (int(args, 11)?, int(args, 12)? != 0);
    on_surface(vm, args, |t| {
        raster::fill(t, x, y, w, h, argb, blend);
        Ok(())
    })
}

fn line0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let (x0, y0, x1, y1) = (int(args, 7)?, int(args, 8)?, int(args, 9)?, int(args, 10)?);
    let argb = int(args, 11)?;
    on_surface(vm, args, |t| {
        raster::line(t, x0, y0, x1, y1, argb);
        Ok(())
    })
}

/// Arguments: the surface (0 to 6), then the source array, its width and
/// height, the source rectangle, the destination corner, the flags, and the
/// key, key colour and solid colour.
fn blit0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let (w, h, clip) = shape(args)?;
    let (src, sw, sh) = (obj(args, 7)?, int(args, 8)?, int(args, 9)?);
    let rect = (
        int(args, 10)?,
        int(args, 11)?,
        int(args, 12)?,
        int(args, 13)?,
    );
    let (dest, flags) = ((int(args, 14)?, int(args, 15)?), int(args, 16)?);
    let tint = Tint {
        key: int(args, 17)?,
        key_color: int(args, 18)?,
        solid: int(args, 19)?,
    };
    // The area is worked out on the target before the source is read.
    let Array::Int(px) = vm.heap.array_mut(obj(args, 0)?)? else {
        return Err(VmError::verify("lazy.gfx.Surface needs an int[]"));
    };
    let target = Target::new(px, w, h, clip).map_err(bad)?;
    let Some(area) = raster::blit_area(&target, (sw, sh), rect, dest, flags).map_err(bad)? else {
        return ret_void();
    };
    SCRATCH.with_borrow_mut(|block| {
        let pixels = ints(vm, src)?;
        raster::check_size(pixels.len(), sw, sh).map_err(bad)?;
        let part = (
            area.sx as usize,
            area.sy as usize,
            area.w as usize,
            area.h as usize,
        );
        raster::copy_block(pixels, sw as usize, part, block).map_err(bad)?;
        on_surface(vm, args, |t| raster::blit_block(t, &area, block, tint))
    })
}

/// Arguments: the surface (0 to 6), the mask (one value per surface pixel),
/// the colour and the strength.
fn blend_mask0(vm: &mut Vm, args: &[Value]) -> VmResult<Option<Value>> {
    let (w, h, clip) = shape(args)?;
    let (mask, rgb, strength) = (obj(args, 7)?, int(args, 8)?, int(args, 9)?);
    SCRATCH.with_borrow_mut(|block| {
        let values = ints(vm, mask)?;
        raster::check_size(values.len(), w, h).map_err(bad)?;
        raster::check_clip(w, h, clip).map_err(bad)?;
        let part = (
            clip.x0 as usize,
            clip.y0 as usize,
            (clip.x1 - clip.x0) as usize,
            (clip.y1 - clip.y0) as usize,
        );
        raster::copy_block(values, w as usize, part, block).map_err(bad)?;
        on_surface(vm, args, |t| raster::blend_mask(t, block, rgb, strength))
    })
}
