//! The constant pool (JVMS 4.4) and modified UTF-8 (JVMS 4.4.7).

use std::sync::Arc;

use crate::FormatError;
use crate::reader::Reader;

/// One constant pool entry. Indices are validated against the pool when the
/// class file is parsed, so the typed accessors below only fail on misuse.
#[derive(Clone, Debug, PartialEq)]
pub enum Constant {
    /// Index 0 and the slot after a `Long` or `Double`.
    Unusable,
    Utf8(Arc<str>),
    Integer(i32),
    Float(f32),
    Long(i64),
    Double(f64),
    Class(u16),
    String(u16),
    FieldRef {
        class: u16,
        name_and_type: u16,
    },
    MethodRef {
        class: u16,
        name_and_type: u16,
    },
    InterfaceMethodRef {
        class: u16,
        name_and_type: u16,
    },
    NameAndType {
        name: u16,
        descriptor: u16,
    },
    MethodHandle {
        kind: u8,
        reference: u16,
    },
    MethodType(u16),
    InvokeDynamic {
        bootstrap: u16,
        name_and_type: u16,
    },
}

/// A symbolic member reference: owner class name, member name and descriptor.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct MemberRef {
    pub class: Arc<str>,
    pub name: Arc<str>,
    pub descriptor: Arc<str>,
}

#[derive(Clone, Debug, Default)]
pub struct ConstantPool {
    entries: Vec<Constant>,
    /// The exact UTF-16 of Utf8 entries holding unpaired surrogates, which
    /// their Rust `str` form cannot represent.
    exact_utf16: std::collections::HashMap<u16, Arc<[u16]>>,
}

impl ConstantPool {
    pub fn parse(r: &mut Reader<'_>) -> Result<Self, FormatError> {
        let count = r.u2()?;
        if count == 0 {
            return Err(r.error("constant_pool_count is 0"));
        }
        let mut entries = Vec::with_capacity(usize::from(count));
        let mut exact_utf16 = std::collections::HashMap::new();
        entries.push(Constant::Unusable);
        while entries.len() < usize::from(count) {
            let mut exact = None;
            let entry = parse_entry(r, &mut exact)?;
            if let Some(units) = exact {
                exact_utf16.insert(entries.len() as u16, units);
            }
            let wide = matches!(entry, Constant::Long(_) | Constant::Double(_));
            entries.push(entry);
            if wide {
                if entries.len() >= usize::from(count) {
                    return Err(r.error("long or double constant in the last pool slot"));
                }
                entries.push(Constant::Unusable);
            }
        }
        let pool = ConstantPool {
            entries,
            exact_utf16,
        };
        pool.validate()
            .map_err(|msg| FormatError::new(r.pos(), msg))?;
        Ok(pool)
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.len() <= 1
    }

    pub fn get(&self, index: u16) -> Option<&Constant> {
        self.entries.get(usize::from(index))
    }

    pub fn utf8(&self, index: u16) -> Result<&Arc<str>, String> {
        match self.get(index) {
            Some(Constant::Utf8(s)) => Ok(s),
            other => Err(format!("constant {index} is not Utf8: {other:?}")),
        }
    }

    /// The UTF-16 contents of a Utf8 entry, exact even for unpaired
    /// surrogates; what a `String` constant must hold.
    pub fn utf16(&self, index: u16) -> Result<Vec<u16>, String> {
        if let Some(units) = self.exact_utf16.get(&index) {
            return Ok(units.to_vec());
        }
        Ok(self.utf8(index)?.encode_utf16().collect())
    }

    /// The internal name (`java/lang/String`, `[I`) of a `Class` constant.
    pub fn class_name(&self, index: u16) -> Result<&Arc<str>, String> {
        match self.get(index) {
            Some(Constant::Class(name)) => self.utf8(*name),
            other => Err(format!("constant {index} is not Class: {other:?}")),
        }
    }

    pub fn name_and_type(&self, index: u16) -> Result<(&Arc<str>, &Arc<str>), String> {
        match self.get(index) {
            Some(Constant::NameAndType { name, descriptor }) => {
                Ok((self.utf8(*name)?, self.utf8(*descriptor)?))
            }
            other => Err(format!("constant {index} is not NameAndType: {other:?}")),
        }
    }

    /// The owner, name and descriptor of a field, method or interface method ref.
    pub fn member_ref(&self, index: u16) -> Result<MemberRef, String> {
        let (class, nat) = match self.get(index) {
            Some(
                Constant::FieldRef {
                    class,
                    name_and_type,
                }
                | Constant::MethodRef {
                    class,
                    name_and_type,
                }
                | Constant::InterfaceMethodRef {
                    class,
                    name_and_type,
                },
            ) => (*class, *name_and_type),
            other => return Err(format!("constant {index} is not a member ref: {other:?}")),
        };
        let (name, descriptor) = self.name_and_type(nat)?;
        Ok(MemberRef {
            class: self.class_name(class)?.clone(),
            name: name.clone(),
            descriptor: descriptor.clone(),
        })
    }

    /// Checks every index inside the pool points at an entry of the right kind.
    fn validate(&self) -> Result<(), String> {
        for (i, entry) in self.entries.iter().enumerate() {
            let check = match entry {
                Constant::Class(n) | Constant::String(n) | Constant::MethodType(n) => {
                    self.utf8(*n).map(|_| ())
                }
                Constant::FieldRef {
                    class,
                    name_and_type,
                }
                | Constant::MethodRef {
                    class,
                    name_and_type,
                }
                | Constant::InterfaceMethodRef {
                    class,
                    name_and_type,
                } => self
                    .class_name(*class)
                    .and_then(|_| self.name_and_type(*name_and_type))
                    .map(|_| ()),
                Constant::NameAndType { .. } => self.name_and_type(i as u16).map(|_| ()),
                Constant::InvokeDynamic { name_and_type, .. } => {
                    self.name_and_type(*name_and_type).map(|_| ())
                }
                Constant::MethodHandle { kind, reference } => self.check_handle(*kind, *reference),
                _ => Ok(()),
            };
            check.map_err(|e| format!("constant {i}: {e}"))?;
        }
        Ok(())
    }

    /// JVMS 4.4.8: the reference kind decides which ref kind it points at.
    fn check_handle(&self, kind: u8, reference: u16) -> Result<(), String> {
        let target = self.get(reference);
        let ok = match kind {
            1..=4 => matches!(target, Some(Constant::FieldRef { .. })),
            5 | 8 => matches!(target, Some(Constant::MethodRef { .. })),
            6 | 7 => matches!(
                target,
                Some(Constant::MethodRef { .. } | Constant::InterfaceMethodRef { .. })
            ),
            9 => matches!(target, Some(Constant::InterfaceMethodRef { .. })),
            _ => return Err(format!("bad method handle kind {kind}")),
        };
        if ok {
            Ok(())
        } else {
            Err(format!("method handle kind {kind} points at {target:?}"))
        }
    }
}

fn parse_entry(
    r: &mut Reader<'_>,
    exact: &mut Option<Arc<[u16]>>,
) -> Result<Constant, FormatError> {
    let tag = r.u1()?;
    Ok(match tag {
        1 => {
            let len = usize::from(r.u2()?);
            let bytes = r.bytes(len)?;
            let units = decode_mutf8_utf16(bytes).map_err(|e| r.error(e))?;
            let text: String = char::decode_utf16(units.iter().copied())
                .map(|c| c.unwrap_or(char::REPLACEMENT_CHARACTER))
                .collect();
            if text.encode_utf16().ne(units.iter().copied()) {
                *exact = Some(units.into());
            }
            Constant::Utf8(text.into())
        }
        3 => Constant::Integer(r.u4()? as i32),
        4 => Constant::Float(f32::from_bits(r.u4()?)),
        5 => Constant::Long(r.u8()? as i64),
        6 => Constant::Double(f64::from_bits(r.u8()?)),
        7 => Constant::Class(r.u2()?),
        8 => Constant::String(r.u2()?),
        9..=11 => {
            let class = r.u2()?;
            let name_and_type = r.u2()?;
            match tag {
                9 => Constant::FieldRef {
                    class,
                    name_and_type,
                },
                10 => Constant::MethodRef {
                    class,
                    name_and_type,
                },
                _ => Constant::InterfaceMethodRef {
                    class,
                    name_and_type,
                },
            }
        }
        12 => Constant::NameAndType {
            name: r.u2()?,
            descriptor: r.u2()?,
        },
        15 => Constant::MethodHandle {
            kind: r.u1()?,
            reference: r.u2()?,
        },
        16 => Constant::MethodType(r.u2()?),
        18 => Constant::InvokeDynamic {
            bootstrap: r.u2()?,
            name_and_type: r.u2()?,
        },
        _ => return Err(r.error(format!("unknown constant tag {tag}"))),
    })
}

/// Decodes modified UTF-8 (JVMS 4.4.7) into UTF-16 code units.
pub fn decode_mutf8_utf16(bytes: &[u8]) -> Result<Vec<u16>, String> {
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let b = bytes[i];
        let unit = match b {
            0x01..=0x7f => {
                i += 1;
                u16::from(b)
            }
            0xc0..=0xdf => {
                let c = continuation(bytes, i + 1)?;
                i += 2;
                (u16::from(b & 0x1f) << 6) | c
            }
            0xe0..=0xef => {
                let c1 = continuation(bytes, i + 1)?;
                let c2 = continuation(bytes, i + 2)?;
                i += 3;
                (u16::from(b & 0x0f) << 12) | (c1 << 6) | c2
            }
            _ => return Err(format!("bad modified UTF-8 byte {b:#04x} at {i}")),
        };
        out.push(unit);
    }
    Ok(out)
}

fn continuation(bytes: &[u8], i: usize) -> Result<u16, String> {
    match bytes.get(i) {
        Some(&c) if c & 0xc0 == 0x80 => Ok(u16::from(c & 0x3f)),
        _ => Err(format!("truncated modified UTF-8 sequence at {i}")),
    }
}

/// Decodes modified UTF-8 into a Rust string. An unpaired surrogate (legal in
/// Java, not in Rust) becomes U+FFFD; string constants that must keep them
/// use [`decode_mutf8_utf16`].
pub fn decode_mutf8(bytes: &[u8]) -> Result<String, String> {
    let units = decode_mutf8_utf16(bytes)?;
    Ok(char::decode_utf16(units)
        .map(|c| c.unwrap_or(char::REPLACEMENT_CHARACTER))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mutf8_handles_nul_and_supplementary() {
        // "a", U+0000 as C0 80, U+00E9, U+1F600 as a surrogate pair.
        let bytes = [
            b'a', 0xc0, 0x80, 0xc3, 0xa9, 0xed, 0xa0, 0xbd, 0xed, 0xb8, 0x80,
        ];
        assert_eq!(decode_mutf8(&bytes).ok().as_deref(), Some("a\0é😀"));
    }

    #[test]
    fn mutf8_rejects_raw_nul_and_truncation() {
        assert!(decode_mutf8(&[0]).is_err());
        assert!(decode_mutf8(&[0xe0, 0x80]).is_err());
        assert!(decode_mutf8(&[0xf0, 0x80, 0x80, 0x80]).is_err());
    }
}
