//! Bytecode emission for generated lambda classes: loads, returns, and the
//! value adaptations `LambdaMetafactory` allows (its Javadoc's table:
//! primitive widening, boxing, unboxing and reference casts).

use classfile::descriptor::FieldType;
use classfile::writer::Pool;

/// A method body under construction, sharing the class's constant pool.
pub struct Asm<'p> {
    pub code: Vec<u8>,
    pub pool: &'p mut Pool,
}

/// A primitive's stack kind: `I` for boolean, byte, char, short and int.
fn kind(t: &FieldType) -> Option<u8> {
    Some(match t {
        FieldType::Boolean
        | FieldType::Byte
        | FieldType::Char
        | FieldType::Short
        | FieldType::Int => b'I',
        FieldType::Long => b'J',
        FieldType::Float => b'F',
        FieldType::Double => b'D',
        FieldType::Object(_) | FieldType::Array(_) => return None,
    })
}

/// The wrapper class and its unboxing method of a primitive type.
fn wrapper(t: &FieldType) -> Option<(&'static str, &'static str)> {
    Some(match t {
        FieldType::Boolean => ("java/lang/Boolean", "booleanValue"),
        FieldType::Byte => ("java/lang/Byte", "byteValue"),
        FieldType::Char => ("java/lang/Character", "charValue"),
        FieldType::Short => ("java/lang/Short", "shortValue"),
        FieldType::Int => ("java/lang/Integer", "intValue"),
        FieldType::Long => ("java/lang/Long", "longValue"),
        FieldType::Float => ("java/lang/Float", "floatValue"),
        FieldType::Double => ("java/lang/Double", "doubleValue"),
        _ => return None,
    })
}

/// The primitive a wrapper class boxes, if `class` is one.
fn unwrapped(class: &str) -> Option<FieldType> {
    Some(match class {
        "java/lang/Boolean" => FieldType::Boolean,
        "java/lang/Byte" => FieldType::Byte,
        "java/lang/Character" => FieldType::Char,
        "java/lang/Short" => FieldType::Short,
        "java/lang/Integer" => FieldType::Int,
        "java/lang/Long" => FieldType::Long,
        "java/lang/Float" => FieldType::Float,
        "java/lang/Double" => FieldType::Double,
        _ => return None,
    })
}

/// The class name `checkcast` takes for a reference type.
fn class_operand(t: &FieldType) -> String {
    match t {
        FieldType::Object(name) => name.to_string(),
        other => other.descriptor(),
    }
}

impl Asm<'_> {
    pub fn op(&mut self, op: u8) {
        self.code.push(op);
    }

    pub fn op_u2(&mut self, op: u8, v: u16) {
        self.code.push(op);
        self.code.extend_from_slice(&v.to_be_bytes());
    }

    /// Loads local `slot` of type `t` (slots stay below 256: at most 255 parameters).
    pub fn load(&mut self, t: &FieldType, slot: u16) {
        let op = match kind(t) {
            Some(b'I') => 0x15,
            Some(b'J') => 0x16,
            Some(b'F') => 0x17,
            Some(_) => 0x18,
            None => 0x19,
        };
        self.code.push(op);
        self.code.push(slot as u8);
    }

    /// `return` for `None`, else the typed return.
    pub fn ret(&mut self, t: Option<&FieldType>) {
        self.op(match t.map(kind) {
            None => 0xb1,
            Some(Some(b'I')) => 0xac,
            Some(Some(b'J')) => 0xad,
            Some(Some(b'F')) => 0xae,
            Some(Some(_)) => 0xaf,
            Some(None) => 0xb0,
        });
    }

    /// Discards a value of type `t`.
    pub fn pop(&mut self, t: &FieldType) {
        self.op(if t.slots() == 2 { 0x58 } else { 0x57 });
    }

    pub fn checkcast(&mut self, t: &FieldType) {
        let c = self.pool.class(&class_operand(t));
        self.op_u2(0xc0, c);
    }

    /// Converts the value on top of the stack from `from` to `to`.
    pub fn adapt(&mut self, from: &FieldType, to: &FieldType) -> Result<(), String> {
        if from == to {
            return Ok(());
        }
        match (kind(from).is_some(), kind(to).is_some()) {
            (true, true) => self.widen(from, to),
            (true, false) => {
                self.boxed(from);
                let (w, _) = wrapper(from).ok_or("no wrapper")?;
                if !matches!(to, FieldType::Object(n) if &**n == w || &**n == "java/lang/Object") {
                    self.checkcast(to);
                }
                Ok(())
            }
            (false, true) => {
                let source = match from {
                    FieldType::Object(n) => unwrapped(n),
                    _ => None,
                };
                match source {
                    Some(p) => {
                        self.unbox(&p);
                        self.widen(&p, to)
                    }
                    None => {
                        let (w, _) = wrapper(to).ok_or("no wrapper")?;
                        let c = self.pool.class(w);
                        self.op_u2(0xc0, c);
                        self.unbox(to);
                        Ok(())
                    }
                }
            }
            (false, false) => {
                if !matches!(to, FieldType::Object(n) if &**n == "java/lang/Object") {
                    self.checkcast(to);
                }
                Ok(())
            }
        }
    }

    /// Primitive widening (JLS 5.1.2); narrowing, and anything involving
    /// `boolean`, is a linkage error.
    fn widen(&mut self, from: &FieldType, to: &FieldType) -> Result<(), String> {
        let bad = || format!("cannot widen {} to {}", from.descriptor(), to.descriptor());
        if from == to {
            return Ok(());
        }
        if matches!(from, FieldType::Boolean) || matches!(to, FieldType::Boolean) {
            return Err(bad());
        }
        let (f, t) = (kind(from).ok_or_else(bad)?, kind(to).ok_or_else(bad)?);
        let op = match (f, t) {
            // Among the int kinds only byte, short and char to int, and byte to short.
            (b'I', b'I') => {
                return match (from, to) {
                    (_, FieldType::Int) | (FieldType::Byte, FieldType::Short) => Ok(()),
                    _ => Err(bad()),
                };
            }
            (b'I', b'J') => 0x85,
            (b'I', b'F') => 0x86,
            (b'I', b'D') => 0x87,
            (b'J', b'F') => 0x89,
            (b'J', b'D') => 0x8a,
            (b'F', b'D') => 0x8d,
            _ => return Err(bad()),
        };
        self.op(op);
        Ok(())
    }

    fn boxed(&mut self, p: &FieldType) {
        if let Some((w, _)) = wrapper(p) {
            let desc = format!("({})L{w};", p.descriptor());
            let m = self.pool.method_ref(w, "valueOf", &desc);
            self.op_u2(0xb8, m);
        }
    }

    fn unbox(&mut self, p: &FieldType) {
        if let Some((w, method)) = wrapper(p) {
            let desc = format!("(){}", p.descriptor());
            let m = self.pool.method_ref(w, method, &desc);
            self.op_u2(0xb6, m);
        }
    }
}
