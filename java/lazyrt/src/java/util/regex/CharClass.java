package java.util.regex;

/** A set of code points, tested one at a time. Instances are immutable. */
abstract class CharClass {
    abstract boolean test(int cp);

    CharClass not() {
        return new Not(this);
    }

    static CharClass or(CharClass a, CharClass b) {
        return new Or(a, b);
    }

    static CharClass and(CharClass a, CharClass b) {
        return new And(a, b);
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

    static CharClass categories(int mask) {
        return new Cat(mask);
    }

    static final class Range extends CharClass {
        private final int lo;
        private final int hi;

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

    private static final class Or extends CharClass {
        private final CharClass a;
        private final CharClass b;

        Or(CharClass a, CharClass b) {
            this.a = a;
            this.b = b;
        }

        boolean test(int cp) {
            return a.test(cp) || b.test(cp);
        }
    }

    private static final class And extends CharClass {
        private final CharClass a;
        private final CharClass b;

        And(CharClass a, CharClass b) {
            this.a = a;
            this.b = b;
        }

        boolean test(int cp) {
            return a.test(cp) && b.test(cp);
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
                case U_IDEOGRAPHIC: return isIdeographic(cp);
                case U_ASSIGNED: return Category.type(cp) != Category.Cn;
                case U_JOIN: return cp == 0x200C || cp == 0x200D;
                case U_NONCHAR: return (cp & 0xFFFE) == 0xFFFE || (cp >= 0xFDD0 && cp <= 0xFDEF);
                case J_WHITESPACE: return Character.isWhitespace(cp);
                case J_SPACECHAR: return Character.isSpaceChar(cp);
                case J_ISOCONTROL: return Character.isISOControl(cp);
                case J_TITLE: return Category.type(cp) == Category.Lt;
                case J_DEFINED: return Category.type(cp) != Category.Cn;
                case J_MIRRORED: return "()<>[]{}«»".indexOf(cp) >= 0;
                case J_JAVA_START: return isJavaStart(cp);
                case J_JAVA_PART: return isJavaStart(cp) || isJavaPartOnly(cp);
                case J_UNI_START: return Character.isLetter(cp) || Category.type(cp) == Category.Nl;
                case J_UNI_PART: return Character.isLetterOrDigit(cp) || isJavaPartOnly(cp) && Category.type(cp) != Category.Sc;
                case J_IGNORABLE: return isIgnorable(cp);
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
        return (cp >= '0' && cp <= '9') || (cp >= 'a' && cp <= 'f') || (cp >= 'A' && cp <= 'F')
            || (cp >= 0xFF10 && cp <= 0xFF19) || (cp >= 0xFF21 && cp <= 0xFF26) || (cp >= 0xFF41 && cp <= 0xFF46);
    }

    private static boolean isIdeographic(int cp) {
        return cp == 0x3006 || cp == 0x3007 || (cp >= 0x3021 && cp <= 0x3029) || (cp >= 0x3038 && cp <= 0x303A)
            || (cp >= 0x3400 && cp <= 0x4DB5) || (cp >= 0x4E00 && cp <= 0x9FCB) || (cp >= 0xF900 && cp <= 0xFA6D)
            || (cp >= 0xFA70 && cp <= 0xFAD9) || (cp >= 0x20000 && cp <= 0x2FA1D);
    }

    private static boolean isJavaStart(int cp) {
        int t = Category.type(cp);
        return Character.isLetter(cp) || t == Category.Nl || t == Category.Sc || t == Category.Pc;
    }

    private static boolean isIgnorable(int cp) {
        return (cp >= 0 && cp <= 8) || (cp >= 0xE && cp <= 0x1B) || (cp >= 0x7F && cp <= 0x9F)
            || Category.type(cp) == Category.Cf;
    }

    private static boolean isJavaPartOnly(int cp) {
        int t = Category.type(cp);
        return Character.isDigit(cp) || t == Category.Mn || t == Category.Mc || isIgnorable(cp);
    }
}
