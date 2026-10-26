package java.lang;

/**
 * Case conversion of whole strings, the part of String.toUpperCase and toLowerCase that goes
 * beyond the one-to-one mapping of Character: characters that map to several (ß becomes SS) and
 * the Greek final sigma. The default locale is the root locale, so there is no Turkish or
 * Lithuanian tailoring.
 */
final class StringCase {
    private static final int SIGMA = 0x3A3;
    private static final int FINAL_SIGMA = 0x3C2;
    private static final int CASED = 1;
    private static final int SKIPPED = 2;

    private StringCase() {
    }

    /** Code point index of the multi-code-point mapping of cp, or -1; see unicode.rs. */
    static native int special(int cp, boolean upper, int index);

    /** How a final-sigma check sees cp: CASED, SKIPPED or neither. */
    static native int context(int cp);

    static String convert(String s, boolean upper) {
        int length = s.length();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; ) {
            int cp = s.codePointAt(i);
            int next = i + Character.charCount(cp);
            if (!appendSpecial(sb, cp, upper)) {
                if (!upper && cp == SIGMA) {
                    sb.append(isFinalSigma(s, i, next) ? (char) FINAL_SIGMA : (char) 0x3C3);
                } else {
                    sb.appendCodePoint(upper ? Character.toUpperCase(cp) : Character.toLowerCase(cp));
                }
            }
            i = next;
        }
        String result = sb.toString();
        return result.equals(s) ? s : result;
    }

    private static boolean appendSpecial(StringBuilder sb, int cp, boolean upper) {
        int k = 0;
        for (int c = special(cp, upper, 0); c >= 0; c = special(cp, upper, ++k)) {
            sb.appendCodePoint(c);
        }
        return k > 0;
    }

    // A sigma is final after a cased letter and before no cased letter, skipping the characters
    // the oracle skips. Before the sigma Java 8 only recognises a supplementary letter at the very
    // start of the string, and after it U+0345 counts as cased; both are mirrored here.
    private static boolean isFinalSigma(String s, int sigma, int next) {
        boolean casedBefore = false;
        for (int j = sigma; j > 0; ) {
            char c = s.charAt(j - 1);
            int cp = c;
            if (Character.isLowSurrogate(c) && j >= 2 && Character.isHighSurrogate(s.charAt(j - 2))) {
                cp = j == 2 ? s.codePointAt(0) : c;
            }
            int how = context(cp);
            if ((how & CASED) != 0) {
                casedBefore = true;
                break;
            }
            if ((how & SKIPPED) == 0) {
                break;
            }
            j -= cp > 0xFFFF ? 2 : 1;
        }
        if (!casedBefore) {
            return false;
        }
        for (int j = next; j < s.length(); ) {
            int cp = s.codePointAt(j);
            int how = context(cp);
            if ((how & CASED) != 0 || cp == 0x345) {
                return false;
            }
            if ((how & SKIPPED) == 0) {
                return true;
            }
            j += Character.charCount(cp);
        }
        return true;
    }
}
