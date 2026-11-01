//! Constant pool resolution (JVMS 5.4.3, with the access checks of 5.4.4) and
//! method selection (JVMS 5.4.6).

use super::{ClassId, MethodRef};
use crate::value::Ref;
use crate::{Vm, VmError, VmResult};

#[derive(Clone, Copy, Debug)]
pub enum Resolved {
    Class(ClassId),
    Field {
        class: ClassId,
        slot: u32,
        is_static: bool,
    },
    Method(MethodRef),
    String(Ref),
}

const NO_SUCH_METHOD: &str = "java/lang/NoSuchMethodError";
const INCOMPATIBLE: &str = "java/lang/IncompatibleClassChangeError";

impl Vm {
    pub(crate) fn cached(&self, from: ClassId, index: u16) -> Option<Resolved> {
        self.class(from)
            .cp_cache
            .get(usize::from(index))
            .copied()
            .flatten()
    }

    fn cache(&mut self, from: ClassId, index: u16, r: Resolved) {
        if let Some(slot) = self.class_mut(from).cp_cache.get_mut(usize::from(index)) {
            *slot = Some(r);
        }
    }

    fn pool_of(&self, from: ClassId) -> VmResult<std::sync::Arc<classfile::ClassFile>> {
        self.class(from)
            .file
            .clone()
            .ok_or_else(|| VmError::Internal("constant pool of a synthetic class".into()))
    }

    pub fn resolve_class(&mut self, from: ClassId, index: u16) -> VmResult<ClassId> {
        if let Some(Resolved::Class(c)) = self.cached(from, index) {
            return Ok(c);
        }
        let file = self.pool_of(from)?;
        let name = file.pool.class_name(index).map_err(VmError::verify)?;
        let id = self.load_class(name)?;
        self.check_class_access(from, id)?;
        self.cache(from, index, Resolved::Class(id));
        Ok(id)
    }

    /// Resolves a field ref; `want_static` is the instruction's kind.
    pub fn resolve_field(
        &mut self,
        from: ClassId,
        index: u16,
        want_static: bool,
    ) -> VmResult<(ClassId, u32)> {
        if let Some(Resolved::Field {
            class,
            slot,
            is_static,
        }) = self.cached(from, index)
        {
            return check_static(class, slot, is_static, want_static);
        }
        let file = self.pool_of(from)?;
        let r = file.pool.member_ref(index).map_err(VmError::verify)?;
        let owner = self.load_class(&r.class)?;
        self.check_class_access(from, owner)?;
        let Some((class, i)) = self.lookup_field(owner, &r.name, &r.descriptor) else {
            return Err(VmError::exception("java/lang/NoSuchFieldError", &*r.name));
        };
        let field = &self.class(class).fields[i];
        let (slot, is_static, access) = (field.slot, field.is_static(), field.access);
        let what = format!("field {}.{}", self.class(class).java_name(), r.name);
        self.check_member_access(from, class, owner, access, is_static, &what)?;
        self.cache(
            from,
            index,
            Resolved::Field {
                class,
                slot,
                is_static,
            },
        );
        check_static(class, slot, is_static, want_static)
    }

    /// JVMS 5.4.3.2: the class itself, then its superinterfaces, then its superclass.
    pub fn lookup_field(&self, class: ClassId, name: &str, desc: &str) -> Option<(ClassId, usize)> {
        let c = self.class(class);
        if let Some(i) = c
            .fields
            .iter()
            .position(|f| &*f.name == name && &*f.descriptor == desc)
        {
            return Some((class, i));
        }
        let mut visited = Vec::new();
        for &iface in &c.interfaces {
            if let Some(found) = self.interface_field(iface, name, desc, &mut visited) {
                return Some(found);
            }
        }
        c.super_class.and_then(|s| self.lookup_field(s, name, desc))
    }

    /// Depth-first over superinterfaces, visiting each interface once so a
    /// diamond-shaped hierarchy cannot make the search exponential.
    fn interface_field(
        &self,
        iface: ClassId,
        name: &str,
        desc: &str,
        visited: &mut Vec<ClassId>,
    ) -> Option<(ClassId, usize)> {
        if visited.contains(&iface) {
            return None;
        }
        visited.push(iface);
        let c = self.class(iface);
        if let Some(i) = c
            .fields
            .iter()
            .position(|f| &*f.name == name && &*f.descriptor == desc)
        {
            return Some((iface, i));
        }
        c.interfaces
            .iter()
            .find_map(|&s| self.interface_field(s, name, desc, visited))
    }

    /// Resolves a `Methodref` (`interface` false) or `InterfaceMethodref`.
    pub fn resolve_method(
        &mut self,
        from: ClassId,
        index: u16,
        interface: bool,
    ) -> VmResult<MethodRef> {
        if let Some(Resolved::Method(m)) = self.cached(from, index) {
            return Ok(m);
        }
        let file = self.pool_of(from)?;
        let r = file.pool.member_ref(index).map_err(VmError::verify)?;
        let owner = self.load_class(&r.class)?;
        self.check_class_access(from, owner)?;
        if self.class(owner).is_interface() != interface {
            let what = if interface {
                "Found class, but interface was expected"
            } else {
                "Found interface, but class was expected"
            };
            return Err(VmError::exception(
                INCOMPATIBLE,
                format!("{what}: {}", r.class),
            ));
        }
        // JVMS 5.4.3.3 / 5.4.3.4: among maximally-specific superinterface
        // methods, the one non-abstract method if there is one.
        let from_interfaces = |vm: &Self| {
            let candidates = vm.maximally_specific(owner, &r.name, &r.descriptor);
            candidates
                .iter()
                .find(|m| !vm.method(**m).is_abstract())
                .or(candidates.first())
                .copied()
        };
        let found = if interface {
            self.find_in_class(owner, &r.name, &r.descriptor, false)
                .or_else(|| self.object_method(&r.name, &r.descriptor))
                .or_else(|| from_interfaces(self))
        } else {
            self.find_in_class(owner, &r.name, &r.descriptor, true)
                .or_else(|| from_interfaces(self))
        };
        let Some(m) = found else {
            return Err(VmError::exception(
                NO_SUCH_METHOD,
                format!("{}.{}{}", r.class.replace('/', "."), r.name, r.descriptor),
            ));
        };
        let method = self.method(m);
        let (access, is_static) = (method.access, method.is_static());
        // JVMS 6.5: an array's `clone` is public, though `Object`'s is protected.
        if !self.class(owner).is_array() {
            let what = format!(
                "method {}.{}{}",
                self.class(m.class).java_name(),
                r.name,
                r.descriptor
            );
            self.check_member_access(from, m.class, owner, access, is_static, &what)?;
        }
        self.cache(from, index, Resolved::Method(m));
        Ok(m)
    }

    /// Looks for a method declared by `class` or (with `supers`) its superclasses.
    pub fn find_in_class(
        &self,
        class: ClassId,
        name: &str,
        desc: &str,
        supers: bool,
    ) -> Option<MethodRef> {
        let mut cur = Some(class);
        while let Some(c) = cur {
            if let Some(index) = self.class(c).find_method(name, desc) {
                return Some(MethodRef { class: c, index });
            }
            cur = if supers {
                self.class(c).super_class
            } else {
                None
            };
        }
        None
    }

    /// A public instance method of `java.lang.Object` (interface resolution step 3).
    fn object_method(&self, name: &str, desc: &str) -> Option<MethodRef> {
        let m = self.find_in_class(self.wk.object?, name, desc, false)?;
        let method = self.method(m);
        (method.access & classfile::ACC_PUBLIC != 0 && !method.is_static()).then_some(m)
    }

    /// JVMS 5.4.3.3: the superinterface methods of `class` with this name and
    /// descriptor that no other candidate's interface overrides.
    pub fn maximally_specific(&self, class: ClassId, name: &str, desc: &str) -> Vec<MethodRef> {
        let ifaces = self.class(class).all_interfaces.clone();
        let candidates: Vec<MethodRef> = ifaces
            .iter()
            .filter_map(|&i| self.find_in_class(i, name, desc, false))
            .filter(|&m| !self.method(m).is_private() && !self.method(m).is_static())
            .collect();
        candidates
            .iter()
            .copied()
            .filter(|m| {
                !candidates
                    .iter()
                    .any(|o| o.class != m.class && self.implements(o.class, m.class))
            })
            .collect()
    }

    /// JVMS 5.4.6: the method `invokevirtual`/`invokeinterface` runs for a
    /// receiver of class `receiver`, given the resolved method.
    pub fn select(&mut self, receiver: ClassId, resolved: MethodRef) -> VmResult<MethodRef> {
        if let Some(&m) = self.select_cache.get(&(receiver, resolved)) {
            return Ok(m);
        }
        let selected = self.select_uncached(receiver, resolved)?;
        self.select_cache.insert((receiver, resolved), selected);
        Ok(selected)
    }

    fn select_uncached(&self, receiver: ClassId, resolved: MethodRef) -> VmResult<MethodRef> {
        let m = self.method(resolved);
        if m.is_private() {
            return Ok(resolved);
        }
        let (name, desc) = (m.name.clone(), m.descriptor.clone());
        let mut cur = Some(receiver);
        while let Some(c) = cur {
            if let Some(index) = self.class(c).find_method(&name, &desc) {
                let found = MethodRef { class: c, index };
                let fm = self.method(found);
                if !fm.is_static() && !fm.is_private() {
                    return self.check_concrete(found);
                }
            }
            cur = self.class(c).super_class;
        }
        let defaults: Vec<MethodRef> = self
            .maximally_specific(receiver, &name, &desc)
            .into_iter()
            .filter(|&m| !self.method(m).is_abstract())
            .collect();
        match defaults.as_slice() {
            [one] => Ok(*one),
            [] => Err(self.abstract_error(receiver, &name, &desc)),
            _ => Err(VmError::exception(
                INCOMPATIBLE,
                format!("Conflicting default methods: {name}"),
            )),
        }
    }

    fn check_concrete(&self, m: MethodRef) -> VmResult<MethodRef> {
        if self.method(m).is_abstract() {
            let method = self.method(m);
            return Err(self.abstract_error(m.class, &method.name, &method.descriptor));
        }
        Ok(m)
    }

    fn abstract_error(&self, class: ClassId, name: &str, desc: &str) -> VmError {
        let msg = format!("{}.{name}{desc}", self.class(class).java_name());
        VmError::exception("java/lang/AbstractMethodError", msg)
    }
}

fn check_static(
    class: ClassId,
    slot: u32,
    is_static: bool,
    want: bool,
) -> VmResult<(ClassId, u32)> {
    if is_static != want {
        let msg = if want {
            "Expected static field"
        } else {
            "Expected non-static field"
        };
        return Err(VmError::exception(INCOMPATIBLE, msg));
    }
    Ok((class, slot))
}
