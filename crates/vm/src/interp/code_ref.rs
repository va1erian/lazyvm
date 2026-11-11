//! `CodeRef`: how a frame holds its method's code. A call and a return used to
//! clone and drop an `Arc<Code>`, two atomic operations that were a tenth of
//! the time of call-heavy code (plan S11-06); a pointer costs nothing.
#![allow(unsafe_code)]

use std::ops::Deref;
use std::ptr::NonNull;
use std::sync::Arc;

use classfile::Code;

/// Points at the `Code` inside a `Method::code` `Arc`.
///
/// Sound because that `Arc` outlives every frame of the method: a class in
/// `Vm::classes` is never removed, a method's `code` is set when its class is
/// laid out and never replaced (`class/layout.rs`), and frames live in the
/// VM (its current thread's or a parked thread's), so they go when it goes.
/// Everything that could break this from outside is crate-private
/// (`Vm::classes`, `Vm::class_mut`, `Vm::frames`, `Class::methods`,
/// `Method::code`, `Frame::code`, this type): no safe code elsewhere can drop
/// a method's code, or move a frame or a class to another VM.
#[derive(Clone, Copy)]
pub(crate) struct CodeRef(NonNull<Code>);

impl CodeRef {
    pub(crate) fn new(code: &Arc<Code>) -> CodeRef {
        CodeRef(NonNull::from(&**code))
    }
}

impl Deref for CodeRef {
    type Target = Code;

    #[inline(always)]
    fn deref(&self) -> &Code {
        // SAFETY: the pointer came from a live `Arc<Code>` that is never
        // dropped while the VM, and so this frame, exists (see the type's
        // doc); `Code` is never mutated after it is built.
        unsafe { self.0.as_ref() }
    }
}

// SAFETY: a `CodeRef` is a shared reference to an immutable `Code`, which is
// itself `Send` and `Sync` (it is held in an `Arc`); frames move between OS
// threads with the VM, under its lock.
unsafe impl Send for CodeRef {}
// SAFETY: as above; nothing is ever written through the pointer.
unsafe impl Sync for CodeRef {}
