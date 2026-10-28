package java.util;

/**
 * What a Scanner's hasNext calls remember for the next call (docs/design/util-text.md SC24). A
 * pattern hasNext that says yes keeps its token for a next of the same pattern, and a typed one
 * keeps its token and value for a next of the same type and radix; either is used only at the
 * position it was found at, whatever the delimiter has become since. Positions are input
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
    /** The radix the last typed reader read in (10 for the floating ones). */
    private int integerRadix = 10;

    /**
     * SC19, SC24: every typed reader starts here. The radix is checked first; a reader whose
     * radix differs from the last typed reader's (the floating ones read in 10) forgets the typed
     * memory, even when its token then turns out to have the wrong shape.
     */
    void reader(int kind, int radix) {
        ScannerSyntax.checkDigits(kind, radix);
        if (radix != integerRadix) {
            typeKey = -1;
            integerRadix = radix;
        }
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
     * The remembered token {start, end} for a typed reader of kind `key` at `at`, or null. The
     * radix needs no check: reader() forgets the memory whenever the radix changes.
     */
    long[] type(int key, long at) {
        return key == typeKey && at == typeAt ? new long[] {typeStart, typeEnd} : null;
    }

    Number value() {
        return value;
    }
}
