//! `ThreadReference` (11) and `ThreadGroupReference` (12).

use super::ids::frame_id;
use super::wire::{Reader, Res, Writer, err};
use crate::Vm;
use crate::heap::Array;
use crate::thread::Blocked;
use crate::value::{Ref, Value};

const STATUS_RUNNING: i32 = 1;
const STATUS_SLEEPING: i32 = 2;
const STATUS_MONITOR: i32 = 3;
const STATUS_WAIT: i32 = 4;
const SUSPENDED: i32 = 1;
const STRING: &str = "Ljava/lang/String;";
const GROUP: &str = "Ljava/lang/ThreadGroup;";

impl Vm {
    /// The index of the live thread a thread id names.
    pub(super) fn thread_index_arg(&self, r: &mut Reader) -> Res<usize> {
        let t = self
            .debug
            .ids
            .get(r.id()?)
            .map_err(|_| err::INVALID_THREAD)?;
        self.threads.find(t).ok_or(err::THREAD_NOT_ALIVE)
    }

    pub(super) fn cmd_thread(&mut self, cmd: u8, r: &mut Reader, w: &mut Writer) -> Res<()> {
        let id = r.id()?;
        let obj = self.debug.ids.get(id).map_err(|_| err::INVALID_THREAD)?;
        let index = self.threads.find(obj).ok_or(err::THREAD_NOT_ALIVE)?;
        match cmd {
            1 => w.string(&self.text_field(obj, "name")?),
            2 => self.debug.suspend.suspend_thread(index),
            3 => {
                let before = self.held_threads();
                let live = self.java_thread_indices();
                self.debug.suspend.resume_thread(index, &live);
                self.wake_released(&before);
            }
            4 => {
                w.i32(self.thread_status(index));
                w.i32(if self.debug.suspend.held(index) {
                    SUSPENDED
                } else {
                    0
                });
            }
            5 => {
                let group = self.get_ref_field(obj, "group", GROUP)?;
                w.id(self.debug.ids.of_opt(group)?);
            }
            6 => self.thread_frames_reply(index, r, w)?,
            7 => {
                self.require_suspended(index)?;
                w.len(self.frames_of_thread(index).map_or(0, <[_]>::len));
            }
            12 => w.i32(i32::try_from(self.debug.suspend.count(index)).unwrap_or(i32::MAX)),
            _ => return Err(err::NOT_IMPLEMENTED),
        }
        Ok(())
    }

    fn require_suspended(&self, index: usize) -> Res<()> {
        if self.debug.suspend.held(index) {
            Ok(())
        } else {
            Err(err::THREAD_NOT_SUSPENDED)
        }
    }

    fn thread_status(&self, index: usize) -> i32 {
        match self.thread_blocked(index) {
            Some(Blocked::Monitor) => STATUS_MONITOR,
            Some(Blocked::Waiting) => STATUS_WAIT,
            Some(Blocked::TimedWaiting) => STATUS_SLEEPING,
            _ => STATUS_RUNNING,
        }
    }

    /// `Frames`: from the innermost, `start` frames in and `length` of them
    /// (-1 for all that are left).
    fn thread_frames_reply(&mut self, index: usize, r: &mut Reader, w: &mut Writer) -> Res<()> {
        self.require_suspended(index)?;
        let start = usize::try_from(r.i32()?).map_err(|_| err::INVALID_INDEX)?;
        let length = r.i32()?;
        let frames = self.frames_of_thread(index).ok_or(err::INVALID_THREAD)?;
        let depth = frames.len();
        if start > depth {
            return Err(err::INVALID_INDEX);
        }
        let left = depth - start;
        let take = match length {
            -1 => left,
            n if n >= 0 => (n as usize).min(left),
            _ => return Err(err::INVALID_LENGTH),
        };
        w.len(take);
        for k in start..start + take {
            let bottom = depth - 1 - k;
            let f = &frames[bottom];
            w.id(frame_id(index, bottom));
            self.write_location(w, f.method, f.pc as u64);
        }
        Ok(())
    }

    pub(super) fn cmd_thread_group(&mut self, cmd: u8, r: &mut Reader, w: &mut Writer) -> Res<()> {
        let group = self.debug.ids.get(r.id()?)?;
        match cmd {
            1 => w.string(&self.text_field(group, "name")?),
            2 => {
                let parent = self.get_ref_field(group, "parent", GROUP)?;
                w.id(self.debug.ids.of_opt(parent)?);
            }
            3 => {
                let threads =
                    self.group_members(group, "threads", "threadCount", "[Ljava/lang/Thread;")?;
                let groups =
                    self.group_members(group, "groups", "groupCount", "[Ljava/lang/ThreadGroup;")?;
                for list in [threads, groups] {
                    w.len(list.len());
                    for t in list {
                        w.id(self.debug.ids.of(t)?);
                    }
                }
            }
            _ => return Err(err::NOT_IMPLEMENTED),
        }
        Ok(())
    }

    /// The first `count` entries of a group's array of threads or groups.
    fn group_members(&self, group: Ref, array: &str, count: &str, desc: &str) -> Res<Vec<Ref>> {
        let n = match self.field_by_name(group, count, "I") {
            Ok(Value::Int(n)) => usize::try_from(n).unwrap_or(0),
            _ => return Err(err::INVALID_OBJECT),
        };
        let Some(arr) = self.get_ref_field(group, array, desc)? else {
            return Ok(Vec::new());
        };
        let Ok(Array::Ref(items)) = self.heap.array(arr) else {
            return Ok(Vec::new());
        };
        Ok(items.iter().take(n).flatten().copied().collect())
    }

    fn get_ref_field(&self, obj: Ref, name: &str, desc: &str) -> Res<Option<Ref>> {
        match self.field_by_name(obj, name, desc) {
            Ok(Value::Ref(r)) => Ok(r),
            _ => Err(err::INVALID_OBJECT),
        }
    }

    fn text_field(&self, obj: Ref, name: &str) -> Res<String> {
        let s = self.get_ref_field(obj, name, STRING)?;
        match s {
            Some(s) => self.string_to_rust(s).map_err(|_| err::INVALID_OBJECT),
            None => Ok(String::new()),
        }
    }
}
