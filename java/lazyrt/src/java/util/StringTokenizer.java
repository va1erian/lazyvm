package java.util;

/** Splits a string at delimiter code points (docs/design/util-text.md section 3, T1-T7). */
public class StringTokenizer implements Enumeration<Object> {
    private final String str;
    private final int length;
    private final boolean returnDelims;
    /**
     * The text's chars when it has no surrogates, else null. Then every code point is one char and
     * no supplementary or lone-surrogate delimiter can match (T7), so the scans index this array
     * instead of decoding code points through calls, which an interpreter makes slow.
     */
    private final char[] plain;
    /** T7: the delimiter string read as code points, so a pair is one delimiter. Null with it. */
    private int[] delimiterPoints;
    /** The largest delimiter, so most text chars are rejected with one comparison. */
    private int maxDelimiter;
    private int position;

    public StringTokenizer(String str, String delim, boolean returnDelims) {
        this.str = str;
        this.length = str.length(); // T6: a null string fails here
        this.returnDelims = returnDelims;
        this.plain = plainChars(str);
        setDelimiters(delim);
    }

    public StringTokenizer(String str, String delim) { this(str, delim, false); }

    /** T1: space, tab, newline, carriage return and form feed only. */
    public StringTokenizer(String str) { this(str, " \t\n\r\f", false); }

    /** The chars of s, or null when one of them is a surrogate. */
    private static char[] plainChars(String s) {
        char[] chars = s.toCharArray();
        for (char c : chars) {
            if (c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE) {
                return null;
            }
        }
        return chars;
    }

    private void setDelimiters(String delim) {
        delimiterPoints = delim == null ? null : codePoints(delim);
        maxDelimiter = -1;
        if (delimiterPoints != null) {
            for (int d : delimiterPoints) {
                maxDelimiter = Math.max(maxDelimiter, d);
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

    /** T2: past the delimiters before a token, unless they are tokens themselves. */
    private int skipDelimiters(int pos) {
        if (returnDelims || pos >= length) {
            return pos; // T6: countTokens on an empty string needs no delimiters
        }
        int[] d = points();
        char[] text = plain;
        if (text != null) {
            int max = maxDelimiter;
            while (pos < length) {
                int c = text[pos];
                if (c > max || !contains(d, c)) {
                    break;
                }
                pos++;
            }
            return pos;
        }
        while (pos < length) {
            int cp = str.codePointAt(pos);
            if (!contains(d, cp)) {
                break;
            }
            pos += Character.charCount(cp);
        }
        return pos;
    }

    /** T2: the end of the token at pos: a run of text, or one returned delimiter. */
    private int scanToken(int pos) {
        int[] d = points();
        int start = pos;
        char[] text = plain;
        if (text != null) {
            int max = maxDelimiter;
            while (pos < length) {
                int c = text[pos];
                if (c <= max && contains(d, c)) {
                    return returnDelims && pos == start ? pos + 1 : pos;
                }
                pos++;
            }
            return pos;
        }
        while (pos < length) {
            int cp = str.codePointAt(pos);
            if (contains(d, cp)) {
                if (returnDelims && pos == start) {
                    pos += Character.charCount(cp);
                }
                break;
            }
            pos += Character.charCount(cp);
        }
        return pos;
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
