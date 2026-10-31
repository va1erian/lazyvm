package java.util.regex;

/**
 * Parses bracketed character classes: ranges, nested classes, && intersection
 * and the predefined classes (\d \w \p{..} ...).
 *
 * <p>Two behaviours were found by running classes on the Temurin 8 oracle (RegexClasses).
 * A leading ^ negates only the items written directly in the first segment, one
 * by one (so [^a[b]] is "not a, or b" and [^a-c&&b-d] is "not a-c, and b-d"),
 * and an empty side of && is ignored.
 */
class ClassReader extends PatternReader {
    /** How far classes nest, here and so far: a class is parsed by recursion (see Parser.nestedDeeply). */
    private int classDepth;
    int deepestClass;

    ClassReader(String pattern, int flags) {
        super(pattern, flags);
    }

    /** The predefined class an escape letter names (\d \s \w \h \v and their negations), or null. */
    final CharClass predefined(int e) {
        boolean uni = has(Pattern.UNICODE_CHARACTER_CLASS);
        CharClass cc;
        switch (e | 0x20) {
            case 'd': cc = uni ? CharClass.of(CharClass.U_DIGIT) : CharClass.range('0', '9'); break;
            case 's': cc = CharClass.of(uni ? CharClass.U_SPACE : CharClass.SPACE); break;
            case 'w': cc = CharClass.of(uni ? CharClass.U_WORD : CharClass.WORD); break;
            case 'h': cc = CharClass.of(CharClass.HSPACE); break;
            case 'v': cc = CharClass.of(CharClass.VSPACE); break;
            default: return null;
        }
        cc = CharClass.cached(cc);
        return e >= 'a' ? cc : cc.not();
    }

    /** Parses a class with the cursor just after its '['. */
    final CharClass parseClass() {
        boolean negating = false;
        if (!atEnd() && cur() == '^') {
            cursor++;
            negating = true;
        }
        CharClass result = deeper(negating, true);
        return result == null ? CharClass.range(1, 0) : CharClass.cached(result);
    }

    private CharClass deeper(boolean negating, boolean bracketed) {
        deepestClass = Math.max(deepestClass, ++classDepth);
        CharClass result = body(negating, bracketed);
        classDepth--;
        return result;
    }

    /**
     * The items of a class, folded left to right. `bracketed` is false for the tail of an &&
     * side, which ends before the ']' instead of consuming it.
     *
     * <p>Observed on the oracle (RegexClassAlgebra, mutation-checked there): && intersects what
     * was gathered so far with the right side; when the right side is empty it intersects with
     * the last item written before it, not with the empty set, so [a\d&&] is just [\d]. A run
     * of plain characters counts as one item, however it is interrupted: [a[bc]b&&] is [ab],
     * and [^a&&] is empty because that item is the un-negated a. Each plain character is
     * combined as that whole run (`stale` marks when that differs from combining it alone), so
     * [a[b]&&&] is [a-b&] and [^aa[^b]b] excludes a again.
     */
    private CharClass body(boolean negating, boolean bracketed) {
        Acc acc = new Acc();
        Acc singles = new Acc();
        CharClass last = null;
        boolean lastIsSingle = false;
        boolean stale = false;
        boolean first = true;
        boolean literalNext = false;
        while (true) {
            skipComments();
            if (atEnd()) {
                throw errorAt("Unclosed character class", len - 1);
            }
            int c = src.charAt(cursor);
            boolean forced = literalNext && (c == '[' || c == ']');
            literalNext = false;
            if (c == ']' && !(first && bracketed) && !forced) {
                if (bracketed) {
                    cursor++;
                }
                break;
            }
            first = false;
            int and = c == '&' ? ampersand() : PLAIN;
            if (c == '[' && !forced) {
                cursor++;
                last = parseClass();
                lastIsSingle = false;
                stale |= negating;
                acc.add(Acc.UNION, last);
            } else if (and == DROPPED) {
                literalNext = true;
            } else if (and == OPERATOR) {
                CharClass rhs = rightSide();
                if (rhs == null && acc.isEmpty()) {
                    throw error("Bad class syntax");
                }
                if (rhs == null && lastIsSingle) {
                    rhs = singles.build();
                }
                acc.add(acc.isEmpty() ? Acc.UNION : Acc.INTERSECT, rhs != null ? rhs : last);
                last = rhs != null ? rhs : last;
                lastIsSingle = false;
                stale = true;
            } else {
                last = item();
                lastIsSingle = singleItem;
                CharClass added = last;
                if (lastIsSingle) {
                    singles.add(Acc.UNION, last);
                    if (stale) {
                        added = singles.build();
                        stale = false;
                    }
                }
                acc.add(negating ? Acc.INTERSECT : Acc.UNION, negating ? added.not() : added);
            }
        }
        return acc.build();
    }

    /**
     * Collects the parts of a class. Consecutive unions (or intersections) go into one flat
     * list, so a class of thousands of items is not a chain of thousands of nested objects.
     */
    private static final class Acc {
        static final int UNION = 1;
        static final int INTERSECT = 2;
        private int mode;
        private CharClass[] parts = new CharClass[8];
        private int n;

        boolean isEmpty() {
            return n == 0;
        }

        void add(int newMode, CharClass c) {
            if (n > 0 && mode != newMode) {
                CharClass soFar = build();
                parts = new CharClass[8];
                n = 0;
                push(soFar);
            }
            mode = newMode;
            push(c);
        }

        private void push(CharClass c) {
            if (n == parts.length) {
                CharClass[] bigger = new CharClass[n * 2];
                System.arraycopy(parts, 0, bigger, 0, n);
                parts = bigger;
            }
            parts[n++] = c;
        }

        CharClass build() {
            if (n == 0) {
                return null;
            }
            return mode == UNION ? CharClass.anyOf(parts, n) : CharClass.allOf(parts, n);
        }
    }

    private static final int PLAIN = 0, OPERATOR = 1, DROPPED = 2;

    /**
     * Classifies the '&' at the cursor and moves past what it uses. Observed on the oracle
     * (RegexClassAlgebra, RegexFlags): under COMMENTS, white space and comments after a '&' are
     * skipped before looking for the second '&', so [a& &b] is [a&&b]; when none follows, a '&'
     * that had white space after it is dropped, and a '[' or ']' right after that is an ordinary
     * character: [a& ]] holds a and ']', [a& ] is unclosed.
     */
    private int ampersand() {
        int start = cursor++;
        if (has(Pattern.COMMENTS)) {
            skipComments();
        }
        if (cur() == '&' && !atEnd()) {
            cursor++;
            return OPERATOR;
        }
        if (cursor > start + 1) {
            return DROPPED;
        }
        cursor = start;
        return PLAIN;
    }

    /**
     * The side of an && up to the next '&' or the closing bracket; null when it is empty.
     * Observed on the oracle: nested classes are unioned, but a plain item (or range) starts
     * a fresh class that runs to the end of the side and replaces whatever came before, so
     * [a-z&&[b]c] is [c].
     */
    private CharClass rightSide() {
        CharClass side = null;
        while (true) {
            skipComments();
            if (atEnd()) {
                throw errorAt("Unclosed character class", len - 1);
            }
            int c = src.charAt(cursor);
            if (c == ']' || c == '&') {
                return side;
            }
            if (c == '[') {
                cursor++;
                CharClass nested = parseClass();
                side = side == null ? nested : CharClass.or(side, nested);
            } else {
                side = CharClass.cached(deeper(false, false));
            }
        }
    }

    final int readCodePoint() {
        int c = read();
        if (Character.isHighSurrogate((char) c) && !atEnd() && Character.isLowSurrogate((char) cur())) {
            return Character.toCodePoint((char) c, (char) read());
        }
        return c;
    }

    /**
     * True when the last item() call returned a plain character below U+0100, the only ones that
     * join a "run of plain characters" (see body); a range, a class or a character from U+0100 up
     * is an item of its own.
     */
    private boolean singleItem;

    /** One char, one range, or one predefined class. */
    private CharClass item() {
        singleItem = false;
        int c = readCodePoint();
        if (c == '\\') {
            int e = read();
            if (e == 0 && cursor > len) {
                throw error("Unclosed character class");
            }
            // Observed on the oracle: a \v directly before '-' is the one char U+000B, so [\v-z]
            // is a range and [\v-] holds U+000B and '-'. Pinned by RegexClassAlgebra.
            CharClass cc = e == 'v' && cur() == '-' ? null : predefined(e);
            if (cc != null) {
                return cc;
            }
            if (e == 'p' || e == 'P') {
                return property(e == 'P');
            }
            c = e == 'v' ? 0x0B : classEscapeChar(e);
        }
        skipComments();
        if (cur() == '-' && !atEnd()) {
            int after = cursor + 1 < len ? src.charAt(cursor + 1) : 0;
            boolean literalDash = cursor + 1 < len && (after == ']' || after == '[');
            if (!literalDash) {
                cursor++;
                return range(c, rangeEnd(c));
            }
        }
        singleItem = c < 256 && (!(has(Pattern.CASE_INSENSITIVE) && has(Pattern.UNICODE_CASE)) || CaseFold.staysInLatin1(c));
        return single(c);
    }

    private int classEscapeChar(int e) {
        int ch = escapedChar(e);
        if (ch >= 0) {
            return ch;
        }
        if (isAsciiLetter(e) || (e >= '1' && e <= '9')) {
            throw error("Illegal/unsupported escape sequence");
        }
        return e;
    }

    private int rangeEnd(int lo) {
        skipComments();
        int c = readCodePoint();
        if (c != '\\') {
            if (c < lo) {
                // Observed on the oracle (RegexFlags): after a plain end of range under COMMENTS the
                // white space that follows is read before the error, so [k-a ] reports index 4.
                skipComments();
            }
            return c;
        }
        int e = read();
        if (e == 0 && cursor > len) {
            throw error("Illegal character range");
        }
        if (e == 'v') {
            return 0x0B;
        }
        if (predefined(e) != null) {
            throw error("Illegal character range");
        }
        return classEscapeChar(e);
    }

    private CharClass single(int c) {
        return has(Pattern.CASE_INSENSITIVE)
            ? CaseFold.single(c, has(Pattern.UNICODE_CASE)) : CharClass.single(c);
    }

    private CharClass range(int lo, int hi) {
        if (hi < lo) {
            throw error("Illegal character range");
        }
        return has(Pattern.CASE_INSENSITIVE)
            ? CaseFold.range(lo, hi, has(Pattern.UNICODE_CASE)) : CharClass.range(lo, hi);
    }
}
