//! Field and method descriptors (JVMS 4.3).

use std::sync::Arc;

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum FieldType {
    Byte,
    Char,
    Double,
    Float,
    Int,
    Long,
    Short,
    Boolean,
    /// An internal class name such as `java/lang/String`.
    Object(Arc<str>),
    Array(Box<FieldType>),
}

impl FieldType {
    /// Local variable / operand stack slots: 2 for long and double.
    pub fn slots(&self) -> u16 {
        match self {
            FieldType::Long | FieldType::Double => 2,
            _ => 1,
        }
    }

    pub fn is_reference(&self) -> bool {
        matches!(self, FieldType::Object(_) | FieldType::Array(_))
    }

    /// The descriptor text, the inverse of [`parse_field`].
    pub fn descriptor(&self) -> String {
        match self {
            FieldType::Byte => "B".into(),
            FieldType::Char => "C".into(),
            FieldType::Double => "D".into(),
            FieldType::Float => "F".into(),
            FieldType::Int => "I".into(),
            FieldType::Long => "J".into(),
            FieldType::Short => "S".into(),
            FieldType::Boolean => "Z".into(),
            FieldType::Object(name) => format!("L{name};"),
            FieldType::Array(inner) => format!("[{}", inner.descriptor()),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MethodDescriptor {
    pub params: Vec<FieldType>,
    /// `None` for `void`.
    pub ret: Option<FieldType>,
}

impl MethodDescriptor {
    /// Argument slots, not counting `this`. A parsed descriptor has at most
    /// 255, so the narrowing never saturates for one from `parse_method`.
    pub fn arg_slots(&self) -> u16 {
        u16::try_from(self.slot_count()).unwrap_or(u16::MAX)
    }

    /// The slot count without narrowing, so a hostile descriptor with 65536
    /// parameters cannot wrap around the limit check.
    fn slot_count(&self) -> u32 {
        self.params.iter().map(|p| u32::from(p.slots())).sum()
    }
}

pub fn parse_field(desc: &str) -> Result<FieldType, String> {
    let mut chars = desc.as_bytes();
    let ty = parse_one(&mut chars, desc)?;
    if !chars.is_empty() {
        return Err(format!("trailing characters in field descriptor {desc:?}"));
    }
    Ok(ty)
}

pub fn parse_method(desc: &str) -> Result<MethodDescriptor, String> {
    let bad = || format!("bad method descriptor {desc:?}");
    let mut rest = desc.as_bytes().strip_prefix(b"(").ok_or_else(bad)?;
    let mut params = Vec::new();
    while rest.first() != Some(&b')') {
        if rest.is_empty() {
            return Err(bad());
        }
        params.push(parse_one(&mut rest, desc)?);
    }
    rest = &rest[1..];
    let ret = if rest == b"V" {
        None
    } else {
        let ty = parse_one(&mut rest, desc)?;
        if !rest.is_empty() {
            return Err(bad());
        }
        Some(ty)
    };
    let md = MethodDescriptor { params, ret };
    // JVMS 4.3.3: at most 255 slots. `this` counts for an instance method, which
    // a descriptor does not say, so the class's format check adds it.
    if md.slot_count() > 255 {
        return Err(format!("too many parameter slots in {desc:?}"));
    }
    Ok(md)
}

fn parse_one(rest: &mut &[u8], whole: &str) -> Result<FieldType, String> {
    let mut dims = 0usize;
    while rest.first() == Some(&b'[') {
        dims += 1;
        *rest = &rest[1..];
    }
    if dims > 255 {
        return Err(format!("more than 255 array dimensions in {whole:?}"));
    }
    let (&tag, tail) = rest
        .split_first()
        .ok_or_else(|| format!("truncated descriptor {whole:?}"))?;
    *rest = tail;
    let mut ty = match tag {
        b'B' => FieldType::Byte,
        b'C' => FieldType::Char,
        b'D' => FieldType::Double,
        b'F' => FieldType::Float,
        b'I' => FieldType::Int,
        b'J' => FieldType::Long,
        b'S' => FieldType::Short,
        b'Z' => FieldType::Boolean,
        b'L' => {
            let end = rest
                .iter()
                .position(|&c| c == b';')
                .ok_or_else(|| format!("unterminated class name in {whole:?}"))?;
            let name = std::str::from_utf8(&rest[..end]).map_err(|e| e.to_string())?;
            if name.is_empty() || name.contains(['.', '[']) {
                return Err(format!("bad class name in descriptor {whole:?}"));
            }
            *rest = &rest[end + 1..];
            FieldType::Object(name.into())
        }
        _ => {
            return Err(format!(
                "bad descriptor character {:?} in {whole:?}",
                tag as char
            ));
        }
    };
    for _ in 0..dims {
        ty = FieldType::Array(Box::new(ty));
    }
    Ok(ty)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_method_descriptors() {
        let md = parse_method("(I[JLjava/lang/String;D)V").ok();
        let md = md.as_ref();
        assert_eq!(md.map(|m| m.params.len()), Some(4));
        assert_eq!(md.map(MethodDescriptor::arg_slots), Some(5));
        assert_eq!(md.and_then(|m| m.ret.clone()), None);
    }

    #[test]
    fn round_trips_field_descriptors() {
        for d in ["I", "[[Ljava/lang/Object;", "[Z", "Ljava/util/Map$Entry;"] {
            assert_eq!(
                parse_field(d).map(|t| t.descriptor()).ok().as_deref(),
                Some(d)
            );
        }
    }

    #[test]
    fn huge_descriptors_do_not_wrap() {
        let desc = format!("({}{})V", "J".repeat(32768), "I".repeat(10));
        assert!(parse_method(&desc).is_err());
    }

    #[test]
    fn rejects_malformed() {
        for d in ["", "L;", "Lfoo", "Q", "II", "[", "Ljava.lang.String;"] {
            assert!(parse_field(d).is_err(), "{d}");
        }
        for d in ["", "(", "()", "(I", "()VV", "(V)V", "I)V"] {
            assert!(parse_method(d).is_err(), "{d}");
        }
    }
}
