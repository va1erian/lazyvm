//! Class file parsing (JVMS chapter 4).
//!
//! [`ClassFile::parse`] reads a whole class file and checks everything that
//! can be checked without loading other classes: the format, the versions,
//! the constant pool's internal references, member names and descriptors and
//! the `Code` attribute's layout. Malformed input is a [`FormatError`] with
//! the byte offset; it never panics.

mod attr;
mod cpool;
pub mod descriptor;
pub mod jar;
mod reader;

use std::fmt;
use std::sync::Arc;

pub use attr::{BootstrapMethod, Code, Handler, RawAttribute};
pub use cpool::{Constant, ConstantPool, MemberRef, decode_mutf8, decode_mutf8_utf16};
use reader::Reader;

pub const ACC_PUBLIC: u16 = 0x0001;
pub const ACC_PRIVATE: u16 = 0x0002;
pub const ACC_PROTECTED: u16 = 0x0004;
pub const ACC_STATIC: u16 = 0x0008;
pub const ACC_FINAL: u16 = 0x0010;
pub const ACC_SUPER: u16 = 0x0020;
pub const ACC_SYNCHRONIZED: u16 = 0x0020;
pub const ACC_VOLATILE: u16 = 0x0040;
pub const ACC_BRIDGE: u16 = 0x0040;
pub const ACC_VARARGS: u16 = 0x0080;
pub const ACC_NATIVE: u16 = 0x0100;
pub const ACC_INTERFACE: u16 = 0x0200;
pub const ACC_ABSTRACT: u16 = 0x0400;
pub const ACC_STRICT: u16 = 0x0800;
pub const ACC_SYNTHETIC: u16 = 0x1000;
pub const ACC_ANNOTATION: u16 = 0x2000;
pub const ACC_ENUM: u16 = 0x4000;

/// The newest class file version accepted: Java 8.
pub const MAX_MAJOR: u16 = 52;
pub const MIN_MAJOR: u16 = 45;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FormatError {
    pub offset: usize,
    pub message: String,
}

impl FormatError {
    pub fn new(offset: usize, message: impl Into<String>) -> Self {
        FormatError {
            offset,
            message: message.into(),
        }
    }
}

impl fmt::Display for FormatError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} (at byte {})", self.message, self.offset)
    }
}

impl std::error::Error for FormatError {}

/// A field or method (JVMS 4.5, 4.6).
#[derive(Clone, Debug)]
pub struct Member {
    pub access: u16,
    pub name: Arc<str>,
    pub descriptor: Arc<str>,
    /// Methods only: the `Code` attribute.
    pub code: Option<Code>,
    /// Fields only: the `ConstantValue` pool index.
    pub constant_value: Option<u16>,
    /// Methods only: the `Exceptions` attribute's class indices.
    pub exceptions: Vec<u16>,
    pub signature: Option<Arc<str>>,
    pub attributes: Vec<RawAttribute>,
}

#[derive(Clone, Debug)]
pub struct ClassFile {
    pub minor: u16,
    pub major: u16,
    pub pool: ConstantPool,
    pub access: u16,
    pub this_class: Arc<str>,
    /// `None` only for `java/lang/Object`.
    pub super_class: Option<Arc<str>>,
    pub interfaces: Vec<Arc<str>>,
    pub fields: Vec<Member>,
    pub methods: Vec<Member>,
    pub source_file: Option<Arc<str>>,
    pub signature: Option<Arc<str>>,
    pub bootstrap_methods: Vec<BootstrapMethod>,
    pub attributes: Vec<RawAttribute>,
}

impl ClassFile {
    pub fn parse(data: &[u8]) -> Result<ClassFile, FormatError> {
        let mut r = Reader::new(data);
        if r.u4()? != 0xCAFE_BABE {
            return Err(FormatError::new(0, "not a class file (bad magic)"));
        }
        let minor = r.u2()?;
        let major = r.u2()?;
        if !(MIN_MAJOR..=MAX_MAJOR).contains(&major) {
            return Err(FormatError::new(
                6,
                format!("unsupported class file version {major}.{minor} (LazyVM runs 45 to 52)"),
            ));
        }
        let pool = ConstantPool::parse(&mut r)?;
        let access = r.u2()?;
        let this_index = r.u2()?;
        let this_class = class_at(&r, &pool, this_index)?;
        let super_index = r.u2()?;
        let super_class = match super_index {
            0 if &*this_class == "java/lang/Object" => None,
            0 => return Err(r.error("only java/lang/Object may have no superclass")),
            i => Some(class_at(&r, &pool, i)?),
        };
        let count = r.u2()?;
        let mut interfaces = Vec::with_capacity(usize::from(count));
        for _ in 0..count {
            let i = r.u2()?;
            interfaces.push(class_at(&r, &pool, i)?);
        }
        let fields = parse_members(&mut r, &pool, false)?;
        let methods = parse_members(&mut r, &pool, true)?;
        let mut cf = ClassFile {
            minor,
            major,
            pool,
            access,
            this_class,
            super_class,
            interfaces,
            fields,
            methods,
            source_file: None,
            signature: None,
            bootstrap_methods: Vec::new(),
            attributes: Vec::new(),
        };
        cf.parse_class_attributes(&mut r)?;
        r.expect_end("the class file")?;
        cf.check_bootstrap_indices()?;
        Ok(cf)
    }

    pub fn is_interface(&self) -> bool {
        self.access & ACC_INTERFACE != 0
    }

    /// The access flags `InnerClasses` gives this class, when it is a nested
    /// class (they carry `private`, `protected` and `static`, which the class
    /// file's own flags cannot).
    pub fn inner_class_flags(&self) -> Option<u16> {
        let attr = self
            .attributes
            .iter()
            .find(|a| &*a.name == "InnerClasses")?;
        let mut r = Reader::new(&attr.data);
        let count = r.u2().ok()?;
        for _ in 0..count {
            let (inner, _outer, _name, flags) =
                (r.u2().ok()?, r.u2().ok()?, r.u2().ok()?, r.u2().ok()?);
            if self
                .pool
                .class_name(inner)
                .is_ok_and(|n| *n == self.this_class)
            {
                return Some(flags);
            }
        }
        None
    }

    pub fn method(&self, name: &str, descriptor: &str) -> Option<&Member> {
        self.methods
            .iter()
            .find(|m| &*m.name == name && &*m.descriptor == descriptor)
    }

    fn parse_class_attributes(&mut self, r: &mut Reader<'_>) -> Result<(), FormatError> {
        let count = r.u2()?;
        for _ in 0..count {
            let name = self.pool.utf8(r.u2()?).map_err(|e| r.error(e))?.clone();
            let body = r.sized()?;
            match &*name {
                "SourceFile" => {
                    let i = attr::parse_index(body, "SourceFile")?;
                    self.source_file = Some(self.pool.utf8(i).map_err(|e| r.error(e))?.clone());
                }
                "Signature" => {
                    let i = attr::parse_index(body, "Signature")?;
                    self.signature = Some(self.pool.utf8(i).map_err(|e| r.error(e))?.clone());
                }
                "BootstrapMethods" => {
                    self.bootstrap_methods = attr::parse_bootstrap_methods(body)?;
                }
                _ => self.attributes.push(RawAttribute {
                    name,
                    data: body.into(),
                }),
            }
        }
        Ok(())
    }

    /// JVMS 4.4.10: every `InvokeDynamic` names an existing bootstrap method.
    fn check_bootstrap_indices(&self) -> Result<(), FormatError> {
        for i in 0..self.pool.len() {
            if let Some(Constant::InvokeDynamic { bootstrap, .. }) = self.pool.get(i as u16)
                && usize::from(*bootstrap) >= self.bootstrap_methods.len()
            {
                return Err(FormatError::new(
                    0,
                    format!("constant {i}: no bootstrap method {bootstrap}"),
                ));
            }
        }
        for bsm in &self.bootstrap_methods {
            if !matches!(
                self.pool.get(bsm.method_ref),
                Some(Constant::MethodHandle { .. })
            ) {
                return Err(FormatError::new(
                    0,
                    "bootstrap method is not a MethodHandle",
                ));
            }
        }
        Ok(())
    }
}

fn class_at(r: &Reader<'_>, pool: &ConstantPool, index: u16) -> Result<Arc<str>, FormatError> {
    pool.class_name(index).cloned().map_err(|e| r.error(e))
}

fn parse_members(
    r: &mut Reader<'_>,
    pool: &ConstantPool,
    methods: bool,
) -> Result<Vec<Member>, FormatError> {
    let count = r.u2()?;
    let mut out: Vec<Member> = Vec::with_capacity(usize::from(count));
    // A set, not a scan: 65535 members must not cost 65535^2 comparisons.
    let mut seen = std::collections::HashSet::with_capacity(usize::from(count));
    for _ in 0..count {
        let member = parse_member(r, pool, methods)?;
        if !seen.insert((member.name.clone(), member.descriptor.clone())) {
            return Err(r.error(format!(
                "duplicate member {}{}",
                member.name, member.descriptor
            )));
        }
        out.push(member);
    }
    Ok(out)
}

fn parse_member(
    r: &mut Reader<'_>,
    pool: &ConstantPool,
    method: bool,
) -> Result<Member, FormatError> {
    let access = r.u2()?;
    let name = pool.utf8(r.u2()?).map_err(|e| r.error(e))?.clone();
    let descriptor = pool.utf8(r.u2()?).map_err(|e| r.error(e))?.clone();
    let check = if method {
        descriptor::parse_method(&descriptor).map(|_| ())
    } else {
        descriptor::parse_field(&descriptor).map(|_| ())
    };
    check.map_err(|e| r.error(e))?;
    let mut member = Member {
        access,
        name,
        descriptor,
        code: None,
        constant_value: None,
        exceptions: Vec::new(),
        signature: None,
        attributes: Vec::new(),
    };
    let count = r.u2()?;
    for _ in 0..count {
        let attr_name = pool.utf8(r.u2()?).map_err(|e| r.error(e))?.clone();
        let body = r.sized()?;
        match (&*attr_name, method) {
            ("Code", true) => {
                if member.code.is_some() {
                    return Err(r.error("two Code attributes"));
                }
                member.code = Some(attr::parse_code(body, pool)?);
            }
            ("ConstantValue", false) => {
                member.constant_value = Some(attr::parse_index(body, "ConstantValue")?);
            }
            ("Exceptions", true) => member.exceptions = parse_exceptions(body)?,
            ("Signature", _) => {
                let i = attr::parse_index(body, "Signature")?;
                member.signature = Some(pool.utf8(i).map_err(|e| r.error(e))?.clone());
            }
            _ => member.attributes.push(RawAttribute {
                name: attr_name,
                data: body.into(),
            }),
        }
    }
    let needs_code = method && access & (ACC_NATIVE | ACC_ABSTRACT) == 0;
    if needs_code != member.code.is_some() {
        return Err(r.error(format!(
            "method {}{} {} a Code attribute",
            member.name,
            member.descriptor,
            if needs_code { "lacks" } else { "must not have" }
        )));
    }
    Ok(member)
}

fn parse_exceptions(data: &[u8]) -> Result<Vec<u16>, FormatError> {
    let mut r = Reader::new(data);
    let count = r.u2()?;
    let mut out = Vec::with_capacity(usize::from(count));
    for _ in 0..count {
        out.push(r.u2()?);
    }
    r.expect_end("Exceptions")?;
    Ok(out)
}

#[cfg(test)]
mod tests;
