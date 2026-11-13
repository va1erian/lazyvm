//! The bytecode assembler: instructions, labels, the exception table, line
//! numbers, and the verification types of the stack and locals at every
//! point, from which `StackMapTable` frames are written (JVMS 4.7.4).
//!
//! The generator says what each instruction pops and pushes, so the
//! assembler always knows the frame and needs no inference pass. Code after
//! an unconditional jump that nothing jumps to is never emitted, which is
//! what the type checker demands of dead code (JVMS 4.10.1).

use std::collections::BTreeMap;

use classfile::writer::Pool;

use crate::types::Type;

/// A verification type (JVMS 4.10.1.2).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum VType {
    Top,
    Int,
    Float,
    Long,
    Double,
    Null,
    UninitThis,
    /// A class's internal name, or an array's descriptor.
    Object(String),
    /// The result of `new` at that offset, before its constructor ran.
    Uninit(u16),
}

impl VType {
    pub fn of(t: &Type) -> VType {
        use crate::ast::Prim::*;
        match t {
            Type::Prim(Long) => VType::Long,
            Type::Prim(Double) => VType::Double,
            Type::Prim(Float) => VType::Float,
            Type::Prim(_) => VType::Int,
            Type::Null => VType::Null,
            other => VType::Object(other.internal()),
        }
    }

    fn wide(&self) -> bool {
        matches!(self, VType::Long | VType::Double)
    }
}

#[derive(Clone, Debug, PartialEq)]
struct Frame {
    locals: Vec<VType>,
    stack: Vec<VType>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Label(usize);

struct LabelInfo {
    pos: Option<u16>,
    /// How many local slots are in scope where the label belongs.
    scope: usize,
    frame: Option<Frame>,
    /// Offsets of the branch instructions to patch, and of their operand.
    fixups: Vec<(usize, usize)>,
}

#[derive(Default)]
pub struct Asm {
    pub code: Vec<u8>,
    locals: Vec<VType>,
    stack: Vec<VType>,
    depth: u16,
    pub max_stack: u16,
    pub max_locals: u16,
    reachable: bool,
    labels: Vec<LabelInfo>,
    frames: BTreeMap<u16, Frame>,
    pub handlers: Vec<(Label, Label, Label, u16)>,
    pub lines: Vec<(u16, u16)>,
    /// Set when the method outgrows what a class file can hold.
    pub too_large: bool,
    /// Where the last instruction starts when it is a `checkcast`.
    pub last_cast: Option<usize>,
}

impl Asm {
    pub fn new(locals: Vec<VType>) -> Asm {
        let mut a = Asm {
            reachable: true,
            ..Asm::default()
        };
        for l in locals {
            a.push_local(l);
        }
        a
    }

    pub fn reachable(&self) -> bool {
        self.reachable
    }

    /// The offset of the next instruction.
    pub fn pc(&self) -> u16 {
        u16::try_from(self.code.len()).unwrap_or(u16::MAX)
    }

    // ---- locals ----

    /// Adds a local at the next free slot and returns its slot.
    pub fn push_local(&mut self, t: VType) -> u16 {
        let slot = self.locals.len() as u16;
        let wide = t.wide();
        self.locals.push(t);
        if wide {
            self.locals.push(VType::Top);
        }
        self.max_locals = self.max_locals.max(self.locals.len() as u16);
        if self.locals.len() > usize::from(u16::MAX) {
            self.too_large = true;
        }
        slot
    }

    pub fn scope(&self) -> usize {
        self.locals.len()
    }

    /// Drops the locals declared since `scope`, when their block ends.
    pub fn end_scope(&mut self, scope: usize) {
        self.locals.truncate(scope);
    }

    /// Records that `slot` now holds `t` (after `this` is initialised).
    pub fn set_local(&mut self, slot: u16, t: VType) {
        if let Some(l) = self.locals.get_mut(usize::from(slot)) {
            *l = t;
        }
    }

    // ---- instructions ----

    /// Emits `bytes` (an opcode and its operands), popping `pops` stack
    /// entries and pushing `push`. Nothing is emitted in dead code.
    pub fn emit(&mut self, bytes: &[u8], pops: usize, push: Option<VType>) {
        if !self.reachable {
            return;
        }
        self.last_cast = None;
        self.code.extend_from_slice(bytes);
        if self.code.len() > 65535 {
            self.too_large = true;
        }
        self.pop(pops);
        if let Some(t) = push {
            self.push(t);
        }
    }

    pub fn push(&mut self, t: VType) {
        self.depth += if t.wide() { 2 } else { 1 };
        self.max_stack = self.max_stack.max(self.depth);
        self.stack.push(t);
    }

    pub fn pop(&mut self, n: usize) {
        for _ in 0..n {
            if let Some(t) = self.stack.pop() {
                self.depth -= if t.wide() { 2 } else { 1 };
            }
        }
    }

    /// Removes a `checkcast` that ends the code, for a value about to be
    /// popped: the cast after a generic call whose result is thrown away.
    pub fn drop_trailing_cast(&mut self) {
        if let Some(at) = self.last_cast.take()
            && at + 3 == self.code.len()
        {
            self.code.truncate(at);
        }
    }

    pub fn top(&self) -> Option<&VType> {
        self.stack.last()
    }

    /// Gives `l` the frame of this point now, for a label placed later in
    /// code nothing falls into (an `await`'s resume point).
    pub fn record(&mut self, l: Label) {
        let f = self.frame_for(self.labels[l.0].scope);
        self.labels[l.0].frame = Some(f);
    }

    /// The live local slots from `first` on, with their types, skipping
    /// unset slots and the second half of wide ones.
    pub fn live_slots(&self, first: u16) -> Vec<(u16, VType)> {
        let mut out = Vec::new();
        let mut i = usize::from(first);
        while i < self.locals.len() {
            let t = &self.locals[i];
            if !matches!(t, VType::Top | VType::UninitThis | VType::Uninit(_)) {
                out.push((i as u16, t.clone()));
            }
            i += if t.wide() { 2 } else { 1 };
        }
        out
    }

    /// `swap` of two one-slot values.
    pub fn swap(&mut self) {
        if !self.reachable || self.stack.len() < 2 {
            return;
        }
        self.code.push(0x5f);
        let n = self.stack.len();
        self.stack.swap(n - 1, n - 2);
    }

    /// Records that the value on top has type `t`, a supertype of what
    /// was pushed, so both arms of a join agree on the frame.
    pub fn retype_top(&mut self, t: VType) {
        if !self.reachable {
            return;
        }
        if let Some(top) = self.stack.last_mut() {
            *top = t;
        }
    }

    /// `dup2` over two one-slot values (an array and an index).
    pub fn dup_pair(&mut self) {
        if !self.reachable || self.stack.len() < 2 {
            return;
        }
        let n = self.stack.len();
        let (a, b) = (self.stack[n - 2].clone(), self.stack[n - 1].clone());
        self.code.push(0x5c);
        self.push(a);
        self.push(b);
    }

    /// Copies the top value below the `skip` entries under it: `dup`,
    /// `dup_x1`, `dup_x2` and their `dup2` forms for a wide value. `skip`
    /// counts slots (an object reference and an array index are one each).
    pub fn dup_under(&mut self, skip: u16) {
        if !self.reachable {
            return;
        }
        let Some(top) = self.stack.last().cloned() else {
            return;
        };
        let wide = top.wide();
        let op = match (wide, skip) {
            (false, 0) => 0x59,
            (false, 1) => 0x5a,
            (false, _) => 0x5b,
            (true, 0) => 0x5c,
            (true, 1) => 0x5d,
            (true, _) => 0x5e,
        };
        self.code.push(op);
        let mut entries = 0;
        let mut slots = 0;
        while slots < skip {
            let Some(t) = self.stack.get(self.stack.len() - 2 - entries) else {
                break;
            };
            slots += if t.wide() { 2 } else { 1 };
            entries += 1;
        }
        let at = self.stack.len() - 1 - entries;
        self.stack.insert(at, top);
        self.depth += if wide { 2 } else { 1 };
        self.max_stack = self.max_stack.max(self.depth);
    }

    /// An opcode with a two-byte constant pool index.
    pub fn emit_u2(&mut self, op: u8, index: u16, pops: usize, push: Option<VType>) {
        let [hi, lo] = index.to_be_bytes();
        self.emit(&[op, hi, lo], pops, push);
    }

    /// `new`: pushes the uninitialised object of this offset.
    pub fn new_object(&mut self, class_index: u16) {
        let at = self.pc();
        self.emit_u2(0xbb, class_index, 0, Some(VType::Uninit(at)));
    }

    /// After a constructor call: every copy of the object it initialised
    /// now has `class`'s type (JVMS 4.10.1.9 `invokespecial`).
    pub fn initialised(&mut self, was: &VType, class: &str) {
        let now = VType::Object(class.to_string());
        for t in self.stack.iter_mut().chain(self.locals.iter_mut()) {
            if t == was {
                *t = now.clone();
            }
        }
    }

    pub fn line(&mut self, line: u16) {
        if !self.reachable {
            return;
        }
        let pc = self.pc();
        match self.lines.last_mut() {
            Some(last) if last.0 == pc => last.1 = line,
            Some(last) if last.1 == line => {}
            _ => self.lines.push((pc, line)),
        }
    }

    // ---- labels and jumps ----

    pub fn label(&mut self) -> Label {
        self.labels.push(LabelInfo {
            pos: None,
            scope: self.locals.len(),
            frame: None,
            fixups: Vec::new(),
        });
        Label(self.labels.len() - 1)
    }

    fn frame_for(&self, scope: usize) -> Frame {
        let mut locals = self.locals.clone();
        locals.truncate(scope);
        Frame {
            locals,
            stack: self.stack.clone(),
        }
    }

    /// A branch to `to`, popping `pops` operands first. `goto` (0xa7) ends
    /// the reachable code.
    pub fn jump(&mut self, op: u8, pops: usize, to: Label) {
        if !self.reachable {
            return;
        }
        self.pop(pops);
        let at = self.code.len();
        self.code.extend_from_slice(&[op, 0, 0]);
        let info = &self.labels[to.0];
        let frame = self.frame_for(info.scope);
        let info = &mut self.labels[to.0];
        if info.frame.is_none() {
            info.frame = Some(frame);
        }
        info.fixups.push((at, at + 1));
        if op == 0xa7 {
            self.reachable = false;
        }
    }

    /// Places `l` here. Code after it is reachable if this point was, or if
    /// a branch goes to it.
    pub fn place(&mut self, l: Label) {
        let pc = self.pc();
        let scope = self.labels[l.0].scope;
        if self.reachable {
            let f = self.frame_for(scope);
            self.labels[l.0].frame = Some(f);
        } else if let Some(f) = self.labels[l.0].frame.clone() {
            self.restore(&f);
            self.reachable = true;
        }
        self.labels[l.0].pos = Some(pc);
        if self.reachable
            && let Some(f) = self.labels[l.0].frame.clone()
        {
            self.frames.insert(pc, f);
        }
    }

    fn restore(&mut self, f: &Frame) {
        self.locals.truncate(f.locals.len());
        for (i, t) in f.locals.iter().enumerate() {
            match self.locals.get_mut(i) {
                Some(l) => *l = t.clone(),
                None => self.locals.push(t.clone()),
            }
        }
        self.stack = f.stack.clone();
        self.depth = f.stack.iter().map(|t| if t.wide() { 2 } else { 1 }).sum();
    }

    /// The start of an exception handler: reachable from the protected
    /// range, with the exception alone on the stack.
    pub fn place_handler(&mut self, l: Label, exception: &str) {
        let scope = self.labels[l.0].scope;
        let mut f = self.frame_for(scope);
        f.stack = vec![VType::Object(exception.to_string())];
        self.labels[l.0].frame = Some(f.clone());
        self.reachable = false;
        self.place(l);
    }

    /// Ends the reachable code after `return` or `athrow`.
    pub fn end_flow(&mut self) {
        self.reachable = false;
    }

    /// Resolves the branch offsets. False if a branch is out of range or a
    /// label was never placed.
    pub fn finish(&mut self) -> bool {
        for info in &self.labels {
            let Some(pos) = info.pos else {
                if !info.fixups.is_empty() {
                    return false;
                }
                continue;
            };
            for &(at, operand) in &info.fixups {
                let Ok(delta) = i16::try_from(i64::from(pos) - at as i64) else {
                    return false;
                };
                self.code[operand..operand + 2].copy_from_slice(&delta.to_be_bytes());
            }
        }
        !self.too_large
    }

    pub fn label_pos(&self, l: Label) -> Option<u16> {
        self.labels[l.0].pos
    }

    /// The `StackMapTable` attribute's body, or `None` when no frame is needed.
    pub fn stack_map(&self, pool: &mut Pool) -> Option<Vec<u8>> {
        if self.frames.is_empty() {
            return None;
        }
        let mut out = Vec::new();
        out.extend_from_slice(&(self.frames.len() as u16).to_be_bytes());
        let mut prev: Option<u16> = None;
        for (&pc, f) in &self.frames {
            let delta = prev.map_or(pc, |p| pc - p - 1);
            prev = Some(pc);
            out.push(255);
            out.extend_from_slice(&delta.to_be_bytes());
            let locals = frame_locals(&f.locals);
            out.extend_from_slice(&(locals.len() as u16).to_be_bytes());
            for t in locals {
                write_vtype(&mut out, t, pool);
            }
            out.extend_from_slice(&(f.stack.len() as u16).to_be_bytes());
            for t in &f.stack {
                write_vtype(&mut out, t, pool);
            }
        }
        Some(out)
    }
}

/// Locals as a frame lists them: one entry for a `long` or `double`, and no
/// trailing `Top`s.
fn frame_locals(slots: &[VType]) -> Vec<&VType> {
    let mut out = Vec::new();
    let mut i = 0;
    while i < slots.len() {
        out.push(&slots[i]);
        i += if slots[i].wide() { 2 } else { 1 };
    }
    while out.last() == Some(&&VType::Top) {
        out.pop();
    }
    out
}

fn write_vtype(out: &mut Vec<u8>, t: &VType, pool: &mut Pool) {
    match t {
        VType::Top => out.push(0),
        VType::Int => out.push(1),
        VType::Float => out.push(2),
        VType::Double => out.push(3),
        VType::Long => out.push(4),
        VType::Null => out.push(5),
        VType::UninitThis => out.push(6),
        VType::Object(name) => {
            out.push(7);
            out.extend_from_slice(&pool.class(name).to_be_bytes());
        }
        VType::Uninit(at) => {
            out.push(8);
            out.extend_from_slice(&at.to_be_bytes());
        }
    }
}
