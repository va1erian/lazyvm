package java.util.regex;

/**
 * Case-insensitive character classes: what a character written alone, and a range, accept under
 * CASE_INSENSITIVE with and without UNICODE_CASE. The rules were found on the oracle (RegexFolding).
 */
final class CaseFold {
    private CaseFold() {
    }

    /**
     * `[lo-hi]` ignoring case. Observed on the oracle (RegexFolding): a range, even of one
     * character, holds the code points x for which x, toUpperCase(x) or toLowerCase(toUpperCase(x))
     * lies in it, so [ſ-ſ] does not match s and [Ω-Ω] does not match the ohm sign.
     */
    static CharClass range(int lo, int hi, boolean unicode) {
        return new Fold(new CharClass.Range(lo, hi), unicode);
    }

    /** One character ignoring case, as written alone: a literal or a class of one plain character. */
    static CharClass single(int c, boolean unicode) {
        return new FoldChar(c, unicode);
    }

    /**
     * True when the plain character c (below U+0100) matches only code points below U+0100 under
     * CASE_INSENSITIVE | UNICODE_CASE. Observed on the oracle: k, s, i, their capitals, the micro
     * sign and the Latin-1 a-ring and y-diaeresis match something wider (the Kelvin sign, the
     * long s, ...) and so are not part of a "run of plain characters" (ClassReader.body).
     */
    static boolean staysInLatin1(int c) {
        int key = foldKey(c);
        if (Character.toUpperCase(c) == key) {
            return true;
        }
        return key < 256 && !widened()[key];
    }

    private static boolean[] widenedKeys;

    private static boolean[] widened() {
        if (widenedKeys == null) {
            boolean[] w = new boolean[256];
            for (int x = 0x100; x < 0x10000; x++) {
                int k = foldKey(x);
                if (k < 256) {
                    w[k] = true;
                }
            }
            widenedKeys = w;
        }
        return widenedKeys;
    }

    /** The ASCII letter of the other case, or cp itself: the only folding without UNICODE_CASE. */
    static int asciiSwap(int cp) {
        if (cp >= 'a' && cp <= 'z') {
            return cp - 32;
        }
        return cp >= 'A' && cp <= 'Z' ? cp + 32 : cp;
    }

    /** toLowerCase(toUpperCase(c)): code points with the same key match each other (FoldChar). */
    static int foldKey(int c) {
        return Character.toLowerCase(Character.toUpperCase(c));
    }

    /** A range that also matches the code points whose case-mapped forms fall inside it. */
    private static final class Fold extends CharClass {
        private final CharClass inner;
        private final boolean unicode;

        Fold(CharClass inner, boolean unicode) {
            this.inner = inner;
            this.unicode = unicode;
        }

        boolean test(int cp) {
            if (inner.test(cp)) {
                return true;
            }
            if (!unicode) {
                return inner.test(asciiSwap(cp));
            }
            return inner.test(Character.toUpperCase(cp)) || inner.test(foldKey(cp));
        }
    }

    /** One code point, compared ignoring case. */
    private static final class FoldChar extends CharClass {
        private final int c;
        private final boolean unicode;
        private boolean loose;

        FoldChar(int c, boolean unicode) {
            this.c = c;
            this.unicode = unicode;
        }

        CharClass loosen() {
            loose = true;
            return this;
        }

        /**
         * Observed on the oracle (RegexFolding): alone, a character whose upper case is its own
         * lower-cased form (the sharp s) matches only itself, so ß does not match the capital
         * sharp s although the capital matches ß; in a literal string of two or more characters
         * it does.
         */
        boolean test(int cp) {
            if (cp == c) {
                return true;
            }
            if (!unicode) {
                return asciiSwap(cp) == c;
            }
            int key = foldKey(c);
            if (!loose && Character.toUpperCase(c) == key) {
                return false;
            }
            return foldKey(cp) == key;
        }
    }
}
