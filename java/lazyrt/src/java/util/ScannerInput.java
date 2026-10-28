package java.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text a Scanner has read but not yet passed (docs/design/util-text.md SC8, SC9). Every
 * decision is made on this buffer; when a match might change with more text, the scanner reads
 * more and decides again, so the answers never depend on how the source splits its reads.
 */
final class ScannerInput {
    private static final int CHUNK = 1024;
    /** Passed text is dropped from the front only in large pieces, to keep the copying linear. */
    private static final int COMPACT = 8192;

    final StringBuilder buf = new StringBuilder();
    /** The next char of buf the scanner has not passed. */
    int pos;
    /** How many chars of input were dropped from buf's front. */
    long base;
    /** Set once the source has ended or failed; then it is never read again. */
    boolean ended;
    IOException failure;

    private final Readable source;
    private final CharBuffer chunk = CharBuffer.allocate(CHUNK);

    ScannerInput(Readable source) {
        this.source = source;
    }

    /** SC8: an unknown name is an IllegalArgumentException wrapping the charset error. */
    static Charset charset(String name) {
        Objects.requireNonNull(name, "charsetName");
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** SC20: a source that can be closed is; a failure to close becomes ioException(). */
    void close() {
        if (source instanceof Closeable) {
            try {
                ((Closeable) source).close();
            } catch (IOException e) {
                failure = e;
            }
        }
    }

    /**
     * Every match sees the text around its region (lookaround reaches past it) but anchors only
     * at the true ends of the input, as the oracle's do.
     */
    Matcher matcher(Pattern p, int from, int to) {
        return p.matcher(buf).useTransparentBounds(true).useAnchoringBounds(false).region(from, to);
    }

    /** The index of the first line terminator at or after pos, or -1 if the buffer has none. */
    int lineEnd() {
        for (int i = pos; i < buf.length(); i++) {
            char c = buf.charAt(i);
            if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029' || c == '\u0085') {
                return i;
            }
        }
        return -1;
    }

    /** The input position of buffer index i. */
    long at(int i) {
        return base + i;
    }

    /**
     * Reads once more from the source; false when the input has ended (SC9: a failure ends it).
     * After true, buffer indexes taken before the call are stale.
     */
    boolean more() {
        if (ended) {
            return false;
        }
        chunk.clear();
        int n;
        try {
            n = source.read(chunk);
        } catch (IOException e) {
            failure = e;
            ended = true;
            return false;
        }
        if (n < 0) {
            ended = true;
            return false;
        }
        // Only after a successful read: a caller that sees false keeps using its buffer indexes.
        if (pos > COMPACT && pos > buf.length() / 2) {
            buf.delete(0, pos);
            base += pos;
            pos = 0;
        }
        chunk.flip();
        buf.append(chunk.array(), chunk.arrayOffset() + chunk.position(), chunk.remaining());
        return true;
    }
}
