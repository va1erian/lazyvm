//! The command dispatcher and the `VirtualMachine` command set (1).

use super::ids::type_id;
use super::wire::{Reader, Res, Writer, err};
use crate::Vm;
use crate::class::{ClassId, ClassKind};

/// The JDWP version this agent speaks (1.8, like the Java 8 it runs the bytecode of).
const JDWP_MAJOR: i32 = 1;
const JDWP_MINOR: i32 = 8;

impl Vm {
    pub(super) fn dispatch(&mut self, set: u8, cmd: u8, r: &mut Reader, w: &mut Writer) -> Res<()> {
        match set {
            1 => self.cmd_vm(cmd, r, w),
            2 => self.cmd_reference_type(cmd, r, w),
            3 => self.cmd_class_type(cmd, r, w),
            6 => self.cmd_method(cmd, r, w),
            9 => self.cmd_object(cmd, r, w),
            10 => self.cmd_string(cmd, r, w),
            11 => self.cmd_thread(cmd, r, w),
            12 => self.cmd_thread_group(cmd, r, w),
            13 => self.cmd_array(cmd, r, w),
            15 => self.cmd_event_request(cmd, r, w),
            16 => self.cmd_frame(cmd, r, w),
            17 => self.cmd_class_object(cmd, r, w),
            _ => Err(err::NOT_IMPLEMENTED),
        }
    }

    fn cmd_vm(&mut self, cmd: u8, r: &mut Reader, w: &mut Writer) -> Res<()> {
        match cmd {
            1 => {
                w.string("LazyVM debugger agent, JDWP version 1.8");
                w.i32(JDWP_MAJOR);
                w.i32(JDWP_MINOR);
                w.string("1.8.0");
                w.string("LazyVM");
            }
            2 => self.classes_by_signature(&r.string()?, w),
            3 => self.all_classes(w, false),
            20 => self.all_classes(w, true),
            4 => {
                let threads = self.java_thread_indices();
                w.len(threads.len());
                for t in threads {
                    let id = self.thread_id(t);
                    w.id(id);
                }
            }
            5 => {
                let group = self.system_group()?;
                w.i32(1);
                w.id(group);
            }
            6 => self.debug.disconnect = true,
            7 => (0..5).for_each(|_| w.i32(8)),
            8 => self.debug.suspend.suspend_all(),
            9 => {
                let before = self.held_threads();
                self.debug.suspend.resume_all();
                self.wake_released(&before);
            }
            10 => self.debug.exit = Some(r.i32()?),
            11 => {
                let text = r.string()?;
                let s = self.new_string(&text).map_err(|_| err::OUT_OF_MEMORY)?;
                w.id(self.debug.ids.of(s)?);
            }
            12 => capabilities(w, 7),
            17 => capabilities(w, 32),
            13 => {
                let dir = std::env::current_dir().map(|d| d.display().to_string());
                w.string(&dir.unwrap_or_default());
                w.i32(0);
                w.i32(0);
            }
            14 => self.dispose_objects(r)?,
            // Events are never queued here, so holding and releasing change nothing.
            15 | 16 => {}
            _ => return Err(err::NOT_IMPLEMENTED),
        }
        Ok(())
    }

    fn classes_by_signature(&self, signature: &str, w: &mut Writer) {
        let name = match signature
            .strip_prefix('L')
            .and_then(|s| s.strip_suffix(';'))
        {
            Some(inner) => inner,
            None => signature,
        };
        let found = self.loaded_class(name).filter(|&c| !self.is_primitive(c));
        w.len(usize::from(found.is_some()));
        if let Some(c) = found {
            w.u8(self.type_tag(c));
            w.id(type_id(c));
            w.i32(self.class_status(c));
        }
    }

    fn is_primitive(&self, c: ClassId) -> bool {
        matches!(self.class(c).kind, ClassKind::Primitive(_))
    }

    fn all_classes(&self, w: &mut Writer, generic: bool) {
        let ids: Vec<ClassId> = (0..self.classes.len() as u32)
            .map(ClassId)
            .filter(|&c| !self.is_primitive(c))
            .collect();
        w.len(ids.len());
        for c in ids {
            w.u8(self.type_tag(c));
            w.id(type_id(c));
            w.string(&self.class_signature(c));
            if generic {
                w.string(self.generic_signature(c));
            }
            w.i32(self.class_status(c));
        }
    }

    pub(super) fn generic_signature(&self, c: ClassId) -> &str {
        self.class(c)
            .file
            .as_ref()
            .and_then(|f| f.signature.as_deref())
            .unwrap_or("")
    }

    fn dispose_objects(&mut self, r: &mut Reader) -> Res<()> {
        for _ in 0..r.count(12)? {
            let id = r.id()?;
            let count = u32::try_from(r.i32()?).unwrap_or(0);
            self.debug.ids.dispose(id, count);
        }
        Ok(())
    }

    /// The root thread group, `Thread.SYSTEM_GROUP`.
    pub(super) fn system_group(&mut self) -> Res<u64> {
        let class = self.loaded_class("java/lang/Thread").ok_or(err::INTERNAL)?;
        let c = self.class(class);
        let slot = c
            .find_field("SYSTEM_GROUP", "Ljava/lang/ThreadGroup;")
            .map(|f| f.slot as usize)
            .ok_or(err::INTERNAL)?;
        let group = c
            .statics
            .get(slot)
            .copied()
            .and_then(|v| v.reference().ok());
        self.debug.ids.of_opt(group.flatten())
    }
}

/// The capability booleans: only what this agent does is set.
fn capabilities(w: &mut Writer, count: usize) {
    // canGetSyntheticAttribute, canUseInstanceFilters, canRequestVMDeathEvent.
    const ON: [usize; 3] = [3, 11, 13];
    for i in 0..count {
        w.bool(ON.contains(&i));
    }
}
