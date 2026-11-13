//! Generic signatures (JVMS 4.7.9.1) read into [`Type`]s, and written back
//! for Siesta's own generic declarations.
//!
//! Wildcards are collapsed as siesta.md section 6 says: `? extends T` and
//! `? super T` read as `T`, `?` as `Object`.

use std::collections::HashMap;
use std::rc::Rc;

use crate::ast::Prim;
use crate::types::{OBJECT, Type, prim_descriptor};

/// A type parameter and the erasure of its first bound.
#[derive(Clone, Debug, PartialEq)]
pub struct TypeParam {
    pub name: Rc<str>,
    pub bound: Type,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct ClassSig {
    pub params: Vec<TypeParam>,
    pub superclass: Option<Type>,
    pub interfaces: Vec<Type>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MethodSig {
    pub params_t: Vec<TypeParam>,
    pub params: Vec<Type>,
    pub ret: Type,
}

/// Type variables in scope, by name, with their bound's erasure.
pub type Scope = HashMap<Rc<str>, Rc<str>>;

pub fn class_sig(sig: &str, outer: &Scope) -> Option<ClassSig> {
    let mut r = Reader {
        s: sig.as_bytes(),
        pos: 0,
        scope: outer.clone(),
    };
    let params = r.type_params()?;
    let superclass = Some(r.reference()?);
    let mut interfaces = Vec::new();
    while !r.done() {
        interfaces.push(r.reference()?);
    }
    Some(ClassSig {
        params,
        superclass,
        interfaces,
    })
}

pub fn method_sig(sig: &str, outer: &Scope) -> Option<MethodSig> {
    let mut r = Reader {
        s: sig.as_bytes(),
        pos: 0,
        scope: outer.clone(),
    };
    let params_t = r.type_params()?;
    r.expect(b'(')?;
    let mut params = Vec::new();
    while r.peek() != b')' {
        params.push(r.java_type()?);
    }
    r.expect(b')')?;
    let ret = if r.peek() == b'V' {
        r.pos += 1;
        Type::Void
    } else {
        r.java_type()?
    };
    Some(MethodSig {
        params_t,
        params,
        ret,
    })
}

/// A field signature, or a plain descriptor, which is a valid signature.
pub fn field_type(sig: &str, outer: &Scope) -> Option<Type> {
    let mut r = Reader {
        s: sig.as_bytes(),
        pos: 0,
        scope: outer.clone(),
    };
    let t = r.java_type()?;
    r.done().then_some(t)
}

struct Reader<'a> {
    s: &'a [u8],
    pos: usize,
    scope: Scope,
}

impl Reader<'_> {
    fn peek(&self) -> u8 {
        self.s.get(self.pos).copied().unwrap_or(0)
    }

    fn done(&self) -> bool {
        self.pos >= self.s.len()
    }

    fn expect(&mut self, c: u8) -> Option<()> {
        (self.peek() == c).then(|| self.pos += 1)
    }

    fn ident(&mut self) -> Option<&str> {
        let start = self.pos;
        while !matches!(self.peek(), b';' | b'<' | b'>' | b':' | b'.' | b'/' | 0) {
            self.pos += 1;
        }
        std::str::from_utf8(self.s.get(start..self.pos)?)
            .ok()
            .filter(|s| !s.is_empty())
    }

    fn type_params(&mut self) -> Option<Vec<TypeParam>> {
        let mut out = Vec::new();
        if self.peek() != b'<' {
            return Some(out);
        }
        self.pos += 1;
        // Names first, so a bound may mention a later parameter.
        let start = self.pos;
        let mut names = Vec::new();
        while self.peek() != b'>' {
            let name: Rc<str> = self.ident()?.into();
            self.scope.insert(name.clone(), OBJECT.into());
            names.push(name);
            while self.peek() == b':' {
                self.pos += 1;
                if self.peek() != b':' {
                    self.reference()?;
                }
            }
        }
        self.pos = start;
        for name in names {
            self.ident()?;
            let mut bound = Type::object();
            let mut first = true;
            while self.peek() == b':' {
                self.pos += 1;
                if self.peek() == b':' {
                    continue;
                }
                let b = self.reference()?;
                if first {
                    bound = b;
                    first = false;
                }
            }
            let erased: Rc<str> = bound.erasure().internal().into();
            self.scope.insert(name.clone(), erased);
            out.push(TypeParam { name, bound });
        }
        self.expect(b'>')?;
        Some(out)
    }

    fn java_type(&mut self) -> Option<Type> {
        let p = match self.peek() {
            b'Z' => Prim::Boolean,
            b'B' => Prim::Byte,
            b'C' => Prim::Char,
            b'S' => Prim::Short,
            b'I' => Prim::Int,
            b'J' => Prim::Long,
            b'F' => Prim::Float,
            b'D' => Prim::Double,
            _ => return self.reference(),
        };
        self.pos += 1;
        Some(Type::Prim(p))
    }

    fn reference(&mut self) -> Option<Type> {
        match self.peek() {
            b'[' => {
                self.pos += 1;
                Some(Type::Array(Box::new(self.java_type()?)))
            }
            b'T' => {
                self.pos += 1;
                let name: Rc<str> = self.ident()?.into();
                self.expect(b';')?;
                let bound = self
                    .scope
                    .get(&name)
                    .cloned()
                    .unwrap_or_else(|| OBJECT.into());
                Some(Type::Var(name, bound))
            }
            b'L' => {
                self.pos += 1;
                let mut name = String::new();
                let mut args = Vec::new();
                loop {
                    name.push_str(self.ident()?);
                    if self.peek() == b'/' {
                        self.pos += 1;
                        name.push('/');
                        continue;
                    }
                    if self.peek() == b'<' {
                        args = self.type_args()?;
                    }
                    match self.peek() {
                        b'.' => {
                            self.pos += 1;
                            name.push('$');
                            args.clear();
                        }
                        b';' => {
                            self.pos += 1;
                            return Some(Type::Class(name.into(), args));
                        }
                        _ => return None,
                    }
                }
            }
            _ => None,
        }
    }

    fn type_args(&mut self) -> Option<Vec<Type>> {
        self.expect(b'<')?;
        let mut out = Vec::new();
        while self.peek() != b'>' {
            out.push(match self.peek() {
                b'*' => {
                    self.pos += 1;
                    Type::object()
                }
                b'+' | b'-' => {
                    self.pos += 1;
                    self.reference()?
                }
                _ => self.reference()?,
            });
        }
        self.expect(b'>')?;
        Some(out)
    }
}

/// The signature of a type as written in a `Signature` attribute.
pub fn write_type(t: &Type) -> String {
    match t {
        Type::Prim(p) => prim_descriptor(*p).to_string(),
        Type::Void => "V".into(),
        Type::Class(n, args) if !args.is_empty() => {
            let a: String = args.iter().map(write_type).collect();
            format!("L{n}<{a}>;")
        }
        Type::Var(n, _) => format!("T{n};"),
        Type::Array(e) => format!("[{}", write_type(e)),
        other => other.descriptor(),
    }
}

/// Type parameters as written in a signature. Every bound is written as a
/// class bound: the JVM reads neither, and reflection reports both alike.
pub fn write_params(params: &[TypeParam]) -> String {
    if params.is_empty() {
        return String::new();
    }
    let p: String = params
        .iter()
        .map(|p| format!("{}:{}", p.name, write_type(&p.bound)))
        .collect();
    format!("<{p}>")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn class(n: &str, args: Vec<Type>) -> Type {
        Type::Class(n.into(), args)
    }

    #[test]
    fn reads_class_and_method_signatures() {
        let s = Scope::new();
        let c = class_sig("<K:Ljava/lang/Object;V:Ljava/lang/Object;>Ljava/util/AbstractMap<TK;TV;>;Ljava/util/Map<TK;TV;>;", &s)
            .unwrap_or_default();
        assert_eq!(c.params.len(), 2);
        assert_eq!(
            c.interfaces[0],
            class(
                "java/util/Map",
                vec![
                    Type::Var("K".into(), OBJECT.into()),
                    Type::Var("V".into(), OBJECT.into())
                ]
            )
        );
        let m = method_sig(
            "<R:Ljava/lang/Object;>(Ljava/util/function/Function<-TT;+TR;>;)Ljava/util/stream/Stream<TR;>;",
            &s,
        );
        let m = m.unwrap_or_else(|| panic!("method signature"));
        assert_eq!(m.params_t[0].name.as_ref(), "R");
        let r = Type::Var("R".into(), OBJECT.into());
        assert_eq!(m.ret, class("java/util/stream/Stream", vec![r.clone()]));
        assert_eq!(
            m.params[0],
            class(
                "java/util/function/Function",
                vec![Type::Var("T".into(), OBJECT.into()), r]
            )
        );
    }

    #[test]
    fn bounds_wildcards_and_inner_classes() {
        let s = Scope::new();
        let m = method_sig(
            "<T::Ljava/lang/Comparable<-TT;>;>(Ljava/util/List<*>;[TT;)V",
            &s,
        )
        .unwrap_or_else(|| panic!());
        assert_eq!(
            m.params[1],
            Type::Array(Box::new(Type::Var(
                "T".into(),
                "java/lang/Comparable".into()
            )))
        );
        assert_eq!(m.params[0], class("java/util/List", vec![Type::object()]));
        let f = field_type("Ljava/util/Map<TK;TV;>.Entry<TK;TV;>;", &s).unwrap_or_else(|| panic!());
        assert!(
            matches!(f, Type::Class(ref n, ref a) if &**n == "java/util/Map$Entry" && a.len() == 2)
        );
        assert_eq!(field_type("Lbad", &s), None);
        assert_eq!(method_sig("(I", &s), None);
    }

    #[test]
    fn writes_signatures() {
        let t = class("java/util/List", vec![Type::Var("T".into(), OBJECT.into())]);
        assert_eq!(write_type(&t), "Ljava/util/List<TT;>;");
        let p = TypeParam {
            name: "T".into(),
            bound: Type::object(),
        };
        assert_eq!(write_params(&[p]), "<T:Ljava/lang/Object;>");
    }
}
