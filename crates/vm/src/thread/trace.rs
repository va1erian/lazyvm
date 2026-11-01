//! The stack of another thread, for `Thread.getStackTrace`. Whoever asks holds
//! the VM, so every other thread's frames are in its entry and stay still.

use super::Blocked;
use crate::Vm;
use crate::class::{MethodRef, Resolved};
use crate::interp::Frame;
use crate::throw::{MAX_TRACE, TraceFrame};
use crate::value::Ref;

const MONITORENTER: u8 = 0xc2;

impl Vm {
    /// The frames of live thread `t`, innermost first; none if it has no entry.
    pub fn thread_backtrace(&self, t: Ref) -> Vec<TraceFrame> {
        let entry = self.threads.find(t).and_then(|i| self.threads.get(i));
        let Some(entry) = entry else {
            return Vec::new();
        };
        let Some(state) = &entry.state else {
            return Vec::new();
        };
        let mut out = Vec::new();
        // A native has no frame of its own, but a blocked thread's top frame
        // is still at the instruction that called it.
        if entry.blocked != Blocked::No {
            let native = state.frames.last().and_then(|f| self.called_native(f));
            out.extend(native.map(|method| TraceFrame { method, pc: 0 }));
        }
        let shown = out.len();
        out.extend(self.trace_frames(state.frames.iter().rev(), MAX_TRACE - shown));
        // Waiting to enter a monitor, the oracle's frame is already past the
        // `monitorenter`, which can be the line after it (observed).
        if entry.blocked == Blocked::Monitor
            && state.frames.last().and_then(|f| f.u1(0).ok()) == Some(MONITORENTER)
            && let Some(top) = out.first_mut()
        {
            top.pc += 1;
        }
        out
    }

    /// The native method `f`'s current instruction calls, if it is one. Only
    /// the cache is looked at: a call under way has been resolved.
    fn called_native(&self, f: &Frame) -> Option<MethodRef> {
        const INVOKES: std::ops::RangeInclusive<u8> = 0xb6..=0xb9;
        if !INVOKES.contains(&f.u1(0).ok()?) {
            return None;
        }
        match self.cached(f.class, f.u2(1).ok()?) {
            Some(Resolved::Method(m)) if self.method(m).is_native() => Some(m),
            _ => None,
        }
    }
}
