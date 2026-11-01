//! The checker against a stand-in class world: the core verdicts, and input
//! built to make it work hard. `tests/verify` has one class per rule, judged
//! on the oracle too (`tools/verify_test.py`).

use std::sync::Arc;
use std::time::{Duration, Instant};

use classfile::writer::{ClassDef, MethodDef, Pool, write};
use classfile::{ACC_PUBLIC, ACC_STATIC, ACC_SUPER, ClassFile, Handler};

use super::{ClassInfo, Hierarchy, verify_class};
use crate::{VmError, VmResult};

/// `t/Sub` extends `t/Base` extends `Object`; `t/Iface` is an interface.
struct World;

impl Hierarchy for World {
    fn info(&mut self, name: &str) -> VmResult<ClassInfo> {
        let super_name: Option<Arc<str>> = match name {
            "java/lang/Object" => None,
            "t/Sub" => Some("t/Base".into()),
            "t/Missing" => {
                return Err(VmError::exception("java/lang/NoClassDefFoundError", name));
            }
            _ => Some("java/lang/Object".into()),
        };
        Ok(ClassInfo {
            is_interface: name == "t/Iface",
            super_name,
            boot: name.starts_with("java/"),
        })
    }

    fn member_access(
        &mut self,
        _class: &str,
        _name: &str,
        _descriptor: &str,
        _method: bool,
    ) -> VmResult<Option<u16>> {
        Ok(None)
    }
}

fn class(descriptor: &str, max_stack: u16, max_locals: u16, code: &[u8], pool: Pool) -> ClassFile {
    let def = ClassDef {
        access: ACC_PUBLIC | ACC_SUPER,
        name: "t/T".into(),
        super_name: "java/lang/Object".into(),
        interfaces: Vec::new(),
        fields: Vec::new(),
        methods: vec![MethodDef {
            access: ACC_PUBLIC | ACC_STATIC,
            name: "m".into(),
            descriptor: descriptor.into(),
            max_stack,
            max_locals,
            code: code.to_vec(),
        }],
    };
    ClassFile::parse(&write(&def, pool)).unwrap_or_else(|e| panic!("{e}"))
}

fn verdict(cf: &ClassFile) -> Result<(), String> {
    verify_class(cf, None, &mut World).map_err(|e| e.to_string())
}

const RET: u8 = 0xb1;
const ARETURN: u8 = 0xb0;

/// `static Object m(arg)`: returning the argument checks one assignment.
fn assigns(from: &str, to: &str) -> bool {
    let cf = class(
        &format!("({from}){to}"),
        1,
        1,
        &[0x2a, ARETURN],
        Pool::default(),
    );
    verdict(&cf).is_ok()
}

#[test]
fn reference_assignment_follows_the_hierarchy() {
    let (object, base, sub, iface) = ("Ljava/lang/Object;", "Lt/Base;", "Lt/Sub;", "Lt/Iface;");
    assert!(assigns(sub, base));
    assert!(assigns(sub, object));
    assert!(!assigns(base, sub));
    assert!(!assigns(object, base));
    // An interface counts as `Object`, for arrays too.
    assert!(assigns(base, iface));
    assert!(assigns(object, iface));
    assert!(assigns("[I", iface));
    assert!(assigns("[I", object));
    assert!(!assigns("[I", base));
    assert!(!assigns(base, "[I"));
    assert!(assigns("[Lt/Sub;", "[Lt/Base;"));
    assert!(!assigns("[Lt/Base;", "[Lt/Sub;"));
    assert!(assigns("[[I", "[Ljava/lang/Object;"));
    assert!(!assigns("[I", "[Ljava/lang/Object;"));
    assert!(!assigns("[I", "[J"));
    assert!(!assigns("[[I", "[[J"));
}

#[test]
fn a_class_that_will_not_load_is_that_error() {
    let cf = class(
        "(Lt/Base;)Lt/Missing;",
        1,
        1,
        &[0x2a, ARETURN],
        Pool::default(),
    );
    let found = verdict(&cf);
    assert!(found.is_err_and(|e| e.contains("NoClassDefFoundError")));
}

#[test]
fn failures_name_the_method_offset_and_reason() {
    let cf = class("()V", 1, 0, &[0x03, 0x4b, RET], Pool::default());
    let found = class("()V", 1, 1, &[0x57, RET], Pool::default());
    assert!(verdict(&cf).is_err());
    let message = verdict(&found).err().unwrap_or_default();
    assert!(
        message.contains("t/T.m()V at offset 0: operand stack underflow"),
        "{message}"
    );
}

#[test]
fn old_classes_skip_type_checking_but_not_structure() {
    let mut cf = class("()V", 1, 1, &[0x57, RET], Pool::default());
    cf.major = 49;
    assert!(verdict(&cf).is_ok());
    let mut cf = class("()V", 1, 1, &[0xa8, 0, 3, RET], Pool::default());
    cf.major = 49;
    assert!(verdict(&cf).is_err());
}

/// A full frame of `locals` ints, then `count` `same_frame_extended` frames.
fn hostile_frames(locals: u16, count: u16) -> Vec<u8> {
    let mut map = (count + 1).to_be_bytes().to_vec();
    map.extend_from_slice(&[255, 0, 0]);
    map.extend_from_slice(&locals.to_be_bytes());
    map.extend(std::iter::repeat_n(1u8, usize::from(locals)));
    map.extend_from_slice(&[0, 0]);
    for _ in 0..count {
        map.extend_from_slice(&[251, 0, 0]);
    }
    map
}

/// Whatever the verdict, within a bound a class loader can afford.
fn finishes_quickly(cf: &ClassFile) {
    let start = Instant::now();
    let _ = verdict(cf);
    assert!(
        start.elapsed() < Duration::from_secs(30),
        "took {:?}",
        start.elapsed()
    );
}

#[test]
fn huge_frames_stop_at_the_budget() {
    let mut code = vec![0u8; 65534];
    code.push(RET);
    let mut cf = class("()V", 0, 65535, &code, Pool::default());
    let c = cf.methods[0]
        .code
        .as_mut()
        .unwrap_or_else(|| panic!("code"));
    c.stack_map = Some(hostile_frames(65535, 65533).into());
    finishes_quickly(&cf);
    assert!(verdict(&cf).is_err());
}

#[test]
fn many_handlers_stop_at_the_budget() {
    let mut code = vec![0u8; 65534];
    code.push(RET);
    let mut cf = class("()V", 1, 65535, &code, Pool::default());
    let c = cf.methods[0]
        .code
        .as_mut()
        .unwrap_or_else(|| panic!("code"));
    c.handlers = vec![
        Handler {
            start_pc: 0,
            end_pc: 65534,
            handler_pc: 0,
            catch_type: 0,
        };
        65535
    ];
    c.stack_map = Some(hostile_frames(16, 0).into());
    finishes_quickly(&cf);
}

#[test]
fn many_branches_over_wide_locals_finish() {
    // goto +3 repeated: every instruction a branch target, each with a frame.
    let code: Vec<u8> = (0..21844).flat_map(|_| [0xa7, 0, 3]).chain([RET]).collect();
    let mut cf = class("()V", 0, 65535, &code, Pool::default());
    let mut map = 21844u16.to_be_bytes().to_vec();
    map.extend_from_slice(&[255, 0, 3]);
    map.extend_from_slice(&60000u16.to_be_bytes());
    map.extend(std::iter::repeat_n(1u8, 60000));
    map.extend_from_slice(&[0, 0]);
    for _ in 1..21844 {
        map.extend_from_slice(&[2]);
    }
    let c = cf.methods[0]
        .code
        .as_mut()
        .unwrap_or_else(|| panic!("code"));
    c.stack_map = Some(map.into());
    finishes_quickly(&cf);
}
