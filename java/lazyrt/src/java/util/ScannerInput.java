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
 * except for the cases util-text.md section 4 lists.
 */
final class ScannerInput {
    private static final int CHUNK = 1024;
    /** Passed text is dropped from the front only in large pieces, to keep the copying linear. */
    private static final int COMPACT = 8192;
    /** SC2b: passed text kept before pos when compacting, for lookbehind (util-text.md section 4). */
    private static final int MARGIN = 8192;
    /** Below this much unread text a decision is retried after every read (see more). */
    private static final int SMALL = 4096;
    /** How far before the text read since the last decision a Probe looks. */
    private static final int WINDOW = 128;

    /** Whether the decision that asked for more input could now be settled: see more. */
    interface Probe {
        boolean settled(int from, int to);
    }

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
    /** The input position up to which lineEnd found no terminator. */
    private long lineScanned;

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
     * SC2b: every match sees the text around its region (lookaround reaches past it, into the
     * kept margin) but anchors only at the true ends of the input. A region never starts at the
     * buffer's first char once text has been dropped (pos keeps the margin before it), so `^` and
     * `\A` cannot match at that false start except through a lookbehind (section 4).
     */
    Matcher matcher(Pattern p, int from, int to) {
        return p.matcher(buf).useTransparentBounds(true).useAnchoringBounds(false).region(from, to);
    }

    /**
     * The index of the first line terminator at or after pos, or -1 if the buffer has none. It
     * resumes where the last unsuccessful call stopped, so waiting for a long line is linear.
     */
    int lineEnd() {
        for (int i = (int) Math.max(pos, lineScanned - base); i < buf.length(); i++) {
            char c = buf.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == ' ' || c == '\u0085') {
                return i;
            }
        }
        lineScanned = at(buf.length());
        return -1;
    }

    /** The input position of buffer index i. */
    long at(int i) {
        return base + i;
    }

    /**
     * Reads more for a decision that the text so far cannot settle; false when the input has
     * ended (SC9: a failure ends it). After true, buffer indexes taken before the call are stale.
     *
     * <p>A decision costs time in proportion to the unread text, so retrying it after every read
     * would be quadratic when a source hands out a long token in small pieces. Reads therefore go
     * on, before the decision is retried, until one of three things holds: the unread text is
     * still small (under SMALL chars, so an interactive line is answered at once); it has doubled
     * since the decision was last made (so the retries cost linear time in all); or the probe,
     * run only on the new text and a short window before it, says the decision may now be
     * settled (a delimiter or line end arrived). The probe costs linear time too, and it is what
     * keeps a source that blocks from being read further than it must: an interactive user who
     * ends a long token with a delimiter gets the answer without typing more. With no probe, the
     * decision is retried after every read.
     */
    boolean more(Probe probe) {
        if (ended) {
            return false;
        }
        long unreadBefore = buf.length() - pos;
        long probed = at(buf.length());
        boolean appended = false;
        while (true) {
            int n = readSome();
            if (n < 0) {
                return appended; // after text was added, the caller must look once more
            }
            appended |= n > 0;
            if (ended) {
                return appended; // SC9: never read again once the source has ended or failed
            }
            int unread = buf.length() - pos;
            if (probe == null || unread < SMALL || unread >= 2 * unreadBefore) {
                return true;
            }
            int from = (int) Math.max(pos, probed - base - WINDOW);
            if (n > 0 && probe.settled(from, buf.length())) {
                return true;
            }
            probed = at(buf.length());
        }
    }

    /**
     * One read, continued while a Reader says it will not block, of at least as much as is
     * unread; the count appended, or -1 at the end of input.
     */
    private int readSome() {
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
            return ended ? -1 : 0;
        }
        if (pos > COMPACT + MARGIN && pos > buf.length() / 2) {
            int drop = pos - MARGIN;
            buf.delete(0, drop);
            base += drop;
            pos -= drop;
        }
        buf.append(chunk, 0, got);
        return got;
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
