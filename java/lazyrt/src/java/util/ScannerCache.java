package java.util;

/**
 * What a Scanner's hasNext calls remember for the next call (docs/design/util-text.md SC24). A
 * pattern hasNext that says yes keeps its token for a next of the same pattern, and a typed one
 * keeps its token and value for a next of the same type (SC24 has the radix rule); either is
 * used only at the position it was found at, whatever the delimiter has become since. Positions are input
 * positions, so dropping read text from the buffer does not disturb them.
 */
final class ScannerCache {
    private Object patternKey;
    private long patternAt;
    private long patternStart;
    private long patternEnd;

    private int typeKey = -1;
    private long typeAt;
    private long typeStart;
    private long typeEnd;
    private Number value;
    /** The radix of the last typed call (10 for the floating ones). */
    private int lastRadix = 10;

    /**
     * SC19, SC24: every typed call starts here; the radix is checked first. Returns whether a
     * remembered typed value may be used: always for the floating readers, and for an integer
     * reader when its radix is the radix of the previous typed call, whatever came between.
     */
    boolean reader(int kind, int radix) {
        ScannerSyntax.checkDigits(kind, radix);
        boolean usable = kind >= ScannerSyntax.FLOAT || radix == lastRadix;
        lastRadix = radix;
        return usable;
    }

    /** Both kinds forgotten: by hasNextLine, a find, a skip, reset and every next call. */
    void forget() {
        patternKey = null;
        typeKey = -1;
    }

    void forgetPattern() {
        patternKey = null;
    }

    /**
     * `key` is the pattern's source for the String forms, the Pattern itself otherwise, and for a
     * typed reader its ScannerSyntax.shapeKey, which never equals either.
     */
    void putPattern(Object key, long at, long start, long end) {
        patternKey = key;
        patternAt = at;
        patternStart = start;
        patternEnd = end;
    }

    /** The remembered token {start, end} for `key` at `at`, or null. */
    long[] pattern(Object key, long at) {
        return key.equals(patternKey) && at == patternAt ? new long[] {patternStart, patternEnd} : null;
    }

    /** What a typed hasNext parsed; its shape is remembered as a pattern under its own key. */
    void putType(int key, long at, long start, long end, Number parsed) {
        typeKey = key;
        typeAt = at;
        typeStart = start;
        typeEnd = end;
        value = parsed;
    }

    /**
     * The remembered token {start, end} for a typed reader of kind `key` at `at`, or null; the
     * caller has asked reader() whether the radix allows it.
     */
    long[] type(int key, long at) {
        return key == typeKey && at == typeAt ? new long[] {typeStart, typeEnd} : null;
    }

    Number value() {
        return value;
    }
}
