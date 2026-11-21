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
/// A retired frame keeps buffers up to this many slots. Larger ones are
/// freed, or a slot would keep its largest frame ever and the frame-slot
/// budget would no longer bound memory (a huge method called at every
/// depth of a recursion).
const MAX_KEPT_SLOTS: usize = 256;

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

    /// `push` for a call made by the top frame: the arguments are the top
    /// `n` entries of its stack, which they leave, and it resumes `len`
    /// bytes on. They are copied straight into the new frame, which sits
    /// next to its caller. False, with the live frames as they were, if
    /// there is no caller with `n` entries or the arguments do not fit.
    pub(crate) fn push_call(
        &mut self,
        method: MethodRef,
        code: CodeRef,
        arg_slots: u16,
        n: usize,
        len: usize,
    ) -> bool {
        if self.depth == self.slots.len() {
            self.slots.push(Frame::empty(method, code));
        }
        let (live, retired) = self.slots.split_at_mut(self.depth);
        let (Some(caller), Some(callee)) = (live.last_mut(), retired.first_mut()) else {
            return false;
        };
        let Some(at) = caller.stack.len().checked_sub(n) else {
            return false;
        };
        if callee
            .enter(method, code, arg_slots, &caller.stack[at..])
            .is_err()
        {
            return false;
        }
        caller.stack.truncate(at);
        caller.next_pc = caller.pc + len;
        self.depth += 1;
        true
    }

    /// Retires the top frame and hands it out for its owner's last rites
    /// (monitors, slot budget), which `trim_retired` must follow; its
    /// contents stay until it is reused.
    pub(crate) fn retire(&mut self) -> Option<&mut Frame> {
        self.depth = self.depth.checked_sub(1)?;
        if self.slots.len() > self.depth + MAX_RETIRED {
            self.slots.truncate(self.depth + MAX_RETIRED);
        }
        self.slots.get_mut(self.depth)
    }

    /// Frees the large buffers of the frame `retire` just handed out, once
    /// its owner is done with it.
    pub(crate) fn trim_retired(&mut self) {
        if let Some(f) = self.slots.get_mut(self.depth) {
            if f.locals.capacity() > MAX_KEPT_SLOTS {
                f.locals = Vec::new();
            }
            if f.stack.capacity() > MAX_KEPT_SLOTS {
                f.stack = Vec::new();
            }
        }
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

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use classfile::Code;

    use super::*;
    use crate::class::ClassId;

    fn code(max_locals: u16) -> Arc<Code> {
        Arc::new(Code {
            max_stack: 2,
            max_locals,
            bytecode: Arc::from(&[0xb1u8][..]),
            handlers: Vec::new(),
            line_numbers: Vec::new(),
            local_vars: Vec::new(),
            stack_map: None,
        })
    }

    const M: MethodRef = MethodRef {
        class: ClassId(1),
        index: 0,
    };

    #[test]
    fn small_frames_are_reused_and_large_buffers_freed() {
        let (small, big) = (code(4), code(60_000));
        let mut frames = FrameStack::default();
        assert!(
            frames
                .push(M, CodeRef::new(&small), 1, &[Value::Int(7)])
                .is_ok()
        );
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].locals[0], Value::Int(7));
        let kept = frames.retire().map(|f| f.locals.as_ptr());
        assert_eq!(frames.len(), 0);
        assert!(frames.push(M, CodeRef::new(&small), 0, &[]).is_ok());
        assert_eq!(kept, Some(frames[0].locals.as_ptr()), "buffer reused");
        assert_eq!(frames[0].locals[0], Value::Top, "reset on reuse");
        frames.retire();
        assert!(frames.push(M, CodeRef::new(&big), 0, &[]).is_ok());
        assert!(frames.retire().is_some());
        frames.trim_retired();
        frames.push(M, CodeRef::new(&small), 0, &[]).ok();
        assert!(
            frames[0].locals.capacity() <= MAX_KEPT_SLOTS,
            "a large buffer is not kept"
        );
    }

    #[test]
    fn a_call_moves_its_arguments_into_the_new_frame() {
        let small = code(3);
        let mut frames = FrameStack::default();
        assert!(frames.push(M, CodeRef::new(&small), 0, &[]).is_ok());
        frames[0].pc = 7;
        frames[0]
            .stack
            .extend([Value::Int(1), Value::Long(2), Value::Int(3)]);
        assert!(frames.push_call(M, CodeRef::new(&small), 3, 2, 3));
        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0].stack, [Value::Int(1)], "arguments left");
        assert_eq!(frames[0].next_pc, 10);
        // The long takes two locals, as in `Frame::enter`.
        assert_eq!(
            frames[1].locals,
            [Value::Long(2), Value::Top, Value::Int(3)]
        );
        assert!(frames[1].stack.is_empty());
    }

    #[test]
    fn a_refused_call_leaves_the_live_frames_as_they_were() {
        let (small, tiny) = (code(3), code(1));
        let mut frames = FrameStack::default();
        // No caller at all.
        assert!(!frames.push_call(M, CodeRef::new(&small), 0, 0, 3));
        assert_eq!(frames.len(), 0);
        assert!(frames.push(M, CodeRef::new(&small), 0, &[]).is_ok());
        frames[0].stack.push(Value::Long(2));
        // More arguments than the caller has entries.
        assert!(!frames.push_call(M, CodeRef::new(&small), 2, 2, 3));
        // A long that does not fit one local.
        assert!(!frames.push_call(M, CodeRef::new(&tiny), 1, 1, 3));
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].stack, [Value::Long(2)]);
        assert_eq!(frames[0].next_pc, 0);
    }

    #[test]
    fn a_failed_push_leaves_nothing_live() {
        let small = code(1);
        let mut frames = FrameStack::default();
        let args = [Value::Long(1)];
        assert!(frames.push(M, CodeRef::new(&small), 1, &args).is_err());
        assert_eq!(frames.len(), 0);
        assert!(frames.retire().is_none());
    }
}
