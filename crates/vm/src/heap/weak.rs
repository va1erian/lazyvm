//! What the collector needs to know about `java.lang.ref.Reference` objects.

use crate::class::ClassId;

/// Per class id: the referent field's slot if the class is a reference
/// class, and whether it is a `SoftReference`.
pub struct WeakInfo<'a> {
    pub classes: &'a [Option<(u32, bool)>],
    /// Clear soft references too (the last collection before running out
    /// of memory); otherwise they keep their referents like strong ones.
    pub clear_soft: bool,
}

impl WeakInfo<'_> {
    /// The referent slot to treat as weak for an object of `class`.
    pub fn referent_slot(&self, class: ClassId) -> Option<u32> {
        match self.classes.get(class.0 as usize).copied().flatten() {
            Some((_, true)) if !self.clear_soft => None,
            Some((slot, _)) => Some(slot),
            None => None,
        }
    }
}
