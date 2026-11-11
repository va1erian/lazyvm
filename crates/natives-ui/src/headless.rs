//! `lazy.internal.ui.Headless`: the steps a headless test drives a window
//! with (docs/ui.md, "Evidence and tests"), one native for all of them.

use vm::heap::Array;
use vm::{Value, Vm, VmResult};
use xui_bind::drive;

use natives::{arg, int, obj};

pub const HEADLESS: &str = "lazy/internal/ui/Headless";

/// Steps, shared with `Headless.java`.
mod step {
    pub const CLICK: i32 = 1;
    pub const HOVER: i32 = 2;
    pub const SCROLL: i32 = 3;
    pub const KEY: i32 = 4;
    pub const TYPE: i32 = 5;
    pub const ADVANCE: i32 = 6;
    pub const CLOSE: i32 = 7;
    /// Returns `{width, height, checksum}` of the main window's pixels.
    pub const FRAME: i32 = 8;
    /// Writes the frame as a binary PPM to the path in `s`.
    pub const DUMP: i32 = 9;
    /// Returns `{upcalls}` made so far.
    pub const UPCALLS: i32 = 10;
    pub const CLICK_AT: i32 = 11;
}

pub fn register(vm: &mut Vm) {
    let desc = "(IIILjava/lang/String;)[I";
    vm.register_native(HEADLESS, "step0", desc, step0);
    vm.register_native(HEADLESS, "font0", "([B)V", |vm, a| {
        let Array::Byte(b) = vm.heap.array(obj(a, 0)?)? else {
            return Err(vm::VmError::verify("font0 needs a byte[]"));
        };
        xui_bind::set_font(b.iter().map(|&x| x as u8).collect());
        Ok(None)
    });
}

fn state(msg: String) -> vm::VmError {
    vm::VmError::exception("java/lang/IllegalStateException", msg)
}

/// FNV-1a over the RGB bytes, so a golden is one number.
fn checksum(rgba: &[u8]) -> i32 {
    let mut h: u32 = 0x811c_9dc5;
    for px in rgba.as_chunks::<4>().0 {
        for &b in &px[..3] {
            h = (h ^ u32::from(b)).wrapping_mul(0x0100_0193);
        }
    }
    h as i32
}

fn step0(vm: &mut Vm, a: &[Value]) -> VmResult<Option<Value>> {
    let (op, id, i) = (int(a, 0)?, int(a, 1)?, int(a, 2)?);
    let s = match arg(a, 3)? {
        Value::Ref(Some(_)) => Some(vm.string_arg(arg(a, 3)?)?),
        _ => None,
    };
    let s = || s.clone().ok_or_else(|| "a string is required".to_string());
    let mut out = Vec::new();
    // A step pumps the loop, so the callbacks it causes run nested in the
    // script's: the VM is lent again for them.
    let r = vm.lend(|| match op {
        step::CLICK => drive::click(id, None),
        // `i` holds the offset: x in the high 16 bits, y in the low.
        step::CLICK_AT => drive::click(id, Some((i >> 16, i & 0xffff))),
        step::HOVER => drive::hover(id),
        step::SCROLL => drive::scroll(id, i),
        step::KEY => drive::key(id, i),
        step::TYPE => drive::type_text(&s()?),
        step::ADVANCE => drive::advance(u64::try_from(i).unwrap_or(0)),
        step::CLOSE => drive::close(),
        step::FRAME | step::DUMP => drive::frame().and_then(|(w, h, px)| {
            out = vec![w as i32, h as i32, checksum(&px)];
            if op != step::DUMP {
                return Ok(());
            }
            let mut ppm = format!("P6\n{w} {h}\n255\n").into_bytes();
            ppm.extend(px.as_chunks::<4>().0.iter().flat_map(|p| [p[0], p[1], p[2]]));
            let path = s()?;
            std::fs::write(&path, ppm).map_err(|e| format!("{path}: {e}"))
        }),
        step::UPCALLS => {
            out = vec![i32::try_from(drive::upcalls()).unwrap_or(i32::MAX)];
            Ok(())
        }
        _ => Err(format!("headless step {op}")),
    });
    r.map_err(state)?;
    let len = out.len() as i32;
    let array = vm.new_array_of("I", len)?;
    if let vm::heap::Array::Int(dst) = vm.heap.array_mut(array)? {
        dst.copy_from_slice(&out);
    }
    Ok(Some(Value::Ref(Some(array))))
}

#[cfg(test)]
mod tests {
    #[test]
    fn the_checksum_ignores_alpha() {
        let a = super::checksum(&[1, 2, 3, 255, 4, 5, 6, 0]);
        let b = super::checksum(&[1, 2, 3, 0, 4, 5, 6, 9]);
        assert_eq!(a, b);
        assert_ne!(a, super::checksum(&[1, 2, 4, 255, 4, 5, 6, 0]));
    }
}
