package java.util.regex;

/** Zero-width assertions about the position: ^ $ \A \Z \z \b \B \G. */
final class Anchor extends Node {
    static final int BEGIN = 0, BEGIN_LINE = 1, END_LINE = 2, END_INPUT_OR_FINAL = 3, END_INPUT = 4;
    static final int WORD = 5, NOT_WORD = 6, LAST_MATCH = 7;

    private final int kind;
    private final boolean unix;

    /** `unix`: only \n ends a line (UNIX_LINES). */
    Anchor(int kind, boolean unix) {
        this.kind = kind;
        this.unix = unix;
    }

    /** True for ^ outside MULTILINE and \A, which can only match where a search starts. */
    boolean isBegin() {
        return kind == BEGIN;
    }

    boolean match(Matcher m, int i) {
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
        return ok && next.match(m, i);
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

    private static boolean isWord(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    /** A word char, or a combining mark that follows one (so "e" + acute is one word char). */
    private static boolean isWordAt(Matcher m, int i) {
        char c = m.text.charAt(i);
        if (isWord(c)) {
            return true;
        }
        if (Category.type(c) != Category.Mn) {
            return false;
        }
        for (int k = i - 1; k >= 0; k--) {
            char b = m.text.charAt(k);
            if (isWord(b)) {
                return true;
            }
            if (Category.type(b) != Category.Mn) {
                return false;
            }
        }
        return false;
    }

    private static boolean wordBoundary(Matcher m, int i) {
        boolean left = false;
        boolean right = false;
        if (i > m.lo || (m.transparentBounds && i > 0)) {
            left = isWordAt(m, i - 1);
        }
        if (i < m.hi || (m.transparentBounds && i < m.textLength)) {
            right = isWordAt(m, i);
        } else {
            m.hitEnd = true;
            m.requireEnd = true;
        }
        return left != right;
    }
}
