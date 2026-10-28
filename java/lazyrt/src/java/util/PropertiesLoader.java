package java.util;

import java.io.IOException;
import java.io.Reader;

/**
 * Properties.load (docs/design/util-text.md P1-P8, P20): logical lines, then keys and values with
 * their escapes. The line buffer is reused for every line of one load and never cleared, and the
 * backslash parity is not reset by a comment line, because both are observable (P20).
 */
final class PropertiesLoader {
    private final Reader in;
    private final char[] chunk = new char[8192];
    private int pos;
    private int limit;
    /** The current logical line, raw; chars past `len` are left from earlier lines (P20). */
    private char[] line = new char[80];
    private int len;
    /** Whether the last stored char ends an odd run of backslashes (P2, P20). */
    private boolean backslash;

    private PropertiesLoader(Reader in) {
        this.in = in;
    }

    /** P6: every pair goes through the table's own put. */
    static void load(Reader reader, Properties into) throws IOException {
        PropertiesLoader l = new PropertiesLoader(reader);
        while (l.logicalLine()) {
            l.pair(into);
        }
    }

    /** The next char, or -1 at the end of input. */
    private int next() throws IOException {
        while (pos == limit) {
            int n = in.read(chunk, 0, chunk.length);
            if (n < 0) {
                return -1;
            }
            pos = 0;
            limit = n;
        }
        return chunk[pos++];
    }

    private static boolean blank(int c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    private static boolean eol(int c) {
        return c == '\n' || c == '\r';
    }

    private void store(char c) {
        if (len == line.length - 1) {
            line = Arrays.copyOf(line, 2 * line.length);
        }
        line[len++] = c;
        backslash = c == '\\' && !backslash;
    }

    /**
     * P1, P2: reads the next logical line into `line`; false at the end of input. Comment chars are
     * stored too (and later overwritten), as P20 requires.
     */
    private boolean logicalLine() throws IOException {
        len = 0;
        boolean skipBlanks = true;
        boolean newLine = true;
        boolean comment = false;
        boolean appended = false; // in a continuation line
        boolean justContinued = false;
        boolean skipLF = false;
        while (true) {
            int c = next();
            if (c < 0) {
                return endOfInput(justContinued, comment);
            }
            justContinued = false;
            if (skipLF) {
                skipLF = false;
                if (c == '\n') {
                    continue;
                }
            }
            if (skipBlanks) {
                if (blank(c) || !appended && eol(c)) {
                    continue;
                }
                skipBlanks = false;
                appended = false;
            }
            if (newLine) {
                newLine = false;
                if (c == '#' || c == '!') {
                    comment = true;
                    continue;
                }
            }
            if (!eol(c)) {
                store((char) c);
            } else if (comment || len == 0) {
                // A comment, or an empty continuation line that ends an empty logical line: no pair.
                comment = false;
                newLine = true;
                skipBlanks = true;
                len = 0;
            } else if (backslash) {
                len--;
                backslash = false;
                skipBlanks = true;
                appended = true;
                justContinued = true;
                skipLF = c == '\r';
            } else {
                return true;
            }
        }
    }

    /** P2, P20: a line cut by the end of input; one that a continuation just ended counts even when empty. */
    private boolean endOfInput(boolean justContinued, boolean comment) {
        if (justContinued) {
            return true;
        }
        if (len == 0 || comment) {
            return false;
        }
        if (backslash) {
            len--;
        }
        return true;
    }

    /** P3: splits the line at its first unescaped separator; P4: unescapes both parts. */
    private void pair(Properties into) {
        int k = 0;
        boolean escaped = false;
        while (k < len) {
            char c = line[k];
            if (!escaped && (c == '=' || c == ':' || blank(c))) {
                break;
            }
            escaped = c == '\\' && !escaped;
            k++;
        }
        int v = k;
        while (v < len && blank(line[v])) {
            v++;
        }
        if (v < len && (line[v] == '=' || line[v] == ':')) {
            v++;
        }
        while (v < len && blank(line[v])) {
            v++;
        }
        into.put(unescape(0, k), unescape(v, len));
    }

    /** A char of the line buffer; past its end, 0. */
    private char at(int i) {
        return i < line.length ? line[i] : 0;
    }

    /** P4, P5; an escape at the end reads on into the buffer's leftover chars (P20). */
    private String unescape(int from, int to) {
        StringBuilder out = new StringBuilder(to - from);
        int i = from;
        while (i < to) {
            char c = at(i++);
            if (c == '\\') {
                c = at(i++);
                if (c == 'u') {
                    c = hex4(i);
                    i += 4;
                } else {
                    c = c == 't' ? '\t' : c == 'n' ? '\n' : c == 'r' ? '\r' : c == 'f' ? '\f' : c;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private char hex4(int at) {
        int v = 0;
        for (int i = at; i < at + 4; i++) {
            char c = at(i);
            int lower = c | 0x20;
            int d = c >= '0' && c <= '9' ? c - '0' : lower >= 'a' && lower <= 'f' ? lower - 'a' + 10 : -1;
            if (d < 0) {
                throw new IllegalArgumentException("Malformed \\uxxxx encoding.");
            }
            v = v << 4 | d;
        }
        return (char) v;
    }
}
