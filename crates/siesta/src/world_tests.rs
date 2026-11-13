//! These read `dist/lazyrt.jar`, which `tools/lazyrt.py` builds; CI runs
//! them after it through `tools/siesta_test.py` (`cargo test -- --ignored`).

use super::*;
use crate::types::Type;

fn world() -> World {
    let jar = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../dist/lazyrt.jar");
    let data = std::fs::read(&jar).unwrap_or_else(|e| panic!("{}: {e}", jar.display()));
    let mut cp = ClassPath::default();
    cp.add_jar(data).unwrap_or_else(|e| panic!("{e}"));
    World::new(cp)
}

fn class(n: &str, args: Vec<Type>) -> Type {
    Type::Class(n.into(), args)
}

#[test]
#[ignore = "needs dist/lazyrt.jar: python tools/siesta_test.py runs it"]
fn every_lazyrt_class_and_signature_reads() {
    let jar = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../dist/lazyrt.jar");
    let j = classfile::jar::Jar::open(std::fs::read(jar).unwrap_or_default())
        .unwrap_or_else(|e| panic!("{e}"));
    let w = world();
    let mut n = 0;
    for name in j.names().iter().filter_map(|e| e.strip_suffix(".class")) {
        let c = w
            .class(name)
            .unwrap_or_else(|| panic!("{name}: {:?}", w.broken.borrow()));
        // A generic signature that failed to read falls back to the
        // descriptor; count the classes whose signature was read.
        let cf = classfile::ClassFile::parse(
            &j.read(&format!("{name}.class"))
                .ok()
                .flatten()
                .unwrap_or_default(),
        );
        if let Ok(cf) = cf
            && let Some(sig) = cf.signature.as_deref()
        {
            assert!(
                crate::sig::class_sig(sig, &Default::default()).is_some(),
                "{name}: {sig}"
            );
            for m in &cf.methods {
                if let Some(s) = m.signature.as_deref() {
                    assert!(
                        crate::sig::method_sig(s, &c.scope()).is_some(),
                        "{name}.{}: {s}",
                        m.name
                    );
                }
            }
        }
        n += 1;
    }
    assert!(n > 500, "{n} classes");
}

#[test]
#[ignore = "needs dist/lazyrt.jar: python tools/siesta_test.py runs it"]
fn functional_interfaces_have_one_method() {
    let w = world();
    for (iface, name) in [
        ("java/util/function/Function", "apply"),
        ("java/util/function/BiFunction", "apply"),
        ("java/util/function/Consumer", "accept"),
        ("java/util/function/Supplier", "get"),
        ("java/util/function/Predicate", "test"),
        ("java/util/function/IntBinaryOperator", "applyAsInt"),
        ("java/util/function/UnaryOperator", "apply"),
        ("java/util/Comparator", "compare"),
        ("java/lang/Runnable", "run"),
    ] {
        let m = w
            .functional_method(&Type::class(iface))
            .unwrap_or_else(|| panic!("{iface}"));
        assert_eq!(m.info.name, name, "{iface}");
    }
    assert!(
        w.functional_method(&Type::class("java/util/List"))
            .is_none()
    );
    assert!(
        w.functional_method(&Type::class("java/lang/String"))
            .is_none()
    );
    // Function<String, Integer>.apply takes a String and gives an Integer.
    let f = class(
        "java/util/function/Function",
        vec![Type::string(), Type::class("java/lang/Integer")],
    );
    let m = w.functional_method(&f).unwrap_or_else(|| panic!());
    assert_eq!(m.params, vec![Type::string()]);
    assert_eq!(m.ret, Type::class("java/lang/Integer"));
}

#[test]
#[ignore = "needs dist/lazyrt.jar: python tools/siesta_test.py runs it"]
fn supertypes_carry_type_arguments() {
    let w = world();
    let list = class("java/util/ArrayList", vec![Type::string()]);
    assert_eq!(
        w.as_super(&list, "java/util/Collection"),
        Some(class("java/util/Collection", vec![Type::string()]))
    );
    assert!(w.is_subclass("java/util/ArrayList", "java/lang/Iterable"));
    assert!(!w.is_subclass("java/lang/String", "java/util/List"));
    let get = w.methods(&list, "get");
    assert_eq!(get[0].ret, Type::string());
    // Raw types are erased.
    let raw = w.methods(&Type::class("java/util/ArrayList"), "get");
    assert_eq!(raw[0].ret, Type::object());
    let map = class(
        "java/util/HashMap",
        vec![Type::string(), Type::class("java/lang/Integer")],
    );
    let merge = w.methods(&map, "merge");
    assert_eq!(merge[0].params[0], Type::string());
    let (_, _, out) = w
        .field(&Type::class("java/lang/System"), "out")
        .unwrap_or_else(|| panic!());
    assert_eq!(out, Type::class("java/io/PrintStream"));
}

#[test]
#[ignore = "needs dist/lazyrt.jar: python tools/siesta_test.py runs it"]
fn static_generic_methods_are_found() {
    let w = world();
    let c = w.class("java/util/Collections");
    assert!(c.is_some(), "{:?}", w.broken.borrow());
    let sort = w.methods(&Type::class("java/util/Collections"), "sort");
    assert_eq!(sort.len(), 2, "{sort:?}");
}
