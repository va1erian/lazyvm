//! Files, imports and declarations (JLS 7, 8 and 9, as siesta.md section 5
//! narrows them), and Siesta's top level of methods, fields and statements.

use super::Parser;
use crate::ast::{
    Annotation, EnumConst, Expr, FieldDecl, File, Ident, Import, Item, Member, MethodDecl,
    Modifiers, Param, TypeDecl, TypeKind, TypeRef, mods,
};
use crate::diag::{Diag, Result};
use crate::lex::{Kw, Tok};

impl Parser {
    pub(super) fn file_unit(&mut self) -> Result<File> {
        let mut package = Vec::new();
        if self.eat_kw(Kw::Package) {
            package = self.qualified()?;
            self.expect_op(";")?;
        }
        let mut imports = Vec::new();
        while self.eat_kw(Kw::Import) {
            imports.push(self.import()?);
        }
        let mut items = Vec::new();
        while *self.tok(0) != Tok::Eof {
            items.push(self.top_item()?);
        }
        Ok(File {
            file: self.file,
            package,
            imports,
            items,
        })
    }

    fn import(&mut self) -> Result<Import> {
        let is_static = self.eat_kw(Kw::Static);
        let mut path = vec![self.ident()?];
        let mut wildcard = false;
        while self.eat_op(".") {
            if self.eat_op("*") {
                wildcard = true;
                break;
            }
            path.push(self.ident()?);
        }
        self.expect_op(";")?;
        Ok(Import {
            path,
            wildcard,
            is_static,
        })
    }

    fn top_item(&mut self) -> Result<Item> {
        let start = self.pos;
        let m = self.modifiers()?;
        if self.at_type_keyword() {
            return Ok(Item::Type(self.type_decl(m)?));
        }
        if self.is_op("<") || self.at_declaration() {
            return match self.member_after_mods(m, None)? {
                Member::Field(f) => Ok(Item::Field(f)),
                Member::Method(md) => Ok(Item::Method(md)),
                Member::Type(t) => Ok(Item::Type(t)),
            };
        }
        if self.pos != start || m.bits != 0 || !m.annotations.is_empty() {
            return self.fail("a declaration expected after modifiers");
        }
        Ok(Item::Stmt(self.stmt()?))
    }

    fn at_type_keyword(&self) -> bool {
        self.is_kw(Kw::Class) || self.is_kw(Kw::Interface) || self.is_kw(Kw::Enum)
    }

    pub(super) fn modifiers(&mut self) -> Result<Modifiers> {
        let mut m = Modifiers::default();
        loop {
            let async_mod = self.is_kw(Kw::Async) && self.at_async_method();
            let bit = match self.tok(0).clone() {
                Tok::Op("@") => {
                    m.annotations.push(self.annotation()?);
                    continue;
                }
                Tok::Kw(Kw::Public) => mods::PUBLIC,
                Tok::Kw(Kw::Private) => mods::PRIVATE,
                Tok::Kw(Kw::Protected) => mods::PROTECTED,
                Tok::Kw(Kw::Static) => mods::STATIC,
                Tok::Kw(Kw::Final) => mods::FINAL,
                Tok::Kw(Kw::Abstract) => mods::ABSTRACT,
                Tok::Kw(Kw::Synchronized) => mods::SYNCHRONIZED,
                Tok::Kw(Kw::Async) if async_mod => mods::ASYNC,
                Tok::Kw(Kw::Default) if !self.is_op_at(1, "->") => mods::DEFAULT,
                _ => return Ok(m),
            };
            if m.bits & bit != 0 {
                return self.fail("repeated modifier");
            }
            m.bits |= bit;
            self.pos += 1;
        }
    }

    /// `async` before a declaration, as opposed to an `async` lambda.
    fn at_async_method(&mut self) -> bool {
        self.pos += 1;
        let yes = self.is_op("<") || self.at_declaration();
        self.pos -= 1;
        yes
    }

    fn annotation(&mut self) -> Result<Annotation> {
        let lo = self.expect_op("@")?;
        let name = self.qualified()?;
        let mut args = Vec::new();
        if self.eat_op("(") {
            if !self.is_op(")") {
                if matches!(self.tok(0), Tok::Ident(_)) && self.is_op_at(1, "=") {
                    loop {
                        let key = self.ident()?;
                        self.expect_op("=")?;
                        args.push((key, self.element_value()?));
                        if !self.eat_op(",") {
                            break;
                        }
                    }
                } else {
                    let value = self.element_value()?;
                    args.push((
                        Ident {
                            name: "value".into(),
                            span: value.span(),
                        },
                        value,
                    ));
                }
            }
            self.expect_op(")")?;
        }
        Ok(Annotation {
            name,
            args,
            span: lo.to(self.prev_span()),
        })
    }

    fn element_value(&mut self) -> Result<Expr> {
        if self.is_op("{") {
            self.array_init()
        } else {
            self.expr()
        }
    }

    fn type_decl(&mut self, mods: Modifiers) -> Result<TypeDecl> {
        let lo = self.span();
        let kind = match self.bump().tok {
            Tok::Kw(Kw::Interface) => TypeKind::Interface,
            Tok::Kw(Kw::Enum) => TypeKind::Enum,
            _ => TypeKind::Class,
        };
        let name = self.ident()?;
        let tparams = self.type_params()?;
        let mut extends = Vec::new();
        if self.eat_kw(Kw::Extends) {
            extends = self.type_list()?;
            if kind == TypeKind::Class && extends.len() > 1 {
                return Err(Diag::new(name.span, "a class extends one class"));
            }
        }
        let implements = if self.eat_kw(Kw::Implements) {
            self.type_list()?
        } else {
            Vec::new()
        };
        self.expect_op("{")?;
        let constants = if kind == TypeKind::Enum {
            self.enum_constants()?
        } else {
            Vec::new()
        };
        let mut members = Vec::new();
        while !self.eat_op("}") {
            if *self.tok(0) == Tok::Eof {
                return self.fail("`}` expected");
            }
            if self.eat_op(";") {
                continue;
            }
            let m = self.modifiers()?;
            members.push(self.nested(|p| p.member_after_mods(m, Some(&name.name)))?);
        }
        let span = lo.to(self.prev_span());
        Ok(TypeDecl {
            kind,
            mods,
            name,
            tparams,
            extends,
            implements,
            constants,
            members,
            span,
        })
    }

    fn type_list(&mut self) -> Result<Vec<TypeRef>> {
        let mut out = vec![self.ty()?];
        while self.eat_op(",") {
            out.push(self.ty()?);
        }
        Ok(out)
    }

    fn enum_constants(&mut self) -> Result<Vec<EnumConst>> {
        let mut out = Vec::new();
        while let Tok::Ident(_) = self.tok(0) {
            let name = self.ident()?;
            let args = if self.is_op("(") {
                self.args()?
            } else {
                Vec::new()
            };
            if self.is_op("{") {
                return self.fail("enum constants with bodies are not supported");
            }
            out.push(EnumConst { name, args });
            if !self.eat_op(",") {
                break;
            }
        }
        if !self.is_op("}") {
            self.expect_op(";")?;
        }
        Ok(out)
    }

    /// A field, method, constructor or nested type, after its modifiers.
    /// `class_name` is `None` at the top level, where there are no
    /// constructors.
    fn member_after_mods(&mut self, mods: Modifiers, class_name: Option<&str>) -> Result<Member> {
        if self.at_type_keyword() {
            return Ok(Member::Type(self.type_decl(mods)?));
        }
        let lo = self.span();
        let tparams = self.type_params()?;
        let is_ctor = matches!(self.tok(0), Tok::Ident(n) if Some(n.as_str()) == class_name)
            && self.is_op_at(1, "(");
        let ret = if is_ctor { None } else { Some(self.ty()?) };
        let name = self.ident()?;
        if self.is_op("(") {
            let params = self.params()?;
            if self.eat_kw(Kw::Throws) {
                self.type_list()?;
            }
            let body = if self.eat_op(";") {
                None
            } else {
                Some(self.block()?)
            };
            let span = lo.to(self.prev_span());
            return Ok(Member::Method(MethodDecl {
                mods,
                tparams,
                ret,
                name,
                params,
                body,
                span,
            }));
        }
        let Some(ty) = ret.filter(|_| tparams.is_empty()) else {
            return Err(Diag::new(name.span, "`(` expected"));
        };
        if matches!(ty, TypeRef::Void(_)) {
            return Err(Diag::new(name.span, "a field cannot be `void`"));
        }
        let init = if self.eat_op("=") {
            Some(self.var_init()?)
        } else {
            None
        };
        if matches!(ty, TypeRef::Var(_)) && init.is_none() {
            return Err(Diag::new(name.span, "`var` needs an initialiser"));
        }
        if self.is_op(",") {
            return self.fail("one variable per declaration");
        }
        self.expect_op(";")?;
        Ok(Member::Field(FieldDecl {
            mods,
            ty,
            name,
            init,
        }))
    }

    fn params(&mut self) -> Result<Vec<Param>> {
        self.expect_op("(")?;
        let mut out = Vec::new();
        if self.eat_op(")") {
            return Ok(out);
        }
        loop {
            let m = self.modifiers()?;
            if m.bits & !mods::FINAL != 0 {
                return self.fail("only `final` may mark a parameter");
            }
            let ty = self.ty()?;
            let varargs = self.eat_op("...");
            let name = self.ident()?;
            let ty = self.array_dims(ty)?;
            out.push(Param { ty, name, varargs });
            if varargs || !self.eat_op(",") {
                break;
            }
        }
        self.expect_op(")")?;
        Ok(out)
    }

    /// An initializer: an expression, or `{...}` for an array.
    pub(super) fn var_init(&mut self) -> Result<Expr> {
        if self.is_op("{") {
            self.array_init()
        } else {
            self.expr()
        }
    }

    pub(super) fn array_init(&mut self) -> Result<Expr> {
        let lo = self.expect_op("{")?;
        let mut items = Vec::new();
        while !self.is_op("}") {
            items.push(self.nested(|p| p.var_init())?);
            if !self.eat_op(",") {
                break;
            }
        }
        self.expect_op("}")?;
        Ok(Expr::ArrayInit(items, lo.to(self.prev_span())))
    }
}
