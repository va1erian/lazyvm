package java.util.logging;

/**
 * The small "{n}" message language of Formatter.formatMessage: literal text, quoting with apostrophes,
 * and numbered holes. There is no java.text in lazyrt, so numbers are rendered here as well.
 */
final class BraceText {
    private BraceText() {
    }

    /** Only messages naming one of the first four holes are treated as patterns at all. */
    static boolean hasMarker(String message) {
        return message.indexOf("{0") >= 0 || message.indexOf("{1") >= 0
                || message.indexOf("{2") >= 0 || message.indexOf("{3") >= 0;
    }

    /** Fills the holes; a malformed pattern, or an argument that cannot be turned into text, leaves it unchanged. */
    static String expand(String pattern, Object[] args) {
        try {
            return substitute(pattern, args);
        } catch (RuntimeException e) {
            return pattern;
        }
    }

    private static String substitute(String pattern, Object[] args) {
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        int n = pattern.length();
        for (int i = 0; i < n; i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                if (i + 1 < n && pattern.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == '{' && !quoted) {
                int close = pattern.indexOf('}', i);
                if (close < 0) {
                    return pattern;
                }
                fill(out, pattern.substring(i, close + 1), args);
                i = close;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // Holes numbered at or past this are rejected, the whole message with them.
    private static final int MAX_INDEX = 10000;

    // The text between the braces must read as an int the way Integer.parseInt reads one (a sign, leading
    // zeros and non-ASCII digits included), else the pattern is malformed (NumberFormatException). An index
    // past the arguments stays in the text, written as the number it names.
    private static void fill(StringBuilder out, String hole, Object[] args) {
        int index = Integer.parseInt(hole.substring(1, hole.length() - 1));
        if (index < 0 || index >= MAX_INDEX) {
            throw new IllegalArgumentException();
        }
        out.append(index >= args.length ? "{" + index + "}" : argText(args[index]));
    }

    static String argText(Object arg) {
        if (arg instanceof Integer || arg instanceof Long || arg instanceof Short || arg instanceof Byte) {
            return String.format("%,d", ((Number) arg).longValue());
        }
        if (arg instanceof Double || arg instanceof Float) {
            return decimal(((Number) arg).doubleValue());
        }
        return String.valueOf(arg);
    }

    /**
     * At most three fraction digits, rounded half-even on the exact binary value. Magnitudes of 2^63
     * and above, NaN and infinities fall back to the plain Double text.
     */
    private static String decimal(double d) {
        long bits = Double.doubleToLongBits(d);
        int exp = (int) ((bits >> 52) & 0x7ff);
        if (exp == 0x7ff || Math.abs(d) >= 0x1p63) {
            return String.valueOf(d);
        }
        long m = exp == 0 ? bits & ((1L << 52) - 1) : (bits & ((1L << 52) - 1)) | (1L << 52);
        int e = (exp == 0 ? 1 : exp) - 1075;
        long whole = e >= 0 ? m << e : 0;
        long thousandths = e >= 0 ? 0 : roundedThousandths(m, -e);
        whole += thousandths / 1000;
        long frac = thousandths % 1000;
        String text = String.format("%,d", whole);
        if (frac != 0) {
            String digits = Long.toString(1000 + frac).substring(1);
            text += "." + (digits.endsWith("00") ? digits.substring(0, 1)
                    : digits.endsWith("0") ? digits.substring(0, 2) : digits);
        }
        return bits < 0 ? "-" + text : text;
    }

    // round-half-even(m * 1000 / 2^shift); m < 2^53, so m * 1000 fits a long.
    private static long roundedThousandths(long m, int shift) {
        if (shift >= 64) {
            return 0; // below 2^-11, so under half a thousandth
        }
        long scaled = m * 1000;
        long q = scaled >> shift;
        long rest = scaled & ((1L << shift) - 1);
        long half = 1L << (shift - 1);
        return rest > half || (rest == half && (q & 1) == 1) ? q + 1 : q;
    }
}
