//! Class file verification: [`structural`] (JVMS 4.8, 4.9.1) and the
//! `StackMapTable` type checker (JVMS 4.10.1), run when a class is linked.
//!
//! The checker works through [`Hierarchy`] rather than the VM, so the fuzz
//! target can run it with no class library. Failures name the method, offset
//! and reason, and become `VerifyError` (`link.rs`).

mod check;
mod decode;
mod link;
mod ops;
mod ops_ref;
mod ops_stack;
mod stackmap;
pub mod structural;
mod vtype;

pub use link::VerifyMode;

use std::sync::Arc;

use classfile::{ClassFile, Member};

use crate::{VmError, VmResult};

/// How long verification of one class may work, in frame slots compared or
/// copied. A javac class spends far less; a hostile one (65535 frames of 65535
/// locals, in 65535 methods) would otherwise cost hours.
const CLASS_BUDGET: u64 = 1 << 30;
/// Slots one method's declared frames may hold at once: 128 MiB of types.
const MAX_STORED_SLOTS: usize = 1 << 24;

/// The first version type checking applies to. Older classes have no stack
/// maps, and the inference verifier is out of scope (decisions.md D1).
const FIRST_TYPE_CHECKED: u16 = 50;

/// What the checker needs to know about a class other than the one it checks.
/// Superclass chains must end: loading guarantees that.
pub struct ClassInfo {
    pub is_interface: bool,
    pub super_name: Option<Arc<str>>,
    /// Loaded from the boot path: part of the run-time package (JVMS 5.3).
    pub boot: bool,
    /// Fields and methods declared: what a member lookup in it costs.
    pub members: usize,
}

/// The class world the checker asks about. A class that cannot be loaded is
/// the `Err` of the failed load, which propagates as that error (JVMS 5.4.1).
pub trait Hierarchy {
    fn info(&mut self, name: &str) -> VmResult<ClassInfo>;

    /// The access flags of the field or method `class` itself declares.
    fn member_access(
        &mut self,
        class: &str,
        name: &str,
        descriptor: &str,
        method: bool,
    ) -> VmResult<Option<u16>>;
}

/// Why a method did not verify, or a class it needed that could not load.
pub(crate) enum Fail {
    Bad {
        offset: usize,
        reason: String,
    },
    /// A malformed `StackMapTable`: `ClassFormatError`, as on the oracle.
    Format {
        offset: usize,
        reason: String,
    },
    Vm(VmError),
}

impl From<VmError> for Fail {
    fn from(e: VmError) -> Fail {
        Fail::Vm(e)
    }
}

pub(crate) fn fail_at(offset: usize, reason: impl Into<String>) -> Fail {
    Fail::Bad {
        offset,
        reason: reason.into(),
    }
}

pub(crate) struct Budget(u64);

impl Budget {
    pub fn charge(&mut self, units: usize) -> Result<(), Fail> {
        self.0 = self.0.saturating_sub(units as u64);
        if self.0 == 0 {
            return Err(fail_at(0, "too much work to verify"));
        }
        Ok(())
    }
}

/// Verifies every method of `cf`, and that none overrides a final method.
/// `host` is the class a generated lambda class counts as part of.
pub fn verify_class<H: Hierarchy + ?Sized>(
    cf: &ClassFile,
    host: Option<&str>,
    hierarchy: &mut H,
) -> VmResult<()> {
    let mut types = vtype::Types::new(hierarchy);
    let mut budget = Budget(CLASS_BUDGET);
    for m in &cf.methods {
        let outcome = verify_method(cf, m, host, &mut types, &mut budget);
        outcome.map_err(|f| method_error(cf, m, f))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests;

fn verify_method<H: Hierarchy + ?Sized>(
    cf: &ClassFile,
    m: &Member,
    host: Option<&str>,
    types: &mut vtype::Types<'_, H>,
    budget: &mut Budget,
) -> Result<(), Fail> {
    check::not_overriding_final(cf, m, types, budget)?;
    let Some(code) = &m.code else {
        return Ok(());
    };
    let layout = structural::check_code(cf, code, budget)?;
    if cf.major < FIRST_TYPE_CHECKED {
        return Ok(());
    }
    check::check_method(cf, m, code, &layout, host, types, budget)
}

fn method_error(cf: &ClassFile, m: &Member, fail: Fail) -> VmError {
    match fail {
        Fail::Vm(e) => e,
        Fail::Format { offset, reason } => VmError::exception(
            "java/lang/ClassFormatError",
            format!(
                "{}.{}{} at offset {offset}: {reason}",
                cf.this_class, m.name, m.descriptor
            ),
        ),
        Fail::Bad { offset, reason } => VmError::verify(format!(
            "{}.{}{} at offset {offset}: {reason}",
            cf.this_class, m.name, m.descriptor
        )),
    }
}
