//! Type rules of the instructions that name constants: `ldc`, fields, calls,
//! object and array creation, casts, and object initialization (JVMS 4.10.1.9)
//! with the protected check (JVMS 4.10.1.8).

use classfile::descriptor::{MethodDescriptor, parse_field, parse_method};
use classfile::{ACC_PROTECTED, Constant, MemberRef};

use super::check::{Checker, declared_access};
use super::structural::member_text;
use super::vtype::{OBJECT, VType, array_of};
use super::{Fail, Hierarchy, decode};
use crate::class::package_of;

impl<'a, H: Hierarchy + ?Sized> Checker<'a, '_, '_, H> {
    pub(super) fn ref_op(&mut self, op: u8) -> Result<(), Fail> {
        match op {
            0x12 => {
                let index = u16::from(
                    decode::u1(&self.code.bytecode, self.pc + 1).map_err(|e| self.bad(e))?,
                );
                self.ldc(index)
            }
            0x13 | 0x14 => self.ldc(self.u2_at(1)?),
            0xb2..=0xb5 => self.field(op),
            0xb6..=0xb9 => self.invoke(op),
            0xba => self.invokedynamic(),
            0xbb => self.new_object(),
            0xbd => {
                self.pop_as(VType::Int)?;
                let name = self.pool_class(1)?;
                let t = self.types.class(&array_of(&name));
                self.push(t)
            }
            0xc0 => {
                let object = self.types.class(OBJECT);
                self.pop_as(object)?;
                let name = self.pool_class(1)?;
                let t = self.types.class(&name);
                self.push(t)
            }
            0xc1 => {
                let object = self.types.class(OBJECT);
                self.pop_as(object)?;
                self.push(VType::Int)
            }
            _ => self.multianewarray(),
        }
    }

    fn pool_class(&mut self, operand: usize) -> Result<std::sync::Arc<str>, Fail> {
        let index = self.u2_at(operand)?;
        let name = self
            .cf
            .pool
            .class_name(index)
            .cloned()
            .map_err(|e| self.bad(e))?;
        self.budget.charge(name.len())?;
        Ok(name)
    }

    fn ldc(&mut self, index: u16) -> Result<(), Fail> {
        let t = match self.cf.pool.get(index) {
            Some(Constant::Integer(_)) => VType::Int,
            Some(Constant::Float(_)) => VType::Float,
            Some(Constant::Long(_)) => VType::Long,
            Some(Constant::Double(_)) => VType::Double,
            Some(Constant::String(_)) => self.types.class("java/lang/String"),
            Some(Constant::Class(_)) => self.types.class("java/lang/Class"),
            Some(Constant::MethodType(_)) => self.types.class("java/lang/invoke/MethodType"),
            Some(Constant::MethodHandle { .. }) => {
                self.types.class("java/lang/invoke/MethodHandle")
            }
            other => return Err(self.bad(format!("ldc of {other:?}"))),
        };
        self.push(t)
    }

    fn member(&mut self, index: u16) -> Result<MemberRef, Fail> {
        self.budget.charge(member_text(self.cf, index))?;
        self.cf.pool.member_ref(index).map_err(|e| self.bad(e))
    }

    fn field(&mut self, op: u8) -> Result<(), Fail> {
        let r = self.member(self.u2_at(1)?)?;
        let ft = parse_field(&r.descriptor).map_err(|e| self.bad(e))?;
        let t = self.types.of_field(&ft);
        let owner = self.types.class(&r.class);
        match op {
            0xb2 => self.push(t),
            0xb3 => self.pop_as(t).map(|_| ()),
            0xb4 => {
                self.check_protected(&r, false)?;
                self.pop_as(owner)?;
                self.push(t)
            }
            _ => {
                self.pop_as(t)?;
                if self.may_store_before_super(&r)? {
                    self.pop()?;
                    return Ok(());
                }
                self.check_protected(&r, false)?;
                self.pop_as(owner).map(|_| ())
            }
        }
    }

    /// A constructor may set fields its own class declares before calling
    /// `super()`, as javac does for captured variables (observed on the oracle:
    /// `tests/verify/good/PutfieldBeforeSuper`).
    fn may_store_before_super(&mut self, r: &MemberRef) -> Result<bool, Fail> {
        if !self.is_init
            || self.cur.stack.last() != Some(&VType::UninitThis)
            || r.class != self.cf.this_class
        {
            return Ok(false);
        }
        self.budget.charge(self.cf.fields.len())?;
        let fields = &self.cf.fields;
        Ok(fields
            .iter()
            .any(|f| f.name == r.name && f.descriptor == r.descriptor))
    }

    fn pop_args(&mut self, md: &MethodDescriptor) -> Result<(), Fail> {
        for p in md.params.iter().rev() {
            let t = self.types.of_field(p);
            self.pop_as(t)?;
        }
        Ok(())
    }

    fn push_result(&mut self, md: &MethodDescriptor) -> Result<(), Fail> {
        match &md.ret {
            Some(t) => {
                let t = self.types.of_field(t);
                self.push(t)
            }
            None => Ok(()),
        }
    }

    fn invoke(&mut self, op: u8) -> Result<(), Fail> {
        let r = self.member(self.u2_at(1)?)?;
        let md = parse_method(&r.descriptor).map_err(|e| self.bad(e))?;
        self.pop_args(&md)?;
        let owner = self.types.class(&r.class);
        match op {
            0xb6 => {
                self.check_protected(&r, true)?;
                self.pop_as(owner)?;
            }
            0xb7 if &*r.name == "<init>" => return self.invoke_init(&r),
            0xb7 => self.invoke_special_receiver(&r)?,
            0xb8 => {}
            _ => {
                self.pop_as(owner)?;
            }
        }
        self.push_result(&md)
    }

    /// The class whose rights the code has: a generated lambda class has its
    /// host's (`Class::host`), here as at resolution.
    fn current(&self) -> &'a str {
        self.host.unwrap_or(&self.cf.this_class)
    }

    /// `invokespecial` of a method other than `<init>` names the current class,
    /// a superclass or a superinterface, on a receiver of the current class.
    /// A generated lambda class stands for its host.
    fn invoke_special_receiver(&mut self, r: &MemberRef) -> Result<(), Fail> {
        let current = self.current();
        let index = self.u2_at(1)?;
        let names_interface = matches!(
            self.cf.pool.get(index),
            Some(Constant::InterfaceMethodRef { .. })
        );
        self.budget
            .charge(usize::from(names_interface) * self.cf.interfaces.len())?;
        // Observed on the oracle; a class's own interface methods stay callable.
        // A generated class's own interfaces say nothing about its host's, so it
        // may only reach the host's own methods this way.
        let direct = self.host.is_none() && self.cf.interfaces.contains(&r.class);
        if names_interface && *r.class != *current && !direct {
            return Err(self.bad(format!(
                "invokespecial of {}, an indirect superinterface",
                r.class
            )));
        }
        let (this, owner) = (self.types.class(current), self.types.class(&r.class));
        if !self.types.assignable(this, owner)? {
            return Err(self.bad(format!(
                "invokespecial of {}, not {current} or a supertype",
                r.class
            )));
        }
        self.pop_as(this)?;
        Ok(())
    }

    /// `invokespecial <init>`: the object becomes initialized everywhere it is
    /// held. `this` may call its own class's or its direct superclass's
    /// constructor; a `new` object only its own class's.
    fn invoke_init(&mut self, r: &MemberRef) -> Result<(), Fail> {
        let target = self.pop()?;
        match target {
            VType::UninitThis => {
                let own = r.class == self.cf.this_class;
                if !own && self.cf.super_class.as_ref() != Some(&r.class) {
                    return Err(self.bad(format!(
                        "this calls the constructor of {}, not its class or superclass",
                        r.class
                    )));
                }
                self.cur.this_uninit = false;
            }
            VType::Uninit(at) => {
                let index = decode::u2(&self.code.bytecode, usize::from(at) + 1)
                    .map_err(|e| self.bad(e))?;
                let made = self.cf.pool.class_name(index).map_err(|e| self.bad(e))?;
                if **made != *r.class {
                    return Err(self.bad(format!(
                        "new of {made} calls the constructor of {}",
                        r.class
                    )));
                }
            }
            other => {
                let have = self.show(other);
                return Err(self.bad(format!("<init> called on {have}")));
            }
        }
        // `this` is of the current class whichever constructor it called.
        let init = if target == VType::UninitThis {
            self.types.class(&self.cf.this_class)
        } else {
            self.types.class(&r.class)
        };
        self.locals_version += 1;
        self.budget
            .charge(self.cur.locals.len() + self.cur.stack.len())?;
        for t in self.cur.locals.iter_mut().chain(self.cur.stack.iter_mut()) {
            if *t == target {
                *t = init;
            }
        }
        let flag = target == VType::UninitThis || self.cur.this_uninit;
        self.check_handlers_after_init(flag)?;
        if target == VType::UninitThis {
            return Ok(());
        }
        self.check_protected_init(r, init)
    }

    fn invokedynamic(&mut self) -> Result<(), Fail> {
        let index = self.u2_at(1)?;
        let Some(Constant::InvokeDynamic { name_and_type, .. }) = self.cf.pool.get(index) else {
            return Err(self.bad("invokedynamic names no call site"));
        };
        let (_, desc) = self
            .cf
            .pool
            .name_and_type(*name_and_type)
            .map_err(|e| self.bad(e))?;
        self.budget.charge(desc.len())?;
        let md = parse_method(desc).map_err(|e| self.bad(e))?;
        self.pop_args(&md)?;
        self.push_result(&md)
    }

    /// JVMS 4.10.1.9 `new`: no value on the stack may already stand for this
    /// `new`, and a stale copy in a local dies, or two objects would share a type.
    fn new_object(&mut self) -> Result<(), Fail> {
        let t = VType::Uninit(self.pc as u16);
        self.budget
            .charge(self.cur.locals.len() + self.cur.stack.len())?;
        if self.cur.stack.contains(&t) {
            return Err(self.bad("uninitialized object of this new is still on the stack"));
        }
        for l in &mut self.cur.locals {
            if *l == t {
                *l = VType::Top;
                self.locals_version += 1;
            }
        }
        self.push(t)
    }

    fn multianewarray(&mut self) -> Result<(), Fail> {
        let dims = decode::u1(&self.code.bytecode, self.pc + 3).map_err(|e| self.bad(e))?;
        for _ in 0..dims {
            self.pop_as(VType::Int)?;
        }
        let name = self.pool_class(1)?;
        let t = self.types.class(&name);
        self.push(t)
    }

    /// JVMS 4.10.1.8 with the receiver on top of the stack.
    fn check_protected(&mut self, r: &MemberRef, method: bool) -> Result<(), Fail> {
        if !self.protected_applies(&r.class, &r.name, &r.descriptor, method)? {
            return Ok(());
        }
        let target = self
            .cur
            .stack
            .last()
            .copied()
            .ok_or_else(|| self.bad("operand stack underflow"))?;
        // Observed: an array's `clone` is public, even named through `Object`.
        if &*r.name == "clone" && &*r.class == OBJECT && self.is_array_or_null(target) {
            return Ok(());
        }
        self.require_current(target, r)
    }

    /// The protected check on a `new` object's constructor. The object itself is
    /// judged (observed): JVMS 4.10.1.8's top of the stack may be something else.
    fn check_protected_init(&mut self, r: &MemberRef, init: VType) -> Result<(), Fail> {
        if self.protected_applies(&r.class, &r.name, &r.descriptor, true)? {
            self.require_current(init, r)?;
        }
        Ok(())
    }

    fn require_current(&mut self, target: VType, r: &MemberRef) -> Result<(), Fail> {
        let this = self.types.class(self.current());
        if !self.types.assignable(target, this)? {
            let have = self.show(target);
            return Err(self.bad(format!(
                "protected {}.{} used on {have}, not on this class",
                r.class, r.name
            )));
        }
        Ok(())
    }

    /// Whether `class` is a superclass in another run-time package that
    /// declares the member as protected.
    fn protected_applies(
        &mut self,
        class: &str,
        name: &str,
        descriptor: &str,
        method: bool,
    ) -> Result<bool, Fail> {
        let this = self.current();
        if class.starts_with('[') || class == this || !self.types.is_superclass(this, class)? {
            return Ok(false);
        }
        // The member is found from the named class up (observed: JVMS 4.10.1.8
        // looks at the named class alone, which a subclass in between would hide).
        let boot = self.types.info(this)?.boot;
        let mut cur: Option<std::sync::Arc<str>> = Some(class.into());
        while let Some(c) = cur {
            let info = self.types.info(&c)?;
            let wanted = (name, descriptor, method);
            if let Some(a) = declared_access(self.types, self.budget, &c, wanted)? {
                let other_package = info.boot != boot || package_of(this) != package_of(&c);
                return Ok(a & ACC_PROTECTED != 0 && other_package);
            }
            if &*c == OBJECT {
                break;
            }
            cur = info.super_name;
        }
        Ok(false)
    }
}
