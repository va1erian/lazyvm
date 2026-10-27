package java.nio.charset;

/**
 * The one place each standard charset is coded: array to array, one step
 * at a time. CharsetDecoder/Encoder (and through them String, the readers
 * and the writers) drive these. A codec is made per coder, since UTF-16 keeps
 * byte-order state.
 *
 * A step reads src[sp, sl) and writes dst[dp, dl), leaves the new positions
 * in {@link #in} and {@link #out}, and returns UNDERFLOW (all input taken,
 * or a valid prefix waits for more), OVERFLOW (the next char does not fit),
 * a malformed length n (> 0), or n | UNMAPPABLE. On an error the positions
 * are at the start of the bad unit.
 */
abstract class Codec {
    static final int UNDERFLOW = 0;
    static final int OVERFLOW = -1;
    static final int UNMAPPABLE = 1 << 16;

    int in;
    int out;
    /** Whether the coder runs on the buffers' own arrays (an observed difference for ISO-8859-1). */
    boolean arrays;

    int decode(byte[] src, int sp, int sl, char[] dst, int dp, int dl) {
        throw new UnsupportedOperationException();
    }

    int encode(char[] src, int sp, int sl, byte[] dst, int dp, int dl) {
        throw new UnsupportedOperationException();
    }

    /** Back to the start of a stream (UTF-16's byte order mark). */
    void reset() {
    }

    final int stop(int sp, int dp, int result) {
        in = sp;
        out = dp;
        return result;
    }

    static final int NEED_MORE = -1;
    static final int LONE = -2;

    /**
     * At a surrogate src[i]: the code point of a valid pair, NEED_MORE for a
     * high surrogate that ends the input (Spec K2: it waits, unconsumed), or
     * LONE for an unpaired one (Spec K2: MALFORMED[1]).
     */
    static int pair(char[] src, int i, int sl) {
        char c = src[i];
        if (Character.isLowSurrogate(c)) {
            return LONE;
        }
        if (i + 1 >= sl) {
            return NEED_MORE;
        }
        char d = src[i + 1];
        return Character.isLowSurrogate(d) ? Character.toCodePoint(c, d) : LONE;
    }

    /** US-ASCII and ISO-8859-1: one byte per char. */
    static final class Narrow extends Codec {
        private final int max;

        Narrow(int max) {
            this.max = max;
        }

        // Spec C1: US-ASCII calls 80-FF malformed; ISO-8859-1 maps every byte.
        int decode(byte[] src, int sp, int sl, char[] dst, int dp, int dl) {
            while (sp < sl) {
                int b = src[sp] & 0xff;
                if (b > max) {
                    return stop(sp, dp, 1);
                }
                if (dp >= dl) {
                    return stop(sp, dp, OVERFLOW);
                }
                dst[dp++] = (char) b;
                sp++;
            }
            return stop(sp, dp, UNDERFLOW);
        }

        // Spec K2: an unmappable char is UNMAPPABLE[1], a pair UNMAPPABLE[2].
        int encode(char[] src, int sp, int sl, byte[] dst, int dp, int dl) {
            while (sp < sl) {
                char c = src[sp];
                // Observed: on arrays ISO-8859-1 reports a full output before the char after it.
                if (arrays && max == 0xFF && dp >= dl) {
                    return stop(sp, dp, OVERFLOW);
                }
                if (c <= max) {
                    if (dp >= dl) {
                        return stop(sp, dp, OVERFLOW);
                    }
                    dst[dp++] = (byte) c;
                    sp++;
                    continue;
                }
                if (!Character.isSurrogate(c)) {
                    return stop(sp, dp, 1 | UNMAPPABLE);
                }
                int cp = pair(src, sp, sl);
                if (cp == NEED_MORE) {
                    return stop(sp, dp, UNDERFLOW);
                }
                return stop(sp, dp, cp == LONE ? 1 : 2 | UNMAPPABLE);
            }
            return stop(sp, dp, UNDERFLOW);
        }
    }
}
