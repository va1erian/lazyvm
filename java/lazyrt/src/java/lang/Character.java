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

    public static final byte UNASSIGNED = 0;
    public static final byte UPPERCASE_LETTER = 1;
    public static final byte LOWERCASE_LETTER = 2;
    public static final byte TITLECASE_LETTER = 3;
    public static final byte MODIFIER_LETTER = 4;
    public static final byte OTHER_LETTER = 5;
    public static final byte NON_SPACING_MARK = 6;
    public static final byte ENCLOSING_MARK = 7;
    public static final byte COMBINING_SPACING_MARK = 8;
    public static final byte DECIMAL_DIGIT_NUMBER = 9;
    public static final byte LETTER_NUMBER = 10;
    public static final byte OTHER_NUMBER = 11;
    public static final byte SPACE_SEPARATOR = 12;
    public static final byte LINE_SEPARATOR = 13;
    public static final byte PARAGRAPH_SEPARATOR = 14;
    public static final byte CONTROL = 15;
    public static final byte FORMAT = 16;
    public static final byte PRIVATE_USE = 18;
    public static final byte SURROGATE = 19;
    public static final byte DASH_PUNCTUATION = 20;
    public static final byte START_PUNCTUATION = 21;
    public static final byte END_PUNCTUATION = 22;
    public static final byte CONNECTOR_PUNCTUATION = 23;
    public static final byte OTHER_PUNCTUATION = 24;
    public static final byte MATH_SYMBOL = 25;
    public static final byte CURRENCY_SYMBOL = 26;
    public static final byte MODIFIER_SYMBOL = 27;
    public static final byte OTHER_SYMBOL = 28;
    public static final byte INITIAL_QUOTE_PUNCTUATION = 29;
    public static final byte FINAL_QUOTE_PUNCTUATION = 30;

    public static final byte DIRECTIONALITY_UNDEFINED = -1;
    public static final byte DIRECTIONALITY_LEFT_TO_RIGHT = 0;
    public static final byte DIRECTIONALITY_RIGHT_TO_LEFT = 1;
    public static final byte DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC = 2;
    public static final byte DIRECTIONALITY_EUROPEAN_NUMBER = 3;
    public static final byte DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR = 4;
    public static final byte DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR = 5;
    public static final byte DIRECTIONALITY_ARABIC_NUMBER = 6;
    public static final byte DIRECTIONALITY_COMMON_NUMBER_SEPARATOR = 7;
    public static final byte DIRECTIONALITY_NONSPACING_MARK = 8;
    public static final byte DIRECTIONALITY_BOUNDARY_NEUTRAL = 9;
    public static final byte DIRECTIONALITY_PARAGRAPH_SEPARATOR = 10;
    public static final byte DIRECTIONALITY_SEGMENT_SEPARATOR = 11;
    public static final byte DIRECTIONALITY_WHITESPACE = 12;
    public static final byte DIRECTIONALITY_OTHER_NEUTRALS = 13;
    public static final byte DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING = 14;
    public static final byte DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE = 15;
    public static final byte DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING = 16;
    public static final byte DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE = 17;
    public static final byte DIRECTIONALITY_POP_DIRECTIONAL_FORMAT = 18;

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

    public static native boolean isTitleCase(int codePoint);

    public static native boolean isMirrored(int codePoint);

    public static native boolean isIdeographic(int codePoint);

    public static native boolean isDefined(int codePoint);

    public static native boolean isIdentifierIgnorable(int codePoint);

    public static native boolean isJavaIdentifierStart(int codePoint);

    public static native boolean isJavaIdentifierPart(int codePoint);

    public static native boolean isUnicodeIdentifierStart(int codePoint);

    public static native boolean isUnicodeIdentifierPart(int codePoint);

    public static native int toTitleCase(int codePoint);

    public static native int getType(int codePoint);

    public static native int getNumericValue(int codePoint);

    public static native byte getDirectionality(int codePoint);

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

    public static boolean isTitleCase(char ch) {
        return isTitleCase((int) ch);
    }

    public static boolean isMirrored(char ch) {
        return isMirrored((int) ch);
    }

    public static boolean isDefined(char ch) {
        return isDefined((int) ch);
    }

    public static boolean isIdentifierIgnorable(char ch) {
        return isIdentifierIgnorable((int) ch);
    }

    public static boolean isJavaIdentifierStart(char ch) {
        return isJavaIdentifierStart((int) ch);
    }

    public static boolean isJavaIdentifierPart(char ch) {
        return isJavaIdentifierPart((int) ch);
    }

    public static boolean isUnicodeIdentifierStart(char ch) {
        return isUnicodeIdentifierStart((int) ch);
    }

    public static boolean isUnicodeIdentifierPart(char ch) {
        return isUnicodeIdentifierPart((int) ch);
    }

    public static char toTitleCase(char ch) {
        return (char) toTitleCase((int) ch);
    }

    public static int getType(char ch) {
        return getType((int) ch);
    }

    public static byte getDirectionality(char ch) {
        return getDirectionality((int) ch);
    }

    /** @deprecated replaced by isJavaIdentifierStart(char) */
    @Deprecated
    public static boolean isJavaLetter(char ch) {
        return isJavaIdentifierStart((int) ch);
    }

    /** @deprecated replaced by isJavaIdentifierPart(char) */
    @Deprecated
    public static boolean isJavaLetterOrDigit(char ch) {
        return isJavaIdentifierPart((int) ch);
    }

    /** @deprecated replaced by isWhitespace(char) */
    @Deprecated
    public static boolean isSpace(char ch) {
        return ch <= 0x20 && ((1L << ch) & 0x100003600L) != 0;
    }

    public static char forDigit(int digit, int radix) {
        if (radix < MIN_RADIX || radix > MAX_RADIX || digit < 0 || digit >= radix) {
            return '\0';
        }
        return (char) (digit < 10 ? '0' + digit : 'a' + digit - 10);
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
