use super::*;

const JAR: &[u8] = include_bytes!("../../tests/fixtures/sample.jar");
const CLASS: &[u8] = include_bytes!("../../tests/fixtures/Sample.class");

fn jar() -> Jar {
    Jar::open(JAR).unwrap_or_else(|e| panic!("fixture jar should open: {e}"))
}

#[test]
fn reads_stored_and_deflated_entries() {
    let jar = jar();
    assert_eq!(
        jar.read("Sample.class").ok().flatten().as_deref(),
        Some(CLASS)
    );
    assert_eq!(
        jar.read("stored.txt").ok().flatten().as_deref(),
        Some(&b"stored entry\n"[..])
    );
    assert!(jar.read("missing").is_ok_and(|e| e.is_none()));
}

#[test]
fn parses_the_manifest() {
    let jar = jar();
    let text = jar
        .read("META-INF/MANIFEST.MF")
        .ok()
        .flatten()
        .unwrap_or_default();
    let attrs = manifest_attributes(&String::from_utf8_lossy(&text)).unwrap_or_default();
    let main = attrs
        .iter()
        .find(|(k, _)| k == "Main-Class")
        .map(|(_, v)| v.as_str());
    assert_eq!(main, Some("app.VeryLongPackageName.Main"));
}

#[test]
fn crc32_known_value() {
    assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
}

#[test]
fn corrupted_archives_are_errors_not_panics() {
    for len in 0..JAR.len() {
        let _ = Jar::open(&JAR[..len]).map(|j| j.read("Sample.class"));
    }
    let mut x: u64 = 0x2545_f491_4f6c_dd1d;
    for _ in 0..5000 {
        let mut data = JAR.to_vec();
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        let i = (x as usize) % data.len();
        data[i] = (x >> 32) as u8;
        if let Ok(j) = Jar::open(data) {
            for n in j.names().to_vec() {
                let _ = j.read(&n);
            }
        }
    }
}
