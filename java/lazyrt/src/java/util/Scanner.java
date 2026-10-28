package java.util;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits text into tokens and lines and reads numbers from them (docs/design/util-text.md
 * section 1, SC1-SC23). All matching is done on ScannerInput's buffer; the number languages are
 * ScannerSyntax. LazyVM has no locale data, so every locale reads with the ROOT symbols.
 */
public final class Scanner implements Iterator<String>, Closeable {
    private static final Pattern WHITESPACE = Pattern.compile("\\p{javaWhitespace}+");
    /** SC5: a line with its terminator, or a last line without one. */
    private static final Pattern LINE = Pattern.compile(".*(\r\n|[\n\r\u2028\u2029\u0085])|.+$");

    private final ScannerInput in;
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

    public Scanner(InputStream source, String charsetName) {
        this(new InputStreamReader(Objects.requireNonNull(source), ScannerInput.charset(charsetName)));
    }

    public Scanner(File source) throws FileNotFoundException {
        this(new FileInputStream(source));
    }

    public Scanner(File source, String charsetName) throws FileNotFoundException {
        this(new InputStreamReader(new FileInputStream(source), ScannerInput.charset(charsetName)));
    }

    public Scanner(Path source) throws IOException {
        this(Files.newInputStream(source));
    }

    public Scanner(Path source, String charsetName) throws IOException {
        this(new InputStreamReader(Files.newInputStream(source), ScannerInput.charset(charsetName)));
    }

    public Scanner(String source) {
        this(new StringReader(Objects.requireNonNull(source)));
    }

    // Settings.

    public Pattern delimiter() { return delimiter; }

    public Scanner useDelimiter(Pattern pattern) {
        delimiter = pattern;
        delimiterMatcher = null;
        return this;
    }

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

    /** SC22. */
    public Scanner reset() {
        useDelimiter(WHITESPACE);
        locale = Locale.getDefault();
        radix = 10;
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
        if (closed) {
            return;
        }
        closed = true;
        in.close();
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

    /** The token for a hasNext call, which consumes nothing; match() is invalid until it agrees. */
    private int[] peek() {
        lineSeen = -1;
        return any();
    }

    /** SC7: hasNext() alone keeps what hasNextLine saw. */
    private int[] any() {
        ensureOpen();
        last = null;
        return token();
    }

    /** SC7: a hasNext call that says yes leaves the token as the match. */
    private boolean agree(int[] token) {
        last = ScannerMatch.token(text(token), in.at(token[0]));
        return true;
    }

    /** The token for a next call; with none, the delimiter is passed and the call fails. */
    private int[] require() {
        lineSeen = -1;
        int[] t = token();
        if (t == null) {
            in.pos = skipTo;
            last = null;
            throw new NoSuchElementException();
        }
        return t;
    }

    /** A token that a next call refuses stays, but the delimiter before it is passed. */
    private InputMismatchException mismatch(String message) {
        in.pos = skipTo;
        last = null;
        return new InputMismatchException(message);
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

    /** SC7: unlike the other hasNext calls, this one never leaves a match. */
    public boolean hasNext() { return any() != null; }

    public String next() { return take(require()); }

    /** SC21. */
    public void remove() { throw new UnsupportedOperationException(); }

    public boolean hasNext(String pattern) { return hasNext(Pattern.compile(pattern)); }

    public String next(String pattern) { return next(Pattern.compile(pattern)); }

    /** SC4: the whole token must match; the match is null when it does not. */
    private Matcher whole(Pattern pattern, boolean next) {
        Objects.requireNonNull(pattern);
        ensureOpen();
        last = null;
        lineSeen = -1;
        while (true) {
            int[] t = next ? require() : token();
            if (t == null) {
                return null;
            }
            Matcher m = in.matcher(pattern, t[0], t[1]);
            boolean ok = m.matches();
            if (ok && m.requireEnd() && in.more()) {
                continue; // an anchor matched at the end of what has been read so far
            }
            if (ok) {
                last = ScannerMatch.of(m, in.base);
            }
            return ok ? m : null;
        }
    }

    public boolean hasNext(Pattern pattern) { return whole(pattern, false) != null; }

    public String next(Pattern pattern) {
        Matcher m = whole(pattern, true);
        if (m == null) {
            throw mismatch(null);
        }
        in.pos = m.regionEnd();
        return m.group();
    }

    // Lines.

    /** SC5: the next line's matcher, or null when there is no further line. */
    private Matcher line() {
        ensureOpen();
        while (true) {
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

    // Searching and skipping (SC6).

    public String findInLine(String pattern) { return findInLine(Pattern.compile(pattern)); }

    /** Searches up to the next line terminator, which the match never includes. */
    public String findInLine(Pattern pattern) {
        Objects.requireNonNull(pattern);
        ensureOpen();
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
        Objects.requireNonNull(pattern);
        if (horizon < 0) {
            throw new IllegalArgumentException("horizon < 0");
        }
        ensureOpen();
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
        Objects.requireNonNull(pattern);
        ensureOpen();
        while (true) {
            Matcher m = in.matcher(pattern, in.pos, in.buf.length());
            boolean ok = m.lookingAt();
            if (m.hitEnd() && in.more()) {
                continue;
            }
            lineSeen = -1;
            if (!ok) {
                last = null;
                throw new NoSuchElementException();
            }
            last = ScannerMatch.of(m, in.base);
            in.pos = m.end();
            return this;
        }
    }

    // Numbers and booleans (SC10-SC19).

    private boolean hasInteger(int radix, int kind) {
        ScannerSyntax.checkDigits(radix);
        int[] t = peek();
        String text = t == null ? null : ScannerSyntax.integer(text(t), radix);
        if (text == null) {
            return false;
        }
        try {
            ScannerSyntax.parse(text, radix, kind);
            return agree(t);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** SC10, SC13: a wrong shape has no message; a parse failure carries the parser's. */
    private long nextInteger(int radix, int kind) {
        ScannerSyntax.checkDigits(radix);
        int[] t = require();
        String text = ScannerSyntax.integer(text(t), radix);
        if (text == null) {
            throw mismatch(null);
        }
        long v;
        try {
            v = ScannerSyntax.parse(text, radix, kind);
        } catch (NumberFormatException e) {
            throw mismatch(e.getMessage());
        }
        take(t);
        return v;
    }

    public boolean hasNextByte() { return hasInteger(radix, ScannerSyntax.BYTE); }
    public boolean hasNextByte(int radix) { return hasInteger(radix, ScannerSyntax.BYTE); }
    public byte nextByte() { return (byte) nextInteger(radix, ScannerSyntax.BYTE); }
    public byte nextByte(int radix) { return (byte) nextInteger(radix, ScannerSyntax.BYTE); }
    public boolean hasNextShort() { return hasInteger(radix, ScannerSyntax.SHORT); }
    public boolean hasNextShort(int radix) { return hasInteger(radix, ScannerSyntax.SHORT); }
    public short nextShort() { return (short) nextInteger(radix, ScannerSyntax.SHORT); }
    public short nextShort(int radix) { return (short) nextInteger(radix, ScannerSyntax.SHORT); }
    public boolean hasNextInt() { return hasInteger(radix, ScannerSyntax.INT); }
    public boolean hasNextInt(int radix) { return hasInteger(radix, ScannerSyntax.INT); }
    public int nextInt() { return (int) nextInteger(radix, ScannerSyntax.INT); }
    public int nextInt(int radix) { return (int) nextInteger(radix, ScannerSyntax.INT); }
    public boolean hasNextLong() { return hasInteger(radix, ScannerSyntax.LONG); }
    public boolean hasNextLong(int radix) { return hasInteger(radix, ScannerSyntax.LONG); }
    public long nextLong() { return nextInteger(radix, ScannerSyntax.LONG); }
    public long nextLong(int radix) { return nextInteger(radix, ScannerSyntax.LONG); }

    /** SC15-SC17: whether the token is a floating number that parses, as a double or a float. */
    private boolean hasFloating(boolean single) {
        int[] t = peek();
        String text = t == null ? null : ScannerSyntax.decimal(text(t));
        if (text == null) {
            return false;
        }
        try {
            ScannerSyntax.parseFloating(text, single);
            return agree(t);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private double nextFloating(boolean single) {
        int[] t = require();
        String text = ScannerSyntax.decimal(text(t));
        if (text == null) {
            throw mismatch(null);
        }
        double v;
        try {
            v = ScannerSyntax.parseFloating(text, single);
        } catch (NumberFormatException e) {
            throw mismatch(e.getMessage());
        }
        take(t);
        return v;
    }

    public boolean hasNextDouble() { return hasFloating(false); }
    public double nextDouble() { return nextFloating(false); }
    public boolean hasNextFloat() { return hasFloating(true); }
    public float nextFloat() { return (float) nextFloating(true); }

    public boolean hasNextBoolean() {
        int[] t = peek();
        return t != null && ScannerSyntax.isBoolean(text(t)) && agree(t);
    }

    /** SC18. */
    public boolean nextBoolean() {
        int[] t = require();
        if (!ScannerSyntax.isBoolean(text(t))) {
            throw mismatch(null);
        }
        return Boolean.parseBoolean(take(t));
    }

    /** SC23: the fields once the scanner has read nothing are fixed; later ones are free. */
    public String toString() {
        return "java.util.Scanner[delimiters=" + delimiter + "][position=" + in.at(in.pos)
            + "][match valid=" + (last != null) + ScannerSyntax.ROOT_FIELDS.replace("?", "" + in.ended);
    }
}
