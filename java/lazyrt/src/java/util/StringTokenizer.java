package java.util;

/** Splits a string at delimiter code points (docs/design/util-text.md section 3, T1-T7). */
public class StringTokenizer implements Enumeration<Object> {
    /** Chars copied from the text per refill of the window: enough to amortize the copy call. */
    private static final int WINDOW = 256;

    private final String str;
    private final int length;
    private final boolean returnDelims;
    /** T7: the delimiter string read as code points, so a pair is one delimiter. Null with it. */
    private int[] delimiterPoints;
    /** The largest delimiter, so most text chars are rejected with one comparison. */
    private int maxDelimiter;
    /** Whether the scans may compare chars rather than code points (setDelimiters). */
    private boolean charDelimiters;
    private int position;
    /**
     * A copy of text chars windowStart..windowEnd - 1, made only as the scans reach them. Indexing
     * it costs an interpreter far less than a charAt call per char, and nothing is copied or
     * scanned up front, so reading the first token of a long text stays cheap.
     */
    private char[] window;
    private int windowStart;
    private int windowEnd;

    public StringTokenizer(String str, String delim, boolean returnDelims) {
        this.str = str;
        this.length = str.length(); // T6: a null string fails here
        this.returnDelims = returnDelims;
        setDelimiters(delim);
    }

    public StringTokenizer(String str, String delim) { this(str, delim, false); }

    /** T1: space, tab, newline, carriage return and form feed only. */
    public StringTokenizer(String str) { this(str, " \t\n\r\f", false); }

    /**
     * Also decides charDelimiters: T7 compares code points, but when no delimiter is a surrogate
     * or a supplementary code point, no surrogate char in the text (paired or lone) can equal one,
     * so reading a pair as two chars gives the same tokens and the scans may compare chars.
     */
    private void setDelimiters(String delim) {
        delimiterPoints = delim == null ? null : codePoints(delim);
        maxDelimiter = -1;
        charDelimiters = true;
        if (delimiterPoints != null) {
            for (int d : delimiterPoints) {
                maxDelimiter = Math.max(maxDelimiter, d);
                if (d > Character.MAX_VALUE || d >= Character.MIN_SURROGATE && d <= Character.MAX_SURROGATE) {
                    charDelimiters = false;
                }
            }
        }
    }

    private static int[] codePoints(String s) {
        int[] points = new int[s.codePointCount(0, s.length())];
        for (int i = 0, k = 0; i < s.length(); k++) {
            points[k] = s.codePointAt(i);
            i += Character.charCount(points[k]);
        }
        return points;
    }

    /** T6: null delimiters fail only when a scan needs them. */
    private int[] points() {
        if (delimiterPoints == null) {
            throw new NullPointerException();
        }
        return delimiterPoints;
    }

    private static boolean contains(int[] points, int codePoint) {
        for (int d : points) {
            if (d == codePoint) {
                return true;
            }
        }
        return false;
    }

    /** Copies the chars from pos (below length) into the window; the loops call it only at its edge. */
    private void fill(int pos) {
        if (window == null) {
            window = new char[WINDOW];
        }
        windowStart = pos;
        windowEnd = Math.min(length, pos + WINDOW);
        str.getChars(windowStart, windowEnd, window, 0);
    }

    /**
     * From pos, the end of the run of delimiters (when `delimiters`) or of non-delimiters. T7
     * compares code points; charDelimiters says the chars alone decide (see setDelimiters).
     */
    private int run(int pos, boolean delimiters) {
        int[] d = points();
        int max = maxDelimiter;
        if (!charDelimiters) {
            while (pos < length) {
                int cp = str.codePointAt(pos);
                if ((cp <= max && contains(d, cp)) != delimiters) {
                    break;
                }
                pos += Character.charCount(cp);
            }
            return pos;
        }
        if (pos < length && (pos < windowStart || pos >= windowEnd)) {
            fill(pos);
        }
        char[] w = window;
        int ws = windowStart;
        int we = windowEnd;
        while (pos < length) {
            if (pos == we) {
                fill(pos);
                ws = windowStart;
                we = windowEnd;
            }
            int c = w[pos - ws];
            if ((c <= max && contains(d, c)) != delimiters) {
                break;
            }
            pos++;
        }
        return pos;
    }

    /** T2: past the delimiters before a token, unless they are tokens themselves. */
    private int skipDelimiters(int pos) {
        if (returnDelims || pos >= length) {
            return pos; // T6: countTokens on an empty string needs no delimiters
        }
        return run(pos, true);
    }

    /** T2: the end of the token at pos: a run of text, or one returned delimiter. */
    private int scanToken(int pos) {
        int end = run(pos, false);
        if (end == pos && returnDelims) {
            return pos + Character.charCount(str.codePointAt(pos)); // T7: a pair is one token
        }
        return end;
    }

    public boolean hasMoreTokens() {
        points();
        return skipDelimiters(position) < length;
    }

    /** T5: a call that finds no token has still moved past the delimiters it read. */
    public String nextToken() {
        points();
        position = skipDelimiters(position);
        if (position >= length) {
            throw new NoSuchElementException();
        }
        int start = position;
        position = scanToken(start);
        return str.substring(start, position);
    }

    /** T3: the new delimiters stay for later calls, even when this one fails. */
    public String nextToken(String delim) {
        setDelimiters(delim);
        return nextToken();
    }

    public boolean hasMoreElements() { return hasMoreTokens(); }

    public Object nextElement() { return nextToken(); }

    /** T4: from the current position, without moving. */
    public int countTokens() {
        int count = 0;
        int pos = position;
        while (true) {
            pos = skipDelimiters(pos);
            if (pos >= length) {
                return count;
            }
            pos = scanToken(pos);
            count++;
        }
    }
}
