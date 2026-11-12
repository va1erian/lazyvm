//! A small class file writer (JVMS 4), for classes the VM generates itself
//! (annotation implementations) and for the Siesta compiler's output. What
//! it writes is parsed back by [`crate::ClassFile::parse`] like any other
//! class file, so generated code gets the same checks as loaded code.
//! Attributes other than `Code` are passed as raw bytes: each user encodes
//! the few it needs.

use std::collections::HashMap;

/// A constant pool under construction; equal entries are shared.
#[derive(Default)]
pub struct Pool {
    bytes: Vec<u8>,
    /// Slots used; may pass 65535, which [`Pool::len`] reports so the
    /// caller refuses the class (indices past it are meaningless).
    count: usize,
    index: HashMap<Vec<u8>, u16>,
}

impl Pool {
    fn add(&mut self, entry: Vec<u8>) -> u16 {
        if let Some(&i) = self.index.get(&entry) {
            return i;
        }
        self.count += 1;
        let i = self.count as u16;
        self.bytes.extend_from_slice(&entry);
        self.index.insert(entry, i);
        i
    }

    /// The slots used so far, counting the two of a `long` or `double`; a
    /// class file holds at most 65534, which callers check.
    pub fn len(&self) -> usize {
        self.count
    }

    pub fn is_empty(&self) -> bool {
        self.count == 0
    }

    /// `s` in modified UTF-8 (JVMS 4.4.7). Callers keep `s` under 65536
    /// encoded bytes ([`mutf8_len`]); longer text is cut there.
    pub fn utf8(&mut self, s: &str) -> u16 {
        let units: Vec<u16> = s.encode_utf16().collect();
        let mut bytes = crate::encode_mutf8(&units);
        let mut keep = bytes.len().min(usize::from(u16::MAX));
        // Cut before a character, never inside one (continuation bytes are 10xxxxxx).
        while keep < bytes.len() && keep > 0 && bytes[keep] & 0xc0 == 0x80 {
            keep -= 1;
        }
        bytes.truncate(keep);
        let mut e = vec![1];
        e.extend_from_slice(&(bytes.len() as u16).to_be_bytes());
        e.extend_from_slice(&bytes);
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

    pub fn int(&mut self, v: i32) -> u16 {
        self.add([&[3][..], &v.to_be_bytes()].concat())
    }

    pub fn float(&mut self, v: f32) -> u16 {
        self.add([&[4][..], &v.to_bits().to_be_bytes()].concat())
    }

    /// A `long` takes two slots (JVMS 4.4.5).
    pub fn long(&mut self, v: i64) -> u16 {
        self.wide([&[5][..], &v.to_be_bytes()].concat())
    }

    pub fn double(&mut self, v: f64) -> u16 {
        self.wide([&[6][..], &v.to_bits().to_be_bytes()].concat())
    }

    fn wide(&mut self, entry: Vec<u8>) -> u16 {
        let fresh = !self.index.contains_key(&entry);
        let i = self.add(entry);
        if fresh {
            self.count += 1;
        }
        i
    }

    pub fn string(&mut self, s: &str) -> u16 {
        let u = self.utf8(s);
        self.add(tagged(8, &[u]))
    }

    /// `kind` is a `REF_` kind (JVMS 5.4.3.5); `member` a member reference.
    pub fn method_handle(&mut self, kind: u8, member: u16) -> u16 {
        self.add([&[15, kind][..], &member.to_be_bytes()].concat())
    }

    pub fn method_type(&mut self, descriptor: &str) -> u16 {
        let d = self.utf8(descriptor);
        self.add(tagged(16, &[d]))
    }

    /// `bootstrap` indexes the class's `BootstrapMethods` attribute.
    pub fn invoke_dynamic(&mut self, bootstrap: u16, name: &str, descriptor: &str) -> u16 {
        let nt = self.name_and_type(name, descriptor);
        self.add(tagged(18, &[bootstrap, nt]))
    }
}

fn tagged(tag: u8, indices: &[u16]) -> Vec<u8> {
    let mut e = vec![tag];
    for i in indices {
        e.extend_from_slice(&i.to_be_bytes());
    }
    e
}

/// The modified UTF-8 length of `s`, to check that a constant fits before
/// [`Pool::utf8`] is asked for it.
pub fn mutf8_len(s: &str) -> usize {
    s.chars()
        .map(|c| match c as u32 {
            0 => 2,
            1..=0x7f => 1,
            0x80..=0x7ff => 2,
            0x800..=0xffff => 3,
            _ => 6,
        })
        .sum()
}

/// An attribute other than `Code`: its name and its encoded body.
pub type Attribute = (String, Vec<u8>);

/// One exception table entry: start, end, handler, catch type.
pub type HandlerDef = (u16, u16, u16, u16);

#[derive(Default)]
pub struct FieldDef {
    pub access: u16,
    pub name: String,
    pub descriptor: String,
    pub attributes: Vec<Attribute>,
}

#[derive(Default)]
pub struct MethodDef {
    pub access: u16,
    pub name: String,
    pub descriptor: String,
    pub max_stack: u16,
    pub max_locals: u16,
    /// `None` for an abstract or native method, which has no `Code`.
    pub code: Option<Vec<u8>>,
    pub handlers: Vec<HandlerDef>,
    /// Attributes of the `Code` attribute: `StackMapTable`, `LineNumberTable`.
    pub code_attributes: Vec<Attribute>,
    pub attributes: Vec<Attribute>,
}

#[derive(Default)]
pub struct ClassDef {
    pub access: u16,
    pub name: String,
    pub super_name: String,
    pub interfaces: Vec<String>,
    pub fields: Vec<FieldDef>,
    pub methods: Vec<MethodDef>,
    pub attributes: Vec<Attribute>,
}

/// Serializes `class` with constants already added to `pool` (the method
/// bodies refer to them), as a version 52 class file.
pub fn write(class: &ClassDef, mut pool: Pool) -> Vec<u8> {
    let this = pool.class(&class.name);
    let sup = pool.class(&class.super_name);
    let ifaces: Vec<u16> = class.interfaces.iter().map(|i| pool.class(i)).collect();
    let code_attr = pool.utf8("Code");
    let mut body = Vec::new();
    for v in [class.access, this, sup, ifaces.len() as u16] {
        u2(&mut body, v);
    }
    for i in &ifaces {
        u2(&mut body, *i);
    }
    u2(&mut body, class.fields.len() as u16);
    for f in &class.fields {
        let (n, d) = (pool.utf8(&f.name), pool.utf8(&f.descriptor));
        for v in [f.access, n, d] {
            u2(&mut body, v);
        }
        attributes(&mut body, &mut pool, &f.attributes);
    }
    u2(&mut body, class.methods.len() as u16);
    for m in &class.methods {
        let (n, d) = (pool.utf8(&m.name), pool.utf8(&m.descriptor));
        let count = m.attributes.len() + usize::from(m.code.is_some());
        for v in [m.access, n, d, count as u16] {
            u2(&mut body, v);
        }
        if let Some(code) = &m.code {
            let c = code_body(m, code, &mut pool);
            u2(&mut body, code_attr);
            body.extend_from_slice(&(c.len() as u32).to_be_bytes());
            body.extend_from_slice(&c);
        }
        for (name, data) in &m.attributes {
            attribute(&mut body, &mut pool, name, data);
        }
    }
    attributes(&mut body, &mut pool, &class.attributes);
    let mut out = vec![0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 52];
    out.extend_from_slice(&((pool.count + 1) as u16).to_be_bytes());
    out.extend_from_slice(&pool.bytes);
    out.extend_from_slice(&body);
    out
}

fn code_body(m: &MethodDef, code: &[u8], pool: &mut Pool) -> Vec<u8> {
    let mut c = Vec::new();
    u2(&mut c, m.max_stack);
    u2(&mut c, m.max_locals);
    c.extend_from_slice(&(code.len() as u32).to_be_bytes());
    c.extend_from_slice(code);
    u2(&mut c, m.handlers.len() as u16);
    for &(start, end, handler, catch) in &m.handlers {
        for v in [start, end, handler, catch] {
            u2(&mut c, v);
        }
    }
    attributes(&mut c, pool, &m.code_attributes);
    c
}

fn u2(out: &mut Vec<u8>, v: u16) {
    out.extend_from_slice(&v.to_be_bytes());
}

fn attributes(out: &mut Vec<u8>, pool: &mut Pool, attrs: &[Attribute]) {
    u2(out, attrs.len() as u16);
    for (name, data) in attrs {
        attribute(out, pool, name, data);
    }
}

fn attribute(out: &mut Vec<u8>, pool: &mut Pool, name: &str, data: &[u8]) {
    let n = pool.utf8(name);
    u2(out, n);
    out.extend_from_slice(&(data.len() as u32).to_be_bytes());
    out.extend_from_slice(data);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{ACC_ABSTRACT, ACC_PUBLIC, ACC_SUPER, ClassFile};

    #[test]
    fn written_classes_parse_back() {
        let mut pool = Pool::default();
        let init = pool.method_ref("java/lang/Object", "<init>", "()V");
        let [hi, lo] = init.to_be_bytes();
        let class = ClassDef {
            access: ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT,
            name: "gen/Thing".into(),
            super_name: "java/lang/Object".into(),
            interfaces: vec!["java/lang/Runnable".into()],
            fields: vec![FieldDef {
                access: 0x12,
                name: "x".into(),
                descriptor: "I".into(),
                ..FieldDef::default()
            }],
            methods: vec![
                MethodDef {
                    access: ACC_PUBLIC,
                    name: "<init>".into(),
                    descriptor: "()V".into(),
                    max_stack: 1,
                    max_locals: 1,
                    code: Some(vec![0x2a, 0xb7, hi, lo, 0xb1]),
                    handlers: vec![(0, 4, 4, 0)],
                    ..MethodDef::default()
                },
                MethodDef {
                    access: ACC_PUBLIC | ACC_ABSTRACT,
                    name: "run".into(),
                    descriptor: "()V".into(),
                    ..MethodDef::default()
                },
            ],
            attributes: Vec::new(),
        };
        let cf = ClassFile::parse(&write(&class, pool)).unwrap_or_else(|e| panic!("{e}"));
        assert_eq!(&*cf.this_class, "gen/Thing");
        assert_eq!(cf.interfaces.len(), 1);
        assert!(cf.method("<init>", "()V").is_some_and(|m| m.code.is_some()));
        assert!(cf.method("run", "()V").is_some_and(|m| m.code.is_none()));
        assert_eq!(
            cf.pool
                .member_ref(init)
                .map(|r| r.name.to_string())
                .ok()
                .as_deref(),
            Some("<init>")
        );
    }

    #[test]
    fn wide_constants_take_two_slots() {
        let mut pool = Pool::default();
        let a = pool.long(7);
        let b = pool.int(1);
        assert_eq!((a, b), (1, 3));
        assert_eq!(pool.long(7), 1);
        assert_eq!(pool.len(), 3);
        let s = pool.string("\0é𝄞");
        assert_eq!(s, 5);
        assert_eq!(mutf8_len("\0é𝄞"), 2 + 2 + 6);
    }

    #[test]
    fn an_overfull_pool_says_so() {
        let mut pool = Pool::default();
        for v in 0..70_000 {
            pool.int(v);
        }
        assert_eq!(pool.len(), 70_000);
    }

    #[test]
    fn long_text_is_cut_between_characters() {
        let mut pool = Pool::default();
        let text = "é".repeat(40_000); // 80,000 bytes, two per character
        pool.utf8(&text);
        let len = u16::from_be_bytes([pool.bytes[1], pool.bytes[2]]);
        assert_eq!(len, 65_534);
    }
}
