//! Method and constructor calls (JLS 15.12, 15.9): finding the candidates,
//! emitting the receiver and the arguments, varargs, and the invoke
//! instruction.

use super::asm::VType;
use super::body::Body;
use super::infer::Chosen;
use super::names::Recv;
use crate::ast::{Expr, Ident, TypeRef};
use crate::diag::{Result, Span};
use crate::types::{OBJECT, Type};
use crate::world::Method;

impl Body<'_> {
    pub fn call(
        &mut self,
        target: Option<&Expr>,
        name: &Ident,
        args: &[Expr],
        is_super: bool,
        want: Option<&Type>,
    ) -> Result<Type> {
        if is_super {
            if self.is_static {
                return self.err(name.span, "`super` in static code");
            }
            let sup = self
                .cls
                .info
                .sig
                .superclass
                .clone()
                .unwrap_or_else(Type::object);
            let cands: Vec<Method> = self
                .w
                .methods(&sup, &name.name)
                .into_iter()
                .filter(|m| !m.info.is_static())
                .collect();
            let ch = self.select(
                cands,
                args,
                want,
                &format!("super.{}", name.name),
                name.span,
            )?;
            self.load(0, &self.this_type());
            return self.finish_call(ch, args, 0xb7, &sup.erasure().internal(), name.span);
        }
        match target {
            Some(t) => match self.receiver(t)? {
                Recv::Value(rt) => self.call_on(rt, name, args, want),
                Recv::Class(c) => {
                    let ct = Type::class(&c);
                    let cands: Vec<Method> = self
                        .w
                        .methods(&ct, &name.name)
                        .into_iter()
                        .filter(|m| m.info.is_static())
                        .collect();
                    if cands.is_empty() && !self.w.methods(&ct, &name.name).is_empty() {
                        return self.err(
                            name.span,
                            format!(
                                "`{}` is an instance method; call it on an object",
                                name.name
                            ),
                        );
                    }
                    let ch = self.select(
                        cands,
                        args,
                        want,
                        &format!("{}.{}", short(&c), name.name),
                        name.span,
                    )?;
                    self.finish_call(ch, args, 0xb8, &c, name.span)
                }
                Recv::Package(p) => self.err(
                    t.span(),
                    format!("no class or variable {}", p.replace('/', ".")),
                ),
            },
            None => self.call_unqualified(name, args, want),
        }
    }

    /// `name(args)` with no receiver: this class and its supertypes, then
    /// outer classes, then the top-level methods of the files, then
    /// `import static`.
    fn call_unqualified(
        &mut self,
        name: &Ident,
        args: &[Expr],
        want: Option<&Type>,
    ) -> Result<Type> {
        let mut scopes: Vec<(Type, bool)> = Vec::new();
        for (i, outer) in self.cls.enclosing.iter().enumerate().rev() {
            let own = i + 1 == self.cls.enclosing.len();
            scopes.push((
                if own {
                    self.this_type()
                } else {
                    Type::class(outer)
                },
                !own,
            ));
        }
        for fc in self.file_classes() {
            scopes.push((Type::class(&fc), true));
        }
        for (member, class) in self.fscope.static_singles.clone() {
            if member == name.name {
                scopes.push((Type::class(&class), true));
            }
        }
        for class in self.fscope.static_on_demand.clone() {
            scopes.push((Type::class(&class), true));
        }
        for (t, static_only) in scopes {
            let cands: Vec<Method> = self
                .w
                .methods(&t, &name.name)
                .into_iter()
                .filter(|m| !static_only || m.info.is_static())
                .collect();
            if cands.is_empty() {
                continue;
            }
            let ch = self.select(cands, args, want, &format!("`{}`", name.name), name.span)?;
            if ch.method.info.is_static() {
                let owner = ch.method.owner.name.to_string();
                return self.finish_call(ch, args, 0xb8, &owner, name.span);
            }
            if self.is_static {
                return self.err(
                    name.span,
                    format!("`{}` is an instance method; this code is static", name.name),
                );
            }
            self.load(0, &self.this_type());
            return self.invoke_virtual(ch, args, &self.this_type(), name.span);
        }
        self.err(name.span, format!("no method `{}`", name.name))
    }

    /// `recv.name(args)` with the receiver of type `rt` on the stack.
    pub fn call_on(
        &mut self,
        rt: Type,
        name: &Ident,
        args: &[Expr],
        want: Option<&Type>,
    ) -> Result<Type> {
        let lookup = match &rt {
            Type::Prim(p) => {
                // `n.toString()`: the receiver is boxed (siesta.md section 3).
                self.box_prim(*p);
                Type::class(crate::types::boxed(*p))
            }
            Type::Array(_) => Type::object(),
            Type::Null => return self.err(name.span, "a method call on `null`"),
            other => other.clone(),
        };
        let cands = self.w.methods(&lookup, &name.name);
        let ch = self.select(
            cands,
            args,
            want,
            &format!("{lookup}.{}", name.name),
            name.span,
        )?;
        if ch.method.info.is_static() {
            self.pop_value(&lookup);
            let owner = ch.method.owner.name.to_string();
            return self.finish_call(ch, args, 0xb8, &owner, name.span);
        }
        self.invoke_virtual(ch, args, &lookup, name.span)
    }

    fn invoke_virtual(
        &mut self,
        ch: Chosen,
        args: &[Expr],
        recv: &Type,
        span: Span,
    ) -> Result<Type> {
        let owner_iface = ch.method.owner.is_interface();
        let recv_class = recv.erasure().internal();
        let recv_iface = self.w.class(&recv_class).is_some_and(|c| c.is_interface());
        let private = ch.method.info.access & classfile::ACC_PRIVATE != 0;
        if private && *ch.method.owner.name != *self.cls.name {
            return self.err(
                span,
                format!(
                    "`{}` is private to {}",
                    ch.method.info.name,
                    short(&ch.method.owner.name)
                ),
            );
        }
        // Object's methods on an interface go through Object (JLS 9.2).
        let (op, class) = if private {
            (0xb7, recv_class)
        } else if &*ch.method.owner.name == OBJECT && recv_iface {
            (0xb6, OBJECT.to_string())
        } else if recv_iface || (owner_iface && recv_class == OBJECT) {
            (0xb9, recv_class)
        } else {
            (0xb6, recv_class)
        };
        self.finish_call(ch, args, op, &class, span)
    }

    /// Emits the arguments and the invoke instruction, and returns the
    /// call's type.
    pub fn finish_call(
        &mut self,
        ch: Chosen,
        args: &[Expr],
        op: u8,
        class: &str,
        span: Span,
    ) -> Result<Type> {
        self.emit_args(&ch, args)?;
        let m = &ch.method.info;
        let iface = op == 0xb9 || (op != 0xb6 && ch.method.owner.is_interface());
        let r = if iface {
            self.pool.interface_method_ref(class, &m.name, &m.desc)
        } else {
            self.pool.method_ref(class, &m.name, &m.desc)
        };
        let md = classfile::descriptor::parse_method(&m.desc).unwrap_or_else(|_| {
            classfile::descriptor::MethodDescriptor {
                params: Vec::new(),
                ret: None,
            }
        });
        let pops = md.params.len() + usize::from(op != 0xb8);
        let declared_ret =
            crate::sig::method_sig(&m.desc, &Default::default()).map_or(Type::Void, |s| s.ret);
        let push = (declared_ret != Type::Void).then(|| VType::of(&declared_ret));
        if op == 0xb9 {
            let [h, l] = r.to_be_bytes();
            let count = u8::try_from(md.arg_slots() + 1).unwrap_or(255);
            self.asm.emit(&[0xb9, h, l, count, 0], pops, push);
        } else {
            self.asm.emit_u2(op, r, pops, push);
        }
        let _ = span;
        if ch.ret != Type::Void
            && declared_ret.descriptor() != ch.ret.descriptor()
            && ch.ret.is_reference()
        {
            self.checkcast(&ch.ret.erasure());
        }
        Ok(ch.ret)
    }

    fn emit_args(&mut self, ch: &Chosen, args: &[Expr]) -> Result<()> {
        let n = ch.params.len();
        let fixed = if ch.varargs { n - 1 } else { n };
        for (a, p) in args.iter().zip(&ch.params).take(fixed) {
            let t = self.expr(a, Some(p))?;
            self.coerce(&t, p, a.span())?;
        }
        if ch.varargs {
            let Some(Type::Array(elem)) = ch.params.last() else {
                return Ok(());
            };
            let rest = &args[fixed.min(args.len())..];
            self.int_const(i32::try_from(rest.len()).unwrap_or(0));
            self.new_array(elem);
            for (i, a) in rest.iter().enumerate() {
                self.asm.dup_under(0);
                self.int_const(i as i32);
                let t = self.expr(a, Some(elem))?;
                self.coerce(&t, elem, a.span())?;
                self.array_store(elem);
            }
        }
        Ok(())
    }

    pub fn new_object(
        &mut self,
        ty: &TypeRef,
        args: &[Expr],
        span: Span,
        want: Option<&Type>,
    ) -> Result<Type> {
        let mut t = self.resolve_type(ty)?;
        let (name, targs) = match &t {
            Type::Class(n, a) => (n.clone(), a.clone()),
            _ => return self.err(span, format!("cannot create a {t} with `new`")),
        };
        let name = &name;
        let info = self
            .w
            .class(name)
            .ok_or_else(|| crate::diag::Diag::new(span, format!("no class {name}")))?;
        if info.access & (classfile::ACC_ABSTRACT | classfile::ACC_INTERFACE) != 0 {
            return self.err(
                span,
                format!(
                    "{} is abstract; it cannot be created with `new`",
                    short(name)
                ),
            );
        }
        if info.access & classfile::ACC_ENUM != 0 {
            return self.err(span, "enum constants are created by the enum itself");
        }
        // `new HashMap<>()` and raw `new HashMap()` take the expected type's
        // arguments, as Java's diamond does.
        if targs.is_empty()
            && !info.sig.params.is_empty()
            && let Some(a) = self.diamond(&info, want)
        {
            t = Type::Class(name.clone(), a);
        }
        let cands: Vec<Method> = self
            .w
            .methods(&t, "<init>")
            .into_iter()
            .filter(|m| *m.owner.name == **name)
            .collect();
        let ch = self.select(
            cands,
            args,
            None,
            &format!("constructor of {}", short(name)),
            span,
        )?;
        if ch.method.info.access & classfile::ACC_PRIVATE != 0
            && *ch.method.owner.name != *self.cls.name
        {
            return self.err(
                span,
                format!("the constructor of {} is private", short(name)),
            );
        }
        let c = self.pool.class(name);
        self.asm.new_object(c);
        let was = self.asm.top().cloned();
        self.asm.dup_under(0);
        let owner = name.to_string();
        self.finish_call(ch, args, 0xb7, &owner, span)?;
        if let Some(w) = was {
            self.asm.initialised(&w, name);
        }
        Ok(t)
    }

    /// The type arguments a generic class gets from the expected type,
    /// when the expected type is the class itself or one of its supertypes
    /// with arguments that map straight back.
    fn diamond(
        &self,
        info: &crate::classpath::ClassInfo,
        want: Option<&Type>,
    ) -> Option<Vec<Type>> {
        let w = want?;
        let Type::Class(wn, wargs) = w else {
            return None;
        };
        let sup = self.w.as_super(&info.this_type(), wn)?;
        let Type::Class(_, sargs) = sup else {
            return None;
        };
        let mut out: Vec<Option<Type>> = vec![None; info.sig.params.len()];
        for (s, a) in sargs.iter().zip(wargs) {
            if let Type::Var(v, _) = s
                && let Some(i) = info.sig.params.iter().position(|p| p.name == *v)
            {
                out[i] = Some(a.clone());
            }
        }
        out.into_iter().collect()
    }
}

fn short(internal: &str) -> String {
    internal
        .rsplit('/')
        .next()
        .unwrap_or(internal)
        .replace('$', ".")
}
