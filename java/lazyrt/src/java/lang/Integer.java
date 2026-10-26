package java.lang;

public final class Integer extends Number implements Comparable<Integer> {
    public static final int MIN_VALUE = 0x80000000;
    public static final int MAX_VALUE = 0x7fffffff;
    public static final int SIZE = 32;
    public static final int BYTES = 4;

    @SuppressWarnings("unchecked")
    public static final Class<Integer> TYPE = (Class<Integer>) Class.getPrimitiveClass("int");

    private static final char[] DIGITS = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h',
        'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z'
    };

    private final int value;

    public Integer(int value) {
        this.value = value;
    }

    public Integer(String s) throws NumberFormatException {
        this(parseInt(s, 10));
    }

    private static final class Cache {
        static final Integer[] CACHE = new Integer[256];

        static {
            for (int i = 0; i < 256; i++) {
                CACHE[i] = new Integer(i - 128);
            }
        }
    }

    public static Integer valueOf(int i) {
        if (i >= -128 && i <= 127) {
            return Cache.CACHE[i + 128];
        }
        return new Integer(i);
    }

    public static Integer valueOf(String s, int radix) throws NumberFormatException {
        return valueOf(parseInt(s, radix));
    }

    public static Integer valueOf(String s) throws NumberFormatException {
        return valueOf(parseInt(s, 10));
    }

    public static String toString(int i, int radix) {
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            radix = 10;
        }
        char[] buf = new char[33];
        int pos = 33;
        boolean negative = i < 0;
        // Work with negative values so MIN_VALUE needs no special case.
        if (!negative) {
            i = -i;
        }
        while (i <= -radix) {
            buf[--pos] = DIGITS[-(i % radix)];
            i /= radix;
        }
        buf[--pos] = DIGITS[-i];
        if (negative) {
            buf[--pos] = '-';
        }
        return new String(buf, pos, 33 - pos);
    }

    public static String toString(int i) {
        return toString(i, 10);
    }

    public String toString() {
        return toString(value, 10);
    }

    private static String toUnsignedBase(int i, int shift) {
        char[] buf = new char[32];
        int pos = 32;
        int mask = (1 << shift) - 1;
        do {
            buf[--pos] = DIGITS[i & mask];
            i >>>= shift;
        } while (i != 0);
        return new String(buf, pos, 32 - pos);
    }

    public static String toHexString(int i) {
        return toUnsignedBase(i, 4);
    }

    public static String toOctalString(int i) {
        return toUnsignedBase(i, 3);
    }

    public static String toBinaryString(int i) {
        return toUnsignedBase(i, 1);
    }

    public static String toUnsignedString(int i, int radix) {
        return Long.toString(toUnsignedLong(i), radix);
    }

    public static String toUnsignedString(int i) {
        return Long.toString(toUnsignedLong(i), 10);
    }

    public static long toUnsignedLong(int x) {
        return ((long) x) & 0xffffffffL;
    }

    public static int parseInt(String s, int radix) throws NumberFormatException {
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
        int limit = -MAX_VALUE;
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
        int multmin = limit / radix;
        int result = 0;
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

    public static int parseInt(String s) throws NumberFormatException {
        return parseInt(s, 10);
    }

    public static int parseUnsignedInt(String s, int radix) throws NumberFormatException {
        if (s == null) {
            throw new NumberFormatException("null");
        }
        if (s.length() > 0 && s.charAt(0) == '-') {
            throw new NumberFormatException("Illegal leading minus sign on unsigned string " + s + ".");
        }
        long v = Long.parseLong(s, radix);
        if ((v & 0xffffffff00000000L) != 0) {
            throw new NumberFormatException("String value " + s + " exceeds range of unsigned int.");
        }
        return (int) v;
    }

    public static int parseUnsignedInt(String s) throws NumberFormatException {
        return parseUnsignedInt(s, 10);
    }

    public static Integer decode(String nm) throws NumberFormatException {
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
        return value;
    }

    public long longValue() {
        return value;
    }

    public float floatValue() {
        return value;
    }

    public double doubleValue() {
        return value;
    }

    public int hashCode() {
        return value;
    }

    public static int hashCode(int value) {
        return value;
    }

    public boolean equals(Object obj) {
        return obj instanceof Integer && value == ((Integer) obj).value;
    }

    public int compareTo(Integer other) {
        return compare(value, other.value);
    }

    public static int compare(int x, int y) {
        return x < y ? -1 : (x == y ? 0 : 1);
    }

    public static int compareUnsigned(int x, int y) {
        return compare(x + MIN_VALUE, y + MIN_VALUE);
    }

    public static int divideUnsigned(int dividend, int divisor) {
        return (int) (toUnsignedLong(dividend) / toUnsignedLong(divisor));
    }

    public static int remainderUnsigned(int dividend, int divisor) {
        return (int) (toUnsignedLong(dividend) % toUnsignedLong(divisor));
    }

    public static int signum(int i) {
        return (i >> 31) | (-i >>> 31);
    }

    public static int sum(int a, int b) {
        return a + b;
    }

    public static int max(int a, int b) {
        return a >= b ? a : b;
    }

    public static int min(int a, int b) {
        return a <= b ? a : b;
    }

    public static int bitCount(int i) {
        i = i - ((i >>> 1) & 0x55555555);
        i = (i & 0x33333333) + ((i >>> 2) & 0x33333333);
        i = (i + (i >>> 4)) & 0x0f0f0f0f;
        i = i + (i >>> 8);
        i = i + (i >>> 16);
        return i & 0x3f;
    }

    public static int highestOneBit(int i) {
        return i == 0 ? 0 : (MIN_VALUE >>> numberOfLeadingZeros(i));
    }

    public static int lowestOneBit(int i) {
        return i & -i;
    }

    public static int numberOfLeadingZeros(int i) {
        if (i == 0) {
            return 32;
        }
        int n = 0;
        if ((i >>> 16) == 0) {
            n += 16;
            i <<= 16;
        }
        if ((i >>> 24) == 0) {
            n += 8;
            i <<= 8;
        }
        if ((i >>> 28) == 0) {
            n += 4;
            i <<= 4;
        }
        if ((i >>> 30) == 0) {
            n += 2;
            i <<= 2;
        }
        if ((i >>> 31) == 0) {
            n += 1;
        }
        return n;
    }

    public static int numberOfTrailingZeros(int i) {
        if (i == 0) {
            return 32;
        }
        int n = 0;
        while ((i & 1) == 0) {
            n++;
            i >>>= 1;
        }
        return n;
    }

    public static int rotateLeft(int i, int distance) {
        return (i << distance) | (i >>> -distance);
    }

    public static int rotateRight(int i, int distance) {
        return (i >>> distance) | (i << -distance);
    }

    public static int reverse(int i) {
        int r = 0;
        for (int k = 0; k < 32; k++) {
            r = (r << 1) | (i & 1);
            i >>>= 1;
        }
        return r;
    }

    public static int reverseBytes(int i) {
        return (i << 24) | ((i & 0xff00) << 8) | ((i >>> 8) & 0xff00) | (i >>> 24);
    }
}
