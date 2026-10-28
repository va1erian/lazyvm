package java.util;

/** Word-level routines behind BitSet: ranges, searches and extraction (util-data.md section 6). */
final class BitWords {
    private BitWords() {
    }

    static final int SET = 0;
    static final int CLEAR = 1;
    static final int FLIP = 2;

    /** The number of words up to the last non-zero one among the first n. */
    static int inUse(long[] words, int n) {
        while (n > 0 && words[n - 1] == 0) {
            n--;
        }
        return n;
    }

    /** Applies op to bits from..to - 1; the caller has made room for bit to - 1. */
    static void apply(long[] words, int from, int to, int op) {
        int first = from >> 6;
        int last = (to - 1) >> 6;
        for (int w = first; w <= last; w++) {
            long mask = -1L;
            if (w == first) {
                mask &= -1L << from;
            }
            if (w == last) {
                mask &= -1L >>> (63 - ((to - 1) & 63));
            }
            if (op == SET) {
                words[w] |= mask;
            } else if (op == CLEAR) {
                words[w] &= ~mask;
            } else {
                words[w] ^= mask;
            }
        }
    }

    /** Copies bits from..to - 1 of src into dst from bit 0; dst holds exactly to - from bits' words. */
    static void extract(long[] src, int from, int to, long[] dst) {
        int shift = from & 63;
        int base = from >> 6;
        for (int k = 0; k < dst.length; k++) {
            int s = base + k;
            long v = src[s] >>> shift;
            if (shift != 0 && s + 1 < src.length) {
                v |= src[s + 1] << (64 - shift);
            }
            dst[k] = v;
        }
        int bits = to - from;
        if ((bits & 63) != 0) {
            dst[dst.length - 1] &= -1L >>> (64 - (bits & 63));
        }
    }

    /** The first set (or clear) bit at or after from; for clear bits, at most 64 * words.length. */
    static int next(long[] words, int from, boolean clear) {
        int w = from >> 6;
        if (w >= words.length) {
            return clear ? from : -1;
        }
        long word = (clear ? ~words[w] : words[w]) & (-1L << from);
        while (true) {
            if (word != 0) {
                return 64 * w + Long.numberOfTrailingZeros(word);
            }
            if (++w == words.length) {
                return clear ? 64 * w : -1;
            }
            word = clear ? ~words[w] : words[w];
        }
    }

    /** The last set (or clear) bit at or before from, or -1; from is at least -1. */
    static int previous(long[] words, int from, boolean clear) {
        if (from < 0) {
            return -1;
        }
        int w = from >> 6;
        if (w >= words.length) {
            return clear ? from : inUse(words, words.length) == 0 ? -1 : lastSet(words);
        }
        long word = (clear ? ~words[w] : words[w]) & (-1L >>> (63 - (from & 63)));
        while (true) {
            if (word != 0) {
                return 64 * w + 63 - Long.numberOfLeadingZeros(word);
            }
            if (w-- == 0) {
                return -1;
            }
            word = clear ? ~words[w] : words[w];
        }
    }

    private static int lastSet(long[] words) {
        int n = inUse(words, words.length);
        return 64 * n - 1 - Long.numberOfLeadingZeros(words[n - 1]);
    }
}
