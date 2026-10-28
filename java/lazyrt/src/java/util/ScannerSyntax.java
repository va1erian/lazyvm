package java.util;

/**
 * The numeric token languages of Scanner under the ROOT symbols (group separator ',', decimal
 * separator '.', NaN U+FFFD, infinity U+221E), docs/design/util-text.md SC10-SC17. Each recogniser
 * returns the text to hand to the parser, or null when the token has the wrong shape.
 */
final class ScannerSyntax {
    private ScannerSyntax() {
    }

    /** SC23: the toString fields after the match flag; '?' stands for whether the source ended. */
    static final String ROOT_FIELDS = "][need input=false][source closed=?][skipped=false]"
        + "[group separator=\\x{2c}][decimal separator=\\x{2e}][positive prefix=][negative prefix=\\Q-\\E]"
        + "[positive suffix=][negative suffix=][NaN string=\\Q\ufffd\\E][infinity string=\\Q\u221e\\E]";

    static final int BYTE = 0;
    static final int SHORT = 1;
    static final int INT = 2;
    static final int LONG = 3;
    static final int FLOAT = 4;
    static final int DOUBLE = 5;

    /** SC19: the radixes the integer readers can describe at all; others fail as Java 8's do. */
    static void checkDigits(int kind, int radix) {
        if (kind < FLOAT && (radix < 0 || radix > Character.MAX_RADIX)) {
            throw new StringIndexOutOfBoundsException(radix);
        }
    }

    /**
     * SC24: the pattern a typed reader matches, as a key: one per radix for the integer kinds,
     * one shared by the floating kinds. Boxed integers, so never equal to a user's pattern key.
     */
    static Object shapeKey(int kind, int radix) {
        return Integer.valueOf(kind < FLOAT ? radix : -1);
    }

    /** SC10-SC17: the text to parse for a reader of this kind, or null for a wrong shape. */
    static String shape(String token, int kind, int radix) {
        return kind < FLOAT ? integer(token, radix) : decimal(token);
    }

    /** SC13, SC15: the value, or the parser's NumberFormatException; a float rounds as one. */
    static Number parse(String t, int kind, int radix) {
        switch (kind) {
            case BYTE: return Byte.parseByte(t, radix);
            case SHORT: return Short.parseShort(t, radix);
            case INT: return Integer.parseInt(t, radix);
            case LONG: return Long.parseLong(t, radix);
            case FLOAT: return Float.parseFloat(t);
            default: return Double.parseDouble(t);
        }
    }

    /** SC18: true or false in any ASCII case, as the whole token. */
    static boolean isBoolean(String t) {
        return t.length() == 4 && asciiLower(t, "true") || t.length() == 5 && asciiLower(t, "false");
    }

    private static boolean asciiLower(String t, String lower) {
        for (int i = 0; i < lower.length(); i++) {
            char c = t.charAt(i);
            if ((c >= 'A' && c <= 'Z' ? (char) (c | 0x20) : c) != lower.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** SC12: an ASCII digit, an ASCII letter within the radix, or any other decimal digit. */
    private static boolean digit(int cp, int radix) {
        if (cp >= '0' && cp <= '9') {
            return true;
        }
        if (cp < 0x80) {
            int lower = cp | 0x20;
            return lower >= 'a' && lower <= 'z' && lower - 'a' + 10 < radix;
        }
        return Character.isDigit(cp);
    }

    /** SC11: the digit that may open a grouped numeral: a decimal digit other than ASCII '0'. */
    private static boolean leadDigit(int cp) {
        return cp != '0' && (cp >= '1' && cp <= '9' || cp >= 0x80 && Character.isDigit(cp));
    }

    private static int signLength(String t) {
        return !t.isEmpty() && (t.charAt(0) == '+' || t.charAt(0) == '-') ? 1 : 0;
    }

    /** SC10-SC12: sign, then plain or grouped digits; the result has the separators removed. */
    static String integer(String t, int radix) {
        int from = signLength(t);
        int end = numeral(t, from, t.length(), radix);
        if (end != t.length() || end == from) {
            return null;
        }
        return t.indexOf(',') < 0 ? t : t.replace(",", "");
    }

    /**
     * SC11: the end of the numeral that starts at `from` and stops at `limit` or the first char
     * that is neither a digit nor ','; -1 if a ',' appears in a badly grouped numeral.
     */
    private static int numeral(String t, int from, int limit, int radix) {
        int i = from;
        int digits = 0;
        int sinceComma = -1; // digits since the last ',', or -1 before the first
        boolean lead = false;
        while (i < limit) {
            int cp = t.codePointAt(i);
            if (cp == ',') {
                if (sinceComma < 0 ? !lead || digits < 1 || digits > 3 : sinceComma != 3) {
                    return -1;
                }
                sinceComma = 0;
            } else if (digit(cp, radix)) {
                if (digits == 0) {
                    lead = leadDigit(cp);
                }
                digits++;
                if (sinceComma >= 0) {
                    sinceComma++;
                }
            } else {
                break;
            }
            i += Character.charCount(cp);
        }
        return sinceComma < 0 || sinceComma == 3 ? i : -1;
    }

    /** The end of a run of decimal digits from `i` (no separators). */
    private static int digits(String t, int i) {
        while (i < t.length()) {
            int cp = t.codePointAt(i);
            if (!digit(cp, 10)) {
                break;
            }
            i += Character.charCount(cp);
        }
        return i;
    }

    /** SC15-SC17: a decimal, NaN/infinity or hexadecimal floating token, ready for parseDouble. */
    static String decimal(String t) {
        int from = signLength(t);
        String rest = t.substring(from);
        if (rest.equals("NaN") || rest.equals("Infinity") || rest.equals("�") || rest.equals("∞")) {
            return t; // SC16: the locale's own symbols have the shape but never parse
        }
        if (rest.startsWith("0x") || rest.startsWith("0X")) {
            return hex(t, from + 2) ? t : null;
        }
        int stop = from;
        while (stop < t.length() && t.charAt(stop) != '.' && (t.charAt(stop) | 0x20) != 'e') {
            stop++;
        }
        int i = numeral(t, from, stop, 10);
        if (i < 0) {
            return null;
        }
        int count = i - from;
        if (i < t.length() && t.charAt(i) == '.') {
            int f = digits(t, i + 1);
            count += f - (i + 1);
            i = f;
        }
        if (count == 0) {
            return null;
        }
        if (i < t.length() && (t.charAt(i) | 0x20) == 'e') {
            int e = i + 1 + signLength(t.substring(i + 1));
            i = digits(t, e);
            if (i == e) {
                return null;
            }
        }
        return i == t.length() ? asciiDigits(t) : null;
    }

    /** SC17: hex digits, '.', at least one hex digit, then an optional binary exponent; ASCII only. */
    private static boolean hex(String t, int i) {
        int n = t.length();
        while (i < n && Character.digit(t.charAt(i), 16) >= 0 && t.charAt(i) < 0x80) {
            i++;
        }
        if (i == n || t.charAt(i) != '.') {
            return false;
        }
        int f = ++i;
        while (i < n && Character.digit(t.charAt(i), 16) >= 0 && t.charAt(i) < 0x80) {
            i++;
        }
        if (i == f) {
            return false;
        }
        if (i < n && (t.charAt(i) | 0x20) == 'p') {
            i++;
            if (i < n && (t.charAt(i) == '+' || t.charAt(i) == '-')) {
                i++;
            }
            int e = i;
            while (i < n && t.charAt(i) >= '0' && t.charAt(i) <= '9') {
                i++;
            }
            return i > e && i == n;
        }
        return i == n;
    }

    /** SC15: separators dropped and BMP decimal digits made ASCII; supplementary ones stay. */
    private static String asciiDigits(String t) {
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == ',') {
                continue;
            }
            b.append(c >= 0x80 && Character.isDigit(c) ? (char) ('0' + Character.digit(c, 10)) : c);
        }
        return b.toString();
    }
}
