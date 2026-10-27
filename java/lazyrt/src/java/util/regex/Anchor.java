package java.util.regex;

/** Zero-width assertions about the position: ^ $ \A \Z \z \b \B \G. */
final class Anchor extends Node {
    static final int BEGIN = 0, BEGIN_LINE = 1, END_LINE = 2, END_INPUT_OR_FINAL = 3, END_INPUT = 4;
    static final int WORD = 5, NOT_WORD = 6, LAST_MATCH = 7;

    private final int kind;
    private final boolean unix;
    private final boolean wordClasses;

    /** `unix`: only \n ends a line (UNIX_LINES). */
    Anchor(int kind, boolean unix) {
        this(kind, unix, false);
    }

    /** `wordClasses`: \b and \B use the Unicode meaning of \w (UNICODE_CHARACTER_CLASS). */
    Anchor(int kind, boolean unix, boolean wordClasses) {
        this.kind = kind;
        this.unix = unix;
        this.wordClasses = wordClasses;
    }

    /** True for ^ outside MULTILINE and \A, which can only match where a search starts. */
    boolean isBegin() {
        return kind == BEGIN;
    }

    boolean match(Matcher m, int i) {
        return holds(m, i) && next.match(m, i);
    }

    /** Whether the assertion is true at i, without continuing the match. */
    boolean holds(Matcher m, int i) {
        boolean ok;
        switch (kind) {
            case BEGIN: ok = i == (m.anchoringBounds ? m.lo : 0); break;
            case BEGIN_LINE: ok = beginLine(m, i); break;
            case END_LINE: ok = endLine(m, i); break;
            case END_INPUT_OR_FINAL: ok = endOrFinalTerminator(m, i); break;
            case END_INPUT: ok = atEnd(m, i); break;
            case WORD: ok = wordBoundary(m, i); break;
            case NOT_WORD: ok = !wordBoundary(m, i); break;
            default: ok = i == m.oldLast; break;
        }
        return ok;
    }

    private boolean isTerminator(char c) {
        return unix ? c == '\n' : CharClass.isLineTerminator(c);
    }

    private static int endIndex(Matcher m) {
        return m.anchoringBounds ? m.hi : m.textLength;
    }

    private boolean atEnd(Matcher m, int i) {
        if (i != endIndex(m)) {
            return false;
        }
        m.hitEnd = true;
        return true;
    }

    /** ^ in MULTILINE: at the start, or after a line terminator that is not the last thing in the input. */
    private boolean beginLine(Matcher m, int i) {
        int start = m.anchoringBounds ? m.lo : 0;
        if (i == endIndex(m)) {
            m.hitEnd = true;
            return false;
        }
        if (i > start) {
            char prev = m.text.charAt(i - 1);
            if (!isTerminator(prev)) {
                return false;
            }
            return !(prev == '\r' && !unix && m.text.charAt(i) == '\n');
        }
        return true;
    }

    /** $ in MULTILINE: before any line terminator, or at the end. */
    private boolean endLine(Matcher m, int i) {
        int end = endIndex(m);
        if (i == end) {
            m.hitEnd = true;
            m.requireEnd = true;
            return true;
        }
        if (i > end) {
            return false;
        }
        char c = m.text.charAt(i);
        if (!isTerminator(c)) {
            return false;
        }
        return !(c == '\n' && !unix && i > 0 && m.text.charAt(i - 1) == '\r');
    }

    /** $ and \Z outside MULTILINE: at the end, or before the final line terminator. */
    private boolean endOrFinalTerminator(Matcher m, int i) {
        int end = endIndex(m);
        if (i > end || i < end - 2) {
            return false;
        }
        if (i == end - 2) {
            if (unix || m.text.charAt(i) != '\r' || m.text.charAt(i + 1) != '\n') {
                return false;
            }
        } else if (i == end - 1) {
            char c = m.text.charAt(i);
            if (!isTerminator(c) || (c == '\n' && !unix && i > 0 && m.text.charAt(i - 1) == '\r')) {
                return false;
            }
        }
        m.hitEnd = true;
        m.requireEnd = true;
        return true;
    }

    private static final CharClass UNICODE_WORD = CharClass.cached(CharClass.of(CharClass.U_WORD));

    /**
     * Whether the code point starting at `at` counts as a word char for . Observed on the
     * oracle (RegexFlags, "word boundary and marks"): by default a letter, digit or '_', or a
     * nonspacing mark that follows a letter or digit (through other nonspacing marks, but not
     * through '_'; a supplementary mark is judged by the unit before its last unit, which is
     * how the oracle's results come out); under UNICODE_CHARACTER_CLASS exactly the Unicode \w, so every mark and
     * join control counts.
     */
    private boolean isWord(Matcher m, int cp, int at) {
        if (wordClasses) {
            return UNICODE_WORD.test(cp);
        }
        if (cp == '_' || Character.isLetterOrDigit(cp)) {
            return true;
        }
        if (Character.getType(cp) != Character.NON_SPACING_MARK) {
            return false;
        }
        // Walks back one UTF-16 unit at a time from the unit before `at`.
        for (int k = at - 1; k >= 0; k--) {
            char before = m.text.charAt(k);
            if (Character.isLetterOrDigit(before)) {
                return true;
            }
            if (Character.getType(before) != Character.NON_SPACING_MARK) {
                return false;
            }
        }
        return false;
    }

    /** The code point that ends just before index k. */
    private static int cpBefore(CharSequence text, int k) {
        char low = text.charAt(k - 1);
        if (Character.isLowSurrogate(low) && k >= 2 && Character.isHighSurrogate(text.charAt(k - 2))) {
            return Character.toCodePoint(text.charAt(k - 2), low);
        }
        return low;
    }

    private boolean wordBoundary(Matcher m, int i) {
        boolean left = false;
        boolean right = false;
        if (i > m.lo || (m.transparentBounds && i > 0)) {
            int cp = cpBefore(m.text, i);
            left = isWord(m, cp, i - 1);
        }
        if (i < m.hi || (m.transparentBounds && i < m.textLength)) {
            right = isWord(m, Character.codePointAt(m.text, i), i);
        } else {
            m.hitEnd = true;
            m.requireEnd = true;
        }
        return left != right;
    }
}
