//! Hexadecimal floating-point literals (`0x1.8p1`, JLS 3.10.2), rounded
//! once, directly to the target format, so `float` results are not double
//! rounded through `double`.

/// An exact binary value: `mantissa * 2^exponent`. When hex digits beyond
/// 120 significant bits were dropped, the lowest mantissa bit is set as a
/// sticky bit, which is below every rounding position.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Exact {
    mantissa: u128,
    exponent: i64,
}

/// Exponents are saturated here: far beyond any format's range, yet far
/// below what could overflow when a string's digit scale (4 bits a digit)
/// is added, so the two still cancel exactly.
const EXP_CLAMP: i64 = 1 << 40;

/// Parses the part after `0x`: hex digits with an optional point, then a
/// mandatory `p` exponent with at most one sign.
pub fn parse(s: &str) -> Option<Exact> {
    let (sig, exp) = s.split_once(['p', 'P'])?;
    let digits = exp.strip_prefix(['+', '-']).unwrap_or(exp);
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    // Saturate instead of overflowing on absurdly long exponents.
    let magnitude = digits.bytes().fold(0i64, |acc, b| {
        (acc * 10 + i64::from(b - b'0')).min(EXP_CLAMP)
    });
    let exp = if exp.starts_with('-') {
        -magnitude
    } else {
        magnitude
    };
    let (int, frac) = sig.split_once('.').unwrap_or((sig, ""));
    if int.is_empty() && frac.is_empty() {
        return None;
    }
    let mut mantissa: u128 = 0;
    let mut scale: i64 = 0;
    let mut sticky = false;
    for (i, c) in int.chars().chain(frac.chars()).enumerate() {
        let d = c.to_digit(16)?;
        let in_fraction = i >= int.len();
        if mantissa >> 120 == 0 {
            mantissa = mantissa * 16 + u128::from(d);
            if in_fraction {
                scale -= 4;
            }
        } else {
            sticky |= d != 0;
            if !in_fraction {
                scale += 4;
            }
        }
    }
    Some(Exact {
        mantissa: mantissa | u128::from(sticky),
        exponent: exp + scale,
    })
}

pub fn to_f64(x: Exact) -> f64 {
    f64::from_bits(round_binary(x, 53, -1022, 1023))
}

pub fn to_f32(x: Exact) -> f32 {
    f32::from_bits(round_binary(x, 24, -126, 127) as u32)
}

/// Rounds to the nearest value with `p` significand bits and exponents in
/// `[emin, emax]`, ties to even, returning the format's bit pattern
/// (positive; the caller applies the sign).
fn round_binary(x: Exact, p: u32, emin: i64, emax: i64) -> u64 {
    let Exact {
        mantissa: m,
        exponent: e,
    } = x;
    if m == 0 {
        return 0;
    }
    let infinity = ((2 * emax + 1) as u64) << (p - 1);
    let bits = i64::from(128 - m.leading_zeros());
    let top = e + bits - 1;
    let mut unit = top.max(emin) - (i64::from(p) - 1);
    let shift = unit - e;
    let mut q = if shift <= 0 {
        // Exact: at most `p` bits after the shift (see `unit`).
        m << (-shift) as u32
    } else if shift > 128 {
        0
    } else {
        let (q, rem) = if shift == 128 {
            (0, m)
        } else {
            (m >> shift, m & ((1u128 << shift) - 1))
        };
        let half = 1u128 << (shift - 1);
        if rem > half || (rem == half && q & 1 == 1) {
            q + 1
        } else {
            q
        }
    };
    if q >> p != 0 {
        q >>= 1;
        unit += 1;
    }
    if q >> (p - 1) == 0 {
        // Subnormal (or zero): the biased exponent field is zero.
        return q as u64;
    }
    let biased = unit + i64::from(p) - 1 + emax;
    if biased > 2 * emax {
        return infinity;
    }
    ((biased as u64) << (p - 1)) | (q as u64 & ((1u64 << (p - 1)) - 1))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn f64_of(s: &str) -> Option<f64> {
        parse(s).map(to_f64)
    }

    #[test]
    fn exact_values() {
        assert_eq!(f64_of("1.8p1"), Some(3.0));
        assert_eq!(f64_of("1p-1074"), Some(f64::from_bits(1)));
        assert_eq!(f64_of("1.fffffffffffffp1023"), Some(f64::MAX));
        assert_eq!(f64_of("1p1024"), Some(f64::INFINITY));
        assert_eq!(f64_of("0p0"), Some(0.0));
        assert_eq!(f64_of(".8p1"), Some(1.0));
    }

    #[test]
    fn float_rounds_once() {
        // Just above the halfway point between 1 and the next float.
        assert_eq!(parse("1.0000010000000001p0").map(to_f32), Some(1.000_000_1));
        assert_eq!(
            parse("1.000001p0").map(to_f32),
            Some(1.0),
            "a tie rounds to even"
        );
        assert_eq!(parse("1p-149").map(to_f32), Some(f32::from_bits(1)));
        assert_eq!(
            parse("1p-150").map(to_f32),
            Some(0.0),
            "a tie at the smallest subnormal rounds to even (zero)"
        );
    }

    #[test]
    fn digit_scale_and_exponent_cancel() {
        let tiny = format!("0.{}1p120000", "0".repeat(30000));
        assert_eq!(f64_of(&tiny), Some(0.0625));
        let huge = format!("1{}p-120000", "0".repeat(30000));
        assert_eq!(f64_of(&huge), Some(1.0));
    }

    #[test]
    fn rejects_bad_exponents() {
        for bad in ["1p++1", "1p+-1", "1p", "1", "p1", "1pz", "1.8"] {
            assert_eq!(parse(bad), None, "{bad}");
        }
        assert_eq!(f64_of("1p99999999999999999999999"), Some(f64::INFINITY));
        assert_eq!(f64_of("1p-99999999999999999999999"), Some(0.0));
    }
}
