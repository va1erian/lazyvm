package java.util.regex;

/** Nodes that consume input: one code point of a class, a literal string, a back reference. */
final class Atoms {
    private Atoms() {
    }

    /**
     * The code point at i (i < m.hi). A surrogate pair is read whole even when the region ends
     * between its halves (observed on the oracle, RegexRegions, "region inside a pair").
     */
    static int codePointAt(Matcher m, int i) {
        char c = m.text.charAt(i);
        if (Character.isHighSurrogate(c) && i + 1 < m.textLength) {
            char d = m.text.charAt(i + 1);
            if (Character.isLowSurrogate(d)) {
                return Character.toCodePoint(c, d);
            }
        }
        return c;
    }

    /** Equal, or equal after folding case (ASCII only unless `unicode`). */
    static boolean sameFolded(char a, char b, boolean unicode) {
        if (a == b) {
            return true;
        }
        if (a < 128 && b < 128) {
            return Character.toLowerCase(a) == Character.toLowerCase(b);
        }
        if (!unicode) {
            return false;
        }
        char ua = Character.toUpperCase(a);
        char ub = Character.toUpperCase(b);
        return ua == ub || Character.toLowerCase(ua) == Character.toLowerCase(ub);
    }

    /** Exactly one code point that belongs to a class. */
    static final class One extends Node {
        final CharClass cc;
        /** The code point when this is a plain case-sensitive literal (so runs of them can merge into a Str), else -1. */
        int literal = -1;
        /** A single character written as a literal under CASE_INSENSITIVE (see Parser.sequenceOf). */
        boolean foldedLiteral;
        /** This class is the union of alternatives, as in (?:a|b); see LookbehindReach. */
        boolean fromAlternation;

        One(CharClass cc) {
            this.cc = cc;
        }

        boolean match(Matcher m, int i) {
            if (i >= m.hi) {
                m.hitEnd = true;
                return false;
            }
            int c = codePointAt(m, i);
            return cc.test(c) && next.match(m, i + Character.charCount(c));
        }

        int min() {
            return 1;
        }

        int max() {
            return 2;
        }
    }

    /** A literal run of chars compared exactly. */
    static final class Str extends Node {
        final char[] chars;

        Str(char[] chars) {
            this.chars = chars;
        }

        boolean match(Matcher m, int i) {
            int n = chars.length;
            for (int k = 0; k < n; k++) {
                if (i + k >= m.hi) {
                    m.hitEnd = true;
                    return false;
                }
                if (m.text.charAt(i + k) != chars[k]) {
                    return false;
                }
            }
            return next.match(m, i + n);
        }

        int min() {
            return chars.length;
        }

        int max() {
            return chars.length;
        }
    }

    /** \n or \k<name>: the text the group captured, matched again. */
    static final class BackRef extends Node {
        final int group;
        final boolean ignoreCase;
        final boolean unicode;

        BackRef(int group, boolean ignoreCase, boolean unicode) {
            this.group = group;
            this.ignoreCase = ignoreCase;
            this.unicode = unicode;
        }

        boolean match(Matcher m, int i) {
            if (2 * group + 1 >= m.groups.length) {
                return false;
            }
            int s = m.groups[2 * group];
            int e = m.groups[2 * group + 1];
            if (s < 0 || e < 0) {
                return false;
            }
            for (int k = 0; k < e - s; k++) {
                if (i + k >= m.hi) {
                    m.hitEnd = true;
                    return false;
                }
                char a = m.text.charAt(s + k);
                char b = m.text.charAt(i + k);
                if (ignoreCase ? !sameFolded(a, b, unicode) : a != b) {
                    return false;
                }
            }
            return next.match(m, i + e - s);
        }

        int max() {
            return UNBOUNDED;
        }
    }

    /** \R: CRLF as one unit (and then only as one unit), or any single line terminator. */
    static final class LineBreak extends Node {
        boolean match(Matcher m, int i) {
            int w = widthAt(m, i);
            return w > 0 && next.match(m, i + w);
        }

        /** The length of the line break at i, or 0 when there is none. */
        static int widthAt(Matcher m, int i) {
            if (i >= m.hi) {
                m.hitEnd = true;
                return 0;
            }
            char c = m.text.charAt(i);
            if (c == '\r') {
                return i + 1 < m.hi && m.text.charAt(i + 1) == '\n' ? 2 : 1;
            }
            return CharClass.isVerticalSpace(c) ? 1 : 0;
        }

        int min() {
            return 1;
        }

        int max() {
            return 2;
        }
    }
}
