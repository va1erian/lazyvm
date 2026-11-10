//! A debugger agent: JDWP over a socket (`-agentlib:jdwp=transport=dt_socket,...`).
//!
//! One OS thread, the agent (`agent.rs`), owns the connection. It blocks on
//! the socket without the VM, and takes a turn on the VM lock for each command
//! (`thread::with_vm`), so the Java threads are never stopped for it, and a
//! command sees every other thread's frames still. Threads are stopped by
//! suspend counts (`suspend.rs`): a thread checks its count whenever it gets
//! the VM back (`Vm::hold_if_suspended`) and parks until it is zero.
//!
//! Breakpoints do not patch bytecode. While any breakpoint or step exists the
//! interpreter leaves its tight loop (`Vm::slow_interp`) and `hook.rs` looks
//! at each instruction before it runs; with none, nothing here is on the
//! path of an instruction.

mod agent;
mod cmd_event;
mod cmd_object;
mod cmd_thread;
mod cmd_type;
mod cmd_vm;
mod events;
mod hook;
mod ids;
mod requests;
mod suspend;
#[cfg(test)]
mod tests;
mod values;
mod wire;

use std::sync::Arc;

pub use agent::Config;
use ids::Ids;
use requests::Requests;
use suspend::Suspend;

use crate::Vm;

/// The debugger's state inside the VM. Everything is behind the VM lock.
#[derive(Default)]
pub struct Debug {
    /// An agent was asked for (`-agentlib:jdwp`).
    pub(crate) enabled: bool,
    /// The thread index of the agent, which never runs bytecode.
    pub(crate) agent: usize,
    /// The connection's writing side, shared by the agent (replies) and the
    /// threads that stop (events).
    writer: Option<Arc<platform::tcp::Conn>>,
    ids: Ids,
    requests: Requests,
    suspend: Suspend,
    /// A command asked to end the session (`VirtualMachine.Dispose`).
    pub(crate) disconnect: bool,
    /// `VirtualMachine.Exit` asked the process to end with this status.
    pub(crate) exit: Option<i32>,
    next_packet: u32,
}

impl Debug {
    /// What the collector must keep: the objects the debugger holds.
    pub(crate) fn roots(&self, out: &mut Vec<crate::Ref>) {
        self.ids.roots(out);
    }
}

impl Vm {
    /// Whether the interpreter must check every instruction.
    pub(crate) fn refresh_slow_interp(&mut self) {
        self.slow_interp = self.options.trace_interp || self.debug.requests.needs_hooks();
    }

    /// Starts the agent for `-agentlib:jdwp`: binds or connects, prints the
    /// line IDEs wait for, and, with `suspend=y`, stops the calling thread
    /// until the debugger resumes the VM. Call it before the program starts.
    pub fn start_debugger(&mut self, config: &Config) -> Result<(), String> {
        let source = agent::open(config)?;
        // The main thread's `java.lang.Thread` is made on first use; the
        // debugger lists it from the start.
        self.ensure_main_thread().map_err(|e| e.to_string())?;
        self.debug.enabled = true;
        let me = self.add_helper_thread();
        self.debug.agent = me;
        if config.suspend {
            self.debug.suspend.suspend_all();
        }
        let started = agent::spawn(self.gil.clone(), me, config.clone(), source);
        match started {
            Ok(handle) => self.set_helper_os(me, handle.thread().clone()),
            Err(e) => {
                self.remove_helper_thread(me);
                self.debug.enabled = false;
                self.debug.suspend.clear();
                return Err(format!("cannot start the debugger thread: {e}"));
            }
        }
        self.hold_if_suspended();
        Ok(())
    }

    /// Makes the main thread's `java.lang.Thread`, which Java otherwise
    /// creates the first time it asks for the current thread.
    fn ensure_main_thread(&mut self) -> crate::VmResult<()> {
        let class = self.load_class("java/lang/Thread")?;
        self.initialize(class)?;
        let current = self.find_in_class(class, "currentThread", "()Ljava/lang/Thread;", false);
        let m = current
            .ok_or_else(|| crate::VmError::Internal("Thread.currentThread is missing".into()))?;
        self.invoke(m, &[])?;
        Ok(())
    }

    /// Sends the VM-death event and closes the connection. Called as the VM exits.
    pub fn debugger_vm_death(&mut self) {
        if !self.debug.enabled || self.debug.writer.is_none() {
            return;
        }
        let request = self
            .debug
            .requests
            .first_of(requests::VM_DEATH)
            .unwrap_or(0);
        self.send_events(0, &[events::Event::death(request)]);
        if let Some(w) = self.debug.writer.take() {
            w.finish_sending();
        }
    }

    /// A thread that is suspended waits here, outside the VM, until its count
    /// is zero. Free when no debugger is attached.
    #[inline]
    pub(crate) fn hold_if_suspended(&mut self) {
        if self.debug.enabled {
            self.hold_suspended();
        }
    }

    #[cold]
    fn hold_suspended(&mut self) {
        while self.current != self.debug.agent && self.debug.suspend.held(self.current) {
            self.park_suspended();
        }
    }

    /// Writes a packet to the debugger. A failed write means it is gone: the
    /// agent notices on its read, and the session ends there.
    pub(super) fn write_packet(&mut self, packet: &[u8]) {
        let Some(w) = &self.debug.writer else { return };
        if w.write_all(packet).is_err() {
            w.shutdown();
        }
    }

    fn next_packet_id(&mut self) -> u32 {
        self.debug.next_packet = self.debug.next_packet.wrapping_add(1);
        self.debug.next_packet
    }

    /// The debugger connected: replies and events go to `conn`.
    pub(super) fn attach_debugger(&mut self, conn: Arc<platform::tcp::Conn>) {
        self.debug.writer = Some(conn);
        self.debug.disconnect = false;
    }

    /// The debugger left, or was never going to come back: what it set up is
    /// undone and every thread it held runs again.
    pub(super) fn detach_debugger(&mut self) {
        let held: Vec<usize> = self.java_thread_indices();
        self.debug.writer = None;
        self.debug.requests.clear();
        self.debug.suspend.clear();
        self.debug.ids.clear();
        self.refresh_slow_interp();
        for i in held {
            self.wake_thread(i);
        }
    }

    /// Wakes the threads that were held before `before` and are not now.
    pub(super) fn wake_released(&mut self, before: &[(usize, bool)]) {
        for &(i, was) in before {
            if was && !self.debug.suspend.held(i) {
                self.wake_thread(i);
            }
        }
    }

    pub(super) fn held_threads(&self) -> Vec<(usize, bool)> {
        let live = self.java_thread_indices();
        live.into_iter()
            .map(|i| (i, self.debug.suspend.held(i)))
            .collect()
    }
}
