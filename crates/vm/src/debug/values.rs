//! Values on the wire: a tag byte (the signature's first character, with
//! `s`, `t`, `g`, `l`, `c` for the object kinds the debugger treats
//! specially) and then the data.

use super::wire::{Res, Writer, err};
use crate::Vm;
use crate::value::{Ref, Value};

pub const TAG_ARRAY: u8 = b'[';
pub const TAG_OBJECT: u8 = b'L';
const OBJECT_TAGS: &[u8] = b"L[stglc";

impl Vm {
    /// The tag of a live object: what the debugger shows it as.
    pub(super) fn object_tag(&self, r: Ref) -> u8 {
        let Ok(class) = self.heap.class_of(r) else {
            return TAG_OBJECT;
        };
        if self.class(class).is_array() {
            return TAG_ARRAY;
        }
        if Some(class) == self.wk.string {
            return b's';
        }
        if Some(class) == self.wk.class {
            return b'c';
        }
        let kinds = [
            ("java/lang/Thread", b't'),
            ("java/lang/ThreadGroup", b'g'),
            ("java/lang/ClassLoader", b'l'),
        ];
        for (name, tag) in kinds {
            if let Some(base) = self.loaded_class(name)
                && self.is_subclass(class, base)
            {
                return tag;
            }
        }
        TAG_OBJECT
    }

    /// Writes `v` as a field or local whose signature starts with `kind`.
    /// `tagged` puts the tag byte first. A value of another kind than the
    /// signature says is `TYPE_MISMATCH`.
    pub(super) fn write_value(
        &mut self,
        w: &mut Writer,
        v: Value,
        kind: u8,
        tagged: bool,
    ) -> Res<()> {
        let object = OBJECT_TAGS.contains(&kind);
        match (v, kind) {
            (Value::Ref(r), _) if object => {
                let tag = match r {
                    Some(r) => self.object_tag(r),
                    None if kind == TAG_ARRAY => TAG_ARRAY,
                    None => TAG_OBJECT,
                };
                if tagged {
                    w.u8(tag);
                }
                let id = self.debug.ids.of_opt(r)?;
                w.id(id);
            }
            (Value::Int(i), b'Z' | b'B' | b'C' | b'S' | b'I') => {
                if tagged {
                    w.u8(kind);
                }
                match kind {
                    b'Z' | b'B' => w.u8(i as u8),
                    b'C' | b'S' => w.u16(i as u16),
                    _ => w.i32(i),
                }
            }
            (Value::Long(l), b'J') => self.number(w, kind, tagged, |w| w.i64(l)),
            (Value::Float(f), b'F') => self.number(w, kind, tagged, |w| w.i32(f.to_bits() as i32)),
            (Value::Double(d), b'D') => self.number(w, kind, tagged, |w| w.i64(d.to_bits() as i64)),
            _ => return Err(err::TYPE_MISMATCH),
        }
        Ok(())
    }

    fn number(&self, w: &mut Writer, kind: u8, tagged: bool, put: impl FnOnce(&mut Writer)) {
        if tagged {
            w.u8(kind);
        }
        put(w);
    }

    /// Writes an object reference with its tag.
    pub(super) fn write_object(&mut self, w: &mut Writer, r: Option<Ref>) -> Res<()> {
        self.write_value(w, Value::Ref(r), TAG_OBJECT, true)
    }
}
