package java.lang;

public final class Long extends Number implements Comparable<Long> {
    public static final long MIN_VALUE = 0x8000000000000000L;
    public static final long MAX_VALUE = 0x7fffffffffffffffL;
    public static final int SIZE = 64;
    public static final int BYTES = 8;

    @SuppressWarnings("unchecked")
    public static final Class<Long> TYPE = (Class<Long>) Class.getPrimitiveClass("long");

    private static final char[] DIGITS = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h',
        'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z'
    };

    private final long value;

    public Long(long value) {
        this.value = value;
    }

    public Long(String s) throws NumberFormatException {
        this(parseLong(s, 10));
    }

    private static final class Cache {
        static final Long[] CACHE = new Long[256];

        static {
            for (int i = 0; i < 256; i++) {
                CACHE[i] = new Long(i - 128);
            }
        }
    }

    public static Long valueOf(long l) {
        if (l >= -128 && l <= 127) {
            return Cache.CACHE[(int) l + 128];
        }
        return new Long(l);
    }

    public static Long valueOf(String s, int radix) throws NumberFormatException {
        return valueOf(parseLong(s, radix));
    }

    public static Long valueOf(String s) throws NumberFormatException {
        return valueOf(parseLong(s, 10));
    }

    public static String toString(long i, int radix) {
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            radix = 10;
        }
        char[] buf = new char[65];
        int pos = 65;
        boolean negative = i < 0;
        // Work with negative values so MIN_VALUE needs no special case.
        if (!negative) {
            i = -i;
        }
        while (i <= -radix) {
            buf[--pos] = DIGITS[(int) -(i % radix)];
            i /= radix;
        }
        buf[--pos] = DIGITS[(int) -i];
        if (negative) {
            buf[--pos] = '-';
        }
        return new String(buf, pos, 65 - pos);
    }

    public static String toString(long i) {
        return toString(i, 10);
    }

    public String toString() {
        return toString(value, 10);
    }

    private static String toUnsignedBase(long i, int shift) {
        char[] buf = new char[64];
        int pos = 64;
        long mask = (1L << shift) - 1;
        do {
            buf[--pos] = DIGITS[(int) (i & mask)];
            i >>>= shift;
        } while (i != 0);
        return new String(buf, pos, 64 - pos);
    }

    public static String toHexString(long i) {
        return toUnsignedBase(i, 4);
    }

    public static String toOctalString(long i) {
        return toUnsignedBase(i, 3);
    }

    public static String toBinaryString(long i) {
        return toUnsignedBase(i, 1);
    }

    public static String toUnsignedString(long i, int radix) {
        if (i >= 0) {
            return toString(i, radix);
        }
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            radix = 10;
        }
        long quotient = divideUnsigned(i, radix);
        long rem = i - quotient * radix;
        return toString(quotient, radix) + DIGITS[(int) rem];
    }

    public static String toUnsignedString(long i) {
        return toUnsignedString(i, 10);
    }

    public static long parseLong(String s, int radix) throws NumberFormatException {
        if (s == null) {
            throw new NumberFormatException("null");
        }
        if (radix < Character.MIN_RADIX) {
            throw new NumberFormatException("radix " + radix + " less than Character.MIN_RADIX");
        }
        if (radix > Character.MAX_RADIX) {
            throw new NumberFormatException("radix " + radix + " greater than Character.MAX_RADIX");
        }
        int len = s.length();
        if (len == 0) {
            throw NumberFormatException.forInputString(s);
        }
        int i = 0;
        boolean negative = false;
        long limit = -MAX_VALUE;
        char first = s.charAt(0);
        if (first < '0') {
            if (first == '-') {
                negative = true;
                limit = MIN_VALUE;
            } else if (first != '+') {
                throw NumberFormatException.forInputString(s);
            }
            if (len == 1) {
                throw NumberFormatException.forInputString(s);
            }
            i = 1;
        }
        // Accumulate as a negative number: it can hold MIN_VALUE.
        long multmin = limit / radix;
        long result = 0;
        while (i < len) {
            int digit = Character.digit(s.charAt(i++), radix);
            if (digit < 0 || result < multmin) {
                throw NumberFormatException.forInputString(s);
            }
            result *= radix;
            if (result < limit + digit) {
                throw NumberFormatException.forInputString(s);
            }
            result -= digit;
        }
        return negative ? result : -result;
    }

    public static long parseLong(String s) throws NumberFormatException {
        return parseLong(s, 10);
    }

    public static long parseUnsignedLong(String s, int radix) throws NumberFormatException {
        if (s == null) {
            throw new NumberFormatException("null");
        }
        int len = s.length();
        if (len == 0) {
            throw NumberFormatException.forInputString(s);
        }
        if (s.charAt(0) == '-') {
            throw new NumberFormatException("Illegal leading minus sign on unsigned string " + s + ".");
        }
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            throw new NumberFormatException("radix " + radix + " out of range");
        }
        int i = s.charAt(0) == '+' ? 1 : 0;
        if (i == len) {
            throw NumberFormatException.forInputString(s);
        }
        long max = divideUnsigned(-1L, radix);
        long result = 0;
        for (; i < len; i++) {
            int digit = Character.digit(s.charAt(i), radix);
            if (digit < 0 || compareUnsigned(result, max) > 0) {
                throw NumberFormatException.forInputString(s);
            }
            long scaled = result * radix;
            result = scaled + digit;
            if (compareUnsigned(result, scaled) < 0) {
                throw new NumberFormatException("String value " + s + " exceeds range of unsigned long.");
            }
        }
        return result;
    }

    public static long parseUnsignedLong(String s) throws NumberFormatException {
        return parseUnsignedLong(s, 10);
    }

    public static Long decode(String nm) throws NumberFormatException {
        if (nm.length() == 0) {
            throw new NumberFormatException("Zero length string");
        }
        int index = 0;
        boolean negative = false;
        char first = nm.charAt(0);
        if (first == '-') {
            negative = true;
            index++;
        } else if (first == '+') {
            index++;
        }
        int radix = 10;
        if (nm.startsWith("0x", index) || nm.startsWith("0X", index)) {
            index += 2;
            radix = 16;
        } else if (nm.startsWith("#", index)) {
            index++;
            radix = 16;
        } else if (nm.startsWith("0", index) && nm.length() > 1 + index) {
            index++;
            radix = 8;
        }
        if (nm.startsWith("-", index) || nm.startsWith("+", index)) {
            throw new NumberFormatException("Sign character in wrong position");
        }
        String digits = nm.substring(index);
        return valueOf(negative ? "-" + digits : digits, radix);
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
        return value;
    }

    public float floatValue() {
        return (float) value;
    }

    public double doubleValue() {
        return (double) value;
    }

    public int hashCode() {
        return hashCode(value);
    }

    public static int hashCode(long value) {
        return (int) (value ^ (value >>> 32));
    }

    public boolean equals(Object obj) {
        return obj instanceof Long && value == ((Long) obj).value;
    }

    public int compareTo(Long other) {
        return compare(value, other.value);
    }

    public static int compare(long x, long y) {
        return x < y ? -1 : (x == y ? 0 : 1);
    }

    public static int compareUnsigned(long x, long y) {
        return compare(x + MIN_VALUE, y + MIN_VALUE);
    }

    public static long divideUnsigned(long dividend, long divisor) {
        if (divisor < 0) {
            return compareUnsigned(dividend, divisor) < 0 ? 0L : 1L;
        }
        if (dividend >= 0) {
            return dividend / divisor;
        }
        long quotient = ((dividend >>> 1) / divisor) << 1;
        long rem = dividend - quotient * divisor;
        return quotient + (compareUnsigned(rem, divisor) >= 0 ? 1 : 0);
    }

    public static long remainderUnsigned(long dividend, long divisor) {
        if (divisor == 0) {
            throw new ArithmeticException("BigInteger divide by zero");
        }
        return dividend - divideUnsigned(dividend, divisor) * divisor;
    }

    public static int signum(long i) {
        return (int) ((i >> 63) | (-i >>> 63));
    }

    public static long sum(long a, long b) {
        return a + b;
    }

    public static long max(long a, long b) {
        return a >= b ? a : b;
    }

    public static long min(long a, long b) {
        return a <= b ? a : b;
    }

    public static int bitCount(long i) {
        return Integer.bitCount((int) i) + Integer.bitCount((int) (i >>> 32));
    }

    public static long highestOneBit(long i) {
        return i == 0 ? 0L : (MIN_VALUE >>> numberOfLeadingZeros(i));
    }

    public static long lowestOneBit(long i) {
        return i & -i;
    }

    public static int numberOfLeadingZeros(long i) {
        int hi = (int) (i >>> 32);
        return hi == 0 ? 32 + Integer.numberOfLeadingZeros((int) i) : Integer.numberOfLeadingZeros(hi);
    }

    public static int numberOfTrailingZeros(long i) {
        int lo = (int) i;
        return lo == 0 ? 32 + Integer.numberOfTrailingZeros((int) (i >>> 32))
                : Integer.numberOfTrailingZeros(lo);
    }

    public static long rotateLeft(long i, int distance) {
        return (i << distance) | (i >>> -distance);
    }

    public static long rotateRight(long i, int distance) {
        return (i >>> distance) | (i << -distance);
    }

    public static long reverse(long i) {
        long r = 0;
        for (int k = 0; k < 64; k++) {
            r = (r << 1) | (i & 1L);
            i >>>= 1;
        }
        return r;
    }

    public static long reverseBytes(long i) {
        return ((long) Integer.reverseBytes((int) i) << 32)
                | (Integer.reverseBytes((int) (i >>> 32)) & 0xffffffffL);
    }
}
