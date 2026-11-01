//! The type checker's walk over one method (JVMS 4.10.1.6): instructions and
//! declared frames merged by offset, each instruction checked against the
//! type state before it, branches and handlers against their target's frame.

use classfile::descriptor::parse_method;
use classfile::{ACC_FINAL, ACC_PRIVATE, ACC_STATIC, ClassFile, Code, Handler, Member};

use super::decode;
use super::stackmap::{Frame, initial_frame, parse_frames, return_type};
use super::structural::Layout;
use super::vtype::{THROWABLE, Types, VType};
use super::{Budget, Fail, Hierarchy, fail_at};

/// Everything one instruction's check needs. `cur` is the state before the
/// instruction; `dead` says the last one ended the flow (`afterGoto`).
pub(super) struct Checker<'a, 't, 'h, H: Hierarchy + ?Sized> {
    pub cf: &'a ClassFile,
    pub code: &'a Code,
    /// The class standing for "current" in `invokespecial`: a generated lambda
    /// class calls its host's private methods as the host would.
    pub host: Option<&'a str>,
    pub types: &'t mut Types<'h, H>,
    pub budget: &'t mut Budget,
    pub frames: Vec<(u16, Frame)>,
    pub cur: Frame,
    pub dead: bool,
    /// Moves whenever a local may have changed, so a handler's frame is
    /// compared again only then.
    pub locals_version: u64,
    pub pc: usize,
    pub ret: Option<VType>,
    pub is_init: bool,
}

/// JVMS 4.10.1.5: no method overrides a final one up the superclass chain.
pub(super) fn not_overriding_final<H: Hierarchy + ?Sized>(
    cf: &ClassFile,
    m: &Member,
    types: &mut Types<'_, H>,
) -> Result<(), Fail> {
    if m.access & (ACC_PRIVATE | ACC_STATIC) != 0 || m.name.starts_with('<') {
        return Ok(());
    }
    let mut cur = cf.super_class.clone();
    let mut steps = 0;
    while let Some(class) = cur {
        steps += 1;
        if steps > 1 << 16 {
            break;
        }
        let access = types
            .hierarchy
            .member_access(&class, &m.name, &m.descriptor, true)?;
        match access {
            Some(a) if a & ACC_FINAL != 0 => {
                return Err(fail_at(0, format!("overrides a final method of {class}")));
            }
            // A private or static method is not overridden, so the search goes on.
            Some(a) if a & (ACC_PRIVATE | ACC_STATIC) == 0 => break,
            _ => {}
        }
        cur = types.info(&class)?.super_name;
    }
    Ok(())
}

pub(super) fn check_method<H: Hierarchy + ?Sized>(
    cf: &ClassFile,
    m: &Member,
    code: &Code,
    layout: &Layout,
    host: Option<&str>,
    types: &mut Types<'_, H>,
    budget: &mut Budget,
) -> Result<(), Fail> {
    let md = parse_method(&m.descriptor).map_err(|e| fail_at(0, e))?;
    let is_init = &*m.name == "<init>";
    let is_static = m.access & ACC_STATIC != 0;
    let cur = initial_frame(cf, is_static, is_init, &md, types);
    let ret = return_type(md.ret.as_ref(), types);
    let frames = parse_frames(cf, code, layout, &cur, types, budget)?;
    let mut checker = Checker {
        cf,
        code,
        host,
        types,
        budget,
        frames,
        cur,
        dead: false,
        locals_version: 0,
        pc: 0,
        ret,
        is_init,
    };
    checker.walk()
}

impl<H: Hierarchy + ?Sized> Checker<'_, '_, '_, H> {
    fn walk(&mut self) -> Result<(), Fail> {
        self.check_handler_types()?;
        let bytes = self.code.bytecode.clone();
        let mut handler_seen = vec![u64::MAX; self.code.handlers.len()];
        let mut next_frame = 0;
        while self.pc < bytes.len() {
            if let Some((offset, frame)) = self.frames.get(next_frame)
                && usize::from(*offset) == self.pc
            {
                let frame = frame.clone();
                if !self.dead {
                    self.assignable_to(&self.cur.clone(), &frame)?;
                }
                self.budget.charge(frame.locals.len() + frame.stack.len())?;
                self.cur = frame;
                self.dead = false;
                self.locals_version += 1;
                next_frame += 1;
            } else if self.dead {
                return Err(self.bad("no stack map frame after an unconditional branch"));
            }
            self.check_handlers(&mut handler_seen)?;
            self.step()?;
            self.budget.charge(1)?;
            self.pc += decode::length(&bytes, self.pc).map_err(|e| fail_at(self.pc, e))?;
        }
        if !self.dead {
            return Err(fail_at(self.pc, "execution falls off the end of the code"));
        }
        Ok(())
    }

    pub(super) fn bad(&self, reason: impl Into<String>) -> Fail {
        fail_at(self.pc, reason)
    }

    /// JVMS 4.10.1.6: each handler catches a `Throwable`.
    fn check_handler_types(&mut self) -> Result<(), Fail> {
        for h in &self.code.handlers {
            let t = self.handler_class(h)?;
            let throwable = self.types.class(THROWABLE);
            if !self.types.assignable(t, throwable)? {
                return Err(fail_at(
                    usize::from(h.handler_pc),
                    "exception handler's class is not a Throwable",
                ));
            }
        }
        Ok(())
    }

    fn handler_class(&mut self, h: &Handler) -> Result<VType, Fail> {
        if h.catch_type == 0 {
            return Ok(self.types.class(THROWABLE));
        }
        let name = self
            .cf
            .pool
            .class_name(h.catch_type)
            .map_err(|e| fail_at(usize::from(h.start_pc), e))?;
        Ok(self.types.class(name))
    }

    /// JVMS 4.10.1.6 `instructionSatisfiesHandlers`, with the incoming locals:
    /// what an instruction throws cannot have seen its own writes.
    fn check_handlers(&mut self, seen: &mut [u64]) -> Result<(), Fail> {
        self.budget.charge(self.code.handlers.len())?;
        let version = self.locals_version;
        for (i, h) in self.code.handlers.iter().enumerate() {
            let range = usize::from(h.start_pc)..usize::from(h.end_pc);
            if !range.contains(&self.pc) || seen[i] == version {
                continue;
            }
            seen[i] = version;
            let class = self.handler_class(h)?;
            if self.code.max_stack == 0 {
                return Err(self.bad("max_stack leaves no room for a caught exception"));
            }
            let state = Frame {
                locals: self.cur.locals.clone(),
                stack: vec![class],
                depth: 1,
                this_uninit: self.cur.this_uninit,
            };
            self.target_accepts(usize::from(h.handler_pc), &state)?;
        }
        Ok(())
    }

    /// The declared frame at `target`, which `state` must be assignable to.
    pub(super) fn target_accepts(&mut self, target: usize, state: &Frame) -> Result<(), Fail> {
        let found = self
            .frames
            .binary_search_by_key(&target, |(o, _)| usize::from(*o));
        let Ok(i) = found else {
            return Err(self.bad(format!("no stack map frame at target {target}")));
        };
        let frame = self.frames[i].1.clone();
        self.assignable_to(state, &frame)
            .map_err(|f| self.with_target(f, target))
    }

    /// [`Self::target_accepts`] for the locals and flag only: the stack's
    /// type is the handler's own class, which the incoming check judged.
    pub(super) fn target_accepts_locals(
        &mut self,
        target: usize,
        state: &Frame,
    ) -> Result<(), Fail> {
        let found = self
            .frames
            .binary_search_by_key(&target, |(o, _)| usize::from(*o));
        let Ok(i) = found else {
            return Err(self.bad(format!("no stack map frame at target {target}")));
        };
        let mut frame = self.frames[i].1.clone();
        frame.stack.clone_from(&state.stack);
        frame.depth = state.depth;
        self.assignable_to(state, &frame)
            .map_err(|f| self.with_target(f, target))
    }

    fn with_target(&self, f: Fail, target: usize) -> Fail {
        match f {
            Fail::Bad { offset, reason } => Fail::Bad {
                offset,
                reason: format!("{reason} (at target {target})"),
            },
            other => other,
        }
    }

    /// The current stack and locals flowing into a branch target.
    pub(super) fn branch_to(&mut self, target: usize) -> Result<(), Fail> {
        let state = self.cur.clone();
        self.budget.charge(state.locals.len() + state.stack.len())?;
        self.target_accepts(target, &state)
    }

    /// JVMS 4.10.1.4 `frameIsAssignable`.
    fn assignable_to(&mut self, from: &Frame, to: &Frame) -> Result<(), Fail> {
        self.budget.charge(to.locals.len() + to.stack.len())?;
        if from.stack.len() != to.stack.len() {
            return Err(self.bad(format!(
                "stack of {} values where the frame has {}",
                from.stack.len(),
                to.stack.len()
            )));
        }
        for (i, t) in to.locals.iter().enumerate() {
            let have = from.local(i);
            if !self.types.assignable(have, *t)? {
                let (a, b) = (self.show(have), self.show(*t));
                return Err(self.bad(format!("local {i} is {a} where the frame has {b}")));
            }
        }
        for (have, want) in from.stack.iter().zip(&to.stack) {
            if !self.types.assignable(*have, *want)? {
                let (a, b) = (self.show(*have), self.show(*want));
                return Err(self.bad(format!("stack holds {a} where the frame has {b}")));
            }
        }
        if from.this_uninit && !to.this_uninit {
            return Err(self.bad("the frame drops an uninitialized this"));
        }
        Ok(())
    }

    pub(super) fn show(&self, t: VType) -> String {
        match t {
            VType::Ref(id) => self.types.name(id).to_string(),
            VType::Uninit(at) => format!("uninitialized({at})"),
            VType::UninitThis => "uninitializedThis".into(),
            other => format!("{other:?}").to_lowercase(),
        }
    }
}
