//! `ReferenceType` (2), `ClassType` (3), `Method` (6) and `ClassObjectReference`
//! (17): what the debugger asks about classes, their members and their code.

use super::ids::{class_of_type, member_id, split_member, type_id};
use super::wire::{Reader, Res, Writer, err};
use crate::Vm;
use crate::class::{ClassId, MethodRef};

impl Vm {
    /// A class id from the wire, which must name a loaded class.
    pub(super) fn class_arg(&self, r: &mut Reader) -> Res<ClassId> {
        let c = class_of_type(r.id()?)?;
        if (c.0 as usize) < self.classes.len() {
            Ok(c)
        } else {
            Err(err::INVALID_CLASS)
        }
    }

    /// A class id and a method id, which must agree and name a method.
    fn method_arg(&self, r: &mut Reader) -> Res<MethodRef> {
        let class = self.class_arg(r)?;
        let (owner, index) = split_member(r.id()?).map_err(|_| err::INVALID_METHODID)?;
        let index = u16::try_from(index).map_err(|_| err::INVALID_METHODID)?;
        let known = (owner.0 as usize) < self.classes.len()
            && usize::from(index) < self.class(owner).methods.len();
        if owner != class || !known {
            return Err(err::INVALID_METHODID);
        }
        Ok(MethodRef { class, index })
    }

    pub(super) fn cmd_reference_type(
        &mut self,
        cmd: u8,
        r: &mut Reader,
        w: &mut Writer,
    ) -> Res<()> {
        let class = self.class_arg(r)?;
        match cmd {
            1 => w.string(&self.class_signature(class)),
            13 => {
                w.string(&self.class_signature(class));
                w.string(self.generic_signature(class));
            }
            2 => w.id(0),
            3 => w.i32(i32::from(self.class(class).access)),
            4 | 14 => self.write_fields(class, w, cmd == 14),
            5 | 15 => self.write_methods(class, w, cmd == 15),
            6 => self.static_values(r, w)?,
            7 => {
                let file = self
                    .class(class)
                    .source_file()
                    .ok_or(err::ABSENT_INFORMATION)?;
                w.string(file);
            }
            8 => w.i32(0),
            9 => w.i32(self.class_status(class)),
            10 => {
                let ifaces = self.class(class).interfaces.clone();
                w.len(ifaces.len());
                ifaces.iter().for_each(|&i| w.id(type_id(i)));
            }
            11 => {
                let mirror = self.mirror(class).map_err(|_| err::OUT_OF_MEMORY)?;
                w.id(self.debug.ids.of(mirror)?);
            }
            17 => {
                let file = self
                    .class(class)
                    .file
                    .as_ref()
                    .ok_or(err::ABSENT_INFORMATION)?;
                w.i32(i32::from(file.major));
                w.i32(i32::from(file.minor));
            }
            _ => return Err(err::NOT_IMPLEMENTED),
        }
        Ok(())
    }

    fn write_fields(&self, class: ClassId, w: &mut Writer, generic: bool) {
        let fields = &self.class(class).fields;
        w.len(fields.len());
        for (i, f) in fields.iter().enumerate() {
            w.id(member_id(class, i));
            w.string(&f.name);
            w.string(&f.descriptor);
            if generic {
                w.string("");
            }
            w.i32(i32::from(f.access));
        }
    }

    fn write_methods(&self, class: ClassId, w: &mut Writer, generic: bool) {
        let methods = &self.class(class).methods;
        w.len(methods.len());
        for (i, m) in methods.iter().enumerate() {
            w.id(member_id(class, i));
            w.string(&m.name);
            w.string(&m.descriptor);
            if generic {
                w.string("");
            }
            w.i32(i32::from(m.access));
        }
    }

    fn static_values(&mut self, r: &mut Reader, w: &mut Writer) -> Res<()> {
        let n = r.count(8)?;
        w.len(n);
        for _ in 0..n {
            let (owner, index) = split_member(r.id()?).map_err(|_| err::INVALID_FIELDID)?;
            let (value, kind) = self.static_field(owner, index)?;
            self.write_value(w, value, kind, true)?;
        }
        Ok(())
    }

    /// A static field's value and the first character of its descriptor.
    fn static_field(&self, owner: ClassId, index: usize) -> Res<(crate::Value, u8)> {
        let c = self
            .classes
            .get(owner.0 as usize)
            .ok_or(err::INVALID_FIELDID)?;
        let f = c
            .fields
            .get(index)
            .filter(|f| f.is_static())
            .ok_or(err::INVALID_FIELDID)?;
        let value = c
            .statics
            .get(f.slot as usize)
            .copied()
            .ok_or(err::INVALID_FIELDID)?;
        Ok((
            value,
            f.descriptor.as_bytes().first().copied().unwrap_or(b'L'),
        ))
    }

    pub(super) fn cmd_class_type(&mut self, cmd: u8, r: &mut Reader, w: &mut Writer) -> Res<()> {
        let class = self.class_arg(r)?;
        match cmd {
            1 => w.id(self.class(class).super_class.map_or(0, type_id)),
            _ => return Err(err::NOT_IMPLEMENTED),
        }
        Ok(())
    }

    pub(super) fn cmd_method(&mut self, cmd: u8, r: &mut Reader, w: &mut Writer) -> Res<()> {
        let m = self.method_arg(r)?;
        match cmd {
            1 => self.line_table(m, w)?,
            2 | 5 => self.variable_table(m, w, cmd == 5)?,
            4 => w.bool(false),
            _ => return Err(err::NOT_IMPLEMENTED),
        }
        Ok(())
    }

    fn line_table(&self, m: MethodRef, w: &mut Writer) -> Res<()> {
        let Some(code) = self.method(m).code.clone() else {
            w.i64(-1);
            w.i64(-1);
            w.i32(0);
            return Ok(());
        };
        if code.line_numbers.is_empty() {
            return Err(err::ABSENT_INFORMATION);
        }
        w.i64(0);
        w.i64(code.bytecode.len() as i64 - 1);
        w.len(code.line_numbers.len());
        for &(pc, line) in &code.line_numbers {
            w.i64(i64::from(pc));
            w.i32(i32::from(line));
        }
        Ok(())
    }

    fn variable_table(&self, m: MethodRef, w: &mut Writer, generic: bool) -> Res<()> {
        let method = self.method(m);
        let code = method.code.clone().ok_or(err::ABSENT_INFORMATION)?;
        if code.local_vars.is_empty() {
            return Err(err::ABSENT_INFORMATION);
        }
        w.i32(i32::from(method.arg_slots));
        w.len(code.local_vars.len());
        for v in &code.local_vars {
            w.i64(i64::from(v.start_pc));
            w.string(&v.name);
            w.string(&v.descriptor);
            if generic {
                w.string("");
            }
            w.i32(i32::from(v.length));
            w.i32(i32::from(v.slot));
        }
        Ok(())
    }

    pub(super) fn cmd_class_object(&mut self, cmd: u8, r: &mut Reader, w: &mut Writer) -> Res<()> {
        if cmd != 1 {
            return Err(err::NOT_IMPLEMENTED);
        }
        let id = r.id()?;
        let mirror = self.debug.ids.get(id)?;
        let class = self
            .class_of_mirror(mirror)
            .map_err(|_| err::INVALID_OBJECT)?;
        w.u8(self.type_tag(class));
        w.id(type_id(class));
        Ok(())
    }
}
