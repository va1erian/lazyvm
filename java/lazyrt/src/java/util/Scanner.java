package java.util;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits text into tokens and lines and reads numbers from them (docs/design/util-text.md
 * section 1, SC1-SC24). All matching is done on ScannerInput's buffer; the number languages are
 * ScannerSyntax, and what hasNext calls remember is ScannerCache. LazyVM has no locale data, so
 * every locale reads with the ROOT symbols.
 */
public final class Scanner implements Iterator<String>, Closeable {
    private static final Pattern WHITESPACE = Pattern.compile("\\p{javaWhitespace}+");
    /** SC5: a line with its terminator, or a last line without one. */
    private static final Pattern LINE = Pattern.compile(".*(\r\n|[\n\r  \u0085])|.+$");

    private final ScannerInput in;
    private final ScannerCache cache = new ScannerCache();
    private Pattern delimiter = WHITESPACE;
    private Matcher delimiterMatcher;
    private Locale locale = Locale.getDefault();
    private int radix = 10;
    private boolean closed;
    /** The result match() gives; null when there is none (SC7). */
    private MatchResult last;
    /** Where hasNextLine last said yes, or -1; a nextLine there keeps match() as it is (SC7). */
    private long lineSeen = -1;
    /** Where the delimiter before the token that token() last looked at ends. */
    private int skipTo;

    public Scanner(Readable source) {
        in = new ScannerInput(Objects.requireNonNull(source, "source"));
    }

    public Scanner(InputStream source) {
        this(new InputStreamReader(Objects.requireNonNull(source)));
    }

    /** SC21a: a null stream is refused before the charset name is looked at. */
    public Scanner(InputStream source, String charsetName) {
        this(new InputStreamReader(Objects.requireNonNull(source, "source"), ScannerInput.charset(charsetName)));
    }

    public Scanner(File source) throws FileNotFoundException {
        this(ScannerInput.open(source, Charset.defaultCharset()));
    }

    public Scanner(File source, String charsetName) throws FileNotFoundException {
        this(ScannerInput.open(Objects.requireNonNull(source), ScannerInput.charsetNamed(charsetName)));
    }

    public Scanner(Path source) throws IOException {
        this(ScannerInput.open(source, Charset.defaultCharset()));
    }

    public Scanner(Path source, String charsetName) throws IOException {
        this(ScannerInput.open(Objects.requireNonNull(source), ScannerInput.charset(charsetName)));
    }

    public Scanner(String source) {
        this(new StringReader(Objects.requireNonNull(source)));
    }

    public Pattern delimiter() { return delimiter; }

    public Scanner useDelimiter(Pattern pattern) { delimiter = pattern; delimiterMatcher = null; return this; }

    public Scanner useDelimiter(String pattern) { return useDelimiter(Pattern.compile(pattern)); }

    public Locale locale() { return locale; }

    public Scanner useLocale(Locale locale) { this.locale = Objects.requireNonNull(locale); return this; }

    public int radix() { return radix; }

    public Scanner useRadix(int radix) {
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            throw new IllegalArgumentException("radix:" + radix);
        }
        this.radix = radix;
        return this;
    }

    /** SC22; SC24: it also forgets what hasNext calls remembered. */
    public Scanner reset() {
        useDelimiter(WHITESPACE);
        locale = Locale.getDefault();
        radix = 10;
        cache.forget();
        return this;
    }

    public MatchResult match() {
        if (last == null) {
            throw new IllegalStateException("No match result available");
        }
        return last;
    }

    public IOException ioException() { return in.failure; }

    /** SC20. */
    public void close() {
        if (!closed) {
            in.close();
        }
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Scanner closed");
        }
    }

    // Tokens.

    /**
     * SC2: the next token's [start, end) in the buffer, or null when there is none. Either way
     * `skipTo` is where the delimiter before it ends, which a failing next call passes.
     */
    private int[] token() {
        ensureOpen();
        while (true) {
            StringBuilder buf = in.buf;
            int len = buf.length();
            if (delimiterMatcher == null) {
                delimiterMatcher = in.matcher(delimiter, 0, len);
            }
            Matcher d = delimiterMatcher.region(in.pos, len);
            boolean skipped = d.lookingAt();
            if (d.hitEnd() && in.more()) {
                continue;
            }
            int start = skipped ? d.end() : in.pos;
            if (start == len && in.more()) {
                continue;
            }
            skipTo = start;
            if (start == len) {
                return null;
            }
            d.region(start, len);
            boolean found = false;
            while (d.find()) {
                if (d.end() > start) {
                    found = true;
                    break;
                }
            }
            if ((!found || d.requireEnd()) && in.more()) {
                continue;
            }
            return new int[] {start, found ? d.start() : len};
        }
    }

    /** The token for a hasNext call, which consumes nothing and keeps no older match (SC7). */
    private int[] peek(boolean keepLine) {
        ensureOpen();
        last = null;
        lineSeen = keepLine ? lineSeen : -1; // SC7: hasNext() alone keeps what hasNextLine saw
        return token();
    }

    /** SC7: the token as the match, for a hasNext that says yes or a parse that fails (SC7a). */
    private boolean show(int[] token) {
        last = ScannerMatch.token(text(token), in.at(token[0]));
        return true;
    }

    /** The token for a next call; with none, the delimiter is passed and the call fails. */
    private int[] require() {
        lineSeen = -1;
        cache.forget();
        int[] t = token();
        if (t == null) {
            in.pos = skipTo;
            last = null;
            throw new NoSuchElementException();
        }
        return t;
    }

    /**
     * A token that a next call refuses stays, but the delimiter before it is passed. A token of
     * the right shape that fails to parse stays the match (SC7a); any other refusal clears it.
     */
    private InputMismatchException mismatch(int[] token, String parseError) {
        in.pos = skipTo;
        last = null;
        if (parseError != null) {
            show(token);
        }
        return new InputMismatchException(parseError);
    }

    private String text(int[] token) {
        return in.buf.substring(token[0], token[1]);
    }

    /** Passes a token; match() then gives it whole (SC7). */
    private String take(int[] token) {
        String t = text(token);
        last = ScannerMatch.token(t, in.at(token[0]));
        in.pos = token[1];
        return t;
    }

    private int[] remembered(long[] span) { // SC24: a remembered token as buffer indexes
        return span == null ? null : new int[] {(int) (span[0] - in.base), (int) (span[1] - in.base)};
    }

    public boolean hasNext() { return peek(true) != null; }

    public String next() { return take(require()); }

    /** SC21. */
    public void remove() { throw new UnsupportedOperationException(); }

    public boolean hasNext(String pattern) { return whole(Pattern.compile(pattern), pattern, false) != null; }

    public String next(String pattern) { return next(Pattern.compile(pattern), pattern); }

    public boolean hasNext(Pattern pattern) { return whole(pattern, pattern, false) != null; }

    public String next(Pattern pattern) { return next(pattern, pattern); }

    /**
     * SC4: the whole token must match; the matcher, or null when it does not. SC24: `key` names
     * the pattern for what hasNext remembers.
     */
    private Matcher whole(Pattern pattern, Object key, boolean next) {
        ensureOpen();
        Objects.requireNonNull(pattern);
        lineSeen = -1;
        int[] t = next ? remembered(cache.pattern(key, in.at(in.pos))) : null;
        if (t != null) {
            cache.forget(); // SC24: the remembered token, and match() stays as it was
            Matcher m = in.matcher(pattern, t[0], t[1]);
            return m.matches() ? m : null;
        }
        last = null;
        t = next ? require() : token();
        if (t == null) {
            cache.forgetPattern();
            return null;
        }
        // Decided on the text read so far, as the oracle does: a lookahead past the token that
        // meets the end of the buffer is not retried (util-text.md section 4).
        Matcher m = in.matcher(pattern, t[0], t[1]);
        boolean ok = m.matches();
        if (next) {
            cache.forget();
        } else if (ok) {
            cache.putPattern(key, in.at(in.pos), in.at(t[0]), in.at(t[1]));
        } else {
            cache.forgetPattern(); // a typed hasNext's memory survives a pattern's
        }
        last = ok ? ScannerMatch.of(m, in.base) : null;
        return ok ? m : null;
    }

    private String next(Pattern pattern, Object key) {
        Matcher m = whole(pattern, key, true);
        if (m == null) {
            throw mismatch(null, null);
        }
        in.pos = m.regionEnd();
        return m.group();
    }

    // Lines.

    /** SC5: the next line's matcher, or null when there is no further line. */
    private Matcher line() {
        ensureOpen();
        cache.forget();
        while (true) {
            int stop = in.lineEnd(); // a plain scan first, so the regex runs once per line
            if ((stop < 0 || stop == in.buf.length() - 1 && in.buf.charAt(stop) == '\r') && in.more()) {
                continue;
            }
            Matcher m = in.matcher(LINE, in.pos, in.buf.length());
            boolean ok = m.lookingAt();
            if (m.hitEnd() && in.more()) {
                continue;
            }
            return ok ? m : null;
        }
    }

    /** SC7: a next line leaves itself as the match. */
    public boolean hasNextLine() {
        Matcher m = line();
        last = m == null ? null : ScannerMatch.of(m, in.base);
        lineSeen = m == null ? -1 : in.at(in.pos);
        return m != null;
    }

    public String nextLine() {
        Matcher m = line();
        if (m == null) {
            last = null;
            throw new NoSuchElementException("No line found");
        }
        if (lineSeen != in.at(in.pos)) {
            last = ScannerMatch.of(m, in.base);
        }
        lineSeen = -1;
        in.pos = m.end();
        String all = m.group();
        String end = m.group(1);
        return end == null ? all : all.substring(0, all.length() - end.length());
    }

    // Searching and skipping (SC6). A closed scanner says so before any argument is checked.

    public String findInLine(String pattern) { return findInLine(Pattern.compile(pattern)); }

    /** Searches up to the next line terminator, which the match never includes. */
    public String findInLine(Pattern pattern) {
        ensureOpen();
        Objects.requireNonNull(pattern);
        while (true) {
            int stop = in.lineEnd();
            if (stop < 0 && in.more()) {
                continue;
            }
            stop = stop < 0 ? in.buf.length() : stop;
            if (stop == in.pos) {
                return found(null, false); // an empty line has nothing to find, not even ""
            }
            Matcher m = in.matcher(pattern, in.pos, stop);
            boolean ok = m.find();
            if (ok && m.requireEnd() && in.more()) {
                continue;
            }
            return found(m, ok);
        }
    }

    /** A search's outcome: on success match() gives it and the scanner passes it. */
    private String found(Matcher m, boolean ok) {
        lineSeen = -1;
        cache.forget();
        if (!ok) {
            last = null;
            return null;
        }
        last = ScannerMatch.of(m, in.base);
        in.pos = m.end();
        return m.group();
    }

    public String findWithinHorizon(String pattern, int horizon) {
        return findWithinHorizon(Pattern.compile(pattern), horizon);
    }

    /** Searches the next `horizon` chars, or the rest of the input for 0. */
    public String findWithinHorizon(Pattern pattern, int horizon) {
        ensureOpen();
        Objects.requireNonNull(pattern);
        if (horizon < 0) {
            throw new IllegalArgumentException("horizon < 0");
        }
        while (true) {
            int len = in.buf.length();
            boolean bounded = horizon > 0 && (long) in.pos + horizon <= len;
            int stop = bounded ? in.pos + horizon : len;
            Matcher m = in.matcher(pattern, in.pos, stop);
            boolean ok = m.find();
            if ((!bounded && m.hitEnd() || ok && m.requireEnd()) && in.more()) {
                continue;
            }
            return found(m, ok);
        }
    }

    public Scanner skip(String pattern) { return skip(Pattern.compile(pattern)); }

    /** Matches at the current position, ignoring delimiters. */
    public Scanner skip(Pattern pattern) {
        ensureOpen();
        Objects.requireNonNull(pattern);
        while (true) {
            Matcher m = in.matcher(pattern, in.pos, in.buf.length());
            boolean ok = m.lookingAt();
            if (m.hitEnd() && in.more()) {
                continue;
            }
            if (found(m, ok) == null && !ok) {
                throw new NoSuchElementException();
            }
            return this;
        }
    }

    // Numbers and booleans (SC10-SC19). A typed reader's SC24 key is its kind and radix.

    private boolean hasNumber(int kind, int radix) {
        cache.reader(kind, radix);
        int[] t = peek(false);
        String text = t == null ? null : ScannerSyntax.shape(text(t), kind, radix);
        if (text == null) {
            cache.forgetPattern(); // SC24: only a parse that succeeds replaces the typed memory
            return false;
        }
        cache.putPattern(ScannerSyntax.shapeKey(kind, radix), in.at(in.pos), in.at(t[0]), in.at(t[1]));
        try {
            Number v = ScannerSyntax.parse(text, kind, radix);
            cache.putType(kind, in.at(in.pos), in.at(t[0]), in.at(t[1]), v);
            return show(t);
        } catch (NumberFormatException e) {
            show(t); // SC7a
            return false;
        }
    }

    /** SC10, SC13: a wrong shape has no message; a parse failure carries the parser's. */
    private Number nextNumber(int kind, int radix) {
        cache.reader(kind, radix);
        ensureOpen();
        int[] kept = remembered(cache.type(kind, in.at(in.pos)));
        if (kept != null) {
            lineSeen = -1;
            cache.forget();
            in.pos = kept[1]; // SC24: match() stays as the calls before left it
            return cache.value();
        }
        int[] t = require();
        String text = ScannerSyntax.shape(text(t), kind, radix);
        if (text == null) {
            throw mismatch(t, null);
        }
        Number v;
        try {
            v = ScannerSyntax.parse(text, kind, radix);
        } catch (NumberFormatException e) {
            throw mismatch(t, e.getMessage());
        }
        take(t);
        return v;
    }

    public boolean hasNextByte() { return hasNumber(ScannerSyntax.BYTE, radix); }
    public boolean hasNextByte(int radix) { return hasNumber(ScannerSyntax.BYTE, radix); }
    public byte nextByte() { return nextNumber(ScannerSyntax.BYTE, radix).byteValue(); }
    public byte nextByte(int radix) { return nextNumber(ScannerSyntax.BYTE, radix).byteValue(); }
    public boolean hasNextShort() { return hasNumber(ScannerSyntax.SHORT, radix); }
    public boolean hasNextShort(int radix) { return hasNumber(ScannerSyntax.SHORT, radix); }
    public short nextShort() { return nextNumber(ScannerSyntax.SHORT, radix).shortValue(); }
    public short nextShort(int radix) { return nextNumber(ScannerSyntax.SHORT, radix).shortValue(); }
    public boolean hasNextInt() { return hasNumber(ScannerSyntax.INT, radix); }
    public boolean hasNextInt(int radix) { return hasNumber(ScannerSyntax.INT, radix); }
    public int nextInt() { return nextNumber(ScannerSyntax.INT, radix).intValue(); }
    public int nextInt(int radix) { return nextNumber(ScannerSyntax.INT, radix).intValue(); }
    public boolean hasNextLong() { return hasNumber(ScannerSyntax.LONG, radix); }
    public boolean hasNextLong(int radix) { return hasNumber(ScannerSyntax.LONG, radix); }
    public long nextLong() { return nextNumber(ScannerSyntax.LONG, radix).longValue(); }
    public long nextLong(int radix) { return nextNumber(ScannerSyntax.LONG, radix).longValue(); }
    public boolean hasNextFloat() { return hasNumber(ScannerSyntax.FLOAT, 10); }
    public float nextFloat() { return nextNumber(ScannerSyntax.FLOAT, 10).floatValue(); }
    public boolean hasNextDouble() { return hasNumber(ScannerSyntax.DOUBLE, 10); }
    public double nextDouble() { return nextNumber(ScannerSyntax.DOUBLE, 10).doubleValue(); }

    /** SC24: booleans are never remembered, but asking forgets a remembered pattern. */
    public boolean hasNextBoolean() {
        int[] t = peek(false);
        cache.forgetPattern();
        return t != null && ScannerSyntax.isBoolean(text(t)) && show(t);
    }

    /** SC18. */
    public boolean nextBoolean() {
        int[] t = require();
        if (!ScannerSyntax.isBoolean(text(t))) {
            throw mismatch(t, null);
        }
        return Boolean.parseBoolean(take(t));
    }

    /** SC23: the fields while nothing has been read are fixed (a closed source shows true). */
    public String toString() {
        return "java.util.Scanner[delimiters=" + delimiter + "][position=" + in.at(in.pos) + "][match valid="
            + (last != null) + ScannerSyntax.ROOT_FIELDS.replace("?", "" + (closed || in.ended));
    }
}
