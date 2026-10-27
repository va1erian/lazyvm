package java.lang;

public final class Double extends Number implements Comparable<Double> {
    public static final double POSITIVE_INFINITY = 1.0 / 0.0;
    public static final double NEGATIVE_INFINITY = -1.0 / 0.0;
    public static final double NaN = 0.0d / 0.0;
    public static final double MAX_VALUE = 0x1.fffffffffffffP+1023;
    public static final double MIN_NORMAL = 0x1.0p-1022;
    public static final double MIN_VALUE = 0x0.0000000000001P-1022;
    public static final int MAX_EXPONENT = 1023;
    public static final int MIN_EXPONENT = -1022;
    public static final int SIZE = 64;
    public static final int BYTES = 8;

    @SuppressWarnings("unchecked")
    public static final Class<Double> TYPE = (Class<Double>) Class.getPrimitiveClass("double");

    private static final long EXPONENT_MASK = 0x7ff0000000000000L;
    private static final long MANTISSA_MASK = 0x000fffffffffffffL;
    private static final long CANONICAL_NAN = 0x7ff8000000000000L;

    private final double value;

    public Double(double value) {
        this.value = value;
    }

    public Double(String s) throws NumberFormatException {
        this(parseDouble(s));
    }

    public static native String toString(double d);

    public static native double parseDouble(String s) throws NumberFormatException;

    public static native long doubleToRawLongBits(double value);

    public static native double longBitsToDouble(long bits);

    public static Double valueOf(String s) throws NumberFormatException {
        return new Double(parseDouble(s));
    }

    public static Double valueOf(double d) {
        return new Double(d);
    }

    public static long doubleToLongBits(double value) {
        long bits = doubleToRawLongBits(value);
        if ((bits & EXPONENT_MASK) == EXPONENT_MASK && (bits & MANTISSA_MASK) != 0) {
            return CANONICAL_NAN;
        }
        return bits;
    }

    public static boolean isNaN(double v) {
        return v != v;
    }

    public static boolean isInfinite(double v) {
        return v == POSITIVE_INFINITY || v == NEGATIVE_INFINITY;
    }

    public static boolean isFinite(double d) {
        return Math.abs(d) <= MAX_VALUE;
    }

    public boolean isNaN() {
        return isNaN(value);
    }

    public boolean isInfinite() {
        return isInfinite(value);
    }

    public String toString() {
        return toString(value);
    }

    public byte byteValue() {
        return (byte) value;
    }

    public short shortValue() {
        return (short) value;
    }

    public int intValue() {
        return (int) value;
    }

    public long longValue() {
        return (long) value;
    }

    public float floatValue() {
        return (float) value;
    }

    public double doubleValue() {
        return value;
    }

    public int hashCode() {
        return hashCode(value);
    }

    public static int hashCode(double value) {
        long bits = doubleToLongBits(value);
        return (int) (bits ^ (bits >>> 32));
    }

    public boolean equals(Object obj) {
        return obj instanceof Double
                && doubleToLongBits(((Double) obj).value) == doubleToLongBits(value);
    }

    public int compareTo(Double other) {
        return compare(value, other.value);
    }

    /** Orders -0.0 below 0.0 and NaN above everything. */
    public static int compare(double d1, double d2) {
        if (d1 < d2) {
            return -1;
        }
        if (d1 > d2) {
            return 1;
        }
        long a = doubleToLongBits(d1);
        long b = doubleToLongBits(d2);
        return a == b ? 0 : (a < b ? -1 : 1);
    }

    public static double sum(double a, double b) {
        return a + b;
    }

    public static double max(double a, double b) {
        return Math.max(a, b);
    }

    public static double min(double a, double b) {
        return Math.min(a, b);
    }
}
