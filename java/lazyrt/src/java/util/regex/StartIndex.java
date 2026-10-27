package java.util.regex;

/**
 * For one matcher and one class: the positions of the text whose code point is in the class, as
 * a bit set, so that a lookbehind can jump back to the previous position it could begin at
 * instead of testing every one (see GroupNodes.Look). The text is read once, when the index is
 * first needed; it is dropped when the matcher gets another input.
 */
final class StartIndex {
    private final long[] bits;

    StartIndex(Matcher m, CharClass cc) {
        bits = new long[(m.textLength >> 6) + 1];
        for (int q = 0; q < m.textLength; q++) {
            if (cc.test(Atoms.codePointAt(m, q))) {
                bits[q >> 6] |= 1L << (q & 63);
            }
        }
    }

    /** The largest position at or before p that is in the class, or -1. */
    int previous(int p) {
        if (p < 0) {
            return -1;
        }
        int w = p >> 6;
        long word = bits[w] & (-1L >>> (63 - (p & 63)));
        while (word == 0) {
            if (--w < 0) {
                return -1;
            }
            word = bits[w];
        }
        return (w << 6) + 63 - Long.numberOfLeadingZeros(word);
    }
}
