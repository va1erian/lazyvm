//! Creating exception objects and recording stack traces.

use crate::class::{ClassId, MethodRef};
use crate::value::{Ref, Value};
use crate::{Vm, VmError, VmResult};

/// Nested exception creation deeper than this means the class library
/// cannot construct its own exceptions; the VM gives up instead of looping.
const MAX_EXCEPTION_NESTING: u32 = 8;

/// One recorded frame of a backtrace.
#[derive(Clone, Copy, Debug)]
pub struct TraceFrame {
    pub method: MethodRef,
    pub pc: usize,
}

impl Vm {
    /// Turns a catchable error into the exception object to throw.
    pub fn materialize(&mut self, mut e: VmError) -> VmResult<Ref> {
        for _ in 0..MAX_EXCEPTION_NESTING {
            let attempt = match e {
                VmError::Throw(r) => return Ok(r),
                VmError::Pending { class, ref message } => {
                    let message = message.clone();
                    self.throw_new(class, message.as_deref())
                }
                VmError::Verify(ref m) => {
                    let m = m.clone();
                    self.throw_new("java/lang/VerifyError", Some(&m))
                }
                VmError::OutOfMemory => {
                    self.throw_new("java/lang/OutOfMemoryError", Some("Java heap space"))
                }
                other => return Err(other),
            };
            match attempt {
                Ok(r) => return Ok(r),
                Err(next) if next.is_catchable() => e = next,
                Err(fatal) => return Err(fatal),
            }
        }
        Err(VmError::Internal(format!(
            "could not create an exception for {e}"
        )))
    }

    /// Creates `class` with its `(String)` constructor (`message` may be
    /// absent, meaning a null message).
    pub fn throw_new(&mut self, class: &str, message: Option<&str>) -> VmResult<Ref> {
        let units: Option<Vec<u16>> = message.map(|m| m.encode_utf16().collect());
        self.throw_new_utf16(class, units.as_deref())
    }

    /// `throw_new` with the message as UTF-16 units, so a message naming a
    /// file can hold the path exactly, lone surrogates included.
    pub fn throw_new_utf16(&mut self, class: &str, message: Option<&[u16]>) -> VmResult<Ref> {
        if self.creating_exception >= MAX_EXCEPTION_NESTING {
            return Err(VmError::Internal(format!(
                "recursive failure creating {class}"
            )));
        }
        self.creating_exception += 1;
        let result = self.construct_exception(class, message);
        self.creating_exception -= 1;
        result
    }

    fn construct_exception(&mut self, class: &str, message: Option<&[u16]>) -> VmResult<Ref> {
        let c = self.load_class(class)?;
        self.initialize(c)?;
        let ctor = self
            .find_in_class(c, "<init>", "(Ljava/lang/String;)V", false)
            .ok_or_else(|| VmError::Internal(format!("{class} has no (String) constructor")))?;
        let obj = self.new_object(c)?;
        let msg = match message {
            Some(m) => Value::Ref(Some(self.new_string_utf16(m)?)),
            None => Value::NULL,
        };
        self.invoke(ctor, &[Value::Ref(Some(obj)), msg])?;
        Ok(obj)
    }

    /// The frames to record for `throwable`, innermost first, skipping
    /// `fillInStackTrace` and the throwable's own constructors.
    pub fn capture_backtrace(&self, throwable: Ref) -> VmResult<Vec<TraceFrame>> {
        let tclass = self.heap.class_of(throwable)?;
        let throwable_class = self.wk.throwable;
        let mut frames = self.frames.iter().rev().peekable();
        while let Some(f) = frames.peek() {
            let name = &self.method(f.method).name;
            if Some(f.class) == throwable_class && name.starts_with("fillInStackTrace") {
                frames.next();
            } else {
                break;
            }
        }
        while let Some(f) = frames.peek() {
            if &*self.method(f.method).name == "<init>" && self.is_subclass(tclass, f.class) {
                frames.next();
            } else {
                break;
            }
        }
        Ok(frames
            .map(|f| TraceFrame {
                method: f.method,
                pc: f.pc,
            })
            .collect())
    }

    /// The `(class, method, file, line)` of a recorded frame, for
    /// `StackTraceElement`.
    pub fn describe_frame(&self, t: TraceFrame) -> (String, String, Option<String>, i32) {
        let class = self.class(t.method.class);
        let method = self.method(t.method);
        let line = method
            .code
            .as_ref()
            .and_then(|c| c.line_at(t.pc))
            .map_or(-1, i32::from);
        (
            class.java_name(),
            method.name.to_string(),
            class.source_file().map(str::to_owned),
            line,
        )
    }

    /// Encodes a backtrace as an `int[]` of `(class, method, pc)` triples.
    pub fn encode_backtrace(&mut self, frames: &[TraceFrame]) -> VmResult<Ref> {
        let len = i32::try_from(frames.len() * 3).map_err(|_| VmError::OutOfMemory)?;
        let array = self.new_array_of("I", len)?;
        if let crate::heap::Array::Int(a) = self.heap.array_mut(array)? {
            for (i, f) in frames.iter().enumerate() {
                a[i * 3] = f.method.class.0 as i32;
                a[i * 3 + 1] = i32::from(f.method.index);
                a[i * 3 + 2] = f.pc as i32;
            }
        }
        Ok(array)
    }

    /// Decodes [`encode_backtrace`], dropping entries that do not name a method.
    pub fn decode_backtrace(&self, array: Ref) -> VmResult<Vec<TraceFrame>> {
        let crate::heap::Array::Int(a) = self.heap.array(array)? else {
            return Ok(Vec::new());
        };
        Ok(a.as_chunks::<3>()
            .0
            .iter()
            .filter_map(|t| {
                let class = ClassId(u32::try_from(t[0]).ok()?);
                let index = u16::try_from(t[1]).ok()?;
                let c = self.classes.get(class.0 as usize)?;
                c.methods.get(usize::from(index))?;
                Some(TraceFrame {
                    method: MethodRef { class, index },
                    pc: usize::try_from(t[2]).ok()?,
                })
            })
            .collect())
    }
}
