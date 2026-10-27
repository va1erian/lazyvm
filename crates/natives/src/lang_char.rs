//! `java.lang.Character` classification, case mapping and numeric values,
//! answered from the generated tables (`unicode.rs`, `char_props.rs`).

use vm::Vm;

use crate::char_props as p;
use crate::unicode::tables;
use crate::{int, ret_bool, ret_int};

const CHARACTER: &str = "java/lang/Character";

/// Registers `(I)Z` natives; each needs its own non-capturing closure.
macro_rules! predicates {
    ($vm:expr, $($name:literal => $f:path),+ $(,)?) => {
        $($vm.register_native(CHARACTER, $name, "(I)Z", |_, a| {
            ret_bool($f(tables()?, int(a, 0)?))
        });)+
    };
}

/// Registers `(I)I` natives that map a code point through a table lookup.
macro_rules! lookups {
    ($vm:expr, $($name:literal => $method:ident),+ $(,)?) => {
        $($vm.register_native(CHARACTER, $name, "(I)I", |_, a| {
            ret_int(tables()?.$method(int(a, 0)?))
        });)+
    };
}

pub fn register(vm: &mut Vm) {
    predicates!(vm,
        "isLetter" => p::is_letter,
        "isDigit" => p::is_digit,
        "isLetterOrDigit" => p::is_letter_or_digit,
        "isWhitespace" => p::is_whitespace,
        "isUpperCase" => p::is_upper_case,
        "isLowerCase" => p::is_lower_case,
        "isTitleCase" => p::is_title_case,
        "isSpaceChar" => p::is_space_char,
        "isAlphabetic" => p::is_alphabetic,
        "isIdeographic" => p::is_ideographic,
        "isMirrored" => p::is_mirrored,
        "isDefined" => p::is_defined,
        "isIdentifierIgnorable" => p::is_identifier_ignorable,
        "isJavaIdentifierStart" => p::is_java_identifier_start,
        "isJavaIdentifierPart" => p::is_java_identifier_part,
        "isUnicodeIdentifierStart" => p::is_unicode_identifier_start,
        "isUnicodeIdentifierPart" => p::is_unicode_identifier_part,
    );
    lookups!(vm,
        "toUpperCase" => upper,
        "toLowerCase" => lower,
        "toTitleCase" => title,
        "getNumericValue" => numeric,
    );
    vm.register_native(CHARACTER, "getType", "(I)I", |_, a| {
        ret_int(i32::from(tables()?.category(int(a, 0)?)))
    });
    vm.register_native(CHARACTER, "getDirectionality", "(I)B", |_, a| {
        ret_int(tables()?.direction(int(a, 0)?))
    });
    vm.register_native(CHARACTER, "digit", "(II)I", |_, a| {
        ret_int(p::digit(tables()?, int(a, 0)?, int(a, 1)?))
    });
}
