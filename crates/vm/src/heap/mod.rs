//! The heap: a slot table of objects and a precise, non-moving mark-sweep
//! collector (docs/decisions.md D5).
//!
//! Allocation never collects. The interpreter collects at instruction
//! boundaries, where every live reference is in a frame, a static, the intern
//! table or `Vm::roots`; see `crate::gc`. So Rust code may hold a `Ref`
//! across allocations, but not across a call into Java code.

pub mod array;
mod weak;

use std::num::NonZeroU32;

pub use array::Array;
pub use weak::WeakInfo;

use crate::VmError;
use crate::class::ClassId;
use crate::value::{Ref, Value};

/// Fixed per-object overhead counted against the heap limit.
const HEADER_BYTES: usize = 16;
/// The first collection happens once this much has been allocated.
const MIN_GC_BYTES: usize = 4 << 20;

#[derive(Debug)]
pub enum Body {
    Fields(Box<[Value]>),
    Array(Array),
}

#[derive(Debug)]
pub struct Object {
    pub class: ClassId,
    pub body: Body,
}

impl Object {
    fn bytes(&self) -> usize {
        HEADER_BYTES
            + match &self.body {
                Body::Fields(f) => f.len() * 8,
                Body::Array(a) => a.bytes(),
            }
    }
}

struct Slot {
    generation: u32,
    marked: bool,
    lock: LockWord,
    object: Option<Object>,
}

/// The owner of an object's monitor, kept with the object so an
/// uncontended `synchronized` touches nothing else (`crate::thread`).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct LockWord {
    /// The owning thread's index plus one; 0 when unowned.
    pub owner: u32,
    /// Entries by the owner.
    pub count: u32,
}

pub struct Heap {
    slots: Vec<Slot>,
    free: Vec<u32>,
    live_bytes: usize,
    limit: usize,
    next_gc: usize,
    pub collections: u64,
}

impl Heap {
    pub fn new(limit: usize) -> Heap {
        // Slot 0 is never used, so a `Ref` index is never zero.
        let dummy = Slot {
            generation: 0,
            marked: false,
            lock: LockWord::default(),
            object: None,
        };
        Heap {
            slots: vec![dummy],
            free: Vec::new(),
            live_bytes: 0,
            limit,
            next_gc: MIN_GC_BYTES.min(limit),
            collections: 0,
        }
    }

    pub fn limit(&self) -> usize {
        self.limit
    }

    pub fn live_bytes(&self) -> usize {
        self.live_bytes
    }

    /// Whether the interpreter should collect at the next safe point.
    pub fn wants_gc(&self) -> bool {
        self.live_bytes >= self.next_gc
    }

    /// Whether allocating `bytes` more would pass the limit.
    pub fn would_exceed(&self, bytes: usize) -> bool {
        self.live_bytes.saturating_add(bytes) > self.limit
    }

    /// Allocates an object. Fails with `OutOfMemoryError` only past twice the
    /// limit: natives cannot collect, so they may overshoot until the next
    /// safe point, while the allocating bytecodes collect first.
    pub fn alloc(&mut self, object: Object) -> Result<Ref, VmError> {
        let bytes = object.bytes();
        if self.live_bytes.saturating_add(bytes) > self.limit.saturating_mul(2) {
            return Err(VmError::OutOfMemory);
        }
        self.live_bytes += bytes;
        if let Some(index) = self.free.pop() {
            let slot = &mut self.slots[index as usize];
            slot.object = Some(object);
            return Ok(make_ref(index, slot.generation));
        }
        let index = u32::try_from(self.slots.len()).map_err(|_| VmError::OutOfMemory)?;
        self.slots.push(Slot {
            generation: 0,
            marked: false,
            lock: LockWord::default(),
            object: Some(object),
        });
        Ok(make_ref(index, 0))
    }

    pub fn get(&self, r: Ref) -> Result<&Object, VmError> {
        match self.slots.get(r.index() as usize) {
            Some(Slot {
                generation,
                object: Some(o),
                ..
            }) if *generation == r.generation => Ok(o),
            _ => Err(VmError::Internal(format!("stale reference {r:?}"))),
        }
    }

    pub fn get_mut(&mut self, r: Ref) -> Result<&mut Object, VmError> {
        match self.slots.get_mut(r.index() as usize) {
            Some(Slot {
                generation,
                object: Some(o),
                ..
            }) if *generation == r.generation => Ok(o),
            _ => Err(VmError::Internal(format!("stale reference {r:?}"))),
        }
    }

    /// The monitor word of a live object.
    pub fn lock_word(&mut self, r: Ref) -> Result<&mut LockWord, VmError> {
        match self.slots.get_mut(r.index() as usize) {
            Some(Slot {
                generation,
                lock,
                object: Some(_),
                ..
            }) if *generation == r.generation => Ok(lock),
            _ => Err(VmError::Internal(format!("stale reference {r:?}"))),
        }
    }

    pub fn class_of(&self, r: Ref) -> Result<ClassId, VmError> {
        Ok(self.get(r)?.class)
    }

    pub fn fields(&self, r: Ref) -> Result<&[Value], VmError> {
        match &self.get(r)?.body {
            Body::Fields(f) => Ok(f),
            Body::Array(_) => Err(VmError::verify("field access on an array")),
        }
    }

    pub fn fields_mut(&mut self, r: Ref) -> Result<&mut [Value], VmError> {
        match &mut self.get_mut(r)?.body {
            Body::Fields(f) => Ok(f),
            Body::Array(_) => Err(VmError::verify("field access on an array")),
        }
    }

    pub fn array(&self, r: Ref) -> Result<&Array, VmError> {
        match &self.get(r)?.body {
            Body::Array(a) => Ok(a),
            Body::Fields(_) => Err(VmError::verify("array access on a non-array")),
        }
    }

    pub fn array_mut(&mut self, r: Ref) -> Result<&mut Array, VmError> {
        match &mut self.get_mut(r)?.body {
            Body::Array(a) => Ok(a),
            Body::Fields(_) => Err(VmError::verify("array access on a non-array")),
        }
    }

    /// Marks everything reachable from `roots`, frees the rest, and returns
    /// how many objects were freed.
    pub fn collect(&mut self, roots: impl IntoIterator<Item = Ref>) -> usize {
        self.collect_weak(roots, None).0
    }

    /// Marks from `roots`, treating the referent of every reference object
    /// (`weak`) as not reaching it, then clears the referents nothing else
    /// reached and frees the rest. Returns how many objects were freed and
    /// the references whose referents were cleared, for their queues.
    pub fn collect_weak(
        &mut self,
        roots: impl IntoIterator<Item = Ref>,
        weak: Option<&WeakInfo<'_>>,
    ) -> (usize, Vec<Ref>) {
        let mut work: Vec<Ref> = roots.into_iter().collect();
        let mut discovered = Vec::new();
        while let Some(r) = work.pop() {
            let Some(slot) = self.slots.get_mut(r.index() as usize) else {
                continue;
            };
            if slot.marked || slot.generation != r.generation || slot.object.is_none() {
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
        let cleared = self.clear_dead_referents(&discovered, weak);
        let mut freed = 0;
        let mut live = 0;
        for (index, slot) in self.slots.iter_mut().enumerate().skip(1) {
            if slot.marked {
                slot.marked = false;
                live += slot.object.as_ref().map_or(0, Object::bytes);
            } else if slot.object.take().is_some() {
                slot.generation = slot.generation.wrapping_add(1);
                slot.lock = LockWord::default();
                self.free.push(index as u32);
                freed += 1;
            }
        }
        self.live_bytes = live;
        self.next_gc = live.saturating_mul(2).max(MIN_GC_BYTES).min(self.limit);
        self.collections += 1;
        (freed, cleared)
    }

    /// Clears the referent field of each discovered reference whose referent
    /// was not marked; called between marking and sweeping.
    fn clear_dead_referents(
        &mut self,
        discovered: &[Ref],
        weak: Option<&WeakInfo<'_>>,
    ) -> Vec<Ref> {
        let Some(weak) = weak else { return Vec::new() };
        let mut cleared = Vec::new();
        for &d in discovered {
            let Ok(obj) = self.get(d) else { continue };
            let Some(slot) = weak.referent_slot(obj.class) else {
                continue;
            };
            let referent = match &obj.body {
                Body::Fields(f) => f.get(slot as usize).copied(),
                Body::Array(_) => None,
            };
            let Some(Value::Ref(Some(x))) = referent else {
                continue;
            };
            let alive = self
                .slots
                .get(x.index() as usize)
                .is_some_and(|s| s.marked && s.generation == x.generation && s.object.is_some());
            if !alive {
                if let Ok(Body::Fields(f)) = self.get_mut(d).map(|o| &mut o.body)
                    && let Some(v) = f.get_mut(slot as usize)
                {
                    *v = Value::NULL;
                }
                cleared.push(d);
            }
        }
        cleared
    }

    pub fn object_count(&self) -> usize {
        self.slots.len() - 1 - self.free.len()
    }
}

fn make_ref(index: u32, generation: u32) -> Ref {
    // Slot 0 is reserved, so every allocated index is non-zero.
    let index = NonZeroU32::new(index).unwrap_or(NonZeroU32::MIN);
    Ref { index, generation }
}

/// Pushes what `object` references; `skip` is a reference object's
/// referent slot, which does not keep its referent alive.
fn trace(object: &Object, work: &mut Vec<Ref>, skip: Option<u32>) {
    match &object.body {
        Body::Fields(fields) => {
            work.extend(fields.iter().enumerate().filter_map(|(i, v)| match v {
                Value::Ref(Some(r)) if Some(i as u32) != skip => Some(*r),
                _ => None,
            }));
        }
        Body::Array(Array::Ref(items)) => work.extend(items.iter().flatten()),
        Body::Array(_) => {}
    }
}

#[cfg(test)]
mod tests;
