//! The points where the VM looks for something to report: before an
//! instruction (breakpoints and steps) and when a class is prepared.

use super::events::{Event, Loc};
use super::requests::{
    BREAKPOINT, CLASS_PREPARE, DEPTH_INTO, DEPTH_OUT, Modifier, StepState, Subject, location_key,
};
use crate::Vm;
use crate::class::ClassId;

/// The loudest suspend policy of the events: all threads beat one beat none.
fn louder(a: u8, b: u8) -> u8 {
    a.max(b)
}

impl Vm {
    /// Called before each instruction while `slow_interp` is on and a debugger
    /// is attached. A breakpoint at this instruction, or a step that has
    /// reached its end, is reported, and the thread stops here if the debugger
    /// said so, before the instruction runs.
    pub(crate) fn debug_before_instruction(&mut self) {
        if !self.debug.enabled || self.current == self.debug.agent {
            return;
        }
        let Some(f) = self.frames.last() else { return };
        let (method, pc) = (f.method, f.pc as u64);
        let key = location_key(method.class, method.index, pc);
        let at_breakpoint = self.debug.requests.has_breakpoint_at(key);
        if !at_breakpoint && !self.debug.requests.has_step() {
            return;
        }
        let me = self.current;
        let loc = Loc { method, pc };
        let mut events = Vec::new();
        let mut policy = 0;
        if at_breakpoint {
            policy = self.breakpoint_events(loc, &mut events);
        }
        policy = louder(policy, self.step_events(loc, &mut events));
        if !events.is_empty() {
            self.post_events(policy, me, &events);
        }
    }

    fn breakpoint_events(&mut self, loc: Loc, out: &mut Vec<Event>) -> u8 {
        let thread = self.thread_id(self.current);
        let class = loc.method.class;
        let instance = self.frame_instance_id();
        let name = self.class(class).java_name();
        let ancestors = self.ancestors_of(class);
        let location = Some((class, loc.method.index, loc.pc));
        let subject = Subject {
            thread,
            class_name: &name,
            ancestors: &ancestors,
            instance,
            location,
        };
        let mut policy = 0;
        for (request, p) in self.debug.requests.hits(BREAKPOINT, &subject) {
            out.push(Event::breakpoint(request, thread, loc));
            policy = louder(policy, p);
        }
        policy
    }

    /// The id of the top frame's `this`, if it has been sent to the debugger.
    fn frame_instance_id(&self) -> u64 {
        let Some(f) = self.frames.last() else {
            return 0;
        };
        if self.method(f.method).is_static() {
            return 0;
        }
        match f.locals.first() {
            Some(crate::Value::Ref(Some(r))) => self.debug.ids.find(*r),
            _ => 0,
        }
    }

    /// `class` and every class and interface above it.
    fn ancestors_of(&self, class: ClassId) -> Vec<ClassId> {
        let mut out = vec![class];
        let mut at = self.class(class).super_class;
        while let Some(c) = at {
            out.push(c);
            at = self.class(c).super_class;
        }
        out.extend(self.class(class).all_interfaces.iter().copied());
        out
    }

    fn step_events(&mut self, loc: Loc, out: &mut Vec<Event>) -> u8 {
        let me = self.current;
        let steps: Vec<(i32, StepState)> = self
            .debug
            .requests
            .steps_of(me)
            .filter_map(|r| r.step.map(|s| (r.id, s)))
            .collect();
        let mut policy = 0;
        for (request, mut state) in steps {
            let fired = self.step_reached(&mut state, request);
            self.debug.requests.set_step(request, state);
            if fired {
                self.debug.requests.finish_step(request);
                let thread = self.thread_id(me);
                out.push(Event::step(request, thread, loc));
                policy = louder(policy, self.request_policy(request));
            }
        }
        policy
    }

    fn request_policy(&self, request: i32) -> u8 {
        self.debug.requests.policy_of(request)
    }

    /// Whether a step that began as `st` has finished at the instruction about
    /// to run. Returning to the caller always finishes it.
    fn step_reached(&mut self, st: &mut StepState, request: i32) -> bool {
        let depth = self.frames.len();
        if !st.armed {
            // The first instruction after the thread resumes is the one it
            // stopped before: it has not moved yet.
            st.armed = true;
            if depth == st.frames {
                return false;
            }
        }
        let Some(f) = self.frames.last() else {
            return false;
        };
        if depth < st.frames {
            return true;
        }
        if depth > st.frames {
            let enters = st.depth == DEPTH_INTO && f.pc == 0 && !f.code.line_numbers.is_empty();
            return enters && self.step_filter_accepts(request);
        }
        if st.depth == DEPTH_OUT {
            return false;
        }
        let line = f.code.line_at(f.pc).map_or(-1, i32::from);
        let on_boundary = f
            .code
            .line_numbers
            .binary_search_by_key(&(f.pc as u16), |&(pc, _)| pc)
            .is_ok();
        let moved = st.min || st.line < 0 || (line != st.line && on_boundary);
        moved && self.step_filter_accepts(request)
    }

    /// The class filters of a step request, applied to the top frame's class.
    fn step_filter_accepts(&self, request: i32) -> bool {
        let Some(f) = self.frames.last() else {
            return false;
        };
        let name = self.class(f.class).java_name();
        self.debug
            .requests
            .modifiers_of(request)
            .iter()
            .all(|m| match m {
                Modifier::ClassMatch(p) => super::requests::pattern_matches(p, &name),
                Modifier::ClassExclude(p) => !super::requests::pattern_matches(p, &name),
                _ => true,
            })
    }

    /// A class was verified and prepared: report it to `ClassPrepare` requests.
    pub(crate) fn debug_class_prepared(&mut self, class: ClassId) {
        if !self.debug.enabled
            || self.current == self.debug.agent
            || self.class(class).is_array()
            || self.debug.requests.first_of(CLASS_PREPARE).is_none()
        {
            return;
        }
        let me = self.current;
        let thread = self.thread_id(me);
        let name = self.class(class).java_name();
        let ancestors = self.ancestors_of(class);
        let subject = Subject {
            thread,
            class_name: &name,
            ancestors: &ancestors,
            instance: 0,
            location: None,
        };
        let hits = self.debug.requests.hits(CLASS_PREPARE, &subject);
        let policy = hits.iter().fold(0, |p, &(_, q)| louder(p, q));
        let events: Vec<Event> = hits
            .iter()
            .map(|&(r, _)| Event::prepare(r, thread, class))
            .collect();
        if !events.is_empty() {
            self.post_events(policy, me, &events);
        }
    }
}
