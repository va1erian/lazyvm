//! Root enumeration for the collector, and `java.lang.ref` processing.
//! Collection only happens at a safe point (an instruction boundary, or
//! before an allocating instruction), where every live reference is
//! reachable from one of these roots.

use crate::Vm;
use crate::class::{ClassId, Resolved};
use crate::heap::WeakInfo;
use crate::value::{Ref, Value};

const REFERENCE: &str = "java/lang/ref/Reference";
const SOFT: &str = "java/lang/ref/SoftReference";

impl Vm {
    /// A normal collection: soft references keep their referents.
    pub fn collect_garbage(&mut self) {
        self.collect(false);
    }

    /// The collection before giving up with `OutOfMemoryError`: soft
    /// references are cleared too, as the `SoftReference` Javadoc promises.
    pub fn collect_garbage_all(&mut self) {
        self.collect(true);
    }

    fn collect(&mut self, clear_soft: bool) {
        let roots = self.roots_now();
        let classes = self.reference_classes();
        let weak = WeakInfo {
            classes: &classes,
            clear_soft,
        };
        let (_, cleared) = self.heap.collect_weak(roots, Some(&weak));
        for r in cleared {
            self.enqueue_cleared(r);
        }
    }

    fn roots_now(&self) -> Vec<Ref> {
        let mut roots: Vec<Ref> = Vec::new();
        let refs = |vals: &[Value], out: &mut Vec<Ref>| {
            out.extend(vals.iter().filter_map(|v| match v {
                Value::Ref(Some(r)) => Some(*r),
                _ => None,
            }));
        };
        for f in &self.frames {
            f.roots(&mut roots);
        }
        self.threads.roots(&mut roots);
        self.monitor_roots(&mut roots);
        for c in &self.classes {
            refs(&c.statics, &mut roots);
            roots.extend(c.mirror);
            roots.extend(c.cp_cache.iter().filter_map(|r| match r {
                Some(Resolved::String(s)) => Some(*s),
                _ => None,
            }));
        }
        roots.extend(self.interned.values().copied());
        roots.extend(self.roots.iter().copied());
        self.oom.roots(&mut roots);
        roots
    }

    /// Per class id: the referent slot of `Reference` subclasses and
    /// whether they are soft. Empty until the class library loads them.
    fn reference_classes(&self) -> Vec<Option<(u32, bool)>> {
        let Some(reference) = self.loaded_class(REFERENCE) else {
            return Vec::new();
        };
        let Ok(slot) = self.field_slot(reference, "referent", "Ljava/lang/Object;") else {
            return Vec::new();
        };
        let soft = self.loaded_class(SOFT);
        (0..self.classes.len() as u32)
            .map(ClassId)
            .map(|c| {
                self.is_subclass(c, reference)
                    .then(|| (slot, soft.is_some_and(|s| self.is_subclass(c, s))))
            })
            .collect()
    }

    /// Puts a cleared reference on its queue: the VM's half of
    /// `ReferenceQueue`, done here because no Java code may run during a
    /// collection. The fields are the contract in `java.lang.ref.Reference`,
    /// found on `Reference` and `ReferenceQueue` themselves so a subclass's
    /// field of the same name is never touched.
    fn enqueue_cleared(&mut self, r: Ref) {
        let Some(slots) = self.queue_slots() else {
            return;
        };
        let Ok(Some(queue)) = self.get_field(r, slots.queue).and_then(|v| v.reference()) else {
            return;
        };
        if self
            .get_field(r, slots.enqueued)
            .is_ok_and(|v| v == Value::Int(1))
        {
            return;
        }
        let head = self.get_field(queue, slots.head).unwrap_or(Value::NULL);
        let length = self
            .get_field(queue, slots.length)
            .and_then(|v| v.int())
            .unwrap_or(0);
        let _ = self.set_field(r, slots.next, head);
        let _ = self.set_field(r, slots.enqueued, Value::Int(1));
        let _ = self.set_field(queue, slots.head, Value::Ref(Some(r)));
        let _ = self.set_field(queue, slots.length, Value::Int(length.saturating_add(1)));
    }

    fn queue_slots(&self) -> Option<QueueSlots> {
        let reference = self.loaded_class(REFERENCE)?;
        let queue_class = self.loaded_class("java/lang/ref/ReferenceQueue")?;
        Some(QueueSlots {
            queue: self
                .field_slot(reference, "queue", "Ljava/lang/ref/ReferenceQueue;")
                .ok()?,
            next: self
                .field_slot(reference, "next", "Ljava/lang/ref/Reference;")
                .ok()?,
            enqueued: self.field_slot(reference, "enqueued", "Z").ok()?,
            head: self
                .field_slot(queue_class, "head", "Ljava/lang/ref/Reference;")
                .ok()?,
            length: self.field_slot(queue_class, "length", "I").ok()?,
        })
    }
}

/// Field slots of `Reference` and `ReferenceQueue` the collector writes.
struct QueueSlots {
    queue: u32,
    next: u32,
    enqueued: u32,
    head: u32,
    length: u32,
}
