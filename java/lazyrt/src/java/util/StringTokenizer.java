package java.util;

/** Splits a string at delimiter code points (docs/design/util-text.md section 3, T1-T7). */
public class StringTokenizer implements Enumeration<Object> {
    private final String str;
    private final boolean returnDelims;
    /** T7: the delimiter string read as code points, so a pair is one delimiter. Null with it. */
    private int[] delimiterPoints;
    private int position;

    public StringTokenizer(String str, String delim, boolean returnDelims) {
        this.str = str;
        this.returnDelims = returnDelims;
        setDelimiters(delim);
        str.length(); // T6: a null string fails here
    }

    public StringTokenizer(String str, String delim) { this(str, delim, false); }

    /** T1: space, tab, newline, carriage return and form feed only. */
    public StringTokenizer(String str) { this(str, " \t\n\r\f", false); }

    private void setDelimiters(String delim) {
        delimiterPoints = delim == null ? null : codePoints(delim);
    }

    private static int[] codePoints(String s) {
        int[] points = new int[s.codePointCount(0, s.length())];
        for (int i = 0, k = 0; i < s.length(); k++) {
            points[k] = s.codePointAt(i);
            i += Character.charCount(points[k]);
        }
        return points;
    }

    private int[] points() {
        if (delimiterPoints == null) {
            throw new NullPointerException(); // T6
        }
        return delimiterPoints;
    }

    private boolean isDelimiter(int codePoint) {
        for (int d : points()) {
            if (d == codePoint) {
                return true;
            }
        }
        return false;
    }

    /** T2: past the delimiters before a token, unless they are tokens themselves. */
    private int skipDelimiters(int pos) {
        while (!returnDelims && pos < str.length()) {
            int cp = str.codePointAt(pos);
            if (!isDelimiter(cp)) {
                break;
            }
            pos += Character.charCount(cp);
        }
        return pos;
    }

    /** T2: the end of the token at pos: a run of text, or one returned delimiter. */
    private int scanToken(int pos) {
        int start = pos;
        while (pos < str.length()) {
            int cp = str.codePointAt(pos);
            if (isDelimiter(cp)) {
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
        return skipDelimiters(position) < str.length();
    }

    public String nextToken() {
        points();
        int start = skipDelimiters(position);
        if (start >= str.length()) {
            throw new NoSuchElementException(); // T5
        }
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
            if (pos >= str.length()) {
                return count;
            }
            pos = scanToken(pos);
            count++;
        }
    }
}
