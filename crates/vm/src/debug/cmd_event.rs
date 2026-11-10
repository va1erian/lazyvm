//! `EventRequest` (15): what the debugger wants to hear about.

use super::requests::{
    BREAKPOINT, Modifier, Request, SINGLE_STEP, STEP_MIN, StepState, kind_supported, read_modifier,
};
use super::wire::{Reader, Res, Writer, err};
use crate::Vm;

const MAX_MODIFIERS: usize = 64;
/// Requests held at once: a client that keeps asking for more is not debugging.
const MAX_REQUESTS: usize = 10_000;

impl Vm {
    pub(super) fn cmd_event_request(&mut self, cmd: u8, r: &mut Reader, w: &mut Writer) -> Res<()> {
        match cmd {
            1 => {
                let id = self.set_request(r)?;
                w.i32(id);
            }
            2 => {
                let kind = r.u8()?;
                let id = r.i32()?;
                self.debug.requests.remove(kind, id);
            }
            3 => self.debug.requests.clear_kind(BREAKPOINT),
            _ => return Err(err::NOT_IMPLEMENTED),
        }
        self.refresh_slow_interp();
        Ok(())
    }

    fn set_request(&mut self, r: &mut Reader) -> Res<i32> {
        let kind = r.u8()?;
        let policy = r.u8()?;
        if policy > 2 {
            return Err(err::ILLEGAL_ARGUMENT);
        }
        let n = r.count(1)?;
        if n > MAX_MODIFIERS {
            return Err(err::ILLEGAL_ARGUMENT);
        }
        let mods = (0..n).map(|_| read_modifier(r)).collect::<Res<Vec<_>>>()?;
        kind_supported(kind)?;
        if self.debug.requests.len() >= MAX_REQUESTS {
            return Err(err::OUT_OF_MEMORY);
        }
        let step = match kind {
            BREAKPOINT => {
                self.check_breakpoint_location(&mods)?;
                None
            }
            SINGLE_STEP => Some(self.step_state(&mods)?),
            _ => None,
        };
        Ok(self.debug.requests.add(Request {
            id: 0,
            kind,
            policy,
            mods,
            step,
        }))
    }

    fn check_breakpoint_location(&self, mods: &[Modifier]) -> Res<()> {
        let at = mods.iter().find_map(|m| match m {
            Modifier::Location {
                class,
                method,
                index,
            } => Some((*class, *method, *index)),
            _ => None,
        });
        let (class, method, index) = at.ok_or(err::ILLEGAL_ARGUMENT)?;
        let known = self.classes.get(class.0 as usize);
        let m = known
            .and_then(|c| c.methods.get(usize::from(method)))
            .ok_or(err::INVALID_METHODID)?;
        let length = m.code.as_ref().map_or(0, |c| c.bytecode.len() as u64);
        if index >= length {
            return Err(err::INVALID_LOCATION);
        }
        Ok(())
    }

    /// The start of a step: where its thread is now. A step is of a thread
    /// that has stopped, so its top frame is where the step began.
    fn step_state(&mut self, mods: &[Modifier]) -> Res<StepState> {
        let (thread, size, depth) = mods
            .iter()
            .find_map(|m| match m {
                Modifier::Step {
                    thread,
                    size,
                    depth,
                } => Some((*thread, *size, *depth)),
                _ => None,
            })
            .ok_or(err::ILLEGAL_ARGUMENT)?;
        if !(0..=1).contains(&size) || !(0..=2).contains(&depth) {
            return Err(err::ILLEGAL_ARGUMENT);
        }
        let obj = self
            .debug
            .ids
            .get(thread)
            .map_err(|_| err::INVALID_THREAD)?;
        let index = self.threads.find(obj).ok_or(err::THREAD_NOT_ALIVE)?;
        if self.debug.requests.steps_of(index).next().is_some() {
            return Err(err::DUPLICATE);
        }
        let frames = self.frames_of_thread(index).ok_or(err::INVALID_THREAD)?;
        let top = frames.last().ok_or(err::INVALID_THREAD)?;
        let line = top.code.line_at(top.pc).map_or(-1, i32::from);
        Ok(StepState {
            thread: index,
            frames: frames.len(),
            line,
            min: size == STEP_MIN,
            depth,
            armed: false,
        })
    }
}
