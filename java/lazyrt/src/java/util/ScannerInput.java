package java.util;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text a Scanner has read but not yet passed (docs/design/util-text.md SC8, SC9). Every
 * decision is made on this buffer; when a match might change with more text, the scanner reads
 * more and decides again. That makes the answers independent of how the source splits its reads,
 * except for the bounded searches util-text.md section 4 lists (a horizon that ends where the
 * text read so far ends, as with `\z`).
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
    private char[] chunk = new char[CHUNK];

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

    /** SC21a: the File constructors refuse a bad name with the name alone, before opening. */
    static Charset charsetNamed(String name) {
        Objects.requireNonNull(name, "charsetName");
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name);
        }
    }

    /** SC21a: the charset is known good before the file is opened, so a bad name leaks no stream. */
    static InputStreamReader open(File source, Charset cs) throws FileNotFoundException {
        return new InputStreamReader(new FileInputStream(source), cs);
    }

    static InputStreamReader open(Path source, Charset cs) throws IOException {
        return new InputStreamReader(Files.newInputStream(source), cs);
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
            if (c == '\n' || c == '\r' || c == ' ' || c == ' ' || c == '\u0085') {
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
     * Reads more from the source; false when the input has ended (SC9: a failure ends it). After
     * true, buffer indexes taken before the call are stale. Each call asks for at least as much
     * as is still unread, and keeps reading while a Reader says it will not block, so a decision
     * retried after every read costs linear time overall rather than quadratic.
     */
    boolean more() {
        if (ended) {
            return false;
        }
        int want = Math.max(CHUNK, buf.length() - pos);
        if (chunk.length < want) {
            chunk = new char[want];
        }
        int got = 0;
        while (got < want) {
            int n = readInto(got, want - got);
            if (n <= 0 || !ready()) {
                got += Math.max(n, 0);
                break;
            }
            got += n;
        }
        if (got == 0) {
            return !ended;
        }
        if (pos > COMPACT && pos > buf.length() / 2) {
            buf.delete(0, pos);
            base += pos;
            pos = 0;
        }
        buf.append(chunk, 0, got);
        return true;
    }

    /** One read into chunk[off, off + len); -1 marks the end of input. */
    private int readInto(int off, int len) {
        try {
            int n = source.read(CharBuffer.wrap(chunk, off, len));
            if (n < 0) {
                ended = true;
            }
            return n;
        } catch (IOException e) {
            failure = e;
            ended = true;
            return -1;
        }
    }

    private boolean ready() {
        try {
            return source instanceof Reader && ((Reader) source).ready();
        } catch (IOException e) {
            return false;
        }
    }
}
