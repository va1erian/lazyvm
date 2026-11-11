//! Events sent to the debugger (`Event.Composite`), and stopping for them.

use super::requests::{SUSPEND_ALL, SUSPEND_THREAD};
use super::wire::{self, Writer};
use crate::Vm;
use crate::class::{ClassId, ClassKind, InitState, MethodRef};

const COMPOSITE: u8 = 100;
const EVENT_SET: u8 = 64;
const KIND_STEP: u8 = 1;
const KIND_BREAKPOINT: u8 = 2;
const KIND_CLASS_PREPARE: u8 = 8;
const KIND_VM_START: u8 = 90;
const KIND_VM_DEATH: u8 = 99;

/// A place in code: a method and the index of an instruction.
#[derive(Clone, Copy, Debug)]
pub struct Loc {
    pub method: MethodRef,
    pub pc: u64,
}

#[derive(Debug)]
enum Body {
    At { thread: u64, loc: Loc },
    Prepare { thread: u64, class: ClassId },
    Start { thread: u64 },
    Death,
}

#[derive(Debug)]
pub struct Event {
    kind: u8,
    request: i32,
    body: Body,
}

impl Event {
    pub fn breakpoint(request: i32, thread: u64, loc: Loc) -> Event {
        Event {
            kind: KIND_BREAKPOINT,
            request,
            body: Body::At { thread, loc },
        }
    }

    pub fn step(request: i32, thread: u64, loc: Loc) -> Event {
        Event {
            kind: KIND_STEP,
            request,
            body: Body::At { thread, loc },
        }
    }

    pub fn prepare(request: i32, thread: u64, class: ClassId) -> Event {
        Event {
            kind: KIND_CLASS_PREPARE,
            request,
            body: Body::Prepare { thread, class },
        }
    }

    pub fn death(request: i32) -> Event {
        Event {
            kind: KIND_VM_DEATH,
            request,
            body: Body::Death,
        }
    }
}

impl Vm {
    fn encode_events(&self, policy: u8, events: &[Event]) -> Vec<u8> {
        let mut w = Writer::default();
        w.u8(policy);
        w.len(events.len());
        for e in events {
            w.u8(e.kind);
            w.i32(e.request);
            match &e.body {
                Body::At { thread, loc } => {
                    w.id(*thread);
                    self.write_location(&mut w, loc.method, loc.pc);
                }
                Body::Prepare { thread, class } => {
                    w.id(*thread);
                    w.u8(self.type_tag(*class));
                    w.id(super::ids::type_id(*class));
                    w.string(&self.class_signature(*class));
                    w.i32(self.class_status(*class));
                }
                Body::Start { thread } => w.id(*thread),
                Body::Death => {}
            }
        }
        w.buf
    }

    /// Sends one composite event. Does not stop anything.
    pub(super) fn send_events(&mut self, policy: u8, events: &[Event]) {
        let body = self.encode_events(policy, events);
        let id = self.next_packet_id();
        let packet = wire::command_packet(id, EVENT_SET, COMPOSITE, &body);
        self.write_packet(&packet);
    }

    /// Sends the events and stops what `policy` says: the thread that hit them
    /// (1) or every thread (2). The calling thread then waits to be resumed,
    /// at once, or with `defer` at its next instruction, for callers that
    /// must not give up the VM where they are (linking a class).
    pub(super) fn post_events(&mut self, policy: u8, thread: usize, events: &[Event], defer: bool) {
        match policy {
            SUSPEND_ALL => self.debug.suspend.suspend_all(),
            SUSPEND_THREAD => self.debug.suspend.suspend_thread(thread),
            _ => {}
        }
        self.send_events(policy, events);
        if !defer {
            self.hold_if_suspended();
        } else if policy != 0 {
            self.debug.deferred.insert(thread);
            self.refresh_slow_interp();
        }
    }

    /// The greeting of a connection that arrives before the program starts.
    pub(super) fn send_vm_start(&mut self, policy: u8) {
        let main = self.threads.get(0).and_then(|e| e.java);
        let thread = self.debug.ids.of_opt(main).unwrap_or(0);
        self.send_events(
            policy,
            &[Event {
                kind: KIND_VM_START,
                request: 0,
                body: Body::Start { thread },
            }],
        );
    }

    /// The id of thread `index`'s `java.lang.Thread`, 0 if it has none.
    pub(super) fn thread_id(&mut self, index: usize) -> u64 {
        let java = self.threads.get(index).and_then(|e| e.java);
        self.debug.ids.of_opt(java).unwrap_or(0)
    }

    pub(super) fn type_tag(&self, class: ClassId) -> u8 {
        let c = self.class(class);
        if c.is_array() {
            3
        } else if c.is_interface() {
            2
        } else {
            1
        }
    }

    /// The JNI signature of a class: `Lname;`, or the array's or primitive's own.
    pub(super) fn class_signature(&self, class: ClassId) -> String {
        let c = self.class(class);
        match c.kind {
            ClassKind::Instance => format!("L{};", c.name),
            ClassKind::Array { .. } => c.name.to_string(),
            ClassKind::Primitive(d) => char::from(d).to_string(),
        }
    }

    /// The class status bits: verified, prepared, initialized, in error.
    pub(super) fn class_status(&self, class: ClassId) -> i32 {
        let c = self.class(class);
        if !matches!(c.kind, ClassKind::Instance) {
            return 2 | 4;
        }
        match c.state {
            InitState::Failed => 8,
            InitState::Initialized => 1 | 2 | 4,
            _ if c.verified => 1 | 2,
            _ => 0,
        }
    }

    /// A location as the wire has it: the type tag, class, method and index.
    pub(super) fn write_location(&self, w: &mut Writer, m: MethodRef, pc: u64) {
        w.u8(self.type_tag(m.class));
        w.id(super::ids::type_id(m.class));
        w.id(super::ids::method_id(m));
        w.i64(pc as i64);
    }
}
