//! The syntax tree, as parsed: names are not resolved and nothing is typed.

use crate::diag::Span;
use crate::lex::Num;

#[derive(Clone, Debug, PartialEq)]
pub struct Ident {
    pub name: String,
    pub span: Span,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Prim {
    Boolean,
    Byte,
    Char,
    Short,
    Int,
    Long,
    Float,
    Double,
}

#[derive(Clone, Debug, PartialEq)]
pub enum TypeRef {
    Prim(Prim, Span),
    /// `a.b.C<T>`; type arguments belong to the last segment.
    Named {
        path: Vec<Ident>,
        args: Vec<TypeArg>,
        span: Span,
    },
    Array(Box<TypeRef>, Span),
    /// `var`: the initializer's type.
    Var(Span),
    Void(Span),
}

impl TypeRef {
    pub fn span(&self) -> Span {
        match self {
            TypeRef::Prim(_, s) | TypeRef::Named { span: s, .. } | TypeRef::Array(_, s) => *s,
            TypeRef::Var(s) | TypeRef::Void(s) => *s,
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum TypeArg {
    Type(TypeRef),
    /// `?`, `? extends T` (`upper` true) or `? super T`.
    Wild {
        bound: Option<Box<TypeRef>>,
        upper: bool,
        span: Span,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub struct TypeParam {
    pub name: Ident,
    pub bounds: Vec<TypeRef>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Annotation {
    pub name: Vec<Ident>,
    /// `(value)` is one element named `value`.
    pub args: Vec<(Ident, Expr)>,
    pub span: Span,
}

/// Modifier bits; `ASYNC` is Siesta's own.
pub mod mods {
    pub const PUBLIC: u16 = 0x0001;
    pub const PRIVATE: u16 = 0x0002;
    pub const PROTECTED: u16 = 0x0004;
    pub const STATIC: u16 = 0x0008;
    pub const FINAL: u16 = 0x0010;
    pub const SYNCHRONIZED: u16 = 0x0020;
    pub const ABSTRACT: u16 = 0x0400;
    pub const DEFAULT: u16 = 0x2000;
    pub const ASYNC: u16 = 0x8000;
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Modifiers {
    pub bits: u16,
    pub annotations: Vec<Annotation>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TypeKind {
    Class,
    Interface,
    Enum,
}

#[derive(Clone, Debug, PartialEq)]
pub struct TypeDecl {
    pub kind: TypeKind,
    pub mods: Modifiers,
    pub name: Ident,
    pub tparams: Vec<TypeParam>,
    /// The superclass, or for an interface its superinterfaces.
    pub extends: Vec<TypeRef>,
    pub implements: Vec<TypeRef>,
    pub constants: Vec<EnumConst>,
    pub members: Vec<Member>,
    pub span: Span,
}

#[derive(Clone, Debug, PartialEq)]
pub struct EnumConst {
    pub name: Ident,
    pub args: Vec<Expr>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Member {
    Field(FieldDecl),
    Method(MethodDecl),
    Type(TypeDecl),
}

#[derive(Clone, Debug, PartialEq)]
pub struct FieldDecl {
    pub mods: Modifiers,
    pub ty: TypeRef,
    pub name: Ident,
    pub init: Option<Expr>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Param {
    pub ty: TypeRef,
    pub name: Ident,
    pub varargs: bool,
}

/// A method; a constructor has `ret: None` and its class's name.
#[derive(Clone, Debug, PartialEq)]
pub struct MethodDecl {
    pub mods: Modifiers,
    pub tparams: Vec<TypeParam>,
    pub ret: Option<TypeRef>,
    pub name: Ident,
    pub params: Vec<Param>,
    pub body: Option<Vec<Stmt>>,
    pub span: Span,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Import {
    pub path: Vec<Ident>,
    pub wildcard: bool,
    pub is_static: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Item {
    Type(TypeDecl),
    Method(MethodDecl),
    /// A top-level variable: a static field of the file's class.
    Field(FieldDecl),
    Stmt(Stmt),
}

#[derive(Clone, Debug, PartialEq)]
pub struct File {
    pub file: u32,
    pub package: Vec<Ident>,
    pub imports: Vec<Import>,
    pub items: Vec<Item>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Stmt {
    Local {
        ty: TypeRef,
        name: Ident,
        init: Expr,
    },
    Expr(Expr),
    Block(Vec<Stmt>, Span),
    If {
        cond: Expr,
        then: Box<Stmt>,
        els: Option<Box<Stmt>>,
    },
    While {
        cond: Expr,
        body: Box<Stmt>,
    },
    DoWhile {
        body: Box<Stmt>,
        cond: Expr,
    },
    For {
        init: Vec<Stmt>,
        cond: Option<Expr>,
        update: Vec<Expr>,
        body: Box<Stmt>,
    },
    ForEach {
        ty: TypeRef,
        name: Ident,
        iter: Expr,
        body: Box<Stmt>,
    },
    /// Arrow cases only; a case with no labels is `default`.
    Switch {
        scrut: Expr,
        cases: Vec<Case>,
        span: Span,
    },
    Break(Span),
    Continue(Span),
    Return(Option<Expr>, Span),
    Throw(Expr, Span),
    Try {
        body: Vec<Stmt>,
        catches: Vec<Catch>,
        finally: Option<Vec<Stmt>>,
        span: Span,
    },
    /// `this(...)` or `super(...)` as a constructor's first statement.
    CtorCall {
        is_super: bool,
        args: Vec<Expr>,
        span: Span,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub struct Case {
    pub labels: Vec<Expr>,
    pub body: Stmt,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Catch {
    pub types: Vec<TypeRef>,
    pub name: Ident,
    pub body: Vec<Stmt>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Lit {
    Num(Num),
    Char(u16),
    Str(String),
    Bool(bool),
    Null,
}

#[derive(Clone, Debug, PartialEq)]
pub enum TemplatePart {
    Text(String),
    Expr(Expr),
}

#[derive(Clone, Debug, PartialEq)]
pub enum LambdaBody {
    Expr(Box<Expr>),
    Block(Vec<Stmt>),
}

#[derive(Clone, Debug, PartialEq)]
pub struct LambdaParam {
    pub ty: Option<TypeRef>,
    pub name: Ident,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Expr {
    Lit(Lit, Span),
    Template(Vec<TemplatePart>, Span),
    /// A simple name: a local, a field, a class or a package, resolved later.
    Name(Ident),
    This(Span),
    /// `target.name`; also a qualified name before it is resolved.
    Field {
        target: Box<Expr>,
        name: Ident,
    },
    /// `target.name(args)`, `name(args)`, or `super.name(args)`.
    Call {
        target: Option<Box<Expr>>,
        name: Ident,
        args: Vec<Expr>,
        is_super: bool,
    },
    New {
        ty: TypeRef,
        args: Vec<Expr>,
        span: Span,
    },
    NewArray {
        elem: TypeRef,
        dims: Vec<Expr>,
        extra: usize,
        init: Option<Vec<Expr>>,
        span: Span,
    },
    /// `{a, b}` where an array is expected.
    ArrayInit(Vec<Expr>, Span),
    Index {
        array: Box<Expr>,
        index: Box<Expr>,
    },
    Unary {
        op: &'static str,
        expr: Box<Expr>,
        span: Span,
    },
    /// `++x` or `--x` (`post` false), `x++` or `x--`.
    Step {
        op: &'static str,
        post: bool,
        target: Box<Expr>,
        span: Span,
    },
    Binary {
        op: &'static str,
        left: Box<Expr>,
        right: Box<Expr>,
    },
    /// `=`, or a compound operator such as `+=` (`op` is `"+"`).
    Assign {
        op: Option<&'static str>,
        target: Box<Expr>,
        value: Box<Expr>,
    },
    Cond {
        cond: Box<Expr>,
        then: Box<Expr>,
        els: Box<Expr>,
    },
    Cast {
        ty: TypeRef,
        expr: Box<Expr>,
        span: Span,
    },
    InstanceOf {
        expr: Box<Expr>,
        ty: TypeRef,
        bind: Option<Ident>,
    },
    Lambda {
        params: Vec<LambdaParam>,
        body: LambdaBody,
        is_async: bool,
        span: Span,
    },
    /// `Type::name`, `expr::name`, `Type::new`.
    MethodRef {
        target: Box<Expr>,
        name: Ident,
    },
    ClassLit(TypeRef, Span),
    Await(Box<Expr>, Span),
    /// `recv..a(x)..b(y)`: each call goes to `recv`; the value is `recv`.
    Cascade {
        recv: Box<Expr>,
        calls: Vec<(Ident, Vec<Expr>)>,
    },
    List(Vec<Expr>, Span),
    Map(Vec<(Expr, Expr)>, Span),
    /// A type in expression position: `int[]::new`, generic `List<T>::of`.
    TypeExpr(TypeRef),
}

impl Expr {
    pub fn span(&self) -> Span {
        match self {
            Expr::Lit(_, s) | Expr::Template(_, s) | Expr::This(s) => *s,
            Expr::Name(i) => i.span,
            Expr::Field { target, name } => target.span().to(name.span),
            Expr::Call {
                target, name, args, ..
            } => {
                let lo = target.as_ref().map_or(name.span, |t| t.span());
                args.last().map_or(lo.to(name.span), |a| lo.to(a.span()))
            }
            Expr::New { span, .. } | Expr::NewArray { span, .. } | Expr::ArrayInit(_, span) => {
                *span
            }
            Expr::Index { array, index } => array.span().to(index.span()),
            Expr::Unary { span, .. } | Expr::Step { span, .. } | Expr::Cast { span, .. } => *span,
            Expr::Binary { left, right, .. } => left.span().to(right.span()),
            Expr::Assign { target, value, .. } => target.span().to(value.span()),
            Expr::Cond { cond, els, .. } => cond.span().to(els.span()),
            Expr::InstanceOf { expr, ty, .. } => expr.span().to(ty.span()),
            Expr::Lambda { span, .. } | Expr::ClassLit(_, span) | Expr::Await(_, span) => *span,
            Expr::MethodRef { target, name } => target.span().to(name.span),
            Expr::Cascade { recv, calls } => calls
                .last()
                .map_or(recv.span(), |(n, _)| recv.span().to(n.span)),
            Expr::List(_, s) | Expr::Map(_, s) => *s,
            Expr::TypeExpr(t) => t.span(),
        }
    }
}
