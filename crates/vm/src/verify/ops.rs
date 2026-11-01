//! Type rules of the instructions that touch only locals, arrays and control
//! flow (JVMS 4.10.1.9). `ops_stack` has the stack and arithmetic, `ops_ref` the
//! ones that name constants.

use super::check::Checker;
use super::decode::{self, OP_GOTO, OP_GOTO_W, OP_LOOKUPSWITCH, OP_TABLESWITCH, OP_WIDE};
use super::vtype::{OBJECT, THROWABLE, VType, element_name};
use super::{Fail, Hierarchy};

impl<H: Hierarchy + ?Sized> Checker<'_, '_, '_, H> {
    pub(super) fn pop(&mut self) -> Result<VType, Fail> {
        let t = self
            .cur
            .stack
            .pop()
            .ok_or_else(|| self.bad("operand stack underflow"))?;
        self.cur.depth -= t.size();
        Ok(t)
    }

    pub(super) fn push(&mut self, t: VType) -> Result<(), Fail> {
        self.cur.depth += t.size();
        if self.cur.depth > u32::from(self.code.max_stack) {
            return Err(self.bad("operand stack overflows max_stack"));
        }
        self.cur.stack.push(t);
        Ok(())
    }

    /// Pops a value assignable to `want`.
    pub(super) fn pop_as(&mut self, want: VType) -> Result<VType, Fail> {
        let t = self.pop()?;
        if !self.types.assignable(t, want)? {
            let (have, want) = (self.show(t), self.show(want));
            return Err(self.bad(format!("stack holds {have} where {want} is needed")));
        }
        Ok(t)
    }

    pub(super) fn pop_reference(&mut self) -> Result<VType, Fail> {
        let t = self.pop()?;
        if !t.is_reference() {
            let have = self.show(t);
            return Err(self.bad(format!("stack holds {have} where a reference is needed")));
        }
        Ok(t)
    }

    pub(super) fn pop_category1(&mut self) -> Result<VType, Fail> {
        let t = self.pop()?;
        if t.size() != 1 {
            return Err(self.bad("a long or double is split by this stack instruction"));
        }
        Ok(t)
    }

    /// Pops `pops` in order (the first is the stack's top), then pushes `push`.
    pub(super) fn apply(&mut self, pops: &[VType], push: Option<VType>) -> Result<(), Fail> {
        for &t in pops {
            self.pop_as(t)?;
        }
        match push {
            Some(t) => self.push(t),
            None => Ok(()),
        }
    }

    fn u1_at(&self, offset: usize) -> Result<u8, Fail> {
        decode::u1(&self.code.bytecode, self.pc + offset).map_err(|e| self.bad(e))
    }

    pub(super) fn u2_at(&self, offset: usize) -> Result<u16, Fail> {
        decode::u2(&self.code.bytecode, self.pc + offset).map_err(|e| self.bad(e))
    }

    /// Checks the instruction at `pc` and moves `cur` past it.
    pub(super) fn step(&mut self) -> Result<(), Fail> {
        use VType::{Double as D, Float as F, Int as I, Long as J};
        let op = self.u1_at(0)?;
        match op {
            0x00 => {}
            0x01 => self.push(VType::Null)?,
            0x02..=0x08 | 0x10 | 0x11 => self.push(I)?,
            0x09 | 0x0a => self.push(J)?,
            0x0b..=0x0d => self.push(F)?,
            0x0e | 0x0f => self.push(D)?,
            0x12..=0x14 | 0xb2..=0xba | 0xbb | 0xbd | 0xc0 | 0xc1 | 0xc5 => self.ref_op(op)?,
            0x15..=0x19 => self.load(op - 0x15, usize::from(self.u1_at(1)?))?,
            0x1a..=0x2d => {
                let n = op - 0x1a;
                self.load(n / 4, usize::from(n % 4))?;
            }
            0x2e..=0x35 => self.array_load(op)?,
            0x36..=0x3a => self.store(op - 0x36, usize::from(self.u1_at(1)?))?,
            0x3b..=0x4e => {
                let n = op - 0x3b;
                self.store(n / 4, usize::from(n % 4))?;
            }
            0x4f..=0x56 => self.array_store(op)?,
            0x57..=0x5f => self.stack_op(op)?,
            0x60..=0x83 => self.math(op)?,
            0x84 => self.iinc(usize::from(self.u1_at(1)?))?,
            0x85..=0x93 => self.convert(op)?,
            0x94 => self.apply(&[J, J], Some(I))?,
            0x95 | 0x96 => self.apply(&[F, F], Some(I))?,
            0x97 | 0x98 => self.apply(&[D, D], Some(I))?,
            0x99..=0x9e => {
                self.pop_as(I)?;
                self.branch()?;
            }
            0x9f..=0xa4 => {
                self.apply(&[I, I], None)?;
                self.branch()?;
            }
            0xa5 | 0xa6 => {
                self.pop_reference()?;
                self.pop_reference()?;
                self.branch()?;
            }
            0xc6 | 0xc7 => {
                self.pop_reference()?;
                self.branch()?;
            }
            OP_GOTO | OP_GOTO_W => {
                self.branch()?;
                self.dead = true;
            }
            OP_TABLESWITCH | OP_LOOKUPSWITCH => self.switch()?,
            0xac => self.typed_return(I)?,
            0xad => self.typed_return(J)?,
            0xae => self.typed_return(F)?,
            0xaf => self.typed_return(D)?,
            0xb0 => self.reference_return()?,
            0xb1 => self.void_return()?,
            0xbc => {
                self.pop_as(I)?;
                let t = self.newarray_type()?;
                self.push(t)?;
            }
            0xbe => {
                let t = self.pop()?;
                if !self.is_array_or_null(t) {
                    let have = self.show(t);
                    return Err(self.bad(format!("arraylength of {have}")));
                }
                self.push(I)?;
            }
            0xbf => {
                let throwable = self.types.class(THROWABLE);
                self.pop_as(throwable)?;
                self.dead = true;
            }
            0xc2 | 0xc3 => {
                self.pop_reference()?;
            }
            OP_WIDE => self.wide()?,
            _ => return Err(self.bad(format!("illegal opcode {op:#04x}"))),
        }
        Ok(())
    }

    /// The load or store kind (0 int, 1 long, 2 float, 3 double, 4 reference)
    /// as the type a local must have; `None` for a reference.
    fn kind_type(kind: u8) -> Option<VType> {
        match kind {
            0 => Some(VType::Int),
            1 => Some(VType::Long),
            2 => Some(VType::Float),
            3 => Some(VType::Double),
            _ => None,
        }
    }

    fn load(&mut self, kind: u8, index: usize) -> Result<(), Fail> {
        let t = self.cur.local(index);
        let ok = match Self::kind_type(kind) {
            Some(want) => t == want,
            None => t.is_reference(),
        };
        if !ok {
            let have = self.show(t);
            return Err(self.bad(format!("load of local {index}, which holds {have}")));
        }
        self.push(t)
    }

    fn store(&mut self, kind: u8, index: usize) -> Result<(), Fail> {
        let t = match Self::kind_type(kind) {
            Some(want) => self.pop_as(want)?,
            None => self.pop_reference()?,
        };
        self.write_local(index, t);
        Ok(())
    }

    /// JVMS 4.10.1.7: a two-word value takes the next local too, and a write
    /// into the second half of a two-word value kills it.
    fn write_local(&mut self, index: usize, t: VType) {
        self.locals_version += 1;
        self.cur.set_local(index, t);
        if t.size() == 2 {
            self.cur.set_local(index + 1, VType::Top);
        }
        if index > 0 && self.cur.local(index - 1).size() == 2 {
            self.cur.set_local(index - 1, VType::Top);
        }
    }

    fn iinc(&mut self, index: usize) -> Result<(), Fail> {
        if self.cur.local(index) != VType::Int {
            return Err(self.bad(format!("iinc of local {index}, not an int")));
        }
        Ok(())
    }

    fn wide(&mut self) -> Result<(), Fail> {
        let op = self.u1_at(1)?;
        let index = usize::from(self.u2_at(2)?);
        match op {
            0x15..=0x19 => self.load(op - 0x15, index),
            0x36..=0x3a => self.store(op - 0x36, index),
            0x84 => self.iinc(index),
            _ => Err(self.bad(format!("wide of opcode {op:#04x}"))),
        }
    }

    pub(super) fn is_array_or_null(&self, t: VType) -> bool {
        match t {
            VType::Null => true,
            VType::Ref(id) => self.types.name(id).starts_with('['),
            _ => false,
        }
    }

    /// An array of one of `names`, or null; pops it.
    fn pop_array_of(&mut self, names: &[&str]) -> Result<(), Fail> {
        let t = self.pop()?;
        let ok = match t {
            VType::Null => true,
            VType::Ref(id) => names.contains(&&*self.types.name(id)),
            _ => false,
        };
        if !ok {
            let have = self.show(t);
            return Err(self.bad(format!("stack holds {have} where {} is needed", names[0])));
        }
        Ok(())
    }

    /// An array of references, or null; pops it and gives its element type.
    fn pop_reference_array(&mut self) -> Result<VType, Fail> {
        let t = self.pop()?;
        if t == VType::Null {
            return Ok(VType::Null);
        }
        if let VType::Ref(id) = t {
            let name = self.types.name(id);
            if let Some(element) = name.strip_prefix('[').and_then(element_name) {
                return Ok(self.types.class(element));
            }
        }
        let have = self.show(t);
        Err(self.bad(format!(
            "stack holds {have} where an array of references is needed"
        )))
    }

    fn array_load(&mut self, op: u8) -> Result<(), Fail> {
        self.pop_as(VType::Int)?;
        let (names, result): (&[&str], VType) = match op {
            0x2e => (&["[I"], VType::Int),
            0x2f => (&["[J"], VType::Long),
            0x30 => (&["[F"], VType::Float),
            0x31 => (&["[D"], VType::Double),
            0x33 => (&["[B", "[Z"], VType::Int),
            0x34 => (&["[C"], VType::Int),
            0x35 => (&["[S"], VType::Int),
            _ => {
                let element = self.pop_reference_array()?;
                return self.push(element);
            }
        };
        self.pop_array_of(names)?;
        self.push(result)
    }

    fn array_store(&mut self, op: u8) -> Result<(), Fail> {
        let (names, value): (&[&str], VType) = match op {
            0x4f => (&["[I"], VType::Int),
            0x50 => (&["[J"], VType::Long),
            0x51 => (&["[F"], VType::Float),
            0x52 => (&["[D"], VType::Double),
            0x54 => (&["[B", "[Z"], VType::Int),
            0x55 => (&["[C"], VType::Int),
            0x56 => (&["[S"], VType::Int),
            _ => {
                let object = self.types.class(OBJECT);
                self.pop_as(object)?;
                self.pop_as(VType::Int)?;
                self.pop_reference_array()?;
                return Ok(());
            }
        };
        self.pop_as(value)?;
        self.pop_as(VType::Int)?;
        self.pop_array_of(names)
    }

    fn branch(&mut self) -> Result<(), Fail> {
        let op = self.u1_at(0)?;
        let bytes = &self.code.bytecode;
        let offset = if op == OP_GOTO_W {
            i64::from(decode::i4(bytes, self.pc + 1).map_err(|e| self.bad(e))?)
        } else {
            i64::from(decode::i2(bytes, self.pc + 1).map_err(|e| self.bad(e))?)
        };
        // The structural pass proved this is an instruction inside the code.
        self.branch_to((self.pc as i64 + offset) as usize)
    }

    fn switch(&mut self) -> Result<(), Fail> {
        self.pop_as(VType::Int)?;
        let sw = decode::switch(&self.code.bytecode, self.pc).map_err(|e| self.bad(e))?;
        // Many cases share few targets; compare each target once.
        let mut targets = sw.targets;
        targets.sort_unstable();
        targets.dedup();
        for t in targets {
            self.branch_to(t as usize)?;
        }
        self.dead = true;
        Ok(())
    }

    fn typed_return(&mut self, t: VType) -> Result<(), Fail> {
        if self.ret != Some(t) {
            return Err(self.bad("return instruction does not fit the method's return type"));
        }
        self.pop_as(t)?;
        self.dead = true;
        Ok(())
    }

    fn reference_return(&mut self) -> Result<(), Fail> {
        let Some(t @ VType::Ref(_)) = self.ret else {
            return Err(self.bad("areturn in a method that does not return a reference"));
        };
        self.pop_as(t)?;
        self.dead = true;
        Ok(())
    }

    fn void_return(&mut self) -> Result<(), Fail> {
        if self.ret.is_some() {
            return Err(self.bad("return in a method that returns a value"));
        }
        if self.cur.this_uninit {
            return Err(self.bad("return before the constructor called super() or this()"));
        }
        self.dead = true;
        Ok(())
    }

    fn newarray_type(&mut self) -> Result<VType, Fail> {
        let name = match self.u1_at(1)? {
            4 => "[Z",
            5 => "[C",
            6 => "[F",
            7 => "[D",
            8 => "[B",
            9 => "[S",
            10 => "[I",
            11 => "[J",
            t => return Err(self.bad(format!("newarray of unknown type {t}"))),
        };
        Ok(self.types.class(name))
    }
}
