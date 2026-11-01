//! The type checker's walk over one method (JVMS 4.10.1.6): instructions and
//! declared frames merged by offset, each instruction checked against the
//! type state before it, branches and handlers against their target's frame.

use classfile::descriptor::parse_method;
use classfile::{
    ACC_FINAL, ACC_PRIVATE, ACC_PROTECTED, ACC_PUBLIC, ACC_STATIC, ClassFile, Code, Handler, Member,
};

use super::stackmap::{Frame, initial_frame, parse_frames, return_type};
use super::structural::Layout;
use super::vtype::{THROWABLE, Types, VType};
use super::{Budget, Fail, Hierarchy, fail_at};
use crate::class::package_of;

/// Everything one instruction's check needs. `cur` is the state before the
/// instruction; `dead` says the last one ended the flow (`afterGoto`).
pub(super) struct Checker<'a, 't, 'h, H: Hierarchy + ?Sized> {
    pub cf: &'a ClassFile,
    pub code: &'a Code,
    pub layout: &'a Layout,
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

/// The flags of a member `class` declares. Finding it scans the class's
/// members, so that is what is charged.
pub(super) fn declared_access<H: Hierarchy + ?Sized>(
    types: &mut Types<'_, H>,
    budget: &mut Budget,
    class: &str,
    (name, descriptor, method): (&str, &str, bool),
) -> Result<Option<u16>, Fail> {
    budget.charge(types.info(class)?.members.max(1))?;
    Ok(types
        .hierarchy
        .member_access(class, name, descriptor, method)?)
}

/// JVMS 4.10.1.5: no method overrides a final one up the superclass chain.
pub(super) fn not_overriding_final<H: Hierarchy + ?Sized>(
    cf: &ClassFile,
    m: &Member,
    types: &mut Types<'_, H>,
    budget: &mut Budget,
) -> Result<(), Fail> {
    if m.access & (ACC_PRIVATE | ACC_STATIC) != 0 || m.name.starts_with('<') {
        return Ok(());
    }
    let mut cur = cf.super_class.clone();
    while let Some(class) = cur {
        let wanted = (&*m.name, &*m.descriptor, true);
        match declared_access(types, budget, &class, wanted)? {
            // A private or static method is not overridden: a final one ends the
            // search, any other is skipped.
            Some(a) if a & (ACC_PRIVATE | ACC_STATIC) != 0 => {
                if a & ACC_FINAL != 0 {
                    break;
                }
            }
            // Observed: a package-private final method is out of reach from another
            // run-time package, where JVMS 4.10.1.5 does not say so.
            Some(a) if a & ACC_FINAL != 0 && a & (ACC_PUBLIC | ACC_PROTECTED) == 0 => {
                let same_loader = types.info(&class)?.boot == types.info(&cf.this_class)?.boot;
                if same_loader && package_of(&class) == package_of(&cf.this_class) {
                    return Err(fail_at(0, format!("overrides a final method of {class}")));
                }
            }
            Some(a) if a & ACC_FINAL != 0 => {
                return Err(fail_at(0, format!("overrides a final method of {class}")));
            }
            Some(_) => break,
            None => {}
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
        layout,
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
        let len = self.code.bytecode.len();
        let mut handler_seen = vec![u64::MAX; self.code.handlers.len()];
        let mut next_frame = 0;
        while self.pc < len {
            if self
                .frames
                .get(next_frame)
                .is_some_and(|f| usize::from(f.0) == self.pc)
            {
                if !self.dead {
                    self.branch_to(self.pc)?;
                }
                let frame = self.frames[next_frame].1.clone();
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
            self.pc = self.layout.next(self.pc);
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
            if self.code.max_stack == 0 {
                return Err(self.bad("max_stack leaves no room for a caught exception"));
            }
            let class = self.handler_class(h)?;
            let flag = self.cur.this_uninit;
            self.flows_to(usize::from(h.handler_pc), Some(class), flag)?;
        }
        Ok(())
    }

    /// JVMS 4.10.1.9's note on `invokespecial`: if `<init>` throws, a handler
    /// sees the rewritten locals with the old flag. So a frame that keeps
    /// `this` or a `new` object uninitialized cannot be a handler's across the
    /// call, and neither can one that drops `this` (`tests/verify/bad/HandlerAround*`).
    pub(super) fn check_handlers_after_init(&mut self, this_uninit: bool) -> Result<(), Fail> {
        self.budget.charge(self.code.handlers.len())?;
        for h in &self.code.handlers {
            let range = usize::from(h.start_pc)..usize::from(h.end_pc);
            if range.contains(&self.pc) {
                let class = self.handler_class(h)?;
                self.flows_to(usize::from(h.handler_pc), Some(class), this_uninit)?;
            }
        }
        Ok(())
    }

    /// The current stack and locals flowing into a branch target.
    pub(super) fn branch_to(&mut self, target: usize) -> Result<(), Fail> {
        let flag = self.cur.this_uninit;
        self.flows_to(target, None, flag)
    }

    /// The current locals must fit the declared frame at `target`, with the
    /// current stack or, for a handler, `caught` alone. Neither frame is copied,
    /// so the cost is the declared frame's size, which is what is charged.
    fn flows_to(
        &mut self,
        target: usize,
        caught: Option<VType>,
        this_uninit: bool,
    ) -> Result<(), Fail> {
        let found = self
            .frames
            .binary_search_by_key(&target, |(o, _)| usize::from(*o));
        let Ok(i) = found else {
            return Err(self.bad(format!("no stack map frame at target {target}")));
        };
        let cur = std::mem::take(&mut self.cur);
        let frames = std::mem::take(&mut self.frames);
        let stack = caught.as_ref().map_or(&cur.stack[..], std::slice::from_ref);
        let result = self.assignable_to(&cur.locals, stack, this_uninit, &frames[i].1);
        self.cur = cur;
        self.frames = frames;
        result.map_err(|f| match f {
            Fail::Bad { offset, reason } => Fail::Bad {
                offset,
                reason: format!("{reason} (at target {target})"),
            },
            other => other,
        })
    }

    /// JVMS 4.10.1.4 `frameIsAssignable`. A long is one entry here but two in
    /// the JVMS's lists, so sizes must match as well as types.
    fn assignable_to(
        &mut self,
        locals: &[VType],
        stack: &[VType],
        this_uninit: bool,
        to: &Frame,
    ) -> Result<(), Fail> {
        self.budget.charge(to.locals.len() + to.stack.len())?;
        if stack.len() != to.stack.len() {
            return Err(self.bad(format!(
                "stack of {} values where the frame has {}",
                stack.len(),
                to.stack.len()
            )));
        }
        for (i, t) in to.locals.iter().enumerate() {
            let have = locals.get(i).copied().unwrap_or(VType::Top);
            if !self.types.assignable(have, *t)? {
                let (a, b) = (self.show(have), self.show(*t));
                return Err(self.bad(format!("local {i} is {a} where the frame has {b}")));
            }
        }
        for (have, want) in stack.iter().zip(&to.stack) {
            if have.size() != want.size() || !self.types.assignable(*have, *want)? {
                let (a, b) = (self.show(*have), self.show(*want));
                return Err(self.bad(format!("stack holds {a} where the frame has {b}")));
            }
        }
        if this_uninit && !to.this_uninit {
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
