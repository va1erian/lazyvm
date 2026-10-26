package java.lang;

public final class Character implements java.io.Serializable, Comparable<Character> {
    public static final int MIN_RADIX = 2;
    public static final int MAX_RADIX = 36;
    public static final char MIN_VALUE = '\u0000';
    public static final char MAX_VALUE = '￿';
    public static final char MIN_HIGH_SURROGATE = '\uD800';
    public static final char MAX_HIGH_SURROGATE = '\uDBFF';
    public static final char MIN_LOW_SURROGATE = '\uDC00';
    public static final char MAX_LOW_SURROGATE = '\uDFFF';
    public static final char MIN_SURROGATE = MIN_HIGH_SURROGATE;
    public static final char MAX_SURROGATE = MAX_LOW_SURROGATE;
    public static final int MIN_SUPPLEMENTARY_CODE_POINT = 0x010000;
    public static final int MIN_CODE_POINT = 0x000000;
    public static final int MAX_CODE_POINT = 0x10FFFF;
    public static final int SIZE = 16;
    public static final int BYTES = 2;

    @SuppressWarnings("unchecked")
    public static final Class<Character> TYPE = (Class<Character>) Class.getPrimitiveClass("char");

    private final char value;

    public Character(char value) {
        this.value = value;
    }

    private static final class Cache {
        static final Character[] CACHE = new Character[128];

        static {
            for (int i = 0; i < 128; i++) {
                CACHE[i] = new Character((char) i);
            }
        }
    }

    public static Character valueOf(char c) {
        if (c <= 127) {
            return Cache.CACHE[c];
        }
        return new Character(c);
    }

    public char charValue() {
        return value;
    }

    public int hashCode() {
        return value;
    }

    public static int hashCode(char value) {
        return value;
    }

    public boolean equals(Object obj) {
        return obj instanceof Character && value == ((Character) obj).value;
    }

    public String toString() {
        return String.valueOf(value);
    }

    public static String toString(char c) {
        return String.valueOf(c);
    }

    public int compareTo(Character other) {
        return compare(value, other.value);
    }

    public static int compare(char x, char y) {
        return x - y;
    }

    public static char reverseBytes(char ch) {
        return (char) (((ch & 0xFF00) >> 8) | (ch << 8));
    }

    // Classification and mapping on code points are provided by the VM.

    public static native boolean isLetter(int codePoint);

    public static native boolean isDigit(int codePoint);

    public static native boolean isLetterOrDigit(int codePoint);

    public static native boolean isWhitespace(int codePoint);

    public static native boolean isUpperCase(int codePoint);

    public static native boolean isLowerCase(int codePoint);

    public static native boolean isSpaceChar(int codePoint);

    public static native boolean isAlphabetic(int codePoint);

    public static native int toUpperCase(int codePoint);

    public static native int toLowerCase(int codePoint);

    public static native int digit(int codePoint, int radix);

    public static boolean isLetter(char ch) {
        return isLetter((int) ch);
    }

    public static boolean isDigit(char ch) {
        return isDigit((int) ch);
    }

    public static boolean isLetterOrDigit(char ch) {
        return isLetterOrDigit((int) ch);
    }

    public static boolean isWhitespace(char ch) {
        return isWhitespace((int) ch);
    }

    public static boolean isUpperCase(char ch) {
        return isUpperCase((int) ch);
    }

    public static boolean isLowerCase(char ch) {
        return isLowerCase((int) ch);
    }

    public static boolean isSpaceChar(char ch) {
        return isSpaceChar((int) ch);
    }

    public static char toUpperCase(char ch) {
        return (char) toUpperCase((int) ch);
    }

    public static char toLowerCase(char ch) {
        return (char) toLowerCase((int) ch);
    }

    public static int digit(char ch, int radix) {
        return digit((int) ch, radix);
    }

    public static char forDigit(int digit, int radix) {
        if (radix < MIN_RADIX || radix > MAX_RADIX || digit < 0 || digit >= radix) {
            return '\0';
        }
        return (char) (digit < 10 ? '0' + digit : 'a' + digit - 10);
    }

    /** Numeric value for ASCII digits and letters only; -1 for anything else. */
    public static int getNumericValue(int codePoint) {
        if (codePoint >= '0' && codePoint <= '9') {
            return codePoint - '0';
        }
        if (codePoint >= 'a' && codePoint <= 'z') {
            return codePoint - 'a' + 10;
        }
        if (codePoint >= 'A' && codePoint <= 'Z') {
            return codePoint - 'A' + 10;
        }
        return -1;
    }

    public static int getNumericValue(char ch) {
        return getNumericValue((int) ch);
    }

    public static boolean isISOControl(char ch) {
        return isISOControl((int) ch);
    }

    public static boolean isISOControl(int codePoint) {
        return codePoint <= 0x9F && (codePoint >= 0x7F || (codePoint >>> 5) == 0);
    }

    // Surrogates and code points (JLS 3.1: UTF-16).

    public static boolean isHighSurrogate(char ch) {
        return ch >= MIN_HIGH_SURROGATE && ch <= MAX_HIGH_SURROGATE;
    }

    public static boolean isLowSurrogate(char ch) {
        return ch >= MIN_LOW_SURROGATE && ch <= MAX_LOW_SURROGATE;
    }

    public static boolean isSurrogate(char ch) {
        return ch >= MIN_SURROGATE && ch <= MAX_SURROGATE;
    }

    public static boolean isSurrogatePair(char high, char low) {
        return isHighSurrogate(high) && isLowSurrogate(low);
    }

    public static boolean isBmpCodePoint(int codePoint) {
        return codePoint >>> 16 == 0;
    }

    public static boolean isValidCodePoint(int codePoint) {
        return codePoint >= MIN_CODE_POINT && codePoint <= MAX_CODE_POINT;
    }

    public static boolean isSupplementaryCodePoint(int codePoint) {
        return codePoint >= MIN_SUPPLEMENTARY_CODE_POINT && codePoint <= MAX_CODE_POINT;
    }

    public static int charCount(int codePoint) {
        return codePoint >= MIN_SUPPLEMENTARY_CODE_POINT ? 2 : 1;
    }

    public static int toCodePoint(char high, char low) {
        return ((high - MIN_HIGH_SURROGATE) << 10) + (low - MIN_LOW_SURROGATE)
                + MIN_SUPPLEMENTARY_CODE_POINT;
    }

    public static char highSurrogate(int codePoint) {
        return (char) (MIN_HIGH_SURROGATE + ((codePoint - MIN_SUPPLEMENTARY_CODE_POINT) >>> 10));
    }

    public static char lowSurrogate(int codePoint) {
        return (char) (MIN_LOW_SURROGATE + ((codePoint - MIN_SUPPLEMENTARY_CODE_POINT) & 0x3ff));
    }

    public static int toChars(int codePoint, char[] dst, int dstIndex) {
        if (isBmpCodePoint(codePoint)) {
            dst[dstIndex] = (char) codePoint;
            return 1;
        }
        if (!isValidCodePoint(codePoint)) {
            throw new IllegalArgumentException();
        }
        dst[dstIndex] = highSurrogate(codePoint);
        dst[dstIndex + 1] = lowSurrogate(codePoint);
        return 2;
    }

    public static char[] toChars(int codePoint) {
        if (isBmpCodePoint(codePoint)) {
            return new char[] {(char) codePoint};
        }
        if (!isValidCodePoint(codePoint)) {
            throw new IllegalArgumentException();
        }
        return new char[] {highSurrogate(codePoint), lowSurrogate(codePoint)};
    }

    public static int codePointAt(CharSequence seq, int index) {
        char high = seq.charAt(index);
        if (isHighSurrogate(high) && index + 1 < seq.length()) {
            char low = seq.charAt(index + 1);
            if (isLowSurrogate(low)) {
                return toCodePoint(high, low);
            }
        }
        return high;
    }

    public static int codePointAt(char[] a, int index) {
        return codePointAtUnchecked(a, index, a.length);
    }

    public static int codePointAt(char[] a, int index, int limit) {
        if (index >= limit || index < 0 || limit > a.length) {
            throw new IndexOutOfBoundsException();
        }
        return codePointAtUnchecked(a, index, limit);
    }

    // The two-argument form reports a bad index through the array access itself.
    private static int codePointAtUnchecked(char[] a, int index, int limit) {
        char high = a[index];
        if (isHighSurrogate(high) && index + 1 < limit && isLowSurrogate(a[index + 1])) {
            return toCodePoint(high, a[index + 1]);
        }
        return high;
    }
}
