package java.util.regex;

/** A set of code points, tested one at a time. Instances are immutable. */
abstract class CharClass {
    /** Set on classes that came from a general category or block name (see supplementary). */
    boolean categoryOrBlock;

    abstract boolean test(int cp);

    CharClass markCategoryOrBlock() {
        categoryOrBlock = true;
        return this;
    }

    CharClass not() {
        return new Not(this);
    }

    static CharClass or(CharClass a, CharClass b) {
        return anyOf(new CharClass[] {a, b}, 2);
    }

    static CharClass and(CharClass a, CharClass b) {
        return new All(new CharClass[] {a, b});
    }

    /**
     * The union of parts[0..n). Plain ranges are merged into one sorted table, so a class of
     * thousands of characters is one binary search, not a chain of thousands of objects.
     */
    static CharClass anyOf(CharClass[] parts, int n) {
        long[] keys = new long[n];
        CharClass[] rest = new CharClass[n];
        int nk = 0;
        int nr = 0;
        for (int k = 0; k < n; k++) {
            if (parts[k] instanceof Range) {
                Range r = (Range) parts[k];
                keys[nk++] = (long) r.lo * 0x200000L + r.hi;
            } else {
                rest[nr++] = parts[k];
            }
        }
        CharClass table = nk == 0 ? null : new Ranges(sortedRanges(keys, nk));
        if (nr == 0) {
            return table;
        }
        CharClass[] others = new CharClass[nr + (table == null ? 0 : 1)];
        System.arraycopy(rest, 0, others, 0, nr);
        if (table != null) {
            others[nr] = table;
        }
        return others.length == 1 ? others[0] : new Any(others);
    }

    /** The intersection of the parts. */
    static CharClass allOf(CharClass[] parts, int n) {
        CharClass[] exact = new CharClass[n];
        System.arraycopy(parts, 0, exact, 0, n);
        return n == 1 ? exact[0] : new All(exact);
    }

    /** Sorts range keys (lo, hi packed in a long) and merges overlapping or adjacent ranges. */
    private static int[][] sortedRanges(long[] keys, int n) {
        long[] src = keys;
        long[] dst = new long[n];
        for (int width = 1; width < n; width *= 2) {
            for (int from = 0; from < n; from += 2 * width) {
                int mid = Math.min(from + width, n);
                int end = Math.min(from + 2 * width, n);
                int a = from;
                int b = mid;
                for (int k = from; k < end; k++) {
                    dst[k] = b >= end || (a < mid && src[a] <= src[b]) ? src[a++] : src[b++];
                }
            }
            long[] t = src;
            src = dst;
            dst = t;
        }
        int[] lo = new int[n];
        int[] hi = new int[n];
        int m = 0;
        for (int k = 0; k < n; k++) {
            int l = (int) (src[k] / 0x200000L);
            int h = (int) (src[k] % 0x200000L);
            if (m > 0 && l <= hi[m - 1] + 1) {
                hi[m - 1] = Math.max(hi[m - 1], h);
            } else {
                lo[m] = l;
                hi[m++] = h;
            }
        }
        int[] lo2 = new int[m];
        int[] hi2 = new int[m];
        System.arraycopy(lo, 0, lo2, 0, m);
        System.arraycopy(hi, 0, hi2, 0, m);
        return new int[][] {lo2, hi2};
    }

    static CharClass single(int c) {
        return new Range(c, c);
    }

    static CharClass range(int lo, int hi) {
        return new Range(lo, hi);
    }

    /** `[lo-hi]` ignoring case. */
    static CharClass foldRange(int lo, int hi, boolean unicode) {
        return lo == hi ? new FoldChar(lo, unicode) : new Fold(new Range(lo, hi), unicode);
    }

    /** Answers ASCII queries from a bitmap, which is how character classes are used almost always. */
    static CharClass cached(CharClass inner) {
        return new Cached(inner);
    }

    static CharClass of(int kind) {
        return new Pred(kind);
    }

    /** A set given as "lo-hi,lo-hi,..." in hex, sorted. */
    static CharClass ranges(String spec) {
        return new Ranges(spec);
    }

    static CharClass categories(int mask) {
        return new Cat(mask);
    }

    static final class Range extends CharClass {
        final int lo;
        final int hi;

        Range(int lo, int hi) {
            this.lo = lo;
            this.hi = hi;
        }

        boolean test(int cp) {
            return cp >= lo && cp <= hi;
        }
    }

    private static final class Not extends CharClass {
        private final CharClass a;

        Not(CharClass a) {
            this.a = a;
        }

        boolean test(int cp) {
            return !a.test(cp);
        }

        CharClass not() {
            return a;
        }
    }

    private static final class Any extends CharClass {
        private final CharClass[] parts;

        Any(CharClass[] parts) {
            this.parts = parts;
        }

        boolean test(int cp) {
            for (CharClass p : parts) {
                if (p.test(cp)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class All extends CharClass {
        private final CharClass[] parts;

        All(CharClass[] parts) {
            this.parts = parts;
        }

        boolean test(int cp) {
            for (CharClass p : parts) {
                if (!p.test(cp)) {
                    return false;
                }
            }
            return true;
        }
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
                if (cp >= 'a' && cp <= 'z') {
                    return inner.test(cp - 32);
                }
                return cp >= 'A' && cp <= 'Z' && inner.test(cp + 32);
            }
            int up = Character.toUpperCase(cp);
            return inner.test(up) || inner.test(Character.toLowerCase(up));
        }
    }

    /** One code point, compared ignoring case. */
    private static final class FoldChar extends CharClass {
        private final int c;
        private final boolean unicode;

        FoldChar(int c, boolean unicode) {
            this.c = c;
            this.unicode = unicode;
        }

        boolean test(int cp) {
            if (cp == c) {
                return true;
            }
            if (!unicode) {
                return cp < 128 && c < 128 && Character.toLowerCase(cp) == Character.toLowerCase(c);
            }
            int up = Character.toUpperCase(cp);
            int cup = Character.toUpperCase(c);
            return up == cup || Character.toLowerCase(up) == Character.toLowerCase(cup);
        }
    }

    private static final class Ranges extends CharClass {
        private final int[] lo;
        private final int[] hi;

        Ranges(int[][] table) {
            lo = table[0];
            hi = table[1];
        }

        Ranges(String spec) {
            this(parse(spec));
        }

        private static int[][] parse(String spec) {
            if (spec.length() == 0) {
                return new int[][] {new int[0], new int[0]};
            }
            int n = 1;
            for (int i = 0; i < spec.length(); i++) {
                if (spec.charAt(i) == ',') {
                    n++;
                }
            }
            int[] lo = new int[n];
            int[] hi = new int[n];
            int start = 0;
            for (int k = 0; k < n; k++) {
                int comma = spec.indexOf(',', start);
                int end = comma < 0 ? spec.length() : comma;
                int dash = spec.indexOf('-', start);
                lo[k] = Integer.parseInt(spec.substring(start, dash), 16);
                hi[k] = Integer.parseInt(spec.substring(dash + 1, end), 16);
                start = end + 1;
            }
            return new int[][] {lo, hi};
        }

        boolean test(int cp) {
            int a = 0;
            int b = lo.length - 1;
            while (a <= b) {
                int mid = (a + b) >>> 1;
                if (cp < lo[mid]) {
                    b = mid - 1;
                } else if (cp > hi[mid]) {
                    a = mid + 1;
                } else {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Cached extends CharClass {
        private final CharClass inner;
        private long lowBits;
        private long highBits;

        Cached(CharClass inner) {
            this.inner = inner;
            for (int c = 0; c < 64; c++) {
                if (inner.test(c)) {
                    lowBits |= 1L << c;
                }
                if (inner.test(c + 64)) {
                    highBits |= 1L << c;
                }
            }
        }

        boolean test(int cp) {
            if (cp < 64) {
                return cp >= 0 && (lowBits & (1L << cp)) != 0;
            }
            if (cp < 128) {
                return (highBits & (1L << (cp - 64))) != 0;
            }
            return inner.test(cp);
        }
    }

    private static final class Cat extends CharClass {
        private final int mask;

        Cat(int mask) {
            this.mask = mask;
        }

        boolean test(int cp) {
            return (mask & Category.bit(Category.type(cp))) != 0;
        }
    }

    // Kinds of built-in predicates (Pred).
    static final int ANY = 0, DOT = 1, DOT_UNIX = 2, SPACE = 3, WORD = 4, HSPACE = 5, VSPACE = 6;
    static final int U_SPACE = 10, U_WORD = 11, U_ALPHA = 12, U_LOWER = 13, U_UPPER = 14, U_PUNCT = 15;
    static final int U_ALNUM = 16, U_GRAPH = 17, U_PRINT = 18, U_BLANK = 19, U_CNTRL = 20, U_XDIGIT = 21;
    static final int U_DIGIT = 22, U_IDEOGRAPHIC = 23, U_ASSIGNED = 24, U_JOIN = 25, U_NONCHAR = 26;
    static final int J_WHITESPACE = 30, J_SPACECHAR = 31, J_ISOCONTROL = 32, J_TITLE = 33, J_DEFINED = 34;
    static final int J_MIRRORED = 35, J_JAVA_START = 36, J_JAVA_PART = 37, J_UNI_START = 38;
    static final int J_UNI_PART = 39, J_IGNORABLE = 40, J_LETTER_OR_DIGIT = 41, J_LETTER = 42;

    private static final class Pred extends CharClass {
        private final int kind;

        Pred(int kind) {
            this.kind = kind;
        }

        boolean test(int cp) {
            switch (kind) {
                case ANY: return true;
                case DOT: return !isLineTerminator(cp);
                case DOT_UNIX: return cp != '\n';
                case SPACE: return cp == ' ' || (cp >= 9 && cp <= 13);
                case WORD: return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9') || cp == '_';
                case HSPACE: return isHorizontalSpace(cp);
                case VSPACE: return isVerticalSpace(cp);
                case U_SPACE: return isWhiteSpace(cp);
                case U_WORD: return isUnicodeWord(cp);
                case U_ALPHA: return Character.isAlphabetic(cp);
                case U_LOWER: return Character.isLowerCase(cp);
                case U_UPPER: return Character.isUpperCase(cp);
                case U_PUNCT: return isCategory(cp, PUNCT);
                case U_ALNUM: return Character.isAlphabetic(cp) || Character.isDigit(cp);
                case U_GRAPH: return isGraph(cp);
                case U_PRINT: return (isGraph(cp) || isCategory(cp, Category.bit(Category.Zs))) && !isControlLike(cp);
                case U_BLANK: return cp == '\t' || Category.type(cp) == Category.Zs;
                case U_CNTRL: return Category.type(cp) == Category.Cc;
                case U_XDIGIT: return isHexDigit(cp);
                case U_DIGIT: return Character.isDigit(cp);
                case U_IDEOGRAPHIC: return Character.isIdeographic(cp);
                case U_ASSIGNED: return Character.isDefined(cp);
                case U_JOIN: return cp == 0x200C || cp == 0x200D;
                case U_NONCHAR: return (cp & 0xFFFE) == 0xFFFE || (cp >= 0xFDD0 && cp <= 0xFDEF);
                case J_WHITESPACE: return Character.isWhitespace(cp);
                case J_SPACECHAR: return Character.isSpaceChar(cp);
                case J_ISOCONTROL: return Character.isISOControl(cp);
                case J_TITLE: return Character.isTitleCase(cp);
                case J_DEFINED: return Character.isDefined(cp);
                case J_MIRRORED: return Character.isMirrored(cp);
                case J_JAVA_START: return Character.isJavaIdentifierStart(cp);
                case J_JAVA_PART: return Character.isJavaIdentifierPart(cp);
                case J_UNI_START: return Character.isUnicodeIdentifierStart(cp);
                case J_UNI_PART: return Character.isUnicodeIdentifierPart(cp);
                case J_IGNORABLE: return Character.isIdentifierIgnorable(cp);
                case J_LETTER_OR_DIGIT: return Character.isLetterOrDigit(cp);
                case J_LETTER: return Character.isLetter(cp);
                default: return false;
            }
        }
    }

    private static final int PUNCT = Category.mask("P");

    private static boolean isCategory(int cp, int mask) {
        return (mask & Category.bit(Category.type(cp))) != 0;
    }

    static boolean isLineTerminator(int cp) {
        return cp == '\n' || cp == '\r' || cp == 0x85 || cp == 0x2028 || cp == 0x2029;
    }

    static boolean isHorizontalSpace(int cp) {
        return cp == ' ' || cp == '\t' || cp == 0xA0 || cp == 0x1680 || cp == 0x180E
            || (cp >= 0x2000 && cp <= 0x200A) || cp == 0x202F || cp == 0x205F || cp == 0x3000;
    }

    static boolean isVerticalSpace(int cp) {
        return (cp >= 10 && cp <= 13) || cp == 0x85 || cp == 0x2028 || cp == 0x2029;
    }

    private static boolean isWhiteSpace(int cp) {
        return (cp >= 9 && cp <= 13) || cp == ' ' || cp == 0x85 || cp == 0xA0 || cp == 0x1680
            || cp == 0x180E || (cp >= 0x2000 && cp <= 0x200A) || cp == 0x2028 || cp == 0x2029
            || cp == 0x202F || cp == 0x205F || cp == 0x3000;
    }

    private static boolean isUnicodeWord(int cp) {
        int t = Category.type(cp);
        return Character.isAlphabetic(cp) || t == Category.Mn || t == Category.Me || t == Category.Mc
            || t == Category.Nd || t == Category.Pc || cp == 0x200C || cp == 0x200D;
    }

    private static boolean isControlLike(int cp) {
        int t = Category.type(cp);
        return t == Category.Cc || t == Category.Cs || t == Category.Cn;
    }

    private static boolean isGraph(int cp) {
        int t = Category.type(cp);
        return !(t == Category.Zs || t == Category.Zl || t == Category.Zp || t == Category.Cc
            || t == Category.Cs || t == Category.Cn);
    }

    private static boolean isHexDigit(int cp) {
        return Character.isDigit(cp) || (cp >= 'a' && cp <= 'f') || (cp >= 'A' && cp <= 'F')
            || (cp >= 0xFF21 && cp <= 0xFF26) || (cp >= 0xFF41 && cp <= 0xFF46);
    }
}
