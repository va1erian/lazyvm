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

    private final ScannerInput in;
    private final ScannerTokens tokens;
    private final ScannerCache cache = new ScannerCache();
    private Locale locale = Locale.getDefault(Locale.Category.FORMAT);
    private int radix = 10;
    private boolean closed;
    /** The result match() gives; null when there is none (SC7). */
    private MatchResult last;
    /** Where hasNextLine last said yes, or -1; a nextLine there keeps match() as it is (SC7). */
    private long lineSeen = -1;

    public Scanner(Readable source) {
        in = new ScannerInput(Objects.requireNonNull(source, "source"));
        tokens = new ScannerTokens(in);
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

    public Pattern delimiter() { return tokens.delimiter; }

    public Scanner useDelimiter(Pattern pattern) { tokens.useDelimiter(pattern); return this; }

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

    /** SC22; SC7, SC24: it also forgets what hasNextLine saw and what hasNext calls remembered. */
    public Scanner reset() {
        useDelimiter(ScannerTokens.WHITESPACE);
        locale = Locale.getDefault(Locale.Category.FORMAT);
        radix = 10;
        cache.forget();
        lineSeen = -1;
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

    private int[] token() {
        ensureOpen();
        return tokens.token();
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
            in.pos = tokens.skipTo;
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
        in.pos = tokens.skipTo;
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
        return tokens.line();
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
        return found(tokens.findInLine(Objects.requireNonNull(pattern)));
    }

    /** A search's outcome: on success match() gives it and the scanner passes it. */
    private String found(Matcher m) {
        lineSeen = -1;
        cache.forget();
        if (m == null) {
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
        return found(tokens.findWithinHorizon(pattern, horizon));
    }

    public Scanner skip(String pattern) { return skip(Pattern.compile(pattern)); }

    /** Matches at the current position, ignoring delimiters. */
    public Scanner skip(Pattern pattern) {
        ensureOpen();
        if (found(tokens.skip(Objects.requireNonNull(pattern))) == null) {
            throw new NoSuchElementException();
        }
        return this;
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
        boolean usable = cache.reader(kind, radix);
        ensureOpen();
        int[] kept = usable ? remembered(cache.type(kind, in.at(in.pos))) : null;
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
        return "java.util.Scanner[delimiters=" + tokens.delimiter + "][position=" + in.at(in.pos) + "][match valid="
            + (last != null) + ScannerSyntax.ROOT_FIELDS.replace("?", "" + (closed || in.ended));
    }
}
