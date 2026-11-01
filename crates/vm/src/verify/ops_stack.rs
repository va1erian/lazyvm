//! Type rules of the stack instructions (JVMS 6.5, whose forms say which
//! categories each takes), arithmetic and conversions (JVMS 4.10.1.9).

use super::check::Checker;
use super::vtype::VType;
use super::{Fail, Hierarchy};

impl<H: Hierarchy + ?Sized> Checker<'_, '_, '_, H> {
    pub(super) fn stack_op(&mut self, op: u8) -> Result<(), Fail> {
        match op {
            0x57 => {
                self.pop_category1()?;
            }
            0x58 => {
                if self.pop()?.size() == 1 {
                    self.pop_category1()?;
                }
            }
            0x59 => {
                let a = self.pop_category1()?;
                self.push_all(&[a, a])?;
            }
            0x5a => {
                let (a, b) = (self.pop_category1()?, self.pop_category1()?);
                self.push_all(&[a, b, a])?;
            }
            0x5b => {
                let a = self.pop_category1()?;
                let b = self.pop()?;
                if b.size() == 2 {
                    self.push_all(&[a, b, a])?;
                } else {
                    let c = self.pop_category1()?;
                    self.push_all(&[a, c, b, a])?;
                }
            }
            0x5c => {
                let a = self.pop()?;
                if a.size() == 2 {
                    self.push_all(&[a, a])?;
                } else {
                    let b = self.pop_category1()?;
                    self.push_all(&[b, a, b, a])?;
                }
            }
            0x5d => {
                let a = self.pop()?;
                if a.size() == 2 {
                    let b = self.pop_category1()?;
                    self.push_all(&[a, b, a])?;
                } else {
                    let (b, c) = (self.pop_category1()?, self.pop_category1()?);
                    self.push_all(&[b, a, c, b, a])?;
                }
            }
            0x5e => self.dup2_x2()?,
            _ => {
                let (a, b) = (self.pop_category1()?, self.pop_category1()?);
                self.push_all(&[a, b])?;
            }
        }
        Ok(())
    }

    /// `dup2_x2`'s four forms (JVMS 6.5).
    fn dup2_x2(&mut self) -> Result<(), Fail> {
        let a = self.pop()?;
        if a.size() == 2 {
            let b = self.pop()?;
            if b.size() == 2 {
                return self.push_all(&[a, b, a]);
            }
            let c = self.pop_category1()?;
            return self.push_all(&[a, c, b, a]);
        }
        let b = self.pop_category1()?;
        let c = self.pop()?;
        if c.size() == 2 {
            return self.push_all(&[b, a, c, b, a]);
        }
        let d = self.pop_category1()?;
        self.push_all(&[b, a, d, c, b, a])
    }

    fn push_all(&mut self, ts: &[VType]) -> Result<(), Fail> {
        for &t in ts {
            self.push(t)?;
        }
        Ok(())
    }

    /// `iadd` through `lxor`: the opcode's low bits give the type for the
    /// arithmetic block; the shifts and the logic block are listed.
    pub(super) fn math(&mut self, op: u8) -> Result<(), Fail> {
        use VType::{Double as D, Float as F, Int as I, Long as J};
        let kinds = [I, J, F, D];
        match op {
            0x60..=0x73 => {
                let t = kinds[usize::from((op - 0x60) % 4)];
                self.apply(&[t, t], Some(t))
            }
            0x74..=0x77 => {
                let t = kinds[usize::from(op - 0x74)];
                self.apply(&[t], Some(t))
            }
            0x78 | 0x7a | 0x7c => self.apply(&[I, I], Some(I)),
            0x79 | 0x7b | 0x7d => self.apply(&[I, J], Some(J)),
            0x7e | 0x80 | 0x82 => self.apply(&[I, I], Some(I)),
            _ => self.apply(&[J, J], Some(J)),
        }
    }

    pub(super) fn convert(&mut self, op: u8) -> Result<(), Fail> {
        use VType::{Double as D, Float as F, Int as I, Long as J};
        let (from, to) = match op {
            0x85 => (I, J),
            0x86 => (I, F),
            0x87 => (I, D),
            0x88 => (J, I),
            0x89 => (J, F),
            0x8a => (J, D),
            0x8b => (F, I),
            0x8c => (F, J),
            0x8d => (F, D),
            0x8e => (D, I),
            0x8f => (D, J),
            0x90 => (D, F),
            _ => (I, I),
        };
        self.apply(&[from], Some(to))
    }
}
