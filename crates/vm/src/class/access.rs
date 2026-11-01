//! Access control (JVMS 5.4.4), checked when a symbolic reference resolves.
//!
//! A generated lambda class counts as part of the class that linked it
//! (docs/design/lambdas.md), so it reaches its host's private `lambda$` methods.

use classfile::{ACC_PRIVATE, ACC_PROTECTED, ACC_PUBLIC};

use super::{ClassId, ClassKind};
use crate::{Vm, VmError, VmResult};

const ILLEGAL_ACCESS: &str = "java/lang/IllegalAccessError";

/// `name`'s package, for the run-time package test (JVMS 5.3).
pub(crate) fn package_of(name: &str) -> &str {
    name.rsplit_once('/').map_or("", |(p, _)| p)
}

impl Vm {
    /// The class whose rights `from` has.
    fn accessor(&self, from: ClassId) -> ClassId {
        self.class(from).host.unwrap_or(from)
    }

    /// JVMS 5.3: same defining loader and same package name.
    pub(crate) fn same_runtime_package(&self, a: ClassId, b: ClassId) -> bool {
        self.class(a).from_boot == self.class(b).from_boot
            && package_of(&self.class(a).name) == package_of(&self.class(b).name)
    }

    /// JVMS 5.4.4: `class` is public, or in the accessor's run-time package.
    /// An array class has its element's access.
    pub(crate) fn check_class_access(&self, from: ClassId, class: ClassId) -> VmResult<()> {
        let mut target = class;
        while let ClassKind::Array { component, .. } = self.class(target).kind {
            target = component;
        }
        let d = self.accessor(from);
        if matches!(self.class(target).kind, ClassKind::Primitive(_))
            || self.class(target).access & ACC_PUBLIC != 0
            || self.same_runtime_package(d, target)
        {
            return Ok(());
        }
        Err(VmError::exception(
            ILLEGAL_ACCESS,
            format!(
                "tried to access class {} from class {}",
                self.class(class).java_name(),
                self.class(from).java_name()
            ),
        ))
    }

    /// JVMS 5.4.4 for a member of `owner` with flags `access`, reached through
    /// a reference that names class `named`. `what` starts the error message.
    pub(crate) fn check_member_access(
        &self,
        from: ClassId,
        owner: ClassId,
        named: ClassId,
        access: u16,
        is_static: bool,
        what: &str,
    ) -> VmResult<()> {
        let d = self.accessor(from);
        let allowed = access & ACC_PUBLIC != 0
            || from == owner
            || d == owner
            || (access & ACC_PRIVATE == 0 && self.same_runtime_package(d, owner))
            || (access & ACC_PROTECTED != 0 && self.protected_allowed(d, owner, named, is_static));
        if allowed {
            return Ok(());
        }
        Err(VmError::exception(
            ILLEGAL_ACCESS,
            format!(
                "tried to access {what} from class {}",
                self.class(from).java_name()
            ),
        ))
    }

    /// A protected member: `d` subclasses its owner and, for an instance
    /// member, the reference names a class related to `d`.
    fn protected_allowed(
        &self,
        d: ClassId,
        owner: ClassId,
        named: ClassId,
        is_static: bool,
    ) -> bool {
        self.is_subclass(d, owner)
            && (is_static || self.is_subclass(named, d) || self.is_subclass(d, named))
    }
}
