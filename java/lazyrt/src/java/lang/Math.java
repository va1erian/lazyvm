package java.lang;

public final class Math {
    public static final double E = 2.7182818284590452354;
    public static final double PI = 3.14159265358979323846;

    private static final int FLOAT_SIGN = 0x80000000;
    private static final long DOUBLE_SIGN = 0x8000000000000000L;

    private Math() {
    }

    private static java.util.Random randomGenerator;

    /** One shared generator, made on first use. */
    public static synchronized double random() {
        if (randomGenerator == null) {
            randomGenerator = new java.util.Random();
        }
        return randomGenerator.nextDouble();
    }

    public static double sin(double a) {
        return StrictMath.sin(a);
    }

    public static double cos(double a) {
        return StrictMath.cos(a);
    }

    public static double tan(double a) {
        return StrictMath.tan(a);
    }

    public static double asin(double a) {
        return StrictMath.asin(a);
    }

    public static double acos(double a) {
        return StrictMath.acos(a);
    }

    public static double atan(double a) {
        return StrictMath.atan(a);
    }

    public static double exp(double a) {
        return StrictMath.exp(a);
    }

    public static double log(double a) {
        return StrictMath.log(a);
    }

    public static double log10(double a) {
        return StrictMath.log10(a);
    }

    public static double sqrt(double a) {
        return StrictMath.sqrt(a);
    }

    public static double cbrt(double a) {
        return StrictMath.cbrt(a);
    }

    public static double ceil(double a) {
        return StrictMath.ceil(a);
    }

    public static double floor(double a) {
        return StrictMath.floor(a);
    }

    public static double rint(double a) {
        return StrictMath.rint(a);
    }

    public static double sinh(double x) {
        return StrictMath.sinh(x);
    }

    public static double cosh(double x) {
        return StrictMath.cosh(x);
    }

    public static double tanh(double x) {
        return StrictMath.tanh(x);
    }

    public static double expm1(double x) {
        return StrictMath.expm1(x);
    }

    public static double log1p(double x) {
        return StrictMath.log1p(x);
    }

    public static double atan2(double y, double x) {
        return StrictMath.atan2(y, x);
    }

    public static double pow(double a, double b) {
        return StrictMath.pow(a, b);
    }

    public static double hypot(double x, double y) {
        return StrictMath.hypot(x, y);
    }

    public static double IEEEremainder(double f1, double f2) {
        return StrictMath.IEEEremainder(f1, f2);
    }

    public static double toRadians(double angdeg) {
        return angdeg / 180.0 * PI;
    }

    public static double toDegrees(double angrad) {
        return angrad * 180.0 / PI;
    }

    public static int abs(int a) {
        return a < 0 ? -a : a;
    }

    public static long abs(long a) {
        return a < 0 ? -a : a;
    }

    public static float abs(float a) {
        return Float.intBitsToFloat(Float.floatToRawIntBits(a) & ~FLOAT_SIGN);
    }

    public static double abs(double a) {
        return Double.longBitsToDouble(Double.doubleToRawLongBits(a) & ~DOUBLE_SIGN);
    }

    public static int max(int a, int b) {
        return a >= b ? a : b;
    }

    public static long max(long a, long b) {
        return a >= b ? a : b;
    }

    public static float max(float a, float b) {
        if (a != a) {
            return a;
        }
        if (a == 0.0f && b == 0.0f && Float.floatToRawIntBits(a) == FLOAT_SIGN) {
            return b;
        }
        return a >= b ? a : b;
    }

    public static double max(double a, double b) {
        if (a != a) {
            return a;
        }
        if (a == 0.0 && b == 0.0 && Double.doubleToRawLongBits(a) == DOUBLE_SIGN) {
            return b;
        }
        return a >= b ? a : b;
    }

    public static int min(int a, int b) {
        return a <= b ? a : b;
    }

    public static long min(long a, long b) {
        return a <= b ? a : b;
    }

    public static float min(float a, float b) {
        if (a != a) {
            return a;
        }
        if (a == 0.0f && b == 0.0f && Float.floatToRawIntBits(b) == FLOAT_SIGN) {
            return b;
        }
        return a <= b ? a : b;
    }

    public static double min(double a, double b) {
        if (a != a) {
            return a;
        }
        if (a == 0.0 && b == 0.0 && Double.doubleToRawLongBits(b) == DOUBLE_SIGN) {
            return b;
        }
        return a <= b ? a : b;
    }

    /** Rounds half up (towards positive infinity), saturating; NaN gives 0. */
    public static long round(double a) {
        if (a != a) {
            return 0L;
        }
        double f = floor(a);
        long r = (long) f;
        // a - f is exact below 2^52, and every larger value is already integral.
        if (a - f >= 0.5) {
            r++;
        }
        return r;
    }

    public static int round(float a) {
        long r = round((double) a);
        if (r > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (r < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) r;
    }

    public static double signum(double d) {
        return (d == 0.0 || d != d) ? d : copySign(1.0, d);
    }

    public static float signum(float f) {
        return (f == 0.0f || f != f) ? f : copySign(1.0f, f);
    }

    public static double copySign(double magnitude, double sign) {
        long m = Double.doubleToRawLongBits(magnitude) & ~DOUBLE_SIGN;
        long s = Double.doubleToRawLongBits(sign) & DOUBLE_SIGN;
        return Double.longBitsToDouble(m | s);
    }

    public static float copySign(float magnitude, float sign) {
        int m = Float.floatToRawIntBits(magnitude) & ~FLOAT_SIGN;
        int s = Float.floatToRawIntBits(sign) & FLOAT_SIGN;
        return Float.intBitsToFloat(m | s);
    }

    public static int addExact(int x, int y) {
        int r = x + y;
        if (((x ^ r) & (y ^ r)) < 0) {
            throw new ArithmeticException("integer overflow");
        }
        return r;
    }

    public static long addExact(long x, long y) {
        long r = x + y;
        if (((x ^ r) & (y ^ r)) < 0) {
            throw new ArithmeticException("long overflow");
        }
        return r;
    }

    public static int subtractExact(int x, int y) {
        int r = x - y;
        if (((x ^ y) & (x ^ r)) < 0) {
            throw new ArithmeticException("integer overflow");
        }
        return r;
    }

    public static long subtractExact(long x, long y) {
        long r = x - y;
        if (((x ^ y) & (x ^ r)) < 0) {
            throw new ArithmeticException("long overflow");
        }
        return r;
    }

    public static int multiplyExact(int x, int y) {
        long r = (long) x * (long) y;
        if ((int) r != r) {
            throw new ArithmeticException("integer overflow");
        }
        return (int) r;
    }

    public static long multiplyExact(long x, long y) {
        long r = x * y;
        // Division round-trips unless the product wrapped; MIN_VALUE * -1 is the one trap.
        if ((x == Long.MIN_VALUE && y == -1) || (y != 0 && r / y != x)) {
            throw new ArithmeticException("long overflow");
        }
        return r;
    }

    public static int incrementExact(int a) {
        if (a == Integer.MAX_VALUE) {
            throw new ArithmeticException("integer overflow");
        }
        return a + 1;
    }

    public static long incrementExact(long a) {
        if (a == Long.MAX_VALUE) {
            throw new ArithmeticException("long overflow");
        }
        return a + 1L;
    }

    public static int decrementExact(int a) {
        if (a == Integer.MIN_VALUE) {
            throw new ArithmeticException("integer overflow");
        }
        return a - 1;
    }

    public static long decrementExact(long a) {
        if (a == Long.MIN_VALUE) {
            throw new ArithmeticException("long overflow");
        }
        return a - 1L;
    }

    public static int negateExact(int a) {
        if (a == Integer.MIN_VALUE) {
            throw new ArithmeticException("integer overflow");
        }
        return -a;
    }

    public static long negateExact(long a) {
        if (a == Long.MIN_VALUE) {
            throw new ArithmeticException("long overflow");
        }
        return -a;
    }

    public static int toIntExact(long value) {
        if ((int) value != value) {
            throw new ArithmeticException("integer overflow");
        }
        return (int) value;
    }

    public static int floorDiv(int x, int y) {
        int q = x / y;
        if ((x ^ y) < 0 && q * y != x) {
            q--;
        }
        return q;
    }

    public static long floorDiv(long x, long y) {
        long q = x / y;
        if ((x ^ y) < 0 && q * y != x) {
            q--;
        }
        return q;
    }

    public static int floorMod(int x, int y) {
        int m = x % y;
        if ((m ^ y) < 0 && m != 0) {
            m += y;
        }
        return m;
    }

    public static long floorMod(long x, long y) {
        long m = x % y;
        if ((m ^ y) < 0 && m != 0) {
            m += y;
        }
        return m;
    }
}
