package java.util.regex;

/**
 * The cursor over a pattern's text and the syntax shared by every part of the
 * grammar: error reporting, escapes that denote one character, and \p names.
 *
 * <p>Error positions follow one rule: an error is reported at `cursor - 1`, so
 * a character that was consumed and found wrong is the one pointed at, and
 * reading past the end (which still advances the cursor) points one past it.
 * RegexErrors compares the messages and indexes with the oracle's.
 */
class PatternReader {
    final String original;
    final String src;
    final int len;
    int cursor;
    int flags;
    /**
     * Observed on the oracle: a pattern whose text holds a surrogate char (a pair, or a lone half
     * written raw; a \x{D83D} escape does not count), or that
     * negates a category or block (\P{Lu}, \P{InGreek}, \P{all} excepted), makes a failed
     * search attempt at a high surrogate skip the low half of its pair (Matcher.search). It
     * changes nothing for an explicit start inside a pair. Pinned by RegexSurrogates, blessed
     * in RegexSurrogates.out; mutation: ignore this flag in Matcher.search.
     */
    boolean supplementary;
    /** The pattern text holds a surrogate char written raw (see Atoms.One.width). */
    boolean rawSurrogates;
    /** A class being read holds a character above U+FFFF. */
    boolean wideClass;

    PatternReader(String pattern, int flags) {
        this.original = pattern;
        this.src = (flags & Pattern.LITERAL) != 0 ? pattern : removeQuotes(pattern);
        this.len = src.length();
        this.flags = flags;
        for (int i = 0; i < pattern.length(); i++) {
            if (Character.isSurrogate(pattern.charAt(i))) {
                supplementary = true;
                rawSurrogates = true;
            }
        }
    }

    final PatternSyntaxException error(String desc) {
        return errorAt(desc, cursor - 1);
    }

    /**
     * Observed on the oracle (RegexErrors, "supplementary characters"): the index counts code
     * points, so a pair counts once.
     */
    final PatternSyntaxException errorAt(String desc, int index) {
        int cursorAfter = index + 1;
        int points = cursorAfter <= len ? src.codePointCount(0, cursorAfter)
            : src.codePointCount(0, len) + (cursorAfter - len);
        return new PatternSyntaxException(desc, original, points - 1);
    }

    /** The next char without consuming it; 0 at the end. */
    final int cur() {
        return cursor < len ? src.charAt(cursor) : 0;
    }

    /** Consumes and returns the next char; reading past the end returns 0 and still advances. */
    final int read() {
        int c = cursor < len ? src.charAt(cursor) : 0;
        cursor++;
        return c;
    }

    /** Like read(), but under COMMENTS first skips whitespace and comments. */
    final int readSkipping() {
        skipComments();
        return read();
    }

    final boolean atEnd() {
        return cursor >= len;
    }

    final boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /** Skips whitespace and #-comments when COMMENTS is on. */
    final void skipComments() {
        if (!has(Pattern.COMMENTS)) {
            return;
        }
        while (cursor < len) {
            char c = src.charAt(cursor);
            if (c == ' ' || (c >= 9 && c <= 13)) {
                cursor++;
            } else if (c == '#') {
                while (cursor < len && !isCommentEnd(src.charAt(cursor))) {
                    cursor++;
                }
            } else {
                return;
            }
        }
    }

    private boolean isCommentEnd(char c) {
        return has(Pattern.UNIX_LINES) ? c == '\n' : CharClass.isLineTerminator(c);
    }

    /** Rewrites \Q..\E spans as escaped literals so the grammar never sees quoting. */
    static String removeQuotes(String p) {
        if (p.indexOf("\\Q") < 0) {
            return p;
        }
        StringBuilder sb = new StringBuilder();
        int n = p.length();
        int i = 0;
        while (i < n) {
            char c = p.charAt(i);
            if (c != '\\' || i + 1 >= n) {
                sb.append(c);
                i++;
            } else if (p.charAt(i + 1) == 'Q') {
                int end = p.indexOf("\\E", i + 2);
                int stop = end < 0 ? n : end;
                for (int k = i + 2; k < stop; k++) {
                    char q = p.charAt(k);
                    boolean digit = q >= '0' && q <= '9';
                    boolean plain = q >= 128 || digit || (q >= 'a' && q <= 'z') || (q >= 'A' && q <= 'Z');
                    if (k == i + 2 && digit) {
                        // A leading digit becomes a hex escape, so it cannot extend a preceding
                        // octal, hex or group reference.
                        sb.append("\\x3");
                    } else if (!plain) {
                        sb.append('\\');
                    }
                    sb.append(q);
                }
                i = end < 0 ? n : end + 2;
            } else {
                sb.append(c).append(p.charAt(i + 1));
                i += 2;
            }
        }
        return sb.toString();
    }

    static boolean isHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    static int hexValue(int c) {
        return c <= '9' ? c - '0' : (c | 0x20) - 'a' + 10;
    }

    static boolean isAsciiLetter(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * The character an escape denotes, for the escape letter `c` just read after a
     * backslash; -1 when `c` is not a single-character escape.
     */
    final int escapedChar(int c) {
        switch (c) {
            case 't': return '\t';
            case 'n': return '\n';
            case 'r': return '\r';
            case 'f': return '\f';
            case 'a': return 7;
            case 'e': return 27;
            case '0': return octal();
            case 'x': return hex();
            case 'u': return unicode();
            case 'c':
                if (cursor < len) {
                    return read() ^ 64;
                }
                throw error("Illegal control escape sequence");
            default: return -1;
        }
    }

    private int octal() {
        skipComments();
        int n = read();
        if (n < '0' || n > '7') {
            throw error("Illegal octal escape sequence");
        }
        int v = n - '0';
        skipComments();
        if (cur() >= '0' && cur() <= '7' && !atEnd()) {
            v = v * 8 + (read() - '0');
            skipComments();
            if (n <= '3' && cur() >= '0' && cur() <= '7' && !atEnd()) {
                v = v * 8 + (read() - '0');
            }
        }
        return v;
    }

    private int hex() {
        skipComments();
        int n = read();
        if (n == '{') {
            if (atEnd() || !isHex(cur())) {
                throw error("Illegal hexadecimal escape sequence");
            }
            long v = 0;
            while (!atEnd() && isHex(cur())) {
                v = v * 16 + hexValue(read());
                if (v > 0x10FFFF) {
                    throw error("Hexadecimal codepoint is too big");
                }
            }
            if (read() != '}') {
                throw error("Unclosed hexadecimal escape sequence");
            }
            return (int) v;
        }
        if (!isHex(n)) {
            throw error("Illegal hexadecimal escape sequence");
        }
        skipComments();
        int m = read();
        if (!isHex(m)) {
            throw error("Illegal hexadecimal escape sequence");
        }
        return hexValue(n) * 16 + hexValue(m);
    }

    private int unicode4() {
        int v = 0;
        for (int k = 0; k < 4; k++) {
            skipComments();
            int d = read();
            if (!isHex(d)) {
                throw error("Illegal Unicode escape sequence");
            }
            v = v * 16 + hexValue(d);
        }
        return v;
    }

    /** \\uhhhh, joining a high surrogate escape with a directly following low surrogate escape. */
    private int unicode() {
        int v = unicode4();
        if (Character.isHighSurrogate((char) v) && cursor + 1 < len && src.charAt(cursor) == '\\'
            && src.charAt(cursor + 1) == 'u') {
            int save = cursor;
            cursor += 2;
            int w = unicode4();
            if (Character.isLowSurrogate((char) w)) {
                return Character.toCodePoint((char) v, (char) w);
            }
            cursor = save;
        }
        return v;
    }

    /** \p{name} or \pX with the cursor just after the p/P; returns the class, negated for \P. */
    final CharClass property(boolean negate) {
        String name;
        skipComments();
        int c = read();
        if (c == '{') {
            int close = src.indexOf('}', cursor);
            if (close < 0) {
                cursor = len + 1;
                throw error("Unclosed character family");
            }
            name = src.substring(cursor, close);
            cursor = close + 1;
            if (name.length() == 0) {
                throw error("Empty character family");
            }
        } else {
            name = String.valueOf((char) c);
        }
        String[] failure = new String[1];
        CharClass cc = Props.resolve(name, has(Pattern.UNICODE_CHARACTER_CLASS), failure);
        if (cc == null) {
            throw error(failure[0]);
        }
        if (negate && cc.categoryOrBlock) {
            supplementary = true;
        }
        return negate ? cc.not() : cc;
    }
}
