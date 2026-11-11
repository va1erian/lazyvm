//! The ids the debugger holds. They are eight bytes everywhere (`IDSizes`).
//!
//! Types, methods, fields and frames are numbers computed from what they name,
//! so they need no table. Objects are different: the debugger may hold one
//! across a collection, so an id keeps its object alive until the debugger
//! disposes of it (`Ids::roots` feeds the collector), and the same object
//! always gets the same id.

use std::collections::HashMap;

use super::wire::{Res, err};
use crate::class::{ClassId, MethodRef};
use crate::value::Ref;

/// Objects the debugger may hold at once. Past it a command fails with
/// `OUT_OF_MEMORY` instead of pinning the heap away.
const MAX_OBJECT_IDS: usize = 1 << 20;

#[derive(Default)]
pub struct Ids {
    by_id: HashMap<u64, (Ref, u32)>,
    by_ref: HashMap<Ref, u64>,
    next: u64,
}

impl Ids {
    /// The id of `r`, counting one more time it was sent to the debugger.
    pub fn of(&mut self, r: Ref) -> Res<u64> {
        if let Some(&id) = self.by_ref.get(&r) {
            if let Some(entry) = self.by_id.get_mut(&id) {
                entry.1 = entry.1.saturating_add(1);
            }
            return Ok(id);
        }
        if self.by_id.len() >= MAX_OBJECT_IDS {
            return Err(err::OUT_OF_MEMORY);
        }
        self.next += 1;
        self.by_id.insert(self.next, (r, 1));
        self.by_ref.insert(r, self.next);
        Ok(self.next)
    }

    /// The id of a possibly null reference: 0 is null.
    pub fn of_opt(&mut self, r: Option<Ref>) -> Res<u64> {
        r.map_or(Ok(0), |r| self.of(r))
    }

    /// The id `r` already has, or 0: for matching without pinning anything.
    pub fn find(&self, r: Ref) -> u64 {
        self.by_ref.get(&r).copied().unwrap_or(0)
    }

    /// The object an id names; a null id is `INVALID_OBJECT`.
    pub fn get(&self, id: u64) -> Res<Ref> {
        self.by_id.get(&id).map(|e| e.0).ok_or(err::INVALID_OBJECT)
    }

    /// `DisposeObjects`: drops `count` of the sends; the last one frees the id.
    pub fn dispose(&mut self, id: u64, count: u32) {
        let Some(entry) = self.by_id.get_mut(&id) else {
            return;
        };
        entry.1 = entry.1.saturating_sub(count);
        if entry.1 == 0 {
            let r = entry.0;
            self.by_id.remove(&id);
            self.by_ref.remove(&r);
        }
    }

    pub fn clear(&mut self) {
        self.by_id.clear();
        self.by_ref.clear();
    }

    /// Everything the debugger holds, for the collector.
    pub fn roots(&self, out: &mut Vec<Ref>) {
        out.extend(self.by_ref.keys().copied());
    }
}

/// Reference type ids are the class index plus one, so 0 stays null.
pub fn type_id(c: ClassId) -> u64 {
    u64::from(c.0) + 1
}

pub fn class_of_type(id: u64) -> Res<ClassId> {
    let index = id.checked_sub(1).and_then(|i| u32::try_from(i).ok());
    index.map(ClassId).ok_or(err::INVALID_CLASS)
}

/// A method id holds its class and index; so does a field id.
pub fn member_id(c: ClassId, index: usize) -> u64 {
    (type_id(c) << 16) | (index as u64 & 0xffff)
}

pub fn split_member(id: u64) -> Res<(ClassId, usize)> {
    Ok((class_of_type(id >> 16)?, (id & 0xffff) as usize))
}

pub fn method_id(m: MethodRef) -> u64 {
    member_id(m.class, usize::from(m.index))
}

/// A frame id holds the thread's index and the frame's depth from the bottom
/// of its stack, and is good while the thread stays suspended.
pub fn frame_id(thread: usize, depth: usize) -> u64 {
    ((thread as u64 + 1) << 32) | (depth as u64 & 0xffff_ffff)
}

pub fn split_frame(id: u64) -> Res<(usize, usize)> {
    let thread = (id >> 32).checked_sub(1).ok_or(err::INVALID_FRAMEID)?;
    Ok((
        usize::try_from(thread).map_err(|_| err::INVALID_FRAMEID)?,
        (id & 0xffff_ffff) as usize,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn member_and_frame_ids_round_trip_and_zero_is_never_valid() {
        let m = MethodRef {
            class: ClassId(7),
            index: 3,
        };
        assert_eq!(split_member(method_id(m)), Ok((ClassId(7), 3)));
        assert_eq!(split_member(0), Err(err::INVALID_CLASS));
        assert_eq!(split_frame(frame_id(2, 9)), Ok((2, 9)));
        assert_eq!(split_frame(0), Err(err::INVALID_FRAMEID));
        assert_eq!(class_of_type(0), Err(err::INVALID_CLASS));
        assert_eq!(class_of_type(u64::MAX), Err(err::INVALID_CLASS));
    }
}
