//! Loading and linking (JVMS 5.3, 5.4): class files, array classes and primitives.

use std::sync::Arc;

use classfile::{ACC_ABSTRACT, ACC_FINAL, ACC_PUBLIC, ClassFile};

use super::{Class, ClassId, ClassKind, InitState, layout};
use crate::{Vm, VmError, VmResult};

const NO_CLASS_DEF: &str = "java/lang/NoClassDefFoundError";
const INCOMPATIBLE: &str = "java/lang/IncompatibleClassChangeError";
/// Superclass and superinterface chains longer than this are refused.
const MAX_LOAD_DEPTH: usize = 2048;
/// Classes past `Options::max_classes` that creating an exception may load.
const ERROR_HEADROOM: usize = 256;

impl Vm {
    /// Returns the class with this internal name, loading and linking it
    /// (and its superclasses) first if needed.
    pub fn load_class(&mut self, name: &str) -> VmResult<ClassId> {
        if let Some(&id) = self.by_name.get(name) {
            return Ok(id);
        }
        if name.starts_with('[') {
            return self.load_array_class(name);
        }
        let Some(found) = self.classpath.find_class(name) else {
            return Err(VmError::exception(NO_CLASS_DEF, name));
        };
        // Only the class library may define classes in `java.*`.
        if !found.boot && name.starts_with("java/") {
            let package = name
                .rsplit_once('/')
                .map_or(name, |(p, _)| p)
                .replace('/', ".");
            let msg = format!("Prohibited package name: {package}");
            return Err(VmError::exception("java/lang/SecurityException", msg));
        }
        self.define_class(name, &found.bytes, found.boot)
    }

    /// Looks a class up without loading it.
    pub fn loaded_class(&self, name: &str) -> Option<ClassId> {
        self.by_name.get(name).copied()
    }

    pub(crate) fn define_class(
        &mut self,
        name: &str,
        bytes: &[u8],
        boot: bool,
    ) -> VmResult<ClassId> {
        let cf = ClassFile::parse(bytes).map_err(|e| {
            let class = if e.message.starts_with("unsupported class file version") {
                "java/lang/UnsupportedClassVersionError"
            } else {
                "java/lang/ClassFormatError"
            };
            VmError::exception(class, format!("{name}: {e}"))
        })?;
        crate::verify::structural::check_format(&cf, self.options.max_method_code).map_err(
            |e| VmError::exception("java/lang/ClassFormatError", format!("{name}: {e}")),
        )?;
        if &*cf.this_class != name {
            let msg = format!("{name} (wrong name: {})", cf.this_class);
            return Err(VmError::exception(NO_CLASS_DEF, msg));
        }
        if self.loading.iter().any(|n| &**n == name) {
            return Err(VmError::exception("java/lang/ClassCircularityError", name));
        }
        // Loading recurses through superclasses on the Rust stack.
        if self.loading.len() >= MAX_LOAD_DEPTH {
            return Err(VmError::exception(
                "java/lang/StackOverflowError",
                format!("class hierarchy deeper than {MAX_LOAD_DEPTH}: {name}"),
            ));
        }
        self.loading.push(name.into());
        let supers = self.load_supers(&cf, boot);
        self.loading.pop();
        let (super_class, interfaces) = supers?;
        let id = self.next_id()?;
        let super_template = super_class.map(|s| self.class(s).instance_template.clone());
        let all = self.interface_closure(super_class, &interfaces);
        let mut class = layout::build(id, cf, super_class, interfaces, super_template.as_deref())?;
        class.all_interfaces = all;
        class.from_boot = boot;
        Ok(self.insert(class))
    }

    /// Loads the superclass and superinterfaces and checks JVMS 5.3.5 step 3-4.
    fn load_supers(
        &mut self,
        cf: &ClassFile,
        boot: bool,
    ) -> VmResult<(Option<ClassId>, Vec<ClassId>)> {
        let super_class = match &cf.super_class {
            None => None,
            Some(s) => {
                let id = self.load_class(s)?;
                let sup = self.class(id);
                if sup.is_interface() {
                    let msg = format!("class {} has interface {} as super class", cf.this_class, s);
                    return Err(VmError::exception(INCOMPATIBLE, msg));
                }
                if sup.access & ACC_FINAL != 0 || sup.is_array() {
                    let msg = format!("Cannot inherit from final class {s}");
                    return Err(VmError::exception("java/lang/VerifyError", msg));
                }
                self.check_super_access(cf, boot, id, "superclass")?;
                Some(id)
            }
        };
        if cf.is_interface() && cf.super_class.as_deref() != Some("java/lang/Object") {
            return Err(VmError::exception(
                "java/lang/ClassFormatError",
                "interface superclass must be Object",
            ));
        }
        let mut interfaces = Vec::with_capacity(cf.interfaces.len());
        for i in &cf.interfaces {
            let id = self.load_class(i)?;
            if !self.class(id).is_interface() {
                let msg = format!(
                    "class {} can not implement {}, because it is not an interface",
                    cf.this_class, i
                );
                return Err(VmError::exception(INCOMPATIBLE, msg));
            }
            self.check_super_access(cf, boot, id, "superinterface")?;
            interfaces.push(id);
        }
        Ok((super_class, interfaces))
    }

    /// JVMS 5.3.5: a class must be able to access what it extends.
    fn check_super_access(
        &self,
        cf: &ClassFile,
        boot: bool,
        sup: ClassId,
        what: &str,
    ) -> VmResult<()> {
        let s = self.class(sup);
        let package = |n: &str| n.rfind('/').map_or(String::new(), |at| n[..at].to_string());
        let same = s.from_boot == boot && package(&s.name) == package(&cf.this_class);
        if s.access & ACC_PUBLIC != 0 || same {
            return Ok(());
        }
        let msg = format!(
            "class {} cannot access its {what} {}",
            cf.this_class.replace('/', "."),
            s.java_name()
        );
        Err(VmError::exception("java/lang/IllegalAccessError", msg))
    }

    fn load_array_class(&mut self, name: &str) -> VmResult<ClassId> {
        classfile::descriptor::parse_field(name)
            .map_err(|_| VmError::exception(NO_CLASS_DEF, name))?;
        let element = name.as_bytes()[1];
        let component = match element {
            b'L' => self.load_class(&name[2..name.len() - 1])?,
            b'[' => self.load_class(&name[1..])?,
            c => self.primitive_class(c)?,
        };
        let object = self.load_class("java/lang/Object")?;
        let interfaces = vec![
            self.load_class("java/lang/Cloneable")?,
            self.load_class("java/io/Serializable")?,
        ];
        let visible = self.class(component).access & ACC_PUBLIC;
        let id = self.next_id()?;
        let class = synthetic(
            id,
            name,
            ClassKind::Array { component, element },
            Some(object),
            interfaces,
        );
        // An array class has its component's loader.
        let class = Class {
            access: visible | ACC_FINAL | ACC_ABSTRACT,
            from_boot: self.class(component).from_boot,
            ..class
        };
        Ok(self.insert(class))
    }

    /// The class of a primitive type, by descriptor character (`I`, `V`, ...).
    pub fn primitive_class(&mut self, descriptor: u8) -> VmResult<ClassId> {
        let name = match descriptor {
            b'Z' => "boolean",
            b'B' => "byte",
            b'C' => "char",
            b'S' => "short",
            b'I' => "int",
            b'J' => "long",
            b'F' => "float",
            b'D' => "double",
            b'V' => "void",
            _ => {
                return Err(VmError::Internal(format!(
                    "not a primitive: {}",
                    descriptor as char
                )));
            }
        };
        if let Some(&id) = self.by_name.get(name) {
            return Ok(id);
        }
        let id = self.next_id()?;
        let class = synthetic(id, name, ClassKind::Primitive(descriptor), None, Vec::new());
        Ok(self.insert(Class {
            access: ACC_PUBLIC | ACC_FINAL | ACC_ABSTRACT,
            ..class
        }))
    }

    /// The sorted set of all superinterfaces of a class with these supers.
    fn interface_closure(
        &self,
        super_class: Option<ClassId>,
        direct: &[ClassId],
    ) -> Box<[ClassId]> {
        let mut all: Vec<ClassId> = super_class
            .map(|s| self.class(s).all_interfaces.to_vec())
            .unwrap_or_default();
        for &i in direct {
            all.push(i);
            all.extend_from_slice(&self.class(i).all_interfaces);
        }
        all.sort_unstable();
        all.dedup();
        all.into()
    }

    /// The next class's id, or the class limit's error (`Options::max_classes`).
    fn next_id(&self) -> VmResult<ClassId> {
        let limit = self.options.max_classes;
        // The error itself needs classes: its own, and what its constructor loads.
        let headroom = if self.creating_exception > 0 {
            ERROR_HEADROOM
        } else {
            0
        };
        if self.classes.len() >= limit.saturating_add(headroom) {
            let msg = format!("class limit of {limit} reached (-Xmaxclasses)");
            return Err(VmError::exception("java/lang/OutOfMemoryError", msg));
        }
        u32::try_from(self.classes.len()).map(ClassId).map_err(|_| {
            VmError::exception("java/lang/OutOfMemoryError", "class ids are exhausted")
        })
    }

    fn insert(&mut self, class: Class) -> ClassId {
        let id = class.id;
        if self.options.verbose_class {
            println!("[Loaded {}]", class.java_name());
        }
        self.by_name.insert(class.name.clone(), id);
        self.classes.push(class);
        id
    }
}

fn synthetic(
    id: ClassId,
    name: &str,
    kind: ClassKind,
    super_class: Option<ClassId>,
    interfaces: Vec<ClassId>,
) -> Class {
    Class {
        id,
        name: Arc::from(name),
        kind,
        access: 0,
        super_class,
        all_interfaces: sorted(&interfaces),
        interfaces,
        file: None,
        fields: Vec::new(),
        methods: Vec::new(),
        instance_template: Box::new([]),
        statics: Vec::new(),
        state: InitState::Initialized,
        mirror: None,
        cp_cache: Vec::new(),
        from_boot: true,
        verified: true,
        host: None,
    }
}

fn sorted(ids: &[ClassId]) -> Box<[ClassId]> {
    let mut v = ids.to_vec();
    v.sort_unstable();
    v.dedup();
    v.into()
}
