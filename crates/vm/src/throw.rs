//! Creating exception objects and recording stack traces.

use crate::class::{ClassId, MethodRef};
use crate::objects::ARRAY_LIMIT;
use crate::value::{Ref, Value};
use crate::{Vm, VmError, VmResult};

/// Nested exception creation deeper than this means the class library
/// cannot construct its own exceptions; the VM gives up instead of looping.
const MAX_EXCEPTION_NESTING: u32 = 8;

/// Observed on Java 8: a trace holds the innermost 1024 frames at most.
const MAX_TRACE: usize = 1024;
/// Observed on Java 8: a trace of a VM-made `OutOfMemoryError` holds 32 at most.
const OOM_TRACE: usize = 32;

const OOM: &str = "java/lang/OutOfMemoryError";
const SOE: &str = "java/lang/StackOverflowError";
const HEAP_SPACE: &str = "Java heap space";

/// The `OutOfMemoryError`s the VM makes. Observed on Java 8: the first two of
/// the whole VM are new objects; after that each message has one shared object,
/// with no trace. That object is also where exception creation ends up once the
/// heap has nothing left, as it needs no memory (threads.md, "Out of memory").
#[derive(Default)]
pub(crate) struct OomErrors {
    new_ones_made: u8,
    heap_space: Option<Ref>,
    array_limit: Option<Ref>,
}

impl OomErrors {
    const NEW_ONES: u8 = 2;

    pub(crate) fn roots(&self, out: &mut Vec<Ref>) {
        out.extend(self.heap_space);
        out.extend(self.array_limit);
    }

    fn shared(&mut self, message: &str) -> &mut Option<Ref> {
        if message == HEAP_SPACE {
            &mut self.heap_space
        } else {
            &mut self.array_limit
        }
    }
}

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
                VmError::Pending {
                    class: OOM,
                    message: Some(ref m),
                } if m == ARRAY_LIMIT => self.vm_oom(ARRAY_LIMIT),
                VmError::Pending { class, ref message } => {
                    let message = message.clone();
                    self.throw_new(class, message.as_deref()).and_then(|r| {
                        if class == SOE {
                            self.seal_vm_error(r, false)?;
                        }
                        Ok(r)
                    })
                }
                VmError::Verify(ref m) => {
                    let m = m.clone();
                    self.throw_new("java/lang/VerifyError", Some(&m))
                }
                VmError::OutOfMemory => {
                    self.heap.give_handler_room();
                    self.vm_oom(HEAP_SPACE)
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

    /// The `OutOfMemoryError` to throw (`OomErrors`). Inside another exception's
    /// creation it is the shared one, so a shortage cannot nest, and an exception
    /// the heap has no room for turns into this one, as on Java 8.
    fn vm_oom(&mut self, message: &'static str) -> VmResult<Ref> {
        let shared = self.shared_oom(message)?;
        if self.creating_exception > 0 || self.oom.new_ones_made >= OomErrors::NEW_ONES {
            return Ok(shared);
        }
        self.oom.new_ones_made += 1;
        match self.throw_new(OOM, Some(message)) {
            Ok(r) => {
                self.seal_vm_error(r, false)?;
                Ok(r)
            }
            Err(e) if e.is_catchable() => Ok(shared),
            Err(e) => Err(e),
        }
    }

    /// Made at the first shortage, whatever the heap holds: it is one small
    /// object, of library classes only, and it has to exist from then on.
    fn shared_oom(&mut self, message: &'static str) -> VmResult<Ref> {
        if let Some(r) = *self.oom.shared(message) {
            return Ok(r);
        }
        self.heap.unlimited = true;
        let made = self.throw_new(OOM, Some(message));
        self.heap.unlimited = false;
        let r = made?;
        self.seal_vm_error(r, true)?;
        // Another thread may have made one while this one waited for a class.
        Ok(*self.oom.shared(message).get_or_insert(r))
    }

    /// Observed on Java 8: a VM-made `OutOfMemoryError` or `StackOverflowError`
    /// has a cause of null that cannot be set, and suppresses nothing. A shared
    /// one's trace is empty and stays so.
    fn seal_vm_error(&mut self, error: Ref, shared: bool) -> VmResult<()> {
        const THROWABLE_ARRAY: &str = "[Ljava/lang/Throwable;";
        const TRACE: &str = "[Ljava/lang/StackTraceElement;";
        self.set_field_by_name(error, "cause", "Ljava/lang/Throwable;", Value::NULL)?;
        self.set_field_by_name(error, "suppressed", THROWABLE_ARRAY, Value::NULL)?;
        if shared {
            let frozen = self.throwable_frozen_trace()?;
            self.set_field_by_name(error, "stackTrace", TRACE, frozen)?;
            self.set_field(error, self.wk.throwable_backtrace, Value::NULL)?;
        }
        Ok(())
    }

    /// `Throwable.FROZEN`, the mark of a trace that cannot be written.
    fn throwable_frozen_trace(&self) -> VmResult<Value> {
        let missing = || VmError::Internal("Throwable has no FROZEN".into());
        let throwable = self.wk.throwable.ok_or_else(missing)?;
        let (owner, i) = self
            .lookup_field(throwable, "FROZEN", "[Ljava/lang/StackTraceElement;")
            .ok_or_else(missing)?;
        let c = self.class(owner);
        let slot = c.fields[i].slot as usize;
        c.statics.get(slot).copied().ok_or_else(missing)
    }

    /// Whether `e` is the heap or the stack running out, as an error or as its
    /// exception: something that says nothing about the code it stopped.
    pub(crate) fn is_passing_shortage(&self, e: &VmError) -> bool {
        match e {
            VmError::OutOfMemory => true,
            VmError::Throw(r) => self.heap.class_of(*r).is_ok_and(|c| {
                let name = &*self.class(c).name;
                name == OOM || name == SOE
            }),
            _ => false,
        }
    }

    /// Reports an exception that came out of the running thread's uncaught
    /// exception handler, on stderr, as the oracle does. The heap may well be
    /// full by then, so this allocates nothing in it.
    pub fn report_handler_failure(&self, e: &VmError) -> VmResult<()> {
        let class = match e {
            VmError::Throw(r) => self.class(self.heap.class_of(*r)?).java_name(),
            VmError::Pending { class, .. } => class.replace('/', "."),
            VmError::Verify(_) => "java.lang.VerifyError".to_owned(),
            _ => OOM.replace('/', "."),
        };
        let name = match self.current_thread() {
            Some(thread) => match self.field_by_name(thread, "name", "Ljava/lang/String;")? {
                Value::Ref(Some(name)) => self.string_to_rust(name)?,
                _ => String::new(),
            },
            // Java has never asked for the main thread's object.
            None => "main".to_owned(),
        };
        let line = format!(
            "\nException: {class} thrown from the UncaughtExceptionHandler in thread \"{name}\"\n"
        );
        // Nothing is left to tell if stderr itself fails.
        let _ = platform::write(platform::Stream::Err, line.as_bytes());
        Ok(())
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
        let vm_oom = self.creating_exception > 0 && &*self.class(tclass).name == OOM;
        let limit = if vm_oom { OOM_TRACE } else { MAX_TRACE };
        let mut out: Vec<TraceFrame> = frames
            .take(limit)
            .map(|f| TraceFrame {
                method: f.method,
                pc: f.pc,
            })
            .collect();
        // A started thread's outermost frame is the VM's entry into Java
        // (`Thread.runByVm`), which a trace does not show: `run` is the base.
        if out.last().is_some_and(|f| self.is_thread_entry(f.method)) {
            out.pop();
        }
        Ok(out)
    }

    fn is_thread_entry(&self, m: MethodRef) -> bool {
        &*self.method(m).name == "runByVm" && &*self.class(m.class).name == "java/lang/Thread"
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
