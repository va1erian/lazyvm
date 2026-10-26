//! How failures travel through the VM.

use std::fmt;

use crate::value::Ref;

/// An error from VM code. Java exceptions are values here, not panics: an
/// operation returns `Err` and the interpreter unwinds to a handler.
#[derive(Debug)]
pub enum VmError {
    /// A Java exception object in flight.
    Throw(Ref),
    /// A Java exception the VM still has to create: its class (internal name)
    /// and message. Code that cannot run Java (most of the VM) returns this;
    /// the interpreter creates the object at a safe point.
    Pending {
        class: &'static str,
        message: Option<String>,
    },
    /// Malformed bytecode or a type confusion that a verifier would have
    /// rejected; becomes `java.lang.VerifyError`.
    Verify(String),
    /// The heap limit; becomes `java.lang.OutOfMemoryError`.
    OutOfMemory,
    /// `System.exit` / `Runtime.halt`: unwinds everything, never caught.
    Exit(i32),
    /// A bug in the VM or a broken class library; fatal.
    Internal(String),
}

impl VmError {
    pub fn exception(class: &'static str, message: impl Into<String>) -> VmError {
        VmError::Pending {
            class,
            message: Some(message.into()),
        }
    }

    pub fn bare(class: &'static str) -> VmError {
        VmError::Pending {
            class,
            message: None,
        }
    }

    pub fn verify(message: impl Into<String>) -> VmError {
        VmError::Verify(message.into())
    }

    pub fn npe() -> VmError {
        VmError::bare("java/lang/NullPointerException")
    }

    /// Whether Java code can catch this error.
    pub fn is_catchable(&self) -> bool {
        matches!(
            self,
            VmError::Throw(_) | VmError::Pending { .. } | VmError::Verify(_) | VmError::OutOfMemory
        )
    }
}

impl fmt::Display for VmError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            VmError::Throw(r) => write!(f, "exception object {r:?}"),
            VmError::Pending {
                class,
                message: Some(m),
            } => write!(f, "{class}: {m}"),
            VmError::Pending {
                class,
                message: None,
            } => write!(f, "{class}"),
            VmError::Verify(m) => write!(f, "java/lang/VerifyError: {m}"),
            VmError::OutOfMemory => write!(f, "java/lang/OutOfMemoryError"),
            VmError::Exit(code) => write!(f, "exit {code}"),
            VmError::Internal(m) => write!(f, "internal VM error: {m}"),
        }
    }
}

impl std::error::Error for VmError {}

pub type VmResult<T> = Result<T, VmError>;
