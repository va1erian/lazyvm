use super::parse_file;
use crate::ast::{Expr, Item, LambdaBody, Member, Stmt, TypeRef, mods};

fn parse(src: &str) -> crate::ast::File {
    parse_file(0, src).unwrap_or_else(|d| panic!("{}: {src}", d.message))
}

fn error(src: &str) -> String {
    match parse_file(0, src) {
        Ok(f) => panic!("expected an error, parsed {f:?}"),
        Err(d) => d.message,
    }
}

/// The expression of a one-statement program.
fn expr(src: &str) -> Expr {
    match parse(&format!("{src};")).items.into_iter().next() {
        Some(Item::Stmt(Stmt::Expr(e))) => e,
        other => panic!("{other:?}"),
    }
}

/// The operator structure of an expression, for precedence checks.
fn shape(e: &Expr) -> String {
    match e {
        Expr::Name(n) => n.name.clone(),
        Expr::Lit(..) => "lit".into(),
        Expr::Binary { op, left, right } => format!("({} {op} {})", shape(left), shape(right)),
        Expr::Unary { op, expr, .. } => format!("({op}{})", shape(expr)),
        Expr::Assign { op, target, value } => {
            format!("({} {}= {})", shape(target), op.unwrap_or(""), shape(value))
        }
        Expr::Cond { cond, then, els } => {
            format!("({} ? {} : {})", shape(cond), shape(then), shape(els))
        }
        Expr::Cast { expr, .. } => format!("(cast {})", shape(expr)),
        Expr::InstanceOf { expr, bind, .. } => format!(
            "({} is {})",
            shape(expr),
            bind.as_ref().map_or("_", |b| &b.name)
        ),
        Expr::Call {
            target, name, args, ..
        } => {
            let t = target
                .as_ref()
                .map_or(String::new(), |t| format!("{}.", shape(t)));
            format!(
                "{t}{}({})",
                name.name,
                args.iter().map(shape).collect::<Vec<_>>().join(", ")
            )
        }
        Expr::Field { target, name } => format!("{}.{}", shape(target), name.name),
        Expr::Lambda {
            params, is_async, ..
        } => {
            format!(
                "{}lambda/{}",
                if *is_async { "async " } else { "" },
                params.len()
            )
        }
        Expr::Await(e, _) => format!("(await {})", shape(e)),
        Expr::Step {
            op, post, target, ..
        } => {
            if *post {
                format!("({}{op})", shape(target))
            } else {
                format!("({op}{})", shape(target))
            }
        }
        other => format!("{other:?}")
            .split_whitespace()
            .next()
            .unwrap_or("?")
            .to_string(),
    }
}

#[test]
fn precedence_follows_java() {
    assert_eq!(shape(&expr("a + b * c")), "(a + (b * c))");
    assert_eq!(shape(&expr("a - b - c")), "((a - b) - c)");
    assert_eq!(shape(&expr("a || b && c | d")), "(a || (b && (c | d)))");
    assert_eq!(shape(&expr("a >> b >>> c >= d")), "(((a >> b) >>> c) >= d)");
    assert_eq!(shape(&expr("x = y += z")), "(x = (y += z))");
    assert_eq!(shape(&expr("x >>>= 2")), "(x >>>= lit)");
    assert_eq!(shape(&expr("c ? a : b ? d : e")), "(c ? a : (b ? d : e))");
    assert_eq!(shape(&expr("-a++ + ++b")), "((-(a++)) + (++b))");
    assert_eq!(
        shape(&expr("o instanceof String s && s.isEmpty()")),
        "((o is s) && s.isEmpty())"
    );
}

#[test]
fn casts_and_parentheses() {
    assert_eq!(shape(&expr("(String) o")), "(cast o)");
    assert_eq!(shape(&expr("(int) -x")), "(cast (-x))");
    assert_eq!(shape(&expr("(a) - b")), "(a - b)");
    assert_eq!(shape(&expr("(List<String>) f(x)")), "(cast f(x))");
    assert_eq!(shape(&expr("(a + b) * c")), "((a + b) * c)");
}

#[test]
fn lambdas_and_references() {
    assert_eq!(
        shape(&expr("f(x -> x + 1, (a, b) -> a, () -> {})")),
        "f(lambda/1, lambda/2, lambda/0)"
    );
    assert_eq!(shape(&expr("f((int a, String b) -> a)")), "f(lambda/2)");
    assert_eq!(
        shape(&expr("ui.onClick(async () -> { await x(); })")),
        "ui.onClick(async lambda/0)"
    );
    assert!(matches!(expr("String::length"), Expr::MethodRef { .. }));
    assert!(matches!(expr("int[]::new"), Expr::MethodRef { .. }));
    assert!(matches!(
        expr("ArrayList<String>::new"),
        Expr::MethodRef { .. }
    ));
    assert!(matches!(expr("String.class"), Expr::ClassLit(..)));
    assert!(matches!(
        expr("int[].class"),
        Expr::ClassLit(TypeRef::Array(..), _)
    ));
}

#[test]
fn trailing_lambdas() {
    assert_eq!(
        shape(&expr("button.onClick { save(); }")),
        "button.onClick(lambda/0)"
    );
    assert_eq!(
        shape(&expr("ui.command(\"Save\", \"Ctrl+S\") { save(); }")),
        "ui.command(lit, lit, lambda/0)"
    );
    assert_eq!(
        shape(&expr("topic.subscribe { a, b -> f(a); }")),
        "topic.subscribe(lambda/2)"
    );
    assert_eq!(
        shape(&expr("run async { await x(); }")),
        "run(async lambda/0)"
    );
    assert_eq!(
        error("var x = new Foo() { };"),
        "anonymous classes are not supported; pass a lambda or a named class"
    );
}

#[test]
fn siesta_literals() {
    assert!(matches!(expr("[1, 2, 3]"), Expr::List(v, _) if v.len() == 3));
    assert!(matches!(expr("[\"a\": 1, \"b\": 2]"), Expr::Map(v, _) if v.len() == 2));
    assert!(matches!(expr("[:]"), Expr::Map(v, _) if v.is_empty()));
    assert!(matches!(expr("[]"), Expr::List(v, _) if v.is_empty()));
    assert!(matches!(expr("\"n=${n + 1}\""), Expr::Template(p, _) if p.len() == 2));
    assert!(
        matches!(expr("list..add(1)..add(2)"), Expr::Cascade { calls, .. } if calls.len() == 2)
    );
    assert_eq!(shape(&expr("await a.b()")), "(await a.b())");
}

#[test]
fn generics_close_with_shift_tokens() {
    let f = parse("Map<String, List<List<String>>> m = [:];");
    let Some(Item::Field(field)) = f.items.first() else {
        panic!()
    };
    assert!(matches!(&field.ty, TypeRef::Named { args, .. } if args.len() == 2));
}

#[test]
fn declarations() {
    let f = parse(
        "package demo; import java.util.*; import static java.lang.Math.max;
         @Service(\"x\") class Box<T extends Comparable<T>> implements Runnable {
             private T value;
             Box(T value) { this.value = value; }
             public void run() {}
             static <U> U id(U u) { return u; }
             async CompletionStage<Void> later() { await x(); }
         }
         interface Shape { double area(); default int n() { return 1; } }
         enum Color { RED, GREEN(2); int n; Color() {} Color(int n) { this.n = n; } }
         int count = 0;
         void hello(String... names) {}
         hello(\"a\", \"b\");",
    );
    assert_eq!(f.package.len(), 1);
    assert_eq!(f.imports.len(), 2);
    assert_eq!(f.items.len(), 6);
    let Item::Type(b) = &f.items[0] else { panic!() };
    assert_eq!(b.tparams.len(), 1);
    assert_eq!(b.mods.annotations.len(), 1);
    let is_ctor = |m: &Member| matches!(m, Member::Method(md) if md.ret.is_none());
    assert!(is_ctor(&b.members[1]));
    let Member::Method(later) = &b.members[4] else {
        panic!()
    };
    assert_eq!(later.mods.bits & mods::ASYNC, mods::ASYNC);
    let Item::Type(e) = &f.items[2] else { panic!() };
    assert_eq!(e.constants.len(), 2);
    assert!(matches!(f.items[3], Item::Field(_)));
    assert!(matches!(&f.items[4], Item::Method(m) if m.params[0].varargs));
}

#[test]
fn statements() {
    let f = parse(
        "for (int i = 0; i < n; i++) { if (i % 2 == 0) continue; else break; }
         for (var s : names) total += s.length();
         do { x--; } while (x > 0);
         switch (k) { case 1, 2 -> f(); case 3 -> { g(); } default -> throw new IllegalStateException(); }
         try { f(); } catch (IOException | RuntimeException e) { g(e); } finally { h(); }",
    );
    assert_eq!(f.items.len(), 5);
    let Item::Stmt(Stmt::Switch { cases, .. }) = &f.items[3] else {
        panic!()
    };
    assert_eq!(cases.len(), 3);
    assert!(cases[2].labels.is_empty());
    let Item::Stmt(Stmt::ForEach { .. }) = &f.items[1] else {
        panic!()
    };
}

#[test]
fn cut_features_have_messages() {
    let cases = [
        (
            "void f() { int x; }",
            "a local variable needs an initialiser",
        ),
        (
            "void f() { try (var r = open()) {} }",
            "try-with-resources is not supported; close in `finally`",
        ),
        (
            "void f() { switch (k) { case 1: f(); } }",
            "use `->`: `switch` cases do not fall through",
        ),
        ("void f() { outer: while (true) {} }", "`;` expected"),
        (
            "void f() { while (x) { break outer; } }",
            "labels are not supported",
        ),
        (
            "void f() { synchronized (this) {} }",
            "`synchronized` blocks are not supported",
        ),
        (
            "void f() { class L {} }",
            "local classes are not supported; declare it at the top level",
        ),
        (
            "var x = Collections.<String>emptyList();",
            "explicit type arguments are not supported; the call infers them",
        ),
        (
            "enum E { A { } }",
            "enum constants with bodies are not supported",
        ),
    ];
    for (src, msg) in cases {
        assert_eq!(error(src), msg, "{src}");
    }
}

#[test]
fn hostile_nesting_is_an_error() {
    let deep = format!("var x = {}1{};", "(".repeat(5000), ")".repeat(5000));
    assert_eq!(error(&deep), "nested too deeply");
    let blocks = format!("void f() {}{}", "{".repeat(5000), "}".repeat(5001));
    assert_eq!(error(&blocks), "nested too deeply");
}

#[test]
fn lambda_bodies() {
    let Expr::Call { args, .. } = expr("f(x -> { return x; })") else {
        panic!()
    };
    assert!(matches!(&args[0], Expr::Lambda { body: LambdaBody::Block(b), .. } if b.len() == 1));
}

/// Every `siesta` code block in the design notes parses, so the examples
/// there stay in the language.
#[test]
fn doc_examples_parse() {
    let docs = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../docs");
    let mut count = 0;
    for name in ["siesta.md", "siesta-async.md", "siesta-apps.md"] {
        let text =
            std::fs::read_to_string(docs.join(name)).unwrap_or_else(|e| panic!("{name}: {e}"));
        for block in text.split("```siesta\n").skip(1) {
            let code = block.split("```").next().unwrap_or("");
            if let Err(d) = parse_file(0, code) {
                let src = crate::diag::Source {
                    name: name.into(),
                    text: code.into(),
                };
                panic!(
                    "{}",
                    crate::diag::Rendered {
                        diag: &d,
                        sources: std::slice::from_ref(&src)
                    }
                );
            }
            count += 1;
        }
    }
    assert!(count >= 6, "only {count} examples found");
}
