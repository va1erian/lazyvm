//! One activation: locals, operand stack and the bytecode cursor.
//!
//! Every access is checked. Until the verifier lands (S8), malformed
//! bytecode surfaces as `VerifyError` from these checks instead of a panic.

use std::sync::Arc;

use classfile::Code;

use crate::class::{ClassId, MethodRef};
use crate::value::{Ref, Value};
use crate::{VmError, VmResult};

pub struct Frame {
    pub method: MethodRef,
    pub class: ClassId,
    pub code: Arc<Code>,
    /// The start of the instruction being executed.
    pub pc: usize,
    /// Where to continue after a call made by this frame returns.
    pub next_pc: usize,
    pub locals: Box<[Value]>,
    pub stack: Vec<Value>,
}

impl Frame {
    /// A frame for `method` with `args` (one value each, `this` first) laid
    /// out in local slots, wide values taking two.
    pub fn new(
        method: MethodRef,
        code: Arc<Code>,
        arg_slots: u16,
        args: &[Value],
    ) -> VmResult<Frame> {
        let size = usize::from(code.max_locals.max(arg_slots));
        let mut locals = vec![Value::Top; size].into_boxed_slice();
        let mut i = 0;
        for &arg in args {
            let wide = arg.is_wide();
            let need = if wide { 2 } else { 1 };
            if i + need > size {
                return Err(VmError::verify("arguments do not fit max_locals"));
            }
            locals[i] = arg;
            i += need;
        }
        let stack = Vec::with_capacity(usize::from(code.max_stack));
        Ok(Frame {
            method,
            class: method.class,
            code,
            pc: 0,
            next_pc: 0,
            locals,
            stack,
        })
    }

    pub fn push(&mut self, v: Value) -> VmResult<()> {
        if self.stack.len() >= usize::from(self.code.max_stack) {
            return Err(VmError::verify("operand stack overflow"));
        }
        self.stack.push(v);
        Ok(())
    }

    pub fn pop(&mut self) -> VmResult<Value> {
        self.stack
            .pop()
            .ok_or_else(|| VmError::verify("operand stack underflow"))
    }

    pub fn pop_int(&mut self) -> VmResult<i32> {
        self.pop()?.int()
    }

    pub fn pop_long(&mut self) -> VmResult<i64> {
        self.pop()?.long()
    }

    pub fn pop_float(&mut self) -> VmResult<f32> {
        self.pop()?.float()
    }

    pub fn pop_double(&mut self) -> VmResult<f64> {
        self.pop()?.double()
    }

    pub fn pop_ref(&mut self) -> VmResult<Option<Ref>> {
        self.pop()?.reference()
    }

    /// A non-null reference, or `NullPointerException`.
    pub fn pop_nonnull(&mut self) -> VmResult<Ref> {
        self.pop_ref()?.ok_or_else(VmError::npe)
    }

    /// The value `depth` entries below the top (0 is the top).
    pub fn peek(&self, depth: usize) -> VmResult<Value> {
        self.stack
            .len()
            .checked_sub(depth + 1)
            .map(|i| self.stack[i])
            .ok_or_else(|| VmError::verify("operand stack underflow"))
    }

    pub fn local(&self, i: usize) -> VmResult<Value> {
        self.locals
            .get(i)
            .copied()
            .ok_or_else(|| VmError::verify("local index out of range"))
    }

    /// Stores a local; a wide value also claims slot `i + 1`, and overwriting
    /// half of a wide value invalidates it (JVMS 2.6.1).
    pub fn set_local(&mut self, i: usize, v: Value) -> VmResult<()> {
        let need = if v.is_wide() { 2 } else { 1 };
        if i + need > self.locals.len() {
            return Err(VmError::verify("local index out of range"));
        }
        if i > 0 && self.locals[i - 1].is_wide() {
            self.locals[i - 1] = Value::Top;
        }
        self.locals[i] = v;
        if need == 2 {
            self.locals[i + 1] = Value::Top;
        }
        Ok(())
    }

    pub fn u1(&self, offset: usize) -> VmResult<u8> {
        self.code
            .bytecode
            .get(self.pc + offset)
            .copied()
            .ok_or_else(|| VmError::verify("instruction runs past the end of the code"))
    }

    pub fn u2(&self, offset: usize) -> VmResult<u16> {
        Ok(u16::from_be_bytes([self.u1(offset)?, self.u1(offset + 1)?]))
    }

    pub fn i2(&self, offset: usize) -> VmResult<i16> {
        Ok(self.u2(offset)? as i16)
    }

    pub fn i4(&self, offset: usize) -> VmResult<i32> {
        let b = [
            self.u1(offset)?,
            self.u1(offset + 1)?,
            self.u1(offset + 2)?,
            self.u1(offset + 3)?,
        ];
        Ok(i32::from_be_bytes(b))
    }

    /// Moves to the next instruction, `len` bytes on.
    pub fn advance(&mut self, len: usize) {
        self.pc += len;
    }

    /// Jumps by a signed offset from the current instruction.
    pub fn jump(&mut self, offset: i32) -> VmResult<()> {
        let target = self.pc as i64 + i64::from(offset);
        if target < 0 || target as usize >= self.code.bytecode.len() {
            return Err(VmError::verify("branch target outside the code"));
        }
        self.pc = target as usize;
        Ok(())
    }

    /// The source line of the current instruction, -1 if unknown.
    pub fn line(&self) -> i32 {
        self.code.line_at(self.pc).map_or(-1, i32::from)
    }
}
