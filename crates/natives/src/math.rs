//! `java.lang.StrictMath` through the `libm` crate (a port of musl's libm,
//! itself derived from fdlibm), so results are the same on every platform.

use vm::{Value, Vm};

use crate::double;

const STRICT: &str = "java/lang/StrictMath";

macro_rules! unary {
    ($vm:ident, $($name:literal => $f:expr),* $(,)?) => {
        $( $vm.register_native(STRICT, $name, "(D)D", |_, args| {
            let f: fn(f64) -> f64 = $f;
            Ok(Some(Value::Double(f(double(args, 0)?))))
        }); )*
    };
}

macro_rules! binary {
    ($vm:ident, $($name:literal => $f:expr),* $(,)?) => {
        $( $vm.register_native(STRICT, $name, "(DD)D", |_, args| {
            let f: fn(f64, f64) -> f64 = $f;
            Ok(Some(Value::Double(f(double(args, 0)?, double(args, 1)?))))
        }); )*
    };
}

pub fn register(vm: &mut Vm) {
    unary!(vm,
        "sin" => libm::sin, "cos" => libm::cos, "tan" => libm::tan,
        "asin" => libm::asin, "acos" => libm::acos, "atan" => libm::atan,
        "exp" => libm::exp, "log" => libm::log, "log10" => libm::log10,
        "sqrt" => f64::sqrt, "cbrt" => libm::cbrt, "ceil" => libm::ceil,
        "floor" => libm::floor, "rint" => libm::rint, "sinh" => libm::sinh,
        "cosh" => libm::cosh, "tanh" => libm::tanh, "expm1" => libm::expm1,
        "log1p" => libm::log1p,
    );
    binary!(vm,
        "atan2" => libm::atan2, "pow" => java_pow, "hypot" => libm::hypot,
        "IEEEremainder" => libm::remainder,
    );
}

/// `pow` with Java's special case: `pow(x, NaN)` is NaN even for x = 1,
/// where C's `pow` returns 1 (Javadoc of `Math.pow`).
fn java_pow(a: f64, b: f64) -> f64 {
    if b.is_nan() || (a.is_nan() && b != 0.0) {
        return f64::NAN;
    }
    if b.is_infinite() && a.abs() == 1.0 {
        return f64::NAN;
    }
    libm::pow(a, b)
}

#[cfg(test)]
mod tests {
    use super::java_pow;

    #[test]
    fn pow_special_cases_follow_java() {
        assert!(java_pow(1.0, f64::NAN).is_nan());
        assert!(java_pow(1.0, f64::INFINITY).is_nan());
        assert!(java_pow(-1.0, f64::NEG_INFINITY).is_nan());
        assert_eq!(java_pow(f64::NAN, 0.0), 1.0);
        assert_eq!(java_pow(2.0, 10.0), 1024.0);
    }
}
