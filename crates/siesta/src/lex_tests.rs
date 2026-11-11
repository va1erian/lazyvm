use super::*;

fn toks(src: &str) -> Vec<Tok> {
    lex(0, src)
        .unwrap_or_else(|e| panic!("{}", e.message))
        .into_iter()
        .map(|t| t.tok)
        .collect()
}

fn err(src: &str) -> String {
    match lex(0, src) {
        Ok(t) => panic!("expected an error, got {t:?}"),
        Err(e) => e.message,
    }
}

fn num(text: &str, radix: u32, kind: NumKind) -> Tok {
    Tok::Num(Num {
        text: text.into(),
        radix,
        kind,
    })
}

#[test]
fn words_and_operators() {
    assert_eq!(
        toks("var x = a >>>= b..c;"),
        vec![
            Tok::Kw(Kw::Var),
            Tok::Ident("x".into()),
            Tok::Op("="),
            Tok::Ident("a".into()),
            Tok::Op(">"),
            Tok::Op(">"),
            Tok::Op(">"),
            Tok::Op("="),
            Tok::Ident("b".into()),
            Tok::Op(".."),
            Tok::Ident("c".into()),
            Tok::Op(";"),
            Tok::Eof,
        ]
    );
}

#[test]
fn numbers() {
    assert_eq!(
        toks("1_000 0x1F 0b101L 017 1.5 1e3 2f .5 1.")[..9],
        [
            num("1000", 10, NumKind::Int),
            num("1F", 16, NumKind::Int),
            num("101", 2, NumKind::Long),
            num("017", 8, NumKind::Int),
            num("1.5", 10, NumKind::Double),
            num("1e3", 10, NumKind::Double),
            num("2", 10, NumKind::Float),
            num(".5", 10, NumKind::Double),
            num("1", 10, NumKind::Double),
        ]
    );
    // A method call on an int literal is not a float.
    assert_eq!(
        toks("1.toString")[..3],
        [
            num("1", 10, NumKind::Int),
            Tok::Op("."),
            Tok::Ident("toString".into())
        ]
    );
    assert_eq!(err("09"), "digit out of range for an octal number");
    assert_eq!(err("12abc"), "malformed number");
}

#[test]
fn strings_chars_and_escapes() {
    assert_eq!(toks(r#""a\tb\u0041\101\$""#)[0], Tok::Str("a\tbAA$".into()));
    assert_eq!(toks(r"'\n'")[0], Tok::Char(10));
    assert_eq!(toks("'é'")[0], Tok::Char(0xe9));
    assert_eq!(err("\"abc"), "string not closed");
    assert_eq!(err("'ab'"), "character literal not closed");
    assert_eq!(err("/* x"), "comment not closed");
}

#[test]
fn templates_nest() {
    let t = toks(r#""n=${a + f("${b}")}!""#);
    let Tok::Template(parts) = &t[0] else {
        panic!("{t:?}")
    };
    assert_eq!(parts.len(), 3);
    assert_eq!(parts[0], Part::Text("n=".into()));
    let Part::Expr(inner) = &parts[1] else {
        panic!()
    };
    assert!(matches!(inner[4].tok, Tok::Template(_)));
    assert_eq!(parts[2], Part::Text("!".into()));
    assert_eq!(err(r#""${a"#), "`${` not closed");
}

#[test]
fn comments_are_skipped() {
    assert_eq!(
        toks("a // b\n/* c */ d"),
        vec![Tok::Ident("a".into()), Tok::Ident("d".into()), Tok::Eof]
    );
}
