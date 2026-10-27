package java.util;

/** One parsed `%...` specifier of a format string: its fields and its validity checks. */
final class FormatSpec {
    static final int MINUS = 1;
    static final int ALT = 2;
    static final int PLUS = 4;
    static final int SPACE = 8;
    static final int ZERO = 16;
    static final int GROUP = 32;
    static final int PAREN = 64;
    static final int PREV = 128;
    /** Set by an upper-case conversion; shown as `^` in exceptions about flags. */
    static final int UPPER = 256;
    /** The flag characters that can be written, in bit order. */
    private static final String FLAG_CHARS = "-#+ 0,(<";
    /** The order exceptions list flags in, with the bit of each. */
    private static final String SHOWN_CHARS = "-^#+ 0,(<";
    private static final int[] SHOWN_BITS = {MINUS, UPPER, ALT, PLUS, SPACE, ZERO, GROUP, PAREN, PREV};

    /** 0 for the next ordinary argument, -1 for the previous one, n for argument n. */
    int index;
    int flags;
    int width = -1;
    int precision = -1;
    /** The conversion character as written (the date/time suffix for `%t`). */
    char conv;
    /** True for `%t` and `%T`, whose `conv` is the suffix character. */
    boolean dateTime;
    boolean upper;
    /** Where the specifier ends in the format string. */
    int end;

    private FormatSpec() {
    }

    /** Parses the specifier whose `%` is at `pct`; the grammar is the one in the Formatter spec. */
    static FormatSpec parse(String s, int pct) {
        FormatSpec f = new FormatSpec();
        int n = s.length();
        int p = pct + 1;
        int q = digitsEnd(s, p);
        String indexText = null;
        if (q > p && q < n && s.charAt(q) == '$') {
            indexText = s.substring(p, q);
            p = q + 1;
        }
        int flagStart = p;
        while (p < n && flagBit(s.charAt(p)) != 0) {
            p++;
        }
        String flagText = s.substring(flagStart, p);
        q = digitsEnd(s, p);
        String widthText = q > p ? s.substring(p, q) : null;
        p = q;
        String precisionText = null;
        if (p < n && s.charAt(p) == '.') {
            q = digitsEnd(s, p + 1);
            if (q > p + 1) {
                precisionText = s.substring(p + 1, q);
                p = q;
            }
        }
        boolean time = p + 1 < n && (s.charAt(p) == 't' || s.charAt(p) == 'T')
                && isConversionChar(s.charAt(p + 1));
        if (time) {
            f.upper = s.charAt(p) == 'T';
            f.flags |= f.upper ? UPPER : 0;
            f.dateTime = true;
            p++;
        }
        if (p >= n || !isConversionChar(s.charAt(p))) {
            throw new UnknownFormatConversionException(
                    String.valueOf(pct + 1 < n ? s.charAt(pct + 1) : '%'));
        }
        f.conv = s.charAt(p);
        f.end = p + 1;
        f.parseFlags(flagText);
        f.index = indexText == null ? 0 : parseInt(indexText);
        if ((f.flags & PREV) != 0) {
            f.index = -1;
        }
        f.width = widthText == null ? -1 : parseInt(widthText);
        f.precision = precisionText == null ? -1 : parseInt(precisionText);
        f.check();
        return f;
    }

    private static int digitsEnd(String s, int from) {
        int q = from;
        while (q < s.length() && s.charAt(q) >= '0' && s.charAt(q) <= '9') {
            q++;
        }
        return q;
    }

    private static boolean isConversionChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '%';
    }

    private static int flagBit(char c) {
        int i = FLAG_CHARS.indexOf(c);
        return i < 0 ? 0 : 1 << i;
    }

    /** A number too large for an int counts as absent (-1), as the oracle does. */
    private static int parseInt(String digits) {
        if (digits.length() > 10) {
            return -1;
        }
        long v = Long.parseLong(digits);
        return v > Integer.MAX_VALUE ? -1 : (int) v;
    }

    private void parseFlags(String text) {
        for (int i = 0; i < text.length(); i++) {
            int bit = flagBit(text.charAt(i));
            if ((flags & bit) != 0) {
                throw new DuplicateFormatFlagsException(String.valueOf(text.charAt(i)));
            }
            flags |= bit;
        }
    }

    /** The conversion in lower case: exceptions name it that way even for `%S`. */
    char lower() {
        return dateTime ? conv : Character.toLowerCase(conv);
    }

    boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /** The flags as the exceptions list them, in the order of SHOWN_CHARS. */
    static String flagsString(int flags) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SHOWN_BITS.length; i++) {
            if ((flags & SHOWN_BITS[i]) != 0) {
                sb.append(SHOWN_CHARS.charAt(i));
            }
        }
        return sb.toString();
    }

    /** The specifier rebuilt from its fields, as used in exception messages. */
    public String toString() {
        StringBuilder sb = new StringBuilder("%");
        if (index > 0) {
            sb.append(index).append('$');
        }
        sb.append(flagsString(flags & ~UPPER));
        if (width != -1) {
            sb.append(width);
        }
        if (precision != -1) {
            sb.append('.').append(precision);
        }
        if (dateTime) {
            sb.append(upper ? 'T' : 't');
        }
        return sb.append(conv).toString();
    }

    /** Throws if any flag in `bad` is set, naming the first one in `bad`'s order. */
    void rejectFlags(int... bad) {
        for (int i = 0; i < bad.length; i++) {
            if (has(bad[i])) {
                throw mismatch(bad[i]);
            }
        }
    }

    FormatFlagsConversionMismatchException mismatch(int flag) {
        return new FormatFlagsConversionMismatchException(flagsString(flag), lower());
    }

    private void requireWidth() {
        if (width == -1 && has(MINUS)) {
            throw new MissingFormatWidthException(toString());
        }
    }

    private void check() {
        if (dateTime) {
            checkDateTime();
            return;
        }
        upper = Character.isUpperCase(conv);
        if (upper) {
            flags |= UPPER;
        }
        switch (conv) {
            case 'b': case 'B': case 'h': case 'H': case 's': case 'S':
                checkGeneral();
                break;
            case 'c': case 'C':
                checkCharacter();
                break;
            case 'd': case 'o': case 'x': case 'X':
                checkInteger();
                break;
            case 'e': case 'E': case 'f': case 'g': case 'G': case 'a': case 'A':
                checkFloat();
                break;
            case '%': case 'n':
                checkText();
                break;
            default:
                throw new UnknownFormatConversionException(String.valueOf(conv));
        }
    }

    private void checkGeneral() {
        if (conv == 'b' || conv == 'B' || conv == 'h' || conv == 'H') {
            rejectFlags(ALT);
        }
        requireWidth();
        rejectFlags(PLUS, SPACE, ZERO, GROUP, PAREN);
    }

    private void checkCharacter() {
        if (precision != -1) {
            throw new IllegalFormatPrecisionException(precision);
        }
        rejectFlags(ALT, PLUS, SPACE, ZERO, GROUP, PAREN);
        requireWidth();
    }

    private void checkNumeric() {
        if (width == -1 && (has(MINUS) || has(ZERO))) {
            throw new MissingFormatWidthException(toString());
        }
        if ((has(PLUS) && has(SPACE)) || (has(MINUS) && has(ZERO))) {
            throw new IllegalFormatFlagsException(flagsString(flags));
        }
    }

    private void checkInteger() {
        checkNumeric();
        if (precision != -1) {
            throw new IllegalFormatPrecisionException(precision);
        }
        rejectFlags(conv == 'd' ? ALT : GROUP);
    }

    private void checkFloat() {
        checkNumeric();
        if (conv == 'e' || conv == 'E') {
            rejectFlags(GROUP);
        } else if (conv == 'g' || conv == 'G') {
            rejectFlags(ALT);
        } else if (conv == 'a' || conv == 'A') {
            rejectFlags(PAREN, GROUP);
        }
    }

    private void checkText() {
        if (precision != -1) {
            throw new IllegalFormatPrecisionException(precision);
        }
        if (conv == 'n') {
            if (width != -1) {
                throw new IllegalFormatWidthException(width);
            }
            if (flags != 0) {
                throw new IllegalFormatFlagsException(flagsString(flags));
            }
            return;
        }
        if ((flags & ~MINUS) != 0) {
            throw new IllegalFormatFlagsException(flagsString(flags));
        }
        requireWidth();
    }

    private void checkDateTime() {
        if (precision != -1) {
            throw new IllegalFormatPrecisionException(precision);
        }
        if ("HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc".indexOf(conv) < 0) {
            throw new UnknownFormatConversionException("t" + conv);
        }
        rejectFlags(ALT, PLUS, SPACE, ZERO, GROUP, PAREN);
        requireWidth();
    }

    /** Pads `s` with spaces on the left, or on the right with the `-` flag, up to the width. */
    String justify(String s) {
        int pad = width - s.length();
        if (pad <= 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(width);
        if (has(MINUS)) {
            sb.append(s);
        }
        for (int i = 0; i < pad; i++) {
            sb.append(' ');
        }
        if (!has(MINUS)) {
            sb.append(s);
        }
        return sb.toString();
    }
}
