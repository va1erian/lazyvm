//! The Siesta front end: any text gives a syntax tree or an error, never a
//! panic, a hang or a stack overflow (docs/siesta.md section 10).

#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let text = String::from_utf8_lossy(data);
    let _ = siesta::parse::parse_file(0, &text);
});
