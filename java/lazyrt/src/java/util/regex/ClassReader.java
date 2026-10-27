package java.util.regex;

/**
 * Parses bracketed character classes: ranges, nested classes, && intersection
 * and the predefined classes (\d \w \p{..} ...).
 *
 * <p>Two behaviours follow the reference implementation rather than intuition.
 * A leading ^ negates only the items written directly in the first segment, one
 * by one (so [^a[b]] is "not a, or b" and [^a-c&&b-d] is "not a-c, and b-d"),
 * and an empty side of && is ignored.
 */
class ClassReader extends PatternReader {
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
        CharClass acc = null;
        boolean first = true;
        while (true) {
            skipComments();
            if (atEnd()) {
                throw errorAt("Unclosed character class", len - 1);
            }
            int c = src.charAt(cursor);
            if (c == ']' && !first) {
                cursor++;
                break;
            }
            first = false;
            if (c == '[') {
                cursor++;
                CharClass nested = parseClass();
                acc = acc == null ? nested : CharClass.or(acc, nested);
            } else if (c == '&' && cursor + 1 < len && src.charAt(cursor + 1) == '&') {
                cursor += 2;
                CharClass rhs = parseRightSide();
                if (rhs == null && acc == null) {
                    throw error("Bad class syntax");
                }
                if (rhs != null) {
                    acc = acc == null ? rhs : CharClass.and(acc, rhs);
                }
                negating = false;
            } else {
                CharClass item = item();
                if (negating) {
                    item = item.not();
                    acc = acc == null ? item : CharClass.and(acc, item);
                } else {
                    acc = acc == null ? item : CharClass.or(acc, item);
                }
            }
        }
        return acc == null ? CharClass.range(1, 0) : CharClass.cached(acc);
    }

    /** The items after &&, up to the next '&' or the closing bracket; null when there are none. */
    private CharClass parseRightSide() {
        CharClass rhs = null;
        while (true) {
            skipComments();
            if (atEnd()) {
                throw errorAt("Unclosed character class", len - 1);
            }
            int c = src.charAt(cursor);
            if (c == ']' || c == '&') {
                return rhs;
            }
            CharClass item;
            if (c == '[') {
                cursor++;
                item = parseClass();
            } else {
                item = item();
            }
            rhs = rhs == null ? item : CharClass.or(rhs, item);
        }
    }

    final int readCodePoint() {
        int c = read();
        if (Character.isHighSurrogate((char) c) && !atEnd() && Character.isLowSurrogate((char) cur())) {
            return Character.toCodePoint((char) c, (char) read());
        }
        return c;
    }

    /** One char, one range, or one predefined class. */
    private CharClass item() {
        int c = readCodePoint();
        if (c == '\\') {
            int e = read();
            if (e == 0 && cursor > len) {
                throw error("Unclosed character class");
            }
            CharClass cc = predefined(e);
            if (cc != null) {
                return cc;
            }
            if (e == 'p' || e == 'P') {
                return property(e == 'P');
            }
            c = classEscapeChar(e);
        }
        skipComments();
        if (cur() == '-' && !atEnd()) {
            int after = cursor + 1 < len ? src.charAt(cursor + 1) : 0;
            boolean literalDash = cursor + 1 < len && (after == ']' || after == '[');
            if (!literalDash) {
                cursor++;
                return range(c, rangeEnd());
            }
        }
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

    private int rangeEnd() {
        int c = readCodePoint();
        if (c != '\\') {
            return c;
        }
        int e = read();
        if (e == 0 && cursor > len) {
            throw error("Illegal character range");
        }
        if (predefined(e) != null) {
            throw error("Illegal character range");
        }
        return classEscapeChar(e);
    }

    private CharClass single(int c) {
        return has(Pattern.CASE_INSENSITIVE)
            ? CharClass.foldRange(c, c, has(Pattern.UNICODE_CASE)) : CharClass.single(c);
    }

    private CharClass range(int lo, int hi) {
        if (hi < lo) {
            throw error("Illegal character range");
        }
        return has(Pattern.CASE_INSENSITIVE)
            ? CharClass.foldRange(lo, hi, has(Pattern.UNICODE_CASE)) : CharClass.range(lo, hi);
    }
}
