//! The interpreter loop: dispatch, calls, returns and exception unwinding.
//!
//! Frames live in `Vm::frames`, not on the Rust stack, so Java recursion
//! depth is bounded by `Options::max_frames` alone. A call into Java from
//! Rust (`Vm::invoke`) runs a nested loop that stops when its frame returns.

mod frame;
mod ops_array;
mod ops_basic;
mod ops_branch;
mod ops_invoke;
mod ops_math;
mod ops_object;

pub use frame::Frame;

use crate::class::MethodRef;
use crate::value::{Ref, Value};
use crate::{Vm, VmError, VmResult};

/// What an instruction did to control flow.
pub(crate) enum Flow {
    Next,
    Return(Option<Value>),
}

/// Extra frames allowed while creating a `StackOverflowError`, so its
/// constructor can run.
const OVERFLOW_HEADROOM: usize = 64;

impl Vm {
    /// Calls a method from Rust and runs it to completion. Reference
    /// arguments are rooted for the duration of the call.
    pub fn invoke(&mut self, m: MethodRef, args: &[Value]) -> VmResult<Option<Value>> {
        let mark = self.roots.len();
        self.roots.extend(args.iter().filter_map(|v| match v {
            Value::Ref(Some(r)) => Some(*r),
            _ => None,
        }));
        let result = self.invoke_unrooted(m, args);
        self.roots.truncate(mark);
        result
    }

    fn invoke_unrooted(&mut self, m: MethodRef, args: &[Value]) -> VmResult<Option<Value>> {
        let method = self.method(m);
        if usize::from(method.arg_values) != args.len() {
            return Err(VmError::Internal(format!(
                "{} called with {} args",
                method.name,
                args.len()
            )));
        }
        if method.is_native() {
            let f = self.native_for(m)?;
            return f(self, args);
        }
        let base = self.frames.len();
        self.push_frame(m, args)?;
        self.run(base)
    }

    pub(crate) fn push_frame(&mut self, m: MethodRef, args: &[Value]) -> VmResult<()> {
        let headroom = if self.creating_exception > 0 {
            OVERFLOW_HEADROOM
        } else {
            0
        };
        if self.frames.len() >= self.options.max_frames + headroom {
            return Err(VmError::bare("java/lang/StackOverflowError"));
        }
        let method = self.method(m);
        let Some(code) = method.code.clone() else {
            let msg = format!(
                "{}.{}{}",
                self.class(m.class).java_name(),
                method.name,
                method.descriptor
            );
            return Err(VmError::exception("java/lang/AbstractMethodError", msg));
        };
        let frame = Frame::new(m, code, method.arg_slots, args)?;
        self.frames.push(frame);
        Ok(())
    }

    /// Runs until the frame count drops to `base`, returning the value of the
    /// frame that returned last. An exception that escapes every frame above
    /// `base` is returned as `VmError::Throw`.
    pub(crate) fn run(&mut self, base: usize) -> VmResult<Option<Value>> {
        loop {
            if self.heap.wants_gc() {
                self.collect_garbage();
            }
            let outcome = match self.step() {
                Ok(Flow::Next) => continue,
                Ok(Flow::Return(v)) => {
                    self.frames.pop();
                    if self.frames.len() <= base {
                        return Ok(v);
                    }
                    self.resume_caller(v)
                }
                Err(e) => Err(e),
            };
            if let Err(e) = outcome {
                if !e.is_catchable() {
                    return Err(e);
                }
                let ex = self.materialize(e)?;
                self.unwind(ex, base)?;
            }
        }
    }

    fn resume_caller(&mut self, v: Option<Value>) -> VmResult<()> {
        let frame = self.top()?;
        frame.pc = frame.next_pc;
        match v {
            Some(v) => frame.push(v),
            None => Ok(()),
        }
    }

    pub(crate) fn top(&mut self) -> VmResult<&mut Frame> {
        self.frames
            .last_mut()
            .ok_or_else(|| VmError::Internal("no frame".into()))
    }

    /// Finds a handler for `ex`, popping frames above `base` that have none.
    fn unwind(&mut self, ex: Ref, base: usize) -> VmResult<()> {
        let mark = self.roots.len();
        self.roots.push(ex);
        let result = self.unwind_rooted(ex, base);
        self.roots.truncate(mark);
        result
    }

    fn unwind_rooted(&mut self, ex: Ref, base: usize) -> VmResult<()> {
        let ex_class = self.heap.class_of(ex)?;
        while self.frames.len() > base {
            let (class, pc, code) = {
                let f = self.top()?;
                (f.class, f.pc, f.code.clone())
            };
            for h in &code.handlers {
                if pc < usize::from(h.start_pc) || pc >= usize::from(h.end_pc) {
                    continue;
                }
                let catches = h.catch_type == 0 || {
                    let catch = self.resolve_class(class, h.catch_type)?;
                    self.is_assignable(ex_class, catch)
                };
                if catches {
                    let f = self.top()?;
                    f.stack.clear();
                    f.stack.push(Value::Ref(Some(ex)));
                    f.pc = usize::from(h.handler_pc);
                    return Ok(());
                }
            }
            self.frames.pop();
        }
        Err(VmError::Throw(ex))
    }

    /// Executes one instruction of the top frame.
    fn step(&mut self) -> VmResult<Flow> {
        let op = self.top()?.u1(0)?;
        if self.options.trace_interp {
            self.trace_instruction(op);
        }
        match op {
            0x00..=0x11 | 0x15..=0x2d | 0x36..=0x4e | 0x57..=0x5f | 0x84 | 0xc4 => {
                self.op_basic(op)
            }
            0x12..=0x14 | 0xb2..=0xb5 | 0xbb..=0xc3 | 0xc5 => self.op_object(op),
            0x2e..=0x35 | 0x4f..=0x56 => self.op_array(op),
            0x60..=0x83 | 0x85..=0x98 => self.op_math(op),
            0x99..=0xb1 | 0xc6..=0xc9 => self.op_branch(op),
            0xb6..=0xba => self.op_invoke(op),
            _ => Err(VmError::verify(format!("illegal opcode {op:#04x}"))),
        }
    }

    fn trace_instruction(&self, op: u8) {
        if let Some(f) = self.frames.last() {
            let m = self.method(f.method);
            eprintln!(
                "[interp] {}.{}{} pc={} op={op:#04x} stack={}",
                self.class(f.class).java_name(),
                m.name,
                m.descriptor,
                f.pc,
                f.stack.len()
            );
        }
    }
}
