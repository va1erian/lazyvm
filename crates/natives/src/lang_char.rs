//! `java.lang.Character` classification and case mapping.
//!
//! Until S5 brings generated Unicode tables, these use Rust's `char`
//! properties, which match Java for ASCII and Latin-1 and approximate the
//! general categories elsewhere.

use vm::{Value, Vm, VmResult};

use crate::{int, ret_bool, ret_int};

const CHARACTER: &str = "java/lang/Character";

pub fn register(vm: &mut Vm) {
    for name in [
        "isLetter",
        "isDigit",
        "isLetterOrDigit",
        "isWhitespace",
        "isUpperCase",
        "isLowerCase",
        "isSpaceChar",
        "isAlphabetic",
    ] {
        vm.register_native(CHARACTER, name, "(I)Z", predicate(name));
    }
    vm.register_native(CHARACTER, "toUpperCase", "(I)I", |_, a| {
        ret_int(map_case(int(a, 0)?, true))
    });
    vm.register_native(CHARACTER, "toLowerCase", "(I)I", |_, a| {
        ret_int(map_case(int(a, 0)?, false))
    });
    vm.register_native(CHARACTER, "digit", "(II)I", |_, a| {
        ret_int(digit(int(a, 0)?, int(a, 1)?))
    });
}

/// Natives are plain `fn`s, so each predicate gets its own function.
fn predicate(name: &str) -> vm::NativeFn {
    fn run(a: &[Value], p: fn(char) -> bool) -> VmResult<Option<Value>> {
        ret_bool(code_point(int(a, 0)?).is_some_and(p))
    }
    match name {
        "isLetter" => |_, a| run(a, |c| c.is_alphabetic() && !c.is_numeric()),
        "isDigit" => |_, a| run(a, is_digit),
        "isLetterOrDigit" => {
            |_, a| run(a, |c| (c.is_alphabetic() && !c.is_numeric()) || is_digit(c))
        }
        "isWhitespace" => |_, a| run(a, is_java_whitespace),
        "isUpperCase" => |_, a| run(a, char::is_uppercase),
        "isLowerCase" => |_, a| run(a, char::is_lowercase),
        "isSpaceChar" => |_, a| run(a, is_space_separator),
        _ => |_, a| run(a, char::is_alphabetic),
    }
}

/// A valid, non-surrogate code point as a `char`.
fn code_point(cp: i32) -> Option<char> {
    u32::try_from(cp).ok().and_then(char::from_u32)
}

fn is_digit(c: char) -> bool {
    c.is_ascii_digit() || (!c.is_ascii() && c.is_numeric() && fullwidth_digit(c).is_some())
}

fn fullwidth_digit(c: char) -> Option<u32> {
    let cp = c as u32;
    (0xFF10..=0xFF19).contains(&cp).then(|| cp - 0xFF10)
}

/// Zs, Zl and Zp (the separators).
fn is_space_separator(c: char) -> bool {
    matches!(
        c,
        ' ' | '\u{00A0}' | '\u{1680}' | '\u{2000}'
            ..='\u{200A}' | '\u{2028}' | '\u{2029}' | '\u{202F}' | '\u{205F}' | '\u{3000}'
    )
}

/// The Javadoc's definition: separators except the no-break spaces, plus
/// the ASCII controls TAB, LF, VT, FF, CR and FS through US.
fn is_java_whitespace(c: char) -> bool {
    let no_break = matches!(c, '\u{00A0}' | '\u{2007}' | '\u{202F}');
    (is_space_separator(c) && !no_break)
        || matches!(
            c,
            '\t' | '\n' | '\u{000B}' | '\u{000C}' | '\r' | '\u{001C}'..='\u{001F}'
        )
}

/// Simple (one-to-one) case mapping; a code point whose mapping is not a
/// single code point is returned unchanged, as Java does.
fn map_case(cp: i32, upper: bool) -> i32 {
    let Some(c) = code_point(cp) else { return cp };
    let mut mapped: Vec<char> = if upper {
        c.to_uppercase().collect()
    } else {
        c.to_lowercase().collect()
    };
    match (mapped.pop(), mapped.is_empty()) {
        (Some(m), true) => m as i32,
        _ => cp,
    }
}

fn digit(cp: i32, radix: i32) -> i32 {
    if !(2..=36).contains(&radix) {
        return -1;
    }
    let value = code_point(cp).and_then(|c| {
        let cp = c as u32;
        match cp {
            0x30..=0x39 => Some(cp - 0x30),
            0x41..=0x5A => Some(cp - 0x41 + 10),
            0x61..=0x7A => Some(cp - 0x61 + 10),
            0xFF21..=0xFF3A => Some(cp - 0xFF21 + 10),
            0xFF41..=0xFF5A => Some(cp - 0xFF41 + 10),
            _ => fullwidth_digit(c),
        }
    });
    match value {
        Some(v) if (v as i32) < radix => v as i32,
        _ => -1,
    }
}
