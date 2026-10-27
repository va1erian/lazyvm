use super::*;

const SAMPLE: &[u8] = include_bytes!("../tests/fixtures/Sample.class");

fn sample() -> ClassFile {
    match ClassFile::parse(SAMPLE) {
        Ok(cf) => cf,
        Err(e) => panic!("fixture should parse: {e}"),
    }
}

#[test]
fn parses_the_fixture() {
    let cf = sample();
    assert_eq!(cf.major, 52);
    assert_eq!(&*cf.this_class, "Sample");
    assert_eq!(cf.super_class.as_deref(), Some("java/lang/Object"));
    let ifaces: Vec<&str> = cf.interfaces.iter().map(|s| &**s).collect();
    assert_eq!(ifaces, ["java/io/Serializable", "java/lang/Runnable"]);
    assert_eq!(cf.source_file.as_deref(), Some("Sample.java"));
    assert_eq!(cf.bootstrap_methods.len(), 1, "the lambda's bootstrap");
}

#[test]
fn reads_constants_and_code() {
    let cf = sample();
    let answer = cf.fields.iter().find(|f| &*f.name == "ANSWER");
    let index = answer.and_then(|f| f.constant_value);
    assert_eq!(
        index.and_then(|i| cf.pool.get(i)),
        Some(&Constant::Integer(42))
    );
    let run = cf.method("run", "()V");
    let code = run.and_then(|m| m.code.as_ref());
    assert!(code.is_some_and(|c| c.handlers.len() == 1 && !c.line_numbers.is_empty()));
    let native = cf.method("nativeOne", "()I");
    assert!(native.is_some_and(|m| m.code.is_none() && m.access & ACC_NATIVE != 0));
}

#[test]
fn rejects_newer_versions_and_bad_magic() {
    let mut data = SAMPLE.to_vec();
    data[7] = 53;
    assert!(ClassFile::parse(&data).is_err_and(|e| e.message.contains("unsupported")));
    data[0] = 0;
    assert!(ClassFile::parse(&data).is_err());
}

#[test]
fn every_truncation_is_an_error() {
    for len in 0..SAMPLE.len() {
        assert!(
            ClassFile::parse(&SAMPLE[..len]).is_err(),
            "prefix of {len} bytes parsed"
        );
    }
    let mut longer = SAMPLE.to_vec();
    longer.push(0);
    assert!(ClassFile::parse(&longer).is_err());
}

/// A small deterministic generator, so the mutation test needs no dependency.
struct XorShift(u64);

impl XorShift {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
}

#[test]
fn mutations_never_panic() {
    let mut rng = XorShift(0x9e37_79b9_7f4a_7c15);
    for _ in 0..20_000 {
        let mut data = SAMPLE.to_vec();
        for _ in 0..1 + rng.next() % 4 {
            let i = (rng.next() as usize) % data.len();
            data[i] = rng.next() as u8;
        }
        // Parse errors are expected; a panic fails the test.
        let _ = ClassFile::parse(&data);
    }
}
