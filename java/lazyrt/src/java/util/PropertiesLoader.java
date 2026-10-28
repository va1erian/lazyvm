package java.util;

import java.io.IOException;
import java.io.Reader;

/**
 * The parser behind Properties.load (docs/design/util-text.md P1-P8, P7a, P20). One instance
 * serves one load call: it splits the input into logical lines in a reused buffer, then cuts
 * each line into a key and a value and decodes their escapes.
 */
final class PropertiesLoader {
    /** What one physical line is doing so far. */
    private static final int FRESH = 0;    // start of a logical line, blanks skipped
    private static final int JOINED = 1;   // start of a continuation line, blanks skipped
    private static final int CONTENT = 2;  // storing key and value chars
    private static final int COMMENT = 3;  // inside a # or ! line

    private static final String MALFORMED = "Malformed \\uxxxx encoding.";

    private final Reader in;
    /** P8: every read asks for 8192 chars at offset 0, which a spying reader can see. */
    private final char[] chunk = new char[8192];
    private int chunkPos;
    private int chunkEnd;

    /**
     * P20: never cleared between lines, and comment text lands here too, because an escape at
     * the end of a line reads whatever an earlier, longer line left behind.
     */
    private char[] line = new char[80];
    /** P20: whether the run of backslashes just stored is odd; it outlives comment lines. */
    private boolean oddRun;
    /** A CR was the last char; an LF right after it belongs to the same break. */
    private boolean afterCR;

    private PropertiesLoader(Reader in) {
        this.in = in;
    }

    static void load(Reader in, Properties into) throws IOException {
        PropertiesLoader loader = new PropertiesLoader(in);
        int len;
        while ((len = loader.nextLine()) >= 0) {
            loader.split(len, into);
        }
    }

    /**
     * The next char, or -1 at the end. A read of zero chars also ends the input, and the end is
     * not remembered: the next line asks the reader again, as observed (P8).
     */
    private int next() throws IOException {
        if (chunkPos == chunkEnd && !refill()) {
            return -1;
        }
        return chunk[chunkPos++];
    }

    private boolean refill() throws IOException {
        int n = in.read(chunk, 0, chunk.length);
        if (n <= 0) {
            return false;
        }
        chunkPos = 0;
        chunkEnd = n;
        return true;
    }

    /**
     * Before a line ended by a break is handed out, the char after the break is fetched, and an
     * LF that completes a CRLF is taken with it; observable as the order of reads and puts.
     */
    private void lookPastBreak() throws IOException {
        if (chunkPos == chunkEnd && !refill()) {
            return;
        }
        if (afterCR && chunk[chunkPos] == '\n') {
            chunkPos++;
        }
        afterCR = false;
    }

    /** Reads one logical line into `line` and returns its length, or -1 when none is left. */
    private int nextLine() throws IOException {
        int len = 0;
        int mode = FRESH;
        boolean quiet = false; // nothing read since a continuation's break (P7a)
        for (;;) {
            int c = next();
            if (c < 0) {
                return atEnd(mode, len, quiet);
            }
            quiet = false;
            boolean lfOfCrlf = afterCR && c == '\n';
            afterCR = false;
            if (lfOfCrlf) {
                continue;
            }
            if (c == '\n' || c == '\r') {
                afterCR = c == '\r';
                if (mode == CONTENT && oddRun) {
                    // P2: drop the backslash, keep the length, skip the next line's blanks.
                    len--;
                    oddRun = false;
                    mode = JOINED;
                    quiet = true;
                } else if (mode == CONTENT || (mode == JOINED && len > 0)) {
                    lookPastBreak();
                    return len;
                } else {
                    // An empty or blank line, a comment's end, or an empty logical line ended by
                    // an empty continuation (P7a). Parity is left alone (P20).
                    mode = FRESH;
                    len = 0;
                }
                continue;
            }
            if (mode <= JOINED && isBlank(c)) {
                continue;
            }
            if (mode == FRESH && (c == '#' || c == '!')) {
                mode = COMMENT; // P1; the marker itself is not stored (P20)
                continue;
            }
            if (mode != COMMENT) {
                mode = CONTENT;
            }
            len = store(len, (char) c);
        }
    }

    /** P2, P7a: what the input's end makes of the line in progress. */
    private int atEnd(int mode, int len, boolean quiet) {
        switch (mode) {
            case CONTENT:
                // P2: a backslash at the end is dropped, and like a continuation's it leaves
                // even parity behind for a reader that has more after its end.
                if (oddRun) {
                    oddRun = false;
                    return len - 1;
                }
                return len;
            case JOINED:
                return len > 0 || quiet ? len : -1;
            default:
                return -1;
        }
    }

    private int store(int len, char c) {
        if (len == line.length) {
            line = Arrays.copyOf(line, len * 2);
        }
        line[len] = c;
        oddRun = c == '\\' && !oddRun;
        return len + 1;
    }

    /** P3: the key ends at the first unescaped separator or blank; P6: one put per pair. */
    private void split(int len, Properties into) {
        int i = 0;
        boolean escaped = false;
        while (i < len) {
            char c = line[i];
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '=' || c == ':' || isBlank(c)) {
                break;
            }
            i++;
        }
        int keyEnd = i;
        i = skipBlanks(i, len);
        if (i < len && (line[i] == '=' || line[i] == ':')) {
            i++;
        }
        i = skipBlanks(i, len);
        String key = decode(0, keyEnd);
        String value = decode(i, len);
        into.put(key, value);
    }

    private int skipBlanks(int i, int len) {
        while (i < len && isBlank(line[i])) {
            i++;
        }
        return i;
    }

    /** P4, P5; an escape at the end reads on into the buffer's leftovers (P20). */
    private String decode(int from, int to) {
        StringBuilder out = new StringBuilder(to - from);
        int i = from;
        while (i < to) {
            char c = line[i++];
            if (c != '\\') {
                out.append(c);
                continue;
            }
            c = leftover(i++);
            if (c == 'u') {
                int v = 0;
                for (int k = 0; k < 4; k++) {
                    int d = hexDigit(leftover(i++));
                    if (d < 0) {
                        throw new IllegalArgumentException(MALFORMED);
                    }
                    v = (v << 4) | d;
                }
                out.append((char) v);
            } else {
                out.append(control(c));
            }
        }
        return out.toString();
    }

    /** A buffer char past any length: U+0000 where nothing was ever written (P20). */
    private char leftover(int i) {
        return i < line.length ? line[i] : '\0';
    }

    private static char control(char c) {
        switch (c) {
            case 't': return '\t';
            case 'n': return '\n';
            case 'r': return '\r';
            case 'f': return '\f';
            default: return c;
        }
    }

    /** ASCII hex only: other scripts' digits are malformed, as observed. */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static boolean isBlank(int c) {
        return c == ' ' || c == '\t' || c == '\f';
    }
}
