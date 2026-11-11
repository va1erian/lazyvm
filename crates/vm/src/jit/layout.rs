//! Where compiled code finds things in a `Value`. `Value` is `repr(u32)` and
//! `Ref` is `repr(C)`, so the language fixes the layout: each variant is a
//! `repr(C)` struct of the `u32` tag and its payload, which puts a 32-bit
//! payload (`int`, `float`, a reference) at 4 and a 64-bit one (`long`,
//! `double`) at 8. A reference is its slot index (0 for null: `Option<Ref>`
//! is 8 bytes, so `None` is the index's zero niche) then its generation.

use std::mem::{offset_of, size_of};

use crate::value::{Ref, Value};

/// Bytes per `Value`, in locals, on the operand stack and in fields.
pub(super) const SIZE: i32 = 16;
/// The `u32` tag.
pub(super) const TAG: i32 = 0;
/// `int`, `float` and a reference's slot index.
pub(super) const LOW: i32 = 4;
/// `long`, `double`.
pub(super) const HIGH: i32 = 8;

/// Tags, the discriminants `Value` declares.
pub(super) const INT: u32 = 0;
pub(super) const LONG: u32 = 1;
pub(super) const FLOAT: u32 = 2;
pub(super) const DOUBLE: u32 = 3;
pub(super) const REF: u32 = 4;
pub(super) const TOP: u32 = 5;

const _: () = {
    assert!(size_of::<Value>() == SIZE as usize);
    assert!(size_of::<Option<Ref>>() == 8);
    assert!(offset_of!(Ref, index) == 0);
    assert!(offset_of!(Ref, generation) == 4);
};

#[cfg(test)]
mod tests {
    use super::*;

    /// The tags match what `Value` declares, read back through a match.
    #[test]
    fn tags_are_the_declared_discriminants() {
        let tag = |v: Value| match v {
            Value::Int(_) => INT,
            Value::Long(_) => LONG,
            Value::Float(_) => FLOAT,
            Value::Double(_) => DOUBLE,
            Value::Ref(_) => REF,
            Value::Top => TOP,
        };
        assert_eq!(tag(Value::Int(1)), 0);
        assert_eq!(tag(Value::Top), 5);
    }
}
