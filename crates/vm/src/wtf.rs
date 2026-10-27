//! Java strings as OS names and back, without losing lone surrogates.
//!
//! Windows names are UTF-16 that need not be well formed, so the units pass
//! through unchanged. Unix names are bytes: a string becomes its generalized
//! UTF-8 (WTF-8), where a lone surrogate is its own three-byte form, so every
//! string maps to its own name and a listing gives the same string back.
//! Bytes that are not WTF-8 (a name another program made) read as U+FFFD.

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
        match form(&bytes[i..]) {
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
    fn other_bytes_read_as_replacement() {
        assert_eq!(
            decode(&[0x61, 0xFF, 0xC0, 0x80]),
            vec![0x61, 0xFFFD, 0xFFFD, 0xFFFD]
        );
    }
}
