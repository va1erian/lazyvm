//! A small class file writer (JVMS 4), for classes the VM generates itself:
//! lambda classes now, annotation implementations later. What it writes is
//! parsed back by [`crate::ClassFile::parse`] like any other class file, so
//! generated code gets the same checks as loaded code.

use std::collections::HashMap;

/// A constant pool under construction; equal entries are shared.
#[derive(Default)]
pub struct Pool {
    bytes: Vec<u8>,
    count: u16,
    index: HashMap<Vec<u8>, u16>,
}

impl Pool {
    fn add(&mut self, entry: Vec<u8>) -> u16 {
        if let Some(&i) = self.index.get(&entry) {
            return i;
        }
        self.count += 1;
        let i = self.count;
        self.bytes.extend_from_slice(&entry);
        self.index.insert(entry, i);
        i
    }

    /// Text without NUL or supplementary characters, which is all the VM
    /// generates (names and descriptors), so plain UTF-8 is modified UTF-8.
    pub fn utf8(&mut self, s: &str) -> u16 {
        debug_assert!(!s.contains('\0') && s.chars().all(|c| (c as u32) < 0x10000));
        let mut e = vec![1];
        e.extend_from_slice(&(s.len() as u16).to_be_bytes());
        e.extend_from_slice(s.as_bytes());
        self.add(e)
    }

    pub fn class(&mut self, internal_name: &str) -> u16 {
        let name = self.utf8(internal_name);
        self.add(tagged(7, &[name]))
    }

    pub fn name_and_type(&mut self, name: &str, descriptor: &str) -> u16 {
        let (n, d) = (self.utf8(name), self.utf8(descriptor));
        self.add(tagged(12, &[n, d]))
    }

    pub fn field_ref(&mut self, class: &str, name: &str, descriptor: &str) -> u16 {
        self.member(9, class, name, descriptor)
    }

    pub fn method_ref(&mut self, class: &str, name: &str, descriptor: &str) -> u16 {
        self.member(10, class, name, descriptor)
    }

    pub fn interface_method_ref(&mut self, class: &str, name: &str, descriptor: &str) -> u16 {
        self.member(11, class, name, descriptor)
    }

    fn member(&mut self, tag: u8, class: &str, name: &str, descriptor: &str) -> u16 {
        let c = self.class(class);
        let nt = self.name_and_type(name, descriptor);
        self.add(tagged(tag, &[c, nt]))
    }
}

fn tagged(tag: u8, indices: &[u16]) -> Vec<u8> {
    let mut e = vec![tag];
    for i in indices {
        e.extend_from_slice(&i.to_be_bytes());
    }
    e
}

pub struct FieldDef {
    pub access: u16,
    pub name: String,
    pub descriptor: String,
}

pub struct MethodDef {
    pub access: u16,
    pub name: String,
    pub descriptor: String,
    pub max_stack: u16,
    pub max_locals: u16,
    pub code: Vec<u8>,
}

pub struct ClassDef {
    pub access: u16,
    pub name: String,
    pub super_name: String,
    pub interfaces: Vec<String>,
    pub fields: Vec<FieldDef>,
    pub methods: Vec<MethodDef>,
}

/// Serializes `class` with constants already added to `pool` (the method
/// bodies refer to them), as a version 52 class file.
pub fn write(class: &ClassDef, mut pool: Pool) -> Vec<u8> {
    let this = pool.class(&class.name);
    let sup = pool.class(&class.super_name);
    let ifaces: Vec<u16> = class.interfaces.iter().map(|i| pool.class(i)).collect();
    let code_attr = pool.utf8("Code");
    let mut body = Vec::new();
    let u2 = |out: &mut Vec<u8>, v: u16| out.extend_from_slice(&v.to_be_bytes());
    u2(&mut body, class.access);
    u2(&mut body, this);
    u2(&mut body, sup);
    u2(&mut body, ifaces.len() as u16);
    for i in &ifaces {
        u2(&mut body, *i);
    }
    u2(&mut body, class.fields.len() as u16);
    for f in &class.fields {
        let (n, d) = (pool.utf8(&f.name), pool.utf8(&f.descriptor));
        for v in [f.access, n, d, 0] {
            u2(&mut body, v);
        }
    }
    u2(&mut body, class.methods.len() as u16);
    for m in &class.methods {
        let (n, d) = (pool.utf8(&m.name), pool.utf8(&m.descriptor));
        for v in [m.access, n, d, 1, code_attr] {
            u2(&mut body, v);
        }
        // Code: max_stack, max_locals, code, no handlers, no attributes.
        let len = 2 + 2 + 4 + m.code.len() + 2 + 2;
        body.extend_from_slice(&(len as u32).to_be_bytes());
        u2(&mut body, m.max_stack);
        u2(&mut body, m.max_locals);
        body.extend_from_slice(&(m.code.len() as u32).to_be_bytes());
        body.extend_from_slice(&m.code);
        u2(&mut body, 0);
        u2(&mut body, 0);
    }
    u2(&mut body, 0);
    let mut out = vec![0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 52];
    out.extend_from_slice(&(pool.count + 1).to_be_bytes());
    out.extend_from_slice(&pool.bytes);
    out.extend_from_slice(&body);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{ACC_PUBLIC, ACC_SUPER, ClassFile};

    #[test]
    fn written_classes_parse_back() {
        let mut pool = Pool::default();
        let init = pool.method_ref("java/lang/Object", "<init>", "()V");
        let [hi, lo] = init.to_be_bytes();
        let class = ClassDef {
            access: ACC_PUBLIC | ACC_SUPER,
            name: "gen/Thing".into(),
            super_name: "java/lang/Object".into(),
            interfaces: vec!["java/lang/Runnable".into()],
            fields: vec![FieldDef {
                access: 0x12,
                name: "x".into(),
                descriptor: "I".into(),
            }],
            methods: vec![MethodDef {
                access: ACC_PUBLIC,
                name: "<init>".into(),
                descriptor: "()V".into(),
                max_stack: 1,
                max_locals: 1,
                code: vec![0x2a, 0xb7, hi, lo, 0xb1],
            }],
        };
        let cf = ClassFile::parse(&write(&class, pool)).unwrap_or_else(|e| panic!("{e}"));
        assert_eq!(&*cf.this_class, "gen/Thing");
        assert_eq!(cf.interfaces.len(), 1);
        assert!(cf.method("<init>", "()V").is_some_and(|m| m.code.is_some()));
        assert_eq!(
            cf.pool
                .member_ref(init)
                .map(|r| r.name.to_string())
                .ok()
                .as_deref(),
            Some("<init>")
        );
    }
}
