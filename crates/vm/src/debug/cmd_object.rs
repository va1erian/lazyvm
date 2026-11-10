//! `ObjectReference` (9), `StringReference` (10), `ArrayReference` (13) and
//! `StackFrame` (16): looking at values. Nothing here runs Java code.

use super::ids::{split_frame, split_member, type_id};
use super::wire::{Reader, Res, Writer, err};
use crate::Vm;
use crate::class::ClassKind;
use crate::value::{Ref, Value};

/// The most elements one `ArrayReference.GetValues` reply carries.
const MAX_ARRAY_REGION: usize = 1 << 20;

impl Vm {
    fn object_arg(&self, r: &mut Reader) -> Res<Ref> {
        self.debug.ids.get(r.id()?)
    }

    pub(super) fn cmd_object(&mut self, cmd: u8, r: &mut Reader, w: &mut Writer) -> Res<()> {
        let obj = self.object_arg(r)?;
        match cmd {
            1 => {
                let class = self.heap.class_of(obj).map_err(|_| err::INVALID_OBJECT)?;
                w.u8(self.type_tag(class));
                w.id(type_id(class));
            }
            2 => self.object_values(obj, r, w)?,
            // The id keeps its object alive until it is disposed of.
            7 | 8 => {}
            9 => w.bool(false),
            _ => return Err(err::NOT_IMPLEMENTED),
        }
        Ok(())
    }

    fn object_values(&mut self, obj: Ref, r: &mut Reader, w: &mut Writer) -> Res<()> {
        let class = self.heap.class_of(obj).map_err(|_| err::INVALID_OBJECT)?;
        let n = r.count(8)?;
        w.len(n);
        for _ in 0..n {
            let (owner, index) = split_member(r.id()?).map_err(|_| err::INVALID_FIELDID)?;
            let field = self
                .classes
                .get(owner.0 as usize)
                .and_then(|c| c.fields.get(index))
                .filter(|f| !f.is_static())
                .ok_or(err::INVALID_FIELDID)?;
            let (slot, kind) = (field.slot, field.descriptor.as_bytes()[0]);
            if !self.is_subclass(class, owner) {
                return Err(err::INVALID_FIELDID);
            }
            let value = self.get_field(obj, slot).map_err(|_| err::INVALID_OBJECT)?;
            self.write_value(w, value, kind, true)?;
        }
        Ok(())
    }

    pub(super) fn cmd_string(&mut self, cmd: u8, r: &mut Reader, w: &mut Writer) -> Res<()> {
        if cmd != 1 {
            return Err(err::NOT_IMPLEMENTED);
        }
        let s = self.object_arg(r)?;
        if self.heap.class_of(s).ok() != self.wk.string {
            return Err(err::INVALID_OBJECT);
        }
        let units = self.string_utf16(s).map_err(|_| err::INVALID_OBJECT)?;
        w.string(&String::from_utf16_lossy(&units));
        Ok(())
    }

    pub(super) fn cmd_array(&mut self, cmd: u8, r: &mut Reader, w: &mut Writer) -> Res<()> {
        let array = self.object_arg(r)?;
        let len = self
            .heap
            .array(array)
            .map_err(|_| err::INVALID_ARRAY)?
            .len();
        match cmd {
            1 => w.len(len),
            2 => {
                let first = usize::try_from(r.i32()?).map_err(|_| err::INVALID_INDEX)?;
                let count = usize::try_from(r.i32()?).map_err(|_| err::INVALID_LENGTH)?;
                let end = first.checked_add(count).filter(|&e| e <= len);
                if end.is_none() || count > MAX_ARRAY_REGION {
                    return Err(err::INVALID_LENGTH);
                }
                self.array_region(array, first, count, w)?;
            }
            _ => return Err(err::NOT_IMPLEMENTED),
        }
        Ok(())
    }

    fn array_region(&mut self, array: Ref, first: usize, count: usize, w: &mut Writer) -> Res<()> {
        let class = self.heap.class_of(array).map_err(|_| err::INVALID_ARRAY)?;
        let ClassKind::Array { element, .. } = self.class(class).kind else {
            return Err(err::INVALID_ARRAY);
        };
        let object = matches!(element, b'L' | b'[');
        let values: Vec<Value> = {
            let a = self.heap.array(array).map_err(|_| err::INVALID_ARRAY)?;
            (first..first + count)
                .map(|i| a.load(i).unwrap_or(Value::Int(0)))
                .collect()
        };
        w.u8(element);
        w.len(count);
        for v in values {
            self.write_value(w, v, element, object)?;
        }
        Ok(())
    }

    /// The frame a command names: the thread must be suspended, and the frame
    /// must be one of its own. Returns the thread's index and the frame's
    /// index from the bottom of its stack.
    fn frame_arg(&self, r: &mut Reader) -> Res<(usize, usize)> {
        let thread = self.thread_index_arg(r)?;
        let (owner, depth) = split_frame(r.id()?)?;
        if !self.debug.suspend.held(thread) {
            return Err(err::THREAD_NOT_SUSPENDED);
        }
        let frames = self.frames_of_thread(thread).ok_or(err::INVALID_THREAD)?;
        if owner != thread || depth >= frames.len() {
            return Err(err::INVALID_FRAMEID);
        }
        Ok((thread, depth))
    }

    pub(super) fn cmd_frame(&mut self, cmd: u8, r: &mut Reader, w: &mut Writer) -> Res<()> {
        match cmd {
            1 => {
                let (thread, depth) = self.frame_arg(r)?;
                self.frame_values(thread, depth, r, w)
            }
            3 => {
                let (thread, depth) = self.frame_arg(r)?;
                let frame = self.frames_of_thread(thread).and_then(|f| f.get(depth));
                let this = frame
                    .filter(|f| !self.method(f.method).is_static())
                    .map(|f| f.locals.first().copied());
                let this = match this.flatten() {
                    Some(Value::Ref(r)) => r,
                    _ => None,
                };
                self.write_object(w, this)
            }
            _ => Err(err::NOT_IMPLEMENTED),
        }
    }

    fn frame_values(
        &mut self,
        thread: usize,
        depth: usize,
        r: &mut Reader,
        w: &mut Writer,
    ) -> Res<()> {
        let n = r.count(5)?;
        let mut wanted = Vec::with_capacity(n);
        for _ in 0..n {
            let slot = usize::try_from(r.i32()?).map_err(|_| err::INVALID_SLOT)?;
            wanted.push((slot, r.u8()?));
        }
        let frame = self
            .frames_of_thread(thread)
            .and_then(|f| f.get(depth))
            .ok_or(err::INVALID_FRAMEID)?;
        let mut values = Vec::with_capacity(n);
        for &(slot, kind) in &wanted {
            let v = frame.locals.get(slot).copied().ok_or(err::INVALID_SLOT)?;
            values.push((v, kind));
        }
        w.len(n);
        for (v, kind) in values {
            self.write_value(w, v, kind, true)
                .map_err(|_| err::INVALID_SLOT)?;
        }
        Ok(())
    }
}
