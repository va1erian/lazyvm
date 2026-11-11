//! A thread's frames. A frame that returns is not dropped but retired: it
//! stays in place, with its locals and stack buffers, and the next call at
//! that depth reuses it. A call then moves no frame and allocates nothing,
//! where moving frames and their buffers in and out of a spare pool was a
//! fifth of call-heavy code (plan S11-06).
//!
//! Only the live frames are visible: `FrameStack` derefs to them as a slice.

use std::ops::{Deref, DerefMut};

use super::Frame;
use super::code_ref::CodeRef;
use crate::VmResult;
use crate::class::MethodRef;
use crate::value::Value;

/// Retired frames kept above the live ones; after deep recursion the rest
/// are dropped, so a thread's memory follows its usual depth.
const MAX_RETIRED: usize = 64;

#[derive(Default)]
pub struct FrameStack {
    slots: Vec<Frame>,
    depth: usize,
}

impl FrameStack {
    /// Makes a frame for `method` with `args` live on top, reusing a retired
    /// one when there is one. On an error nothing becomes live.
    pub(crate) fn push(
        &mut self,
        method: MethodRef,
        code: CodeRef,
        arg_slots: u16,
        args: &[Value],
    ) -> VmResult<()> {
        if self.depth == self.slots.len() {
            self.slots.push(Frame::empty(method, code));
        }
        self.slots[self.depth].enter(method, code, arg_slots, args)?;
        self.depth += 1;
        Ok(())
    }

    /// Retires the top frame and hands it out for its owner's last rites
    /// (monitors, slot budget); its contents stay until it is reused.
    pub(crate) fn retire(&mut self) -> Option<&mut Frame> {
        self.depth = self.depth.checked_sub(1)?;
        if self.slots.len() > self.depth + MAX_RETIRED {
            self.slots.truncate(self.depth + MAX_RETIRED);
        }
        self.slots.get_mut(self.depth)
    }
}

impl Deref for FrameStack {
    type Target = [Frame];

    #[inline(always)]
    fn deref(&self) -> &[Frame] {
        &self.slots[..self.depth]
    }
}

impl DerefMut for FrameStack {
    #[inline(always)]
    fn deref_mut(&mut self) -> &mut [Frame] {
        &mut self.slots[..self.depth]
    }
}

impl<'a> IntoIterator for &'a FrameStack {
    type Item = &'a Frame;
    type IntoIter = std::slice::Iter<'a, Frame>;

    fn into_iter(self) -> Self::IntoIter {
        self.iter()
    }
}
