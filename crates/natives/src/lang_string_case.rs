//! Natives of the package-private `java.lang.StringCase`: the parts of
//! `String.toUpperCase` and `toLowerCase` that need Unicode tables.

use vm::Vm;

use crate::unicode::tables;
use crate::{int, ret_int};

const STRING_CASE: &str = "java/lang/StringCase";

pub fn register(vm: &mut Vm) {
    // special(cp, upper, index): code point `index` of the multi-code-point
    // mapping of `cp`, or -1 when there is none.
    vm.register_native(STRING_CASE, "special", "(IZI)I", |_, a| {
        let (cp, upper, index) = (int(a, 0)?, int(a, 1)? != 0, int(a, 2)?);
        let chars = tables()?.special(cp, upper).unwrap_or(&[]);
        let at = usize::try_from(index).ok().and_then(|i| chars.get(i));
        ret_int(at.map_or(-1, |&c| c as i32))
    });
    // step(state, cp, after): see `Tables::sigma_step`.
    vm.register_native(STRING_CASE, "step", "(IIZ)I", |_, a| {
        ret_int(tables()?.sigma_step(int(a, 0)?, int(a, 1)?, int(a, 2)? != 0))
    });
    vm.register_native(STRING_CASE, "mask", "(I)I", |_, a| {
        ret_int(tables()?.sigma_mask(int(a, 0)?))
    });
}
