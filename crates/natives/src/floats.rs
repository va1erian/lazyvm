//! `java.lang.Float` and `java.lang.Double`: bit conversions, `toString` and parsing.

use vm::{Value, Vm, VmError, VmResult};

use crate::{arg, double, float, int, long};

const FLOAT: &str = "java/lang/Float";
const DOUBLE: &str = "java/lang/Double";

pub fn register(vm: &mut Vm) {
    vm.register_native(FLOAT, "floatToRawIntBits", "(F)I", |_, a| {
        Ok(Some(Value::Int(float(a, 0)?.to_bits() as i32)))
    });
    vm.register_native(FLOAT, "intBitsToFloat", "(I)F", |_, a| {
        Ok(Some(Value::Float(f32::from_bits(int(a, 0)? as u32))))
    });
    vm.register_native(DOUBLE, "doubleToRawLongBits", "(D)J", |_, a| {
        Ok(Some(Value::Long(double(a, 0)?.to_bits() as i64)))
    });
    vm.register_native(DOUBLE, "longBitsToDouble", "(J)D", |_, a| {
        Ok(Some(Value::Double(f64::from_bits(long(a, 0)? as u64))))
    });
    vm.register_native(FLOAT, "toString", "(F)Ljava/lang/String;", |vm, a| {
        let s = float_to_string(float(a, 0)?);
        Ok(Some(Value::Ref(Some(vm.new_string(&s)?))))
    });
    vm.register_native(DOUBLE, "toString", "(D)Ljava/lang/String;", |vm, a| {
        let s = double_to_string(double(a, 0)?);
        Ok(Some(Value::Ref(Some(vm.new_string(&s)?))))
    });
    vm.register_native(FLOAT, "parseFloat", "(Ljava/lang/String;)F", |vm, a| {
        let s = vm.string_arg(arg(a, 0)?)?;
        let v = parse(&s)?;
        Ok(Some(Value::Float(match v {
            Parsed::Decimal(text) => text.parse::<f32>().map_err(|_| nfe(&s))?,
            Parsed::Value(x) => x as f32,
            Parsed::Hex { negative, value } => {
                let x = crate::hexfloat::to_f32(value);
                if negative { -x } else { x }
            }
        })))
    });
    vm.register_native(DOUBLE, "parseDouble", "(Ljava/lang/String;)D", |vm, a| {
        let s = vm.string_arg(arg(a, 0)?)?;
        let v = parse(&s)?;
        Ok(Some(Value::Double(match v {
            Parsed::Decimal(text) => text.parse::<f64>().map_err(|_| nfe(&s))?,
            Parsed::Value(x) => x,
            Parsed::Hex { negative, value } => {
                let x = crate::hexfloat::to_f64(value);
                if negative { -x } else { x }
            }
        })))
    });
}

/// `Double.toString`: the shortest decimal that rounds back to `x`, with at
/// least two significant digits considered (the Javadoc's rule), shown in
/// plain notation for 1e-3 <= |x| < 1e7 and computerized notation otherwise.
pub fn double_to_string(x: f64) -> String {
    format_java(
        x.is_nan(),
        x.is_infinite(),
        x == 0.0,
        x.is_sign_negative(),
        || (format!("{:e}", x.abs()), format!("{:.1e}", x.abs())),
    )
}

pub fn float_to_string(x: f32) -> String {
    format_java(
        x.is_nan(),
        x.is_infinite(),
        x == 0.0,
        x.is_sign_negative(),
        || (format!("{:e}", x.abs()), format!("{:.1e}", x.abs())),
    )
}

fn format_java(
    nan: bool,
    inf: bool,
    zero: bool,
    neg: bool,
    sci: impl Fn() -> (String, String),
) -> String {
    let sign = if neg { "-" } else { "" };
    if nan {
        return "NaN".into();
    }
    if inf {
        return format!("{sign}Infinity");
    }
    if zero {
        return format!("{sign}0.0");
    }
    let (shortest, two) = sci();
    let (mut digits, mut exp) = split_sci(&shortest);
    if digits.len() == 1 {
        (digits, exp) = split_sci(&two);
        while digits.len() > 1 && digits.ends_with('0') {
            digits.pop();
        }
    }
    format!("{sign}{}", layout(&digits, exp))
}

/// "1.2345e-5" -> ("12345", -5).
fn split_sci(s: &str) -> (String, i32) {
    let (mantissa, exp) = s.split_once('e').unwrap_or((s, "0"));
    let digits: String = mantissa.chars().filter(char::is_ascii_digit).collect();
    (digits, exp.parse().unwrap_or(0))
}

fn layout(digits: &str, exp: i32) -> String {
    if (-3..7).contains(&exp) {
        if exp >= 0 {
            let int_len = exp as usize + 1;
            let mut int_part: String = digits.chars().take(int_len).collect();
            while int_part.len() < int_len {
                int_part.push('0');
            }
            let frac = digits
                .get(int_len..)
                .filter(|f| !f.is_empty())
                .unwrap_or("0");
            format!("{int_part}.{frac}")
        } else {
            format!("0.{}{digits}", "0".repeat((-exp - 1) as usize))
        }
    } else {
        let frac = digits.get(1..).filter(|f| !f.is_empty()).unwrap_or("0");
        format!("{}.{frac}E{exp}", &digits[..1])
    }
}

enum Parsed {
    /// A validated decimal literal for Rust's correctly rounding parser.
    Decimal(String),
    Value(f64),
    Hex {
        negative: bool,
        value: crate::hexfloat::Exact,
    },
}

fn nfe(s: &str) -> VmError {
    VmError::exception(
        "java/lang/NumberFormatException",
        format!("For input string: \"{s}\""),
    )
}

/// Validates the `Double.valueOf` grammar, which is stricter than Rust's
/// (no "inf", no "nan") and allows a type suffix and hex significands.
fn parse(input: &str) -> VmResult<Parsed> {
    let s = input.trim_matches(|c: char| c <= ' ');
    if s.is_empty() {
        return Err(VmError::exception(
            "java/lang/NumberFormatException",
            "empty String",
        ));
    }
    let (neg, body) = match s.as_bytes()[0] {
        b'-' => (true, &s[1..]),
        b'+' => (false, &s[1..]),
        _ => (false, s),
    };
    let signed = |x: f64| if neg { -x } else { x };
    match body {
        "NaN" => return Ok(Parsed::Value(f64::NAN)),
        "Infinity" => return Ok(Parsed::Value(signed(f64::INFINITY))),
        _ => {}
    }
    let body = body.strip_suffix(['f', 'F', 'd', 'D']).unwrap_or(body);
    if let Some(hex) = body.strip_prefix("0x").or_else(|| body.strip_prefix("0X")) {
        return crate::hexfloat::parse(hex)
            .map(|value| Parsed::Hex {
                negative: neg,
                value,
            })
            .ok_or_else(|| nfe(input));
    }
    if !is_decimal(body) {
        return Err(nfe(input));
    }
    Ok(Parsed::Decimal(format!(
        "{}{body}",
        if neg { "-" } else { "" }
    )))
}

fn is_decimal(s: &str) -> bool {
    let b = s.as_bytes();
    let mut i = 0;
    let int_digits = count_digits(b, &mut i);
    let mut frac_digits = 0;
    if b.get(i) == Some(&b'.') {
        i += 1;
        frac_digits = count_digits(b, &mut i);
    }
    if int_digits + frac_digits == 0 {
        return false;
    }
    if matches!(b.get(i), Some(b'e' | b'E')) {
        i += 1;
        if matches!(b.get(i), Some(b'+' | b'-')) {
            i += 1;
        }
        if count_digits(b, &mut i) == 0 {
            return false;
        }
    }
    i == b.len()
}

fn count_digits(b: &[u8], i: &mut usize) -> usize {
    let start = *i;
    while b.get(*i).is_some_and(u8::is_ascii_digit) {
        *i += 1;
    }
    *i - start
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn double_to_string_matches_java_layout() {
        let cases = [
            (1.0, "1.0"),
            (0.1, "0.1"),
            (100.0, "100.0"),
            (1e7, "1.0E7"),
            (1.0e-3, "0.001"),
            (1.0e-4, "1.0E-4"),
            (123456.789, "123456.789"),
            (0.1 + 0.2, "0.30000000000000004"),
            (-0.0, "-0.0"),
            (f64::MIN_POSITIVE, "2.2250738585072014E-308"),
            (f64::from_bits(1), "4.9E-324"),
            (f64::MAX, "1.7976931348623157E308"),
            (1e23, "1.0E23"),
            (0.5, "0.5"),
            (9999999.0, "9999999.0"),
        ];
        for (x, want) in cases {
            assert_eq!(double_to_string(x), want, "{x:e}");
        }
    }

    #[test]
    fn float_to_string_matches_java_layout() {
        for (x, want) in [
            (1.0f32, "1.0"),
            (0.1, "0.1"),
            (3.4028235e38, "3.4028235E38"),
            (1.4e-45, "1.4E-45"),
            (1e10, "1.0E10"),
        ] {
            assert_eq!(float_to_string(x), want);
        }
    }

    #[test]
    fn parse_accepts_java_grammar_only() {
        for ok in [
            "1",
            "1.",
            ".5",
            "1e5",
            "+1.5E-3",
            "  2d ",
            "3f",
            "0x1.8p1",
            "NaN",
            "-Infinity",
        ] {
            assert!(parse(ok).is_ok(), "{ok}");
        }
        for bad in [
            "", "inf", "nan", "1e", "e5", ".", "1.2.3", "0x1.8", "1_0", "--1",
        ] {
            assert!(parse(bad).is_err(), "{bad}");
        }
        assert!(matches!(
            parse("-0x1.8p1"),
            Ok(Parsed::Hex { negative: true, .. })
        ));
    }
}
