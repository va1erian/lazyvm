//! Linking's verification step (JVMS 5.4.1), run before a class initializes.
//!
//! A failure leaves the class unverified, so each later use fails the same way
//! (JVMS 5.4.3; observed on the oracle: the same `VerifyError` each time).

use std::collections::HashSet;
use std::sync::Arc;

use super::{ClassInfo, Hierarchy, verify_class};
use crate::class::ClassId;
use crate::{Vm, VmResult};

/// A large class's members: (name, descriptor, is a method) to access flags.
pub(crate) type MemberIndex = std::collections::HashMap<(Arc<str>, Arc<str>, bool), u16>;

/// Up to this many members a class is scanned, which also saves building an
/// index for the small classes nearly all are.
const LINEAR_MEMBERS: usize = 16;

/// `-Xverify`: which classes are checked. `All` is the default.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VerifyMode {
    All,
    /// Everything but the boot class path.
    Remote,
    None,
}

impl Vm {
    /// Verifies `class` and everything above it, superclasses first.
    pub fn link_class(&mut self, class: ClassId) -> VmResult<()> {
        if self.class(class).verified {
            return Ok(());
        }
        // A loop, not recursion: a class chain is only bounded by the class limit.
        let mut order = Vec::new();
        let mut seen = HashSet::new();
        let mut pending = vec![class];
        while let Some(c) = pending.pop() {
            if self.class(c).verified || !seen.insert(c) {
                continue;
            }
            order.push(c);
            let k = self.class(c);
            pending.extend(k.interfaces.iter().rev());
            pending.extend(k.super_class);
        }
        for c in order.into_iter().rev() {
            self.verify_one(c)?;
        }
        Ok(())
    }

    fn verify_one(&mut self, class: ClassId) -> VmResult<()> {
        let k = self.class(class);
        let skip = match self.options.verify {
            VerifyMode::All => false,
            VerifyMode::Remote => k.from_boot,
            VerifyMode::None => true,
        };
        if let (false, Some(file)) = (skip, k.file.clone()) {
            let host = k.host.map(|h| self.class(h).name.clone());
            verify_class(&file, host.as_deref(), self)?;
        }
        self.class_mut(class).verified = true;
        Ok(())
    }
}

impl Hierarchy for Vm {
    fn info(&mut self, name: &str) -> VmResult<ClassInfo> {
        let id = self.load_class(name)?;
        let c = self.class(id);
        let super_name = c.super_class.map(|s| self.class(s).name.clone());
        Ok(ClassInfo {
            is_interface: c.is_interface(),
            super_name,
            boot: c.from_boot,
        })
    }

    fn member_access(
        &mut self,
        class: &str,
        name: &str,
        descriptor: &str,
        method: bool,
    ) -> VmResult<Option<u16>> {
        let id = self.load_class(class)?;
        let c = self.class(id);
        if c.methods.len() + c.fields.len() <= LINEAR_MEMBERS {
            return Ok(if method {
                c.find_method(name, descriptor)
                    .map(|i| c.methods[usize::from(i)].access)
            } else {
                c.find_field(name, descriptor).map(|f| f.access)
            });
        }
        let index = self.member_index.entry(id).or_insert_with(|| {
            let c = &self.classes[id.0 as usize];
            let methods = c
                .methods
                .iter()
                .map(|m| (&m.name, &m.descriptor, m.access, true));
            let fields = c
                .fields
                .iter()
                .map(|f| (&f.name, &f.descriptor, f.access, false));
            methods
                .chain(fields)
                .rev()
                .map(|(n, d, a, m)| ((n.clone(), d.clone(), m), a))
                .collect()
        });
        let key = (Arc::from(name), Arc::from(descriptor), method);
        Ok(index.get(&key).copied())
    }
}
