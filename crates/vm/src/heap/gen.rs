//! Minor collections: the young objects only (plan S11-07).
//!
//! Objects are young from allocation to the next collection; every one that
//! survives a collection is old. Old objects never move and are not traced
//! here: they count as alive until the next major collection (`collect_weak`).
//! An old object can only come to reference a young one by a store made after
//! it became old, and every store goes through `Heap::get_mut`, which
//! records an old object in the remembered set the first time it is opened
//! for writing. So a minor collection marks from the roots and the remembered
//! objects, sweeps the young list, and promotes what it marked: its cost
//! follows the young objects, not the whole heap.
//!
//! References (`java.lang.ref`) keep their rule: a young referent reached
//! only through one is cleared. An old reference can hold a young referent:
//! one promoted before its constructor stored the referent. Such a store
//! remembers it, so the remembered references are checked with the young
//! ones.

use super::{Heap, WeakInfo, trace};
use crate::value::Ref;

/// Young bytes that make a minor collection due.
pub(super) const YOUNG_BYTES: usize = 8 << 20;

impl Heap {
    /// Marks the young objects reachable from `roots` and the remembered old
    /// objects, clears dead young referents, frees the unmarked young
    /// objects and promotes the rest. Returns how many were freed and the
    /// cleared references, as `collect_weak` does.
    pub fn collect_minor(
        &mut self,
        roots: impl IntoIterator<Item = Ref>,
        weak: Option<&WeakInfo<'_>>,
    ) -> (usize, Vec<Ref>) {
        let mut work: Vec<Ref> = roots.into_iter().collect();
        let mut discovered = Vec::new();
        for &i in &self.remembered {
            let Some(slot) = self.slots.get(i as usize) else {
                continue;
            };
            if let Some(object) = &slot.object {
                let skip = weak.and_then(|w| w.referent_slot(object.class));
                if skip.is_some() {
                    discovered.push(super::make_ref(i, slot.generation));
                }
                trace(object, &mut work, skip);
            }
        }
        while let Some(r) = work.pop() {
            let Some(slot) = self.slots.get_mut(r.index() as usize) else {
                continue;
            };
            let dead = slot.generation != r.generation || slot.object.is_none();
            if slot.old || slot.marked || dead {
                continue;
            }
            slot.marked = true;
            if let Some(object) = &slot.object {
                let skip = weak.and_then(|w| w.referent_slot(object.class));
                if skip.is_some() {
                    discovered.push(r);
                }
                trace(object, &mut work, skip);
            }
        }
        let cleared = self.clear_dead_referents(&discovered, weak, true);
        let mut freed = 0;
        let mut freed_bytes = 0;
        for index in std::mem::take(&mut self.young) {
            let Some(slot) = self.slots.get_mut(index as usize) else {
                continue;
            };
            if slot.marked {
                slot.marked = false;
                slot.old = true;
            } else if let Some(object) = slot.object.take() {
                freed_bytes += object.bytes();
                slot.generation = slot.generation.wrapping_add(1);
                slot.lock = super::LockWord::default();
                self.free.push(index);
                freed += 1;
            }
        }
        for index in std::mem::take(&mut self.remembered) {
            if let Some(slot) = self.slots.get_mut(index as usize) {
                slot.remembered = false;
            }
        }
        self.live_bytes = self.live_bytes.saturating_sub(freed_bytes);
        self.young_bytes = 0;
        self.collections += 1;
        (freed, cleared)
    }

    /// Whether the next collection must be a major one: the old generation
    /// has grown to where a full collection was planned.
    pub fn major_due(&self) -> bool {
        self.live_bytes - self.young_bytes >= self.next_gc
    }
}
