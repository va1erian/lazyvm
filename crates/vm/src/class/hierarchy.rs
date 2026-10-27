//! Subtyping (JVMS 6.5 checkcast/instanceof rules).

use super::{ClassId, ClassKind};
use crate::Vm;

impl Vm {
    /// Whether `sub` is `sup` or a subclass of it, following superclasses only.
    pub fn is_subclass(&self, sub: ClassId, sup: ClassId) -> bool {
        let mut cur = Some(sub);
        while let Some(c) = cur {
            if c == sup {
                return true;
            }
            cur = self.class(c).super_class;
        }
        false
    }

    /// Whether `class` implements interface `iface`, directly or through a
    /// superclass or superinterface.
    pub fn implements(&self, class: ClassId, iface: ClassId) -> bool {
        self.class(class)
            .all_interfaces
            .binary_search(&iface)
            .is_ok()
    }

    /// Whether a value of class `from` can be assigned to type `to`.
    pub fn is_assignable(&self, from: ClassId, to: ClassId) -> bool {
        if from == to {
            return true;
        }
        let target = self.class(to);
        match (&self.class(from).kind, &target.kind) {
            (ClassKind::Primitive(_), _) | (_, ClassKind::Primitive(_)) => false,
            (ClassKind::Array { component: fc, .. }, ClassKind::Array { component: tc, .. }) => {
                let (fc, tc) = (*fc, *tc);
                let prim = |c| matches!(self.class(c).kind, ClassKind::Primitive(_));
                if prim(fc) || prim(tc) {
                    fc == tc
                } else {
                    self.is_assignable(fc, tc)
                }
            }
            (ClassKind::Array { .. }, _) => {
                // Arrays are Objects, Cloneable and Serializable.
                Some(to) == self.wk.object
                    || matches!(
                        &*target.name,
                        "java/lang/Cloneable" | "java/io/Serializable"
                    )
            }
            (_, ClassKind::Array { .. }) => false,
            _ if target.is_interface() => self.implements(from, to),
            _ => self.is_subclass(from, to),
        }
    }
}
