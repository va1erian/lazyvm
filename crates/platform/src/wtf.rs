//! Java strings as OS names and back, without losing lone surrogates.
//!
//! Windows names are UTF-16 that need not be well formed, so the units pass
//! through unchanged. Unix names are bytes: a string becomes its generalized
//! UTF-8 (WTF-8), where a lone surrogate is its own three-byte form, so every
//! string maps to its own name and a listing gives the same string back.
//! Bytes that are not WTF-8 (a name another program made) read as U+FFFD.
//! A high surrogate's three-byte form followed by a low one's is not WTF-8
//! either (that pair has its four-byte form), so no two names that a string
//! can come from both decode to it.

use std::ffi::{OsStr, OsString};

/// The OS name for these UTF-16 units.
#[cfg(windows)]
pub fn from_units(units: &[u16]) -> OsString {
    use std::os::windows::ffi::OsStringExt;
    OsString::from_wide(units)
}

/// The UTF-16 units of an OS name.
#[cfg(windows)]
pub fn to_units(s: &OsStr) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    s.encode_wide().collect()
}

#[cfg(not(windows))]
pub fn from_units(units: &[u16]) -> OsString {
    use std::os::unix::ffi::OsStringExt;
    OsString::from_vec(encode(units))
}

#[cfg(not(windows))]
pub fn to_units(s: &OsStr) -> Vec<u16> {
    use std::os::unix::ffi::OsStrExt;
    decode(s.as_bytes())
}

/// WTF-8: UTF-8 of each code point, pairs joined, lone surrogates alone.
pub fn encode(units: &[u16]) -> Vec<u8> {
    let mut out = Vec::with_capacity(units.len());
    let mut i = 0;
    while i < units.len() {
        let u = u32::from(units[i]);
        let pair = (0xD800..0xDC00).contains(&u)
            && units
                .get(i + 1)
                .is_some_and(|&l| (0xDC00..0xE000).contains(&l));
        let cp = if pair {
            i += 1;
            0x10000 + ((u - 0xD800) << 10) + (u32::from(units[i]) - 0xDC00)
        } else {
            u
        };
        push(&mut out, cp);
        i += 1;
    }
    out
}

fn push(out: &mut Vec<u8>, cp: u32) {
    // The casts keep the low bits each byte of the form carries.
    match cp {
        0..=0x7F => out.push(cp as u8),
        0x80..=0x7FF => out.extend([0xC0 | (cp >> 6) as u8, 0x80 | (cp & 0x3F) as u8]),
        0x800..=0xFFFF => out.extend([
            0xE0 | (cp >> 12) as u8,
            0x80 | ((cp >> 6) & 0x3F) as u8,
            0x80 | (cp & 0x3F) as u8,
        ]),
        _ => out.extend([
            0xF0 | (cp >> 18) as u8,
            0x80 | ((cp >> 12) & 0x3F) as u8,
            0x80 | ((cp >> 6) & 0x3F) as u8,
            0x80 | (cp & 0x3F) as u8,
        ]),
    }
}

/// The inverse of `encode`; a byte that starts no valid form is U+FFFD.
pub fn decode(bytes: &[u8]) -> Vec<u16> {
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match form(&bytes[i..]).filter(|&(cp, n)| !split_pair(cp, &bytes[i + n..])) {
            Some((cp, n)) => {
                if cp >= 0x10000 {
                    let v = cp - 0x10000;
                    out.extend([0xD800 + (v >> 10) as u16, 0xDC00 + (v & 0x3FF) as u16]);
                } else {
                    out.push(cp as u16);
                }
                i += n;
            }
            None => {
                out.push(0xFFFD);
                i += 1;
            }
        }
    }
    out
}

/// Whether `cp`, a high surrogate, is followed by a low surrogate's
/// three-byte form: the CESU-8 spelling of a pair, which `encode` never makes.
fn split_pair(cp: u32, rest: &[u8]) -> bool {
    (0xD800..0xDC00).contains(&cp)
        && form(rest).is_some_and(|(low, _)| (0xDC00..0xE000).contains(&low))
}

/// The code point and length of the shortest-form sequence at the start.
fn form(b: &[u8]) -> Option<(u32, usize)> {
    let lead = *b.first()?;
    let (n, init, min) = match lead {
        0x00..=0x7F => return Some((u32::from(lead), 1)),
        0xC2..=0xDF => (2, u32::from(lead & 0x1F), 0x80),
        0xE0..=0xEF => (3, u32::from(lead & 0x0F), 0x800),
        0xF0..=0xF4 => (4, u32::from(lead & 0x07), 0x10000),
        _ => return None,
    };
    let mut cp = init;
    for k in 1..n {
        let c = *b.get(k)?;
        if c & 0xC0 != 0x80 {
            return None;
        }
        cp = (cp << 6) | u32::from(c & 0x3F);
    }
    (cp >= min && cp <= 0x10FFFF).then_some((cp, n))
}

/// A Java string as a Rust `String` that loses nothing: a lone surrogate, and U+10FFFF,
/// which serves as the escape marker, become the marker plus a code ("m" for U+10FFFF
/// itself, "x" and four hex digits for a surrogate). Two distinct strings never share a
/// result, and `unescape` undoes it. System property keys and command-line arguments
/// that may hold unpaired surrogates use this form.
pub fn escape(units: &[u16]) -> String {
    const MARK: char = '\u{10FFFF}';
    let mut out = String::new();
    for c in char::decode_utf16(units.iter().copied()) {
        match c {
            Ok(MARK) => out.push_str("\u{10FFFF}m"),
            Ok(c) => out.push(c),
            Err(e) => out.push_str(&format!("\u{10FFFF}x{:04x}", e.unpaired_surrogate())),
        }
    }
    out
}

/// The UTF-16 units `escape` came from; text that is not an escape reads as itself.
pub fn unescape(s: &str) -> Vec<u16> {
    let mut out = Vec::new();
    let mut chars = s.chars().peekable();
    while let Some(c) = chars.next() {
        if c != '\u{10FFFF}' {
            let mut buf = [0u16; 2];
            out.extend_from_slice(c.encode_utf16(&mut buf));
            continue;
        }
        let rest: String = chars.clone().take(5).collect();
        let unit = rest
            .strip_prefix('x')
            .filter(|h| h.len() == 4)
            .and_then(|h| u16::from_str_radix(h, 16).ok());
        match (chars.peek(), unit) {
            (Some('m'), _) => {
                chars.next();
                out.extend_from_slice(&[0xDBFF, 0xDFFF]);
            }
            (_, Some(u)) => {
                chars.nth(4);
                out.push(u);
            }
            _ => out.extend_from_slice(&[0xDBFF, 0xDFFF]),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lone_surrogates_round_trip() {
        for units in [
            vec![0x78, 0xD800],
            vec![0x78, 0xDC00],
            vec![0x78, 0xFFFD],
            vec![0xD83D, 0xDE00],
            vec![0xDC00, 0xD800],
        ] {
            assert_eq!(decode(&encode(&units)), units);
        }
        assert_ne!(encode(&[0x78, 0xD800]), encode(&[0x78, 0xFFFD]));
    }

    #[test]
    fn a_split_pair_is_not_the_pair() {
        let four = [0x78, 0xF0, 0x90, 0x80, 0x80];
        let cesu = [0x78, 0xED, 0xA0, 0x80, 0xED, 0xB0, 0x80];
        assert_eq!(decode(&four), vec![0x78, 0xD800, 0xDC00]);
        assert_eq!(decode(&cesu), vec![0x78, 0xFFFD, 0xFFFD, 0xFFFD, 0xDC00]);
    }

    /// Bytes that decode without U+FFFD are the encoding of what they decode
    /// to, so no two such names decode to the same string.
    fn check_bytes(b: &[u8]) {
        let units = decode(b);
        assert!(units.contains(&0xFFFD) || encode(&units) == b, "{b:02X?}");
    }

    #[test]
    fn short_byte_sequences_decode_uniquely() {
        for x in 0..=0xFF_FFFFu32 {
            let [_, a, b, c] = x.to_be_bytes();
            check_bytes(&[a, b, c]);
        }
        // Six bytes around the surrogate forms, where three-byte forms pair up.
        let forms: Vec<[u8; 3]> = [0xD7FFu32, 0xD800, 0xDB80, 0xDBFF, 0xDC00, 0xDFFF, 0xE000]
            .iter()
            .map(|&cp| {
                let mut v = Vec::new();
                push(&mut v, cp);
                [v[0], v[1], v[2]]
            })
            .collect();
        for a in &forms {
            for b in &forms {
                check_bytes(&[a[0], a[1], a[2], b[0], b[1], b[2]]);
            }
        }
    }

    #[test]
    fn every_string_of_one_or_two_units_round_trips() {
        let edges = [
            0, 0x7F, 0x80, 0x7FF, 0x800, 0xD7FF, 0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0xE000, 0xFFFD,
            0xFFFF,
        ];
        for u in 0..=0xFFFFu16 {
            assert_eq!(decode(&encode(&[u])), vec![u]);
            for &v in &edges {
                assert_eq!(decode(&encode(&[u, v])), vec![u, v]);
                assert_eq!(decode(&encode(&[v, u])), vec![v, u]);
            }
        }
    }

    #[test]
    fn other_bytes_read_as_replacement() {
        assert_eq!(
            decode(&[0x61, 0xFF, 0xC0, 0x80]),
            vec![0x61, 0xFFFD, 0xFFFD, 0xFFFD]
        );
    }

    #[test]
    fn escapes_are_exact_and_reversible() {
        let esc = |s: &str| escape(&s.encode_utf16().collect::<Vec<_>>());
        assert_eq!(esc("java.home"), "java.home");
        assert_ne!(esc("k\u{10FFFF}"), esc("k"));
        // The marker's own spelling cannot be forged with a lone surrogate or plain text.
        assert_ne!(esc("\u{10FFFF}xd800"), escape(&[0xD800]));
        assert_ne!(escape(&[0xD800]), escape(&[0xDBFF]));
        assert_ne!(esc("\u{10FFFF}"), esc("\u{10FFFF}m"));
        let samples: [&[u16]; 5] = [
            &[0xD800],
            &[0x61, 0xDBFF, 0x62],
            &[0xDBFF, 0xDFFF, 0x78, 0xDC00],
            &[0xDC00, 0xD800],
            &[],
        ];
        for units in samples {
            assert_eq!(unescape(&escape(units)), units);
        }
        // Malformed escapes read as the marker, never panic.
        assert_eq!(unescape("\u{10FFFF}"), [0xDBFF, 0xDFFF]);
        assert_eq!(
            unescape("\u{10FFFF}x12"),
            [0xDBFF, 0xDFFF, 0x78, 0x31, 0x32]
        );
    }
}
