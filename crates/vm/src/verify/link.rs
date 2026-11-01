//! Linking's verification step (JVMS 5.4.1), run before a class initializes.
//!
//! A failure leaves the class unverified, so each later use fails the same way
//! (JVMS 5.4.3; observed on the oracle: the same `VerifyError` each time).

use std::collections::HashSet;

use super::{ClassInfo, Hierarchy, verify_class};
use crate::class::ClassId;
use crate::{Vm, VmResult};

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
            members: c.methods.len() + c.fields.len(),
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
        Ok(if method {
            c.find_method(name, descriptor)
                .map(|i| c.methods[usize::from(i)].access)
        } else {
            c.find_field(name, descriptor).map(|f| f.access)
        })
    }
}
