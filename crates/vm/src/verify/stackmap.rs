//! Frames and the `StackMapTable` attribute (JVMS 4.7.4, 4.10.1.4).

use classfile::descriptor::{FieldType, MethodDescriptor};
use classfile::{ClassFile, Code, Constant};

use super::decode::{self, OP_NEW};
use super::structural::Layout;
use super::vtype::{Types, VType};
use super::{Budget, Fail, Hierarchy, fail_at};

fn format_error(offset: usize, reason: impl Into<String>) -> Fail {
    Fail::Format {
        offset,
        reason: reason.into(),
    }
}

/// A type state. `locals` may stop early: what is missing is `Top`. That keeps
/// a frame's cost to what it declares, not to a hostile `max_locals`. A long or
/// double is one `stack` entry, and two `locals` entries (itself, then `Top`).
#[derive(Clone, Debug, Default)]
pub struct Frame {
    pub locals: Vec<VType>,
    pub stack: Vec<VType>,
    /// The stack's size in slots, the unit of `max_stack`.
    pub depth: u32,
    pub this_uninit: bool,
}

impl Frame {
    pub fn local(&self, index: usize) -> VType {
        self.locals.get(index).copied().unwrap_or(VType::Top)
    }

    pub fn set_local(&mut self, index: usize, t: VType) {
        if self.locals.len() <= index {
            self.locals.resize(index + 1, VType::Top);
        }
        self.locals[index] = t;
    }

    fn push_local(&mut self, t: VType) {
        self.locals.push(t);
        if t.size() == 2 {
            self.locals.push(VType::Top);
        }
    }
}

/// JVMS 4.10.1.6 `methodInitialStackFrame`.
pub fn initial_frame<H: Hierarchy + ?Sized>(
    cf: &ClassFile,
    is_static: bool,
    is_init: bool,
    md: &MethodDescriptor,
    types: &mut Types<'_, H>,
) -> Frame {
    let mut frame = Frame::default();
    if !is_static {
        // `Object` has no superclass constructor to call, so its `this` starts ready.
        if is_init && cf.super_class.is_some() {
            frame.locals.push(VType::UninitThis);
            frame.this_uninit = true;
        } else {
            let this = types.class(&cf.this_class);
            frame.locals.push(this);
        }
    }
    for p in &md.params {
        let t = types.of_field(p);
        frame.push_local(t);
    }
    frame
}

pub fn return_type<H: Hierarchy + ?Sized>(
    ret: Option<&FieldType>,
    types: &mut Types<'_, H>,
) -> Option<VType> {
    ret.map(|t| types.of_field(t))
}

struct Reader<'a> {
    data: &'a [u8],
    at: usize,
}

impl Reader<'_> {
    fn u1(&mut self) -> Result<u8, String> {
        let v = decode::u1(self.data, self.at).map_err(|_| "StackMapTable is truncated")?;
        self.at += 1;
        Ok(v)
    }

    fn u2(&mut self) -> Result<u16, String> {
        Ok(u16::from_be_bytes([self.u1()?, self.u1()?]))
    }
}

/// The method's declared frames, sorted by offset, the initial one excluded.
pub fn parse_frames<H: Hierarchy + ?Sized>(
    cf: &ClassFile,
    code: &Code,
    layout: &Layout,
    initial: &Frame,
    types: &mut Types<'_, H>,
    budget: &mut Budget,
) -> Result<Vec<(u16, Frame)>, Fail> {
    let Some(data) = &code.stack_map else {
        return Ok(Vec::new());
    };
    let mut r = Reader { data, at: 0 };
    let mut parser = FrameParser {
        cf,
        code,
        layout,
        types,
        text: 0,
    };
    let count = r.u2().map_err(|e| format_error(0, e))?;
    let mut frames: Vec<(u16, Frame)> = Vec::with_capacity(usize::from(count).min(data.len()));
    let mut offset: i32 = -1;
    let mut stored = 0usize;
    for _ in 0..count {
        let (next, frame) = parser
            .next(&mut r, offset, frames.last().map_or(initial, |f| &f.1))
            .map_err(|e| format_error(usize::try_from(offset + 1).unwrap_or(0), e))?;
        // Observed: an offset off the code is `VerifyError`, unlike the rest.
        if !usize::try_from(next).is_ok_and(|o| layout.is_start(o)) {
            return Err(fail_at(
                0,
                format!("stack map frame at {next}, not an instruction"),
            ));
        }
        offset = next;
        budget.charge(std::mem::take(&mut parser.text))?;
        stored += frame.locals.len() + frame.stack.len();
        budget.charge(frame.locals.len() + frame.stack.len())?;
        if stored > super::MAX_STORED_SLOTS {
            return Err(fail_at(
                next as usize,
                "stack map frames are too large to verify",
            ));
        }
        frames.push((next as u16, frame));
    }
    if r.at != data.len() {
        return Err(format_error(0, "StackMapTable has trailing bytes"));
    }
    Ok(frames)
}

struct FrameParser<'a, 't, 'h, H: Hierarchy + ?Sized> {
    cf: &'a ClassFile,
    code: &'a Code,
    layout: &'a Layout,
    types: &'t mut Types<'h, H>,
    /// Bytes of class names read since the last charge.
    text: usize,
}

impl<H: Hierarchy + ?Sized> FrameParser<'_, '_, '_, H> {
    /// One `stack_map_frame`: its absolute offset and its type state. The first
    /// frame's offset is its delta, each later one `delta + 1` past the last.
    fn next(
        &mut self,
        r: &mut Reader<'_>,
        prev_offset: i32,
        prev: &Frame,
    ) -> Result<(i32, Frame), String> {
        let tag = r.u1()?;
        let mut frame = prev.clone();
        frame.stack.clear();
        frame.depth = 0;
        let delta = match tag {
            0..=63 => u16::from(tag),
            64..=127 => {
                let t = self.verification_type(r)?;
                self.push_stack(&mut frame, t)?;
                u16::from(tag - 64)
            }
            247 => {
                let delta = r.u2()?;
                let t = self.verification_type(r)?;
                self.push_stack(&mut frame, t)?;
                delta
            }
            248..=250 => {
                let delta = r.u2()?;
                chop(&mut frame, usize::from(251 - tag))?;
                delta
            }
            251 => r.u2()?,
            252..=254 => {
                let delta = r.u2()?;
                for _ in 0..tag - 251 {
                    let t = self.verification_type(r)?;
                    frame.push_local(t);
                }
                delta
            }
            255 => self.full_frame(r, &mut frame)?,
            _ => return Err(format!("reserved stack map frame type {tag}")),
        };
        self.check_limits(&frame)?;
        let offset = prev_offset + i32::from(delta) + 1;
        frame.this_uninit = frame.locals.contains(&VType::UninitThis);
        Ok((offset, frame))
    }

    fn full_frame(&mut self, r: &mut Reader<'_>, frame: &mut Frame) -> Result<u16, String> {
        let delta = r.u2()?;
        frame.locals.clear();
        for _ in 0..r.u2()? {
            let t = self.verification_type(r)?;
            frame.push_local(t);
            if frame.locals.len() > usize::from(self.code.max_locals) {
                return Err("stack map frame has more locals than max_locals".into());
            }
        }
        for _ in 0..r.u2()? {
            let t = self.verification_type(r)?;
            self.push_stack(frame, t)?;
        }
        Ok(delta)
    }

    fn push_stack(&self, frame: &mut Frame, t: VType) -> Result<(), String> {
        frame.stack.push(t);
        frame.depth += t.size();
        if frame.depth > u32::from(self.code.max_stack) {
            return Err("stack map frame's stack is deeper than max_stack".into());
        }
        Ok(())
    }

    fn check_limits(&self, frame: &Frame) -> Result<(), String> {
        if frame.locals.len() > usize::from(self.code.max_locals) {
            return Err("stack map frame has more locals than max_locals".into());
        }
        Ok(())
    }

    /// `verification_type_info` (JVMS 4.7.4).
    fn verification_type(&mut self, r: &mut Reader<'_>) -> Result<VType, String> {
        Ok(match r.u1()? {
            0 => VType::Top,
            1 => VType::Int,
            2 => VType::Float,
            3 => VType::Double,
            4 => VType::Long,
            5 => VType::Null,
            6 => VType::UninitThis,
            7 => {
                let index = r.u2()?;
                let name = self.cf.pool.class_name(index)?.clone();
                self.text += name.len();
                self.types.class(&name)
            }
            8 => {
                let offset = r.u2()?;
                let at = usize::from(offset);
                if !self.layout.is_start(at) || self.code.bytecode.get(at) != Some(&OP_NEW) {
                    return Err(format!("uninitialized type at {offset}, not a new"));
                }
                match self.cf.pool.get(decode::u2(&self.code.bytecode, at + 1)?) {
                    Some(Constant::Class(_)) => VType::Uninit(offset),
                    other => return Err(format!("new names {other:?}")),
                }
            }
            t => return Err(format!("unknown verification type {t}")),
        })
    }
}

/// `chop_frame`: removes the last `n` locals, a long or double counting as one.
fn chop(frame: &mut Frame, n: usize) -> Result<(), String> {
    for _ in 0..n {
        // Trailing tops are locals only when no two-word value owns them.
        let last = frame
            .locals
            .pop()
            .ok_or("chop_frame removes too many locals")?;
        if last == VType::Top && frame.locals.last().is_some_and(|prev| prev.size() == 2) {
            frame.locals.pop();
        }
    }
    Ok(())
}
