//! `Character` predicates as functions of the stored tables.
//!
//! Each is verified against the Java 8 oracle for every code point by
//! `tools/unicode.py` (its `derive` function is the Python twin of this file)
//! and by the `CharacterTables` differential test.

use crate::unicode::Tables;

const MIRRORED: u8 = 1;
const IDEOGRAPHIC: u8 = 2;
const OTHER_ALPHABETIC: u8 = 4;
const OTHER_LOWER: u8 = 8;
const OTHER_UPPER: u8 = 16;
const NOT_JAVA_IDENTIFIER: u8 = 32;

// General categories, as `Character.getType` numbers them.
const LU: u8 = 1;
const LL: u8 = 2;
const LT: u8 = 3;
const MN: u8 = 6;
const MC: u8 = 8;
const ND: u8 = 9;
const NL: u8 = 10;
const ZS: u8 = 12;
const ZP: u8 = 14;
const CF: u8 = 16;
const PC: u8 = 23;
const SC: u8 = 26;

fn is_letter_category(cat: u8) -> bool {
    (LU..=5).contains(&cat)
}

fn is_space_category(cat: u8) -> bool {
    (ZS..=ZP).contains(&cat)
}

pub fn is_letter(t: &Tables, cp: i32) -> bool {
    is_letter_category(t.category(cp))
}

pub fn is_digit(t: &Tables, cp: i32) -> bool {
    t.category(cp) == ND
}

pub fn is_letter_or_digit(t: &Tables, cp: i32) -> bool {
    is_letter(t, cp) || is_digit(t, cp)
}

pub fn is_upper_case(t: &Tables, cp: i32) -> bool {
    t.category(cp) == LU || t.flags(cp) & OTHER_UPPER != 0
}

pub fn is_lower_case(t: &Tables, cp: i32) -> bool {
    t.category(cp) == LL || t.flags(cp) & OTHER_LOWER != 0
}

pub fn is_title_case(t: &Tables, cp: i32) -> bool {
    t.category(cp) == LT
}

pub fn is_space_char(t: &Tables, cp: i32) -> bool {
    is_space_category(t.category(cp))
}

/// Separators except the no-break spaces, plus TAB..CR and FS..US.
pub fn is_whitespace(t: &Tables, cp: i32) -> bool {
    let no_break = matches!(cp, 0xA0 | 0x2007 | 0x202F);
    (is_space_char(t, cp) && !no_break) || matches!(cp, 9..=13 | 0x1C..=0x1F)
}

pub fn is_alphabetic(t: &Tables, cp: i32) -> bool {
    let cat = t.category(cp);
    is_letter_category(cat) || cat == NL || t.flags(cp) & OTHER_ALPHABETIC != 0
}

pub fn is_ideographic(t: &Tables, cp: i32) -> bool {
    t.flags(cp) & IDEOGRAPHIC != 0
}

pub fn is_mirrored(t: &Tables, cp: i32) -> bool {
    t.flags(cp) & MIRRORED != 0
}

pub fn is_defined(t: &Tables, cp: i32) -> bool {
    t.category(cp) != 0
}

pub fn is_identifier_ignorable(t: &Tables, cp: i32) -> bool {
    matches!(cp, 0..=8 | 0xE..=0x1B | 0x7F..=0x9F) || t.category(cp) == CF
}

pub fn is_java_identifier_start(t: &Tables, cp: i32) -> bool {
    let cat = t.category(cp);
    (is_letter_category(cat) || matches!(cat, NL | SC | PC))
        && t.flags(cp) & NOT_JAVA_IDENTIFIER == 0
}

pub fn is_java_identifier_part(t: &Tables, cp: i32) -> bool {
    let cat = t.category(cp);
    let allowed = is_letter_category(cat)
        || matches!(cat, NL | SC | PC | ND | MN | MC)
        || is_identifier_ignorable(t, cp);
    allowed && t.flags(cp) & NOT_JAVA_IDENTIFIER == 0
}

pub fn is_unicode_identifier_start(t: &Tables, cp: i32) -> bool {
    let cat = t.category(cp);
    is_letter_category(cat) || cat == NL
}

pub fn is_unicode_identifier_part(t: &Tables, cp: i32) -> bool {
    let cat = t.category(cp);
    is_letter_category(cat)
        || matches!(cat, NL | PC | ND | MN | MC)
        || is_identifier_ignorable(t, cp)
}

/// `digit(cp, radix)`: the radix-36 value when below the radix, else -1.
pub fn digit(t: &Tables, cp: i32, radix: i32) -> i32 {
    let v = t.digit36(cp);
    if (2..=36).contains(&radix) && (0..radix).contains(&v) {
        v
    } else {
        -1
    }
}

const SIGMA_CASED: u8 = 64;
const SIGMA_SKIPPED: u8 = 128;

/// How `String.toLowerCase` sees `cp` when it looks for the context of a
/// final sigma: 1 cased, 2 skipped (neither cased nor decisive).
pub fn sigma_context(t: &Tables, cp: i32) -> i32 {
    let flags = t.flags(cp);
    i32::from(flags & SIGMA_CASED != 0) | (i32::from(flags & SIGMA_SKIPPED != 0) << 1)
}
