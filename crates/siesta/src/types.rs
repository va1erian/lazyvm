//! Types as the checker sees them: Java's, with type variables and the
//! inference variables of one call (siesta.md section 6).

use std::collections::HashMap;
use std::fmt;
use std::rc::Rc;

use crate::ast::Prim;

#[derive(Clone, Debug, PartialEq)]
pub enum Type {
    Prim(Prim),
    Void,
    /// The type of `null`.
    Null,
    /// A class or interface by internal name (`java/util/List`), with type
    /// arguments when it is generic and they are known.
    Class(Rc<str>, Vec<Type>),
    Array(Box<Type>),
    /// A type variable with the erasure of its first bound.
    Var(Rc<str>, Rc<str>),
    /// An inference variable of the call being resolved.
    Infer(u32),
}

pub const OBJECT: &str = "java/lang/Object";
pub const STRING: &str = "java/lang/String";

impl Type {
    pub fn class(name: &str) -> Type {
        Type::Class(name.into(), Vec::new())
    }

    pub fn object() -> Type {
        Type::class(OBJECT)
    }

    pub fn string() -> Type {
        Type::class(STRING)
    }

    pub fn is_prim(&self) -> bool {
        matches!(self, Type::Prim(_))
    }

    pub fn is_reference(&self) -> bool {
        matches!(
            self,
            Type::Class(..) | Type::Array(_) | Type::Var(..) | Type::Null | Type::Infer(_)
        )
    }

    pub fn is_numeric(&self) -> bool {
        matches!(self, Type::Prim(p) if *p != Prim::Boolean)
    }

    pub fn is_class(&self, name: &str) -> bool {
        matches!(self, Type::Class(n, _) if &**n == name)
    }

    /// Two slots for `long` and `double` (JVMS 2.6.1).
    pub fn slots(&self) -> u16 {
        match self {
            Type::Prim(Prim::Long | Prim::Double) => 2,
            Type::Void => 0,
            _ => 1,
        }
    }

    /// The type with generics removed (JLS 4.6).
    pub fn erasure(&self) -> Type {
        match self {
            Type::Class(n, _) => Type::Class(n.clone(), Vec::new()),
            Type::Array(e) => Type::Array(Box::new(e.erasure())),
            Type::Var(_, bound) => Type::Class(bound.clone(), Vec::new()),
            Type::Infer(_) | Type::Null => Type::object(),
            other => other.clone(),
        }
    }

    /// The JVM descriptor of the erased type (JVMS 4.3.2).
    pub fn descriptor(&self) -> String {
        match self.erasure() {
            Type::Prim(p) => prim_descriptor(p).to_string(),
            Type::Void => "V".into(),
            Type::Class(n, _) => format!("L{n};"),
            Type::Array(e) => format!("[{}", e.descriptor()),
            _ => "Ljava/lang/Object;".into(),
        }
    }

    /// The internal name used by `checkcast`, `anewarray` and the like:
    /// a class name, or an array descriptor.
    pub fn internal(&self) -> String {
        match self.erasure() {
            Type::Class(n, _) => n.to_string(),
            other => other.descriptor(),
        }
    }

    /// Replaces type variables named in `map`.
    pub fn subst(&self, map: &HashMap<Rc<str>, Type>) -> Type {
        if map.is_empty() {
            return self.clone();
        }
        match self {
            Type::Var(n, _) => map.get(n).cloned().unwrap_or_else(|| self.clone()),
            Type::Class(n, args) => {
                Type::Class(n.clone(), args.iter().map(|a| a.subst(map)).collect())
            }
            Type::Array(e) => Type::Array(Box::new(e.subst(map))),
            other => other.clone(),
        }
    }

    /// Whether the type mentions an inference variable.
    pub fn has_infer(&self) -> bool {
        match self {
            Type::Infer(_) => true,
            Type::Class(_, args) => args.iter().any(Type::has_infer),
            Type::Array(e) => e.has_infer(),
            _ => false,
        }
    }
}

pub fn prim_descriptor(p: Prim) -> char {
    match p {
        Prim::Boolean => 'Z',
        Prim::Byte => 'B',
        Prim::Char => 'C',
        Prim::Short => 'S',
        Prim::Int => 'I',
        Prim::Long => 'J',
        Prim::Float => 'F',
        Prim::Double => 'D',
    }
}

pub fn prim_name(p: Prim) -> &'static str {
    match p {
        Prim::Boolean => "boolean",
        Prim::Byte => "byte",
        Prim::Char => "char",
        Prim::Short => "short",
        Prim::Int => "int",
        Prim::Long => "long",
        Prim::Float => "float",
        Prim::Double => "double",
    }
}

/// The wrapper class of a primitive (JLS 5.1.7).
pub fn boxed(p: Prim) -> &'static str {
    match p {
        Prim::Boolean => "java/lang/Boolean",
        Prim::Byte => "java/lang/Byte",
        Prim::Char => "java/lang/Character",
        Prim::Short => "java/lang/Short",
        Prim::Int => "java/lang/Integer",
        Prim::Long => "java/lang/Long",
        Prim::Float => "java/lang/Float",
        Prim::Double => "java/lang/Double",
    }
}

/// The primitive a wrapper class unboxes to (JLS 5.1.8).
pub fn unboxed(name: &str) -> Option<Prim> {
    [
        Prim::Boolean,
        Prim::Byte,
        Prim::Char,
        Prim::Short,
        Prim::Int,
        Prim::Long,
        Prim::Float,
        Prim::Double,
    ]
    .into_iter()
    .find(|&p| boxed(p) == name)
}

/// The Java spelling, for messages: `List<String>`, `int[]`.
impl fmt::Display for Type {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Type::Prim(p) => f.write_str(prim_name(*p)),
            Type::Void => f.write_str("void"),
            Type::Null => f.write_str("null"),
            Type::Class(n, args) => {
                let simple = n.rsplit(['/', '$']).next().unwrap_or(n);
                f.write_str(simple)?;
                if !args.is_empty() {
                    let parts: Vec<String> = args.iter().map(|a| a.to_string()).collect();
                    write!(f, "<{}>", parts.join(", "))?;
                }
                Ok(())
            }
            Type::Array(e) => write!(f, "{e}[]"),
            Type::Var(n, _) => f.write_str(n),
            Type::Infer(_) => f.write_str("?"),
        }
    }
}
