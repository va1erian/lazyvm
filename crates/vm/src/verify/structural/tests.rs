//! Hand-made bad class files: each test builds a small valid class and breaks
//! one thing, so a verdict can only come from that one rule.

use classfile::writer::{ClassDef, FieldDef, MethodDef, Pool, write};
use classfile::{ACC_ABSTRACT, ACC_FINAL, ACC_INTERFACE, ACC_PUBLIC, ACC_STATIC, ACC_SUPER};
use classfile::{ClassFile, Handler};

use super::{check_code, check_format};
use crate::verify::{Budget, Fail};

const MAX_CODE: usize = 65535;

struct Build {
    pool: Pool,
    access: u16,
    method_access: u16,
    method_name: String,
    descriptor: String,
    fields: Vec<FieldDef>,
    max_stack: u16,
    max_locals: u16,
}

fn build() -> Build {
    Build {
        pool: Pool::default(),
        access: ACC_PUBLIC | ACC_SUPER,
        method_access: ACC_PUBLIC | ACC_STATIC,
        method_name: "m".into(),
        descriptor: "()V".into(),
        fields: Vec::new(),
        max_stack: 4,
        max_locals: 4,
    }
}

impl Build {
    fn class(self, code: &[u8]) -> ClassFile {
        let class = ClassDef {
            access: self.access,
            name: "t/Bad".into(),
            super_name: "java/lang/Object".into(),
            interfaces: Vec::new(),
            fields: self.fields,
            methods: vec![MethodDef {
                access: self.method_access,
                name: self.method_name,
                descriptor: self.descriptor,
                max_stack: self.max_stack,
                max_locals: self.max_locals,
                code: code.to_vec(),
            }],
        };
        ClassFile::parse(&write(&class, self.pool)).unwrap_or_else(|e| panic!("{e}"))
    }
}

/// The reason `code` is rejected, or `None` when it is accepted.
fn verdict(cf: &ClassFile) -> Option<String> {
    let m = &cf.methods[0];
    let code = m.code.as_ref().unwrap_or_else(|| panic!("no code"));
    match check_code(cf, code, &mut Budget(u64::MAX)) {
        Ok(_) => None,
        Err(Fail::Bad { reason, .. }) => Some(reason),
        Err(Fail::Format { reason, .. }) => Some(reason),
        Err(Fail::Vm(e)) => panic!("{e}"),
    }
}

fn rejected(code: &[u8], fragment: &str) {
    let cf = build().class(code);
    let reason = verdict(&cf).unwrap_or_else(|| panic!("{code:x?} was accepted"));
    assert!(reason.contains(fragment), "{reason}");
}

fn accepted(code: &[u8]) {
    let cf = build().class(code);
    assert_eq!(verdict(&cf), None);
}

const RET: u8 = 0xb1;
const GOTO: u8 = 0xa7;

#[test]
fn accepts_a_small_method() {
    accepted(&[0x03, 0x3b, 0x1a, 0x99, 0, 3, RET]);
}

#[test]
fn rejects_an_unknown_opcode() {
    rejected(&[0xcb, RET], "illegal opcode");
    rejected(&[0xff], "illegal opcode");
}

#[test]
fn rejects_jsr_and_ret() {
    rejected(&[0xa8, 0, 3, RET], "jsr and ret");
    rejected(&[0xa9, 0, RET], "jsr and ret");
    rejected(&[0xc9, 0, 0, 0, 5, RET], "jsr and ret");
    rejected(&[0xc4, 0xa9, 0, 0, RET], "jsr and ret");
}

#[test]
fn rejects_an_instruction_past_the_end() {
    rejected(&[0x10], "past");
    rejected(&[0xb2, 0], "past");
    rejected(&[0xc4, 0x15, 0], "past");
}

#[test]
fn rejects_branches_off_an_instruction() {
    rejected(&[GOTO, 0, 1, RET], "not an instruction");
    rejected(&[GOTO, 0xff, 0xff, RET], "not an instruction");
    rejected(&[GOTO, 0x10, 0, RET], "not an instruction");
    rejected(&[0xc8, 0, 0, 0, 2, RET], "not an instruction");
}

#[test]
fn rejects_a_branch_into_a_wide_operand() {
    rejected(&[GOTO, 0, 4, 0xc4, 0x15, 0, 0, RET], "not an instruction");
}

#[test]
fn rejects_bad_switches() {
    let mut low_above_high = vec![0xaa, 0, 0, 0];
    low_above_high.extend_from_slice(&[0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0]);
    rejected(&low_above_high, "low is above high");
    let mut unsorted = vec![0xab, 0, 0, 0];
    unsorted.extend_from_slice(&[0, 0, 0, 28, 0, 0, 0, 2]);
    unsorted.extend_from_slice(&[0, 0, 0, 5, 0, 0, 0, 28, 0, 0, 0, 1, 0, 0, 0, 28, RET]);
    rejected(&unsorted, "not sorted");
    let mut off_target = vec![0xaa, 0, 0, 0];
    off_target.extend_from_slice(&[0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, RET]);
    rejected(&off_target, "not an instruction");
}

#[test]
fn rejects_locals_past_max_locals() {
    rejected(&[0x15, 4, RET], "max_locals");
    rejected(&[0x16, 3, RET], "max_locals");
    rejected(&[0x84, 9, 1, RET], "max_locals");
    rejected(&[0xc4, 0x36, 1, 0, RET], "max_locals");
    let cf = Build {
        max_locals: 1,
        ..build()
    }
    .class(&[0x1f, RET]);
    assert!(verdict(&cf).is_some_and(|r| r.contains("max_locals")));
}

#[test]
fn rejects_arguments_that_do_not_fit_max_locals() {
    let cf = Build {
        descriptor: "(JJJ)V".into(),
        ..build()
    }
    .class(&[RET]);
    let found = check_format(&cf, MAX_CODE).err();
    assert!(found.is_some_and(|r| r.contains("max_locals")));
}

#[test]
fn rejects_wide_of_other_opcodes() {
    rejected(&[0xc4, 0x60, 0, 0, RET], "wide of");
}

#[test]
fn rejects_the_wrong_constant_kind_per_opcode() {
    let mut pool = Pool::default();
    let class = pool.class("java/lang/Object");
    let utf = pool.utf8("text");
    let field = pool.field_ref("t/Bad", "f", "I");
    let method = pool.method_ref("t/Bad", "n", "()V");
    let iface = pool.interface_method_ref("t/I", "n", "()V");
    let [c0, c1] = class.to_be_bytes();
    let [u0, u1] = utf.to_be_bytes();
    let [f0, f1] = field.to_be_bytes();
    let [m0, m1] = method.to_be_bytes();
    let [i0, i1] = iface.to_be_bytes();
    let cases: Vec<(Vec<u8>, &str)> = vec![
        (vec![0x13, u0, u1, RET], "ldc of"),
        (vec![0x14, c0, c1, RET], "ldc2_w of"),
        (vec![0xb2, m0, m1, RET], "field instruction"),
        (vec![0xb4, c0, c1, RET], "field instruction"),
        (vec![0xb8, f0, f1, RET], "names"),
        (vec![0xb6, i0, i1, RET], "names"),
        (vec![0xb9, m0, m1, 1, 0, RET], "names"),
        (vec![0xbb, u0, u1, RET], "not Class"),
        (vec![0xbd, m0, m1, RET], "not Class"),
        (vec![0xc0, f0, f1, RET], "not Class"),
        (vec![0xc5, u0, u1, 1, RET], "not Class"),
        (vec![0xba, m0, m1, 0, 0, RET], "invokedynamic names"),
        (vec![0x12, 0xff, RET], "ldc of"),
        (vec![0xb2, 0xff, 0xff, RET], "field instruction"),
    ];
    for (code, fragment) in cases {
        let cf = Build {
            pool: rebuilt_pool(),
            ..build()
        }
        .class(&code);
        let reason = verdict(&cf).unwrap_or_else(|| panic!("{code:x?} was accepted"));
        assert!(reason.contains(fragment), "{code:x?}: {reason}");
    }
}

/// A pool laid out like the one in the test above: indices must match.
fn rebuilt_pool() -> Pool {
    let mut pool = Pool::default();
    pool.class("java/lang/Object");
    pool.utf8("text");
    pool.field_ref("t/Bad", "f", "I");
    pool.method_ref("t/Bad", "n", "()V");
    pool.interface_method_ref("t/I", "n", "()V");
    pool
}

#[test]
fn rejects_calling_special_methods_the_wrong_way() {
    let mut pool = Pool::default();
    let init = pool.method_ref("t/Bad", "<init>", "()V").to_be_bytes();
    let cf = Build { pool, ..build() }.class(&[0xb8, init[0], init[1], RET]);
    assert!(verdict(&cf).is_some_and(|r| r.contains("may not be called")));
}

#[test]
fn rejects_a_wrong_invokeinterface_count() {
    let mut pool = Pool::default();
    let m = pool.interface_method_ref("t/I", "n", "(J)V").to_be_bytes();
    let cf = Build { pool, ..build() }.class(&[0xb9, m[0], m[1], 2, 0, RET]);
    assert!(verdict(&cf).is_some_and(|r| r.contains("count")));
    let mut pool = Pool::default();
    let m = pool.interface_method_ref("t/I", "n", "(J)V").to_be_bytes();
    let cf = Build { pool, ..build() }.class(&[0xb9, m[0], m[1], 3, 1, RET]);
    assert!(verdict(&cf).is_some_and(|r| r.contains("not zero")));
}

#[test]
fn rejects_bad_array_creation() {
    let mut pool = Pool::default();
    let c = pool.class("[I").to_be_bytes();
    let cf = Build { pool, ..build() }.class(&[0xbb, c[0], c[1], RET]);
    assert!(verdict(&cf).is_some_and(|r| r.contains("array type")));
    rejected(&[0xbc, 3, RET], "newarray");
    rejected(&[0xbc, 12, RET], "newarray");
    let mut pool = Pool::default();
    let c = pool.class("[[I").to_be_bytes();
    let cf = Build { pool, ..build() }.class(&[0xc5, c[0], c[1], 3, RET]);
    assert!(verdict(&cf).is_some_and(|r| r.contains("multianewarray")));
    let mut pool = Pool::default();
    let c = pool.class("[[I").to_be_bytes();
    let cf = Build { pool, ..build() }.class(&[0xc5, c[0], c[1], 0, RET]);
    assert!(verdict(&cf).is_some_and(|r| r.contains("multianewarray")));
    let mut pool = Pool::default();
    let name = format!("{}I", "[".repeat(255));
    let c = pool.class(&name).to_be_bytes();
    let cf = Build { pool, ..build() }.class(&[0xbd, c[0], c[1], RET]);
    assert!(verdict(&cf).is_some_and(|r| r.contains("255")));
}

fn with_handler(h: Handler) -> Option<String> {
    let mut cf = build().class(&[0x00, 0xc4, 0x15, 0, 0, RET]);
    let code = cf.methods[0].code.as_mut().unwrap_or_else(|| panic!());
    code.handlers.push(h);
    verdict(&cf)
}

#[test]
fn rejects_bad_exception_ranges() {
    let good = Handler {
        start_pc: 0,
        end_pc: 6,
        handler_pc: 5,
        catch_type: 0,
    };
    assert_eq!(with_handler(good), None);
    for h in [
        Handler {
            start_pc: 2,
            ..good
        },
        Handler { end_pc: 2, ..good },
        Handler { end_pc: 7, ..good },
        Handler {
            start_pc: 6,
            ..good
        },
        Handler {
            handler_pc: 2,
            ..good
        },
        Handler {
            handler_pc: 6,
            ..good
        },
        Handler {
            catch_type: 0xfff,
            ..good
        },
    ] {
        assert!(with_handler(h).is_some(), "{h:?}");
    }
}

fn format_error(b: Build) -> Option<String> {
    check_format(&b.class(&[RET]), MAX_CODE).err()
}

#[test]
fn format_accepts_the_base_class() {
    assert_eq!(format_error(build()), None);
}

#[test]
fn format_rejects_bad_class_flags() {
    for (access, fragment) in [
        (ACC_INTERFACE, "not abstract"),
        (
            ACC_INTERFACE | ACC_ABSTRACT | ACC_FINAL,
            "interface is final",
        ),
        (ACC_FINAL | ACC_ABSTRACT, "final and abstract"),
        (0x2000, "annotation"),
    ] {
        let found = format_error(Build { access, ..build() });
        assert!(found.is_some_and(|e| e.contains(fragment)), "{access:#x}");
    }
}

#[test]
fn format_rejects_bad_method_flags() {
    for access in [
        ACC_PUBLIC | 0x0002,
        0x0002 | 0x0004,
        ACC_PUBLIC | 0x0001 | 0x0004,
    ] {
        assert!(
            format_error(Build {
                method_access: access,
                ..build()
            })
            .is_some(),
            "{access:#x}"
        );
    }
}

#[test]
fn format_checks_special_method_names() {
    for (name, desc, access, fragment) in [
        ("<init>", "()I", 0, "must return void"),
        ("<init>", "()V", ACC_STATIC, "illegal flags"),
        ("<init>", "()V", ACC_FINAL, "illegal flags"),
        ("<foo>", "()V", 0, "bad name"),
        ("a.b", "()V", 0, "bad name"),
        ("a;b", "()V", 0, "bad name"),
        ("a[b", "()V", 0, "bad name"),
        ("a/b", "()V", 0, "bad name"),
    ] {
        let cf = Build {
            method_name: name.into(),
            descriptor: desc.into(),
            method_access: access,
            ..build()
        }
        .class(&[0x03, 0xac]);
        let found = check_format(&cf, MAX_CODE).err();
        assert!(
            found.as_deref().is_some_and(|e| e.contains(fragment)),
            "{name}{desc}: {found:?}"
        );
    }
}

#[test]
fn format_checks_the_exceptions_attribute() {
    let mut cf = build().class(&[RET]);
    cf.methods[0].exceptions = vec![0xfff];
    assert!(check_format(&cf, MAX_CODE).is_err());
}

#[test]
fn format_checks_field_flags() {
    for (access, name, descriptor) in [
        (ACC_FINAL | 0x0040, "f", "I"),
        (ACC_PUBLIC | 0x0002, "f", "I"),
        (0, "a.b", "I"),
        (0, "", "I"),
    ] {
        let found = format_error(Build {
            fields: vec![FieldDef {
                access,
                name: name.into(),
                descriptor: descriptor.into(),
            }],
            ..build()
        });
        assert!(found.is_some(), "{access:#x} {name}");
    }
}

#[test]
fn format_rejects_descriptor_class_names_with_empty_parts() {
    let found = format_error(Build {
        fields: vec![FieldDef {
            access: 0,
            name: "f".into(),
            descriptor: "La//b;".into(),
        }],
        ..build()
    });
    assert!(found.is_some());
}

#[test]
fn format_rejects_bad_names_in_the_pool() {
    for (class, name, desc) in [
        ("a..b", "f", "()V"),
        ("[", "f", "()V"),
        ("[Q", "f", "()V"),
        ("a", "b<c", "()V"),
        ("a", "<init>", "()I"),
        ("a", "f", "(V)V"),
        ("a", "f", "I"),
    ] {
        let mut pool = Pool::default();
        pool.method_ref(class, name, desc);
        assert!(
            format_error(Build { pool, ..build() }).is_some(),
            "{class} {name} {desc}"
        );
    }
}

#[test]
fn format_applies_the_method_code_limit() {
    let cf = build().class(&[0, 0, 0, RET]);
    assert!(check_format(&cf, 4).is_ok());
    let found = check_format(&cf, 3).err();
    assert!(found.is_some_and(|e| e.contains("-Xmaxcode")));
}

#[test]
fn hostile_input_is_an_error_not_a_panic() {
    // Every one-byte opcode followed by whatever: never a panic.
    for op in 0..=255u8 {
        let cf = build().class(&[op, 0xff, 0xff, 0xff, 0xff]);
        let _ = verdict(&cf);
    }
}
