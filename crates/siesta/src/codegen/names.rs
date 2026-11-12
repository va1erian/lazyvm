//! Names in expressions (JLS 6.5.6): locals, fields of this class and its
//! outer classes, top-level variables, `import static`, classes and
//! packages; and the places an assignment can store to.

use std::rc::Rc;

use super::asm::VType;
use super::body::{Body, Local};
use crate::ast::{Expr, Ident};
use crate::classpath::ClassInfo;
use crate::diag::{Result, Span};
use crate::types::Type;

/// What the left side of a `.` is.
pub enum Recv {
    /// A value, already on the stack.
    Value(Type),
    /// A class: its static members and nested classes.
    Class(String),
    /// A package, by internal prefix (`java/util`).
    Package(String),
}

/// Somewhere a value can be stored. Emitting it pushes what the store
/// needs below the value: nothing, an object, or an array and an index.
#[derive(Clone)]
pub enum Place {
    Local(Local),
    Static {
        owner: Rc<str>,
        name: String,
        ty: Type,
        desc: String,
    },
    Field {
        owner: Rc<str>,
        name: String,
        ty: Type,
        desc: String,
    },
    Element(Type),
}

impl Place {
    pub fn ty(&self) -> Type {
        match self {
            Place::Local(l) => l.ty.clone(),
            Place::Static { ty, .. } | Place::Field { ty, .. } | Place::Element(ty) => ty.clone(),
        }
    }

    /// Slots of the operands under the value (for `dup_x`).
    pub fn below(&self) -> u16 {
        match self {
            Place::Local(l) if l.boxed => 2,
            Place::Local(_) | Place::Static { .. } => 0,
            Place::Field { .. } => 1,
            Place::Element(_) => 2,
        }
    }
}

impl Body<'_> {
    /// The field `name` of the class being compiled or one around it.
    pub(super) fn own_field(&self, name: &str) -> Option<(Rc<ClassInfo>, Type, bool)> {
        for outer in self.cls.enclosing.iter().rev() {
            let t = Type::class(outer);
            if let Some((owner, i, ty)) = self.w.field(&t, name) {
                let is_static = owner.fields[i].access & classfile::ACC_STATIC != 0;
                let ty = if **outer == *self.cls.name {
                    ty
                } else {
                    owner.fields[i].ty.clone()
                };
                return Some((owner, ty, is_static));
            }
        }
        // Top-level variables of the file's class, from the file's classes.
        for fc in self.file_classes() {
            if let Some(c) = self.w.class(&fc)
                && let Some(f) = c
                    .fields
                    .iter()
                    .find(|f| f.name == name && f.access & classfile::ACC_STATIC != 0)
            {
                let ty = f.ty.clone();
                return Some((c, ty, true));
            }
        }
        for (member, class) in &self.fscope.static_singles {
            if member == name
                && let Some((owner, _, ty)) = self.w.field(&Type::class(class), name)
            {
                return Some((owner, ty, true));
            }
        }
        for class in &self.fscope.static_on_demand {
            if let Some((owner, i, ty)) = self.w.field(&Type::class(class), name)
                && owner.fields[i].access & classfile::ACC_STATIC != 0
            {
                return Some((owner, ty, true));
            }
        }
        None
    }

    /// The value of static field `owner.name` if it is a source constant.
    fn constant(&self, owner: &str, name: &str) -> Option<crate::classpath::Constant> {
        let c = self.w.class(owner)?;
        let f = c.fields.iter().find(|f| f.name == name)?;
        f.constant.clone()
    }

    /// The file classes this code sees top-level members of: its own file's.
    pub fn file_classes(&self) -> Vec<Rc<str>> {
        self.extra.file_classes.clone()
    }

    pub fn place_of_name(&mut self, n: &Ident) -> Result<Option<Place>> {
        if let Some(l) = self.find_local(&n.name).cloned() {
            return Ok(Some(Place::Local(l)));
        }
        let Some((owner, ty, is_static)) = self.own_field(&n.name) else {
            return Ok(None);
        };
        let declared = owner
            .fields
            .iter()
            .find(|f| f.name == n.name)
            .map(|f| f.ty.descriptor())
            .unwrap_or_default();
        if is_static {
            return Ok(Some(Place::Static {
                owner: owner.name.clone(),
                name: n.name.clone(),
                ty,
                desc: declared,
            }));
        }
        if self.is_static {
            return self.err(
                n.span,
                format!("`{}` is an instance field; this code is static", n.name),
            );
        }
        if !self.cls.info.name.as_ref().eq(owner.name.as_ref())
            && !self.w.is_subclass(&self.cls.name, &owner.name)
        {
            return self.err(
                n.span,
                format!(
                    "`{}` belongs to an outer object; inner classes are not supported",
                    n.name
                ),
            );
        }
        Ok(Some(Place::Field {
            owner: self.cls.name.clone(),
            name: n.name.clone(),
            ty,
            desc: declared,
        }))
    }

    /// Pushes the operands a store to `p` needs, and returns it.
    pub fn place(&mut self, e: &Expr) -> Result<Place> {
        match e {
            Expr::Name(n) => {
                let Some(p) = self.place_of_name(n)? else {
                    return self.err(n.span, format!("no variable `{}`", n.name));
                };
                self.place_operands(&p);
                Ok(p)
            }
            Expr::Field { target, name } => match self.receiver(target)? {
                Recv::Value(t) => {
                    let Some((owner, i, ty)) = self.w.field(&t, &name.name) else {
                        return self.err(name.span, format!("{t} has no field `{}`", name.name));
                    };
                    self.check_access(&owner, owner.fields[i].access, name)?;
                    let desc = owner.fields[i].ty.descriptor();
                    if owner.fields[i].access & classfile::ACC_STATIC != 0 {
                        self.pop_value(&t);
                        return Ok(Place::Static {
                            owner: owner.name.clone(),
                            name: name.name.clone(),
                            ty,
                            desc,
                        });
                    }
                    Ok(Place::Field {
                        owner: t.erasure().internal().into(),
                        name: name.name.clone(),
                        ty,
                        desc,
                    })
                }
                Recv::Class(c) => {
                    let Some((owner, i, ty)) = self.w.field(&Type::class(&c), &name.name) else {
                        return self.err(
                            name.span,
                            format!("{} has no field `{}`", c.replace('/', "."), name.name),
                        );
                    };
                    self.check_access(&owner, owner.fields[i].access, name)?;
                    let desc = owner.fields[i].ty.descriptor();
                    Ok(Place::Static {
                        owner: owner.name.clone(),
                        name: name.name.clone(),
                        ty,
                        desc,
                    })
                }
                Recv::Package(_) => self.err(target.span(), "not a value"),
            },
            Expr::Index { array, index } => {
                let t = self.expr(array, None)?;
                let Type::Array(elem) = t else {
                    return self.err(array.span(), format!("a {t} is not an array"));
                };
                let it = self.expr(index, None)?;
                self.coerce(&it, &Type::Prim(crate::ast::Prim::Int), index.span())?;
                Ok(Place::Element(*elem))
            }
            other => self.err(other.span(), "cannot assign to this"),
        }
    }

    fn place_operands(&mut self, p: &Place) {
        match p {
            Place::Local(l) if l.boxed => {
                self.load(l.slot, &Type::Array(Box::new(l.ty.clone())));
                self.int_const(0);
            }
            Place::Field { .. } => self.load(0, &self.this_type()),
            _ => {}
        }
    }

    /// Loads from `p`, consuming its operands.
    pub fn place_load(&mut self, p: &Place) {
        match p {
            Place::Local(l) if l.boxed => self.array_load(&l.ty),
            Place::Local(l) => self.load(l.slot, &l.ty),
            Place::Static {
                owner,
                name,
                ty,
                desc,
            } => {
                if let Some(k) = self.constant(owner, name) {
                    // Out-of-range literals are reported where the field is initialised.
                    let _ = self.literal(&k.lit, Span::default(), Some(ty), k.negate);
                    return;
                }
                let f = self.pool.field_ref(owner, name, desc);
                self.asm
                    .emit_u2(0xb2, f, 0, Some(VType::of(&erased_from(desc))));
                self.cast_generic(desc, ty);
            }
            Place::Field {
                owner,
                name,
                ty,
                desc,
            } => {
                let f = self.pool.field_ref(owner, name, desc);
                self.asm
                    .emit_u2(0xb4, f, 1, Some(VType::of(&erased_from(desc))));
                self.cast_generic(desc, ty);
            }
            Place::Element(t) => self.array_load(t),
        }
    }

    /// Stores the value on the stack into `p`, consuming its operands.
    pub fn place_store(&mut self, p: &Place) {
        match p {
            Place::Local(l) if l.boxed => self.array_store(&l.ty),
            Place::Local(l) => self.store(l.slot, &l.ty),
            Place::Static {
                owner, name, desc, ..
            } => {
                let f = self.pool.field_ref(owner, name, desc);
                self.asm.emit_u2(0xb3, f, 1, None);
            }
            Place::Field {
                owner, name, desc, ..
            } => {
                let f = self.pool.field_ref(owner, name, desc);
                self.asm.emit_u2(0xb5, f, 2, None);
            }
            Place::Element(t) => self.array_store(t),
        }
    }

    /// Duplicates a place's operands, for a compound assignment.
    pub fn place_dup_operands(&mut self, p: &Place) {
        match p.below() {
            1 => self.asm.dup_under(0),
            2 => self.asm.dup_pair(),
            _ => {}
        }
    }

    /// A checkcast when a generic member's declared erasure is wider than
    /// the type it has here: `List<String>.get` declares `Object`.
    pub fn cast_generic(&mut self, declared_desc: &str, ty: &Type) {
        if ty.is_reference() && ty.descriptor() != declared_desc && !matches!(ty, Type::Null) {
            self.checkcast(&ty.erasure());
        }
    }

    pub fn check_access(&self, owner: &ClassInfo, access: u16, name: &Ident) -> Result<()> {
        if access & classfile::ACC_PRIVATE != 0 && *owner.name != *self.cls.name {
            return self.err(
                name.span,
                format!(
                    "`{}` is private to {}",
                    name.name,
                    owner.name.replace('/', ".")
                ),
            );
        }
        Ok(())
    }

    /// What `e` means before a `.`: a value (emitted), a class, a package.
    pub fn receiver(&mut self, e: &Expr) -> Result<Recv> {
        match e {
            Expr::Name(n) => {
                if self.find_local(&n.name).is_some() || self.own_field(&n.name).is_some() {
                    return Ok(Recv::Value(self.expr(e, None)?));
                }
                if let Some(c) = self.fscope.class(self.w, &n.name, &self.cls.enclosing) {
                    return Ok(Recv::Class(c));
                }
                Ok(Recv::Package(n.name.clone()))
            }
            Expr::TypeExpr(t) => {
                let t = self.resolve_type(t)?;
                Ok(Recv::Class(t.internal()))
            }
            Expr::Field { target, name } => match self.receiver(target)? {
                Recv::Package(p) => {
                    let c = format!("{p}/{}", name.name);
                    Ok(if self.w.exists(&c) {
                        Recv::Class(c)
                    } else {
                        Recv::Package(c)
                    })
                }
                Recv::Class(c) => {
                    let nested = format!("{c}${}", name.name);
                    if self.w.field(&Type::class(&c), &name.name).is_none()
                        && self.w.exists(&nested)
                    {
                        return Ok(Recv::Class(nested));
                    }
                    let p = self.place_on_class(&c, name)?;
                    self.place_load(&p);
                    Ok(Recv::Value(p.ty()))
                }
                Recv::Value(t) => Ok(Recv::Value(self.field_of_value(t, name)?)),
            },
            other => Ok(Recv::Value(self.expr(other, None)?)),
        }
    }

    pub fn place_on_class(&mut self, c: &str, name: &Ident) -> Result<Place> {
        let Some((owner, i, ty)) = self.w.field(&Type::class(c), &name.name) else {
            return self.err(
                name.span,
                format!("{} has no field `{}`", c.replace('/', "."), name.name),
            );
        };
        self.check_access(&owner, owner.fields[i].access, name)?;
        let desc = owner.fields[i].ty.descriptor();
        Ok(Place::Static {
            owner: owner.name.clone(),
            name: name.name.clone(),
            ty,
            desc,
        })
    }

    /// `value.name` with the value on the stack: a field, or an array's length.
    pub fn field_of_value(&mut self, t: Type, name: &Ident) -> Result<Type> {
        if let Type::Array(_) = t
            && name.name == "length"
        {
            self.asm.emit(&[0xbe], 1, Some(VType::Int));
            return Ok(Type::Prim(crate::ast::Prim::Int));
        }
        let Some((owner, i, ty)) = self.w.field(&t, &name.name) else {
            return self.err(name.span, format!("{t} has no field `{}`", name.name));
        };
        self.check_access(&owner, owner.fields[i].access, name)?;
        let desc = owner.fields[i].ty.descriptor();
        let p = if owner.fields[i].access & classfile::ACC_STATIC != 0 {
            self.pop_value(&t);
            Place::Static {
                owner: owner.name.clone(),
                name: name.name.clone(),
                ty: ty.clone(),
                desc,
            }
        } else {
            Place::Field {
                owner: t.erasure().internal().into(),
                name: name.name.clone(),
                ty: ty.clone(),
                desc,
            }
        };
        self.place_load(&p);
        Ok(ty)
    }
}

fn erased_from(desc: &str) -> Type {
    crate::sig::field_type(desc, &Default::default()).unwrap_or_else(Type::object)
}
