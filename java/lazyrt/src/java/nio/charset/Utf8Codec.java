package java.nio.charset;

/** UTF-8, with the malformed units of io.md section 5 (rules U1-U5). */
final class Utf8Codec extends Codec {
    private static boolean cont(int b) {
        return (b & 0xC0) == 0x80;
    }

    int decode(byte[] src, int sp, int sl, char[] dst, int dp, int dl) {
        while (sp < sl) {
            int b0 = src[sp] & 0xff;
            int n = length(b0);
            if (n == 0) {
                return stop(sp, dp, 1);
            }
            // A bad byte ends the unit before it. Observed: when the whole sequence is there,
            // its last byte is judged only after the output is found to have room.
            int avail = Math.min(n, sl - sp);
            int early = avail < n ? avail : n - 1;
            for (int k = 1; k < early; k++) {
                if (!valid(b0, k, src[sp + k] & 0xff)) {
                    return stop(sp, dp, k);
                }
            }
            if (avail < n) {
                // Spec U5: a valid prefix cut by the end of the input waits for more.
                return stop(sp, dp, UNDERFLOW);
            }
            if (dl - dp < (n == 4 ? 2 : 1)) {
                return stop(sp, dp, OVERFLOW);
            }
            if (n > 1 && !valid(b0, n - 1, src[sp + n - 1] & 0xff)) {
                return stop(sp, dp, n - 1);
            }
            if (n == 1) {
                dst[dp++] = (char) b0;
            } else if (n == 2) {
                dst[dp++] = (char) (((b0 & 0x1F) << 6) | (src[sp + 1] & 0x3F));
            } else if (n == 3) {
                int c = ((b0 & 0x0F) << 12) | ((src[sp + 1] & 0x3F) << 6) | (src[sp + 2] & 0x3F);
                if (Character.isSurrogate((char) c)) {
                    // Spec U3: ED A0-BF xx encodes a surrogate, one unit of 3.
                    return stop(sp, dp, 3);
                }
                dst[dp++] = (char) c;
            } else {
                int cp = ((b0 & 0x07) << 18) | ((src[sp + 1] & 0x3F) << 12) | ((src[sp + 2] & 0x3F) << 6)
                        | (src[sp + 3] & 0x3F);
                dst[dp++] = Character.highSurrogate(cp);
                dst[dp++] = Character.lowSurrogate(cp);
            }
            sp += n;
        }
        return stop(sp, dp, UNDERFLOW);
    }

    /** Byte `k` (from 1) of a sequence led by `b0`: a continuation, in range for the second. */
    private static boolean valid(int b0, int k, int b) {
        return cont(b) && (k != 1 || secondOk(b0, b));
    }

    /** Spec U1-U4: the length a lead byte announces, 0 for one that cannot lead. */
    private static int length(int b0) {
        if (b0 < 0x80) {
            return 1;
        }
        if (b0 < 0xC2) {
            return 0;
        }
        if (b0 < 0xE0) {
            return 2;
        }
        if (b0 < 0xF0) {
            return 3;
        }
        return b0 < 0xF5 ? 4 : 0;
    }

    /**
     * Spec U3, U4: second bytes that make an overlong form or a value above
     * U+10FFFF end the unit at 1. Surrogates (ED A0-BF) are a whole unit of 3.
     */
    private static boolean secondOk(int b0, int b1) {
        switch (b0) {
            case 0xE0:
                return b1 >= 0xA0;
            case 0xF0:
                return b1 >= 0x90;
            case 0xF4:
                return b1 < 0x90;
            default:
                return true;
        }
    }

    int encode(char[] src, int sp, int sl, byte[] dst, int dp, int dl) {
        while (sp < sl) {
            char c = src[sp];
            int room = dl - dp;
            if (c < 0x80) {
                if (room < 1) {
                    return stop(sp, dp, OVERFLOW);
                }
                dst[dp++] = (byte) c;
                sp++;
            } else if (c < 0x800) {
                if (room < 2) {
                    return stop(sp, dp, OVERFLOW);
                }
                dst[dp++] = (byte) (0xC0 | (c >> 6));
                dst[dp++] = (byte) (0x80 | (c & 0x3F));
                sp++;
            } else if (!Character.isSurrogate(c)) {
                if (room < 3) {
                    return stop(sp, dp, OVERFLOW);
                }
                dst[dp++] = (byte) (0xE0 | (c >> 12));
                dst[dp++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                dst[dp++] = (byte) (0x80 | (c & 0x3F));
                sp++;
            } else {
                int cp = pair(src, sp, sl);
                if (cp == NEED_MORE) {
                    return stop(sp, dp, UNDERFLOW);
                }
                if (cp == LONE) {
                    return stop(sp, dp, 1);
                }
                if (room < 4) {
                    return stop(sp, dp, OVERFLOW);
                }
                dst[dp++] = (byte) (0xF0 | (cp >> 18));
                dst[dp++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                dst[dp++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                dst[dp++] = (byte) (0x80 | (cp & 0x3F));
                sp += 2;
            }
        }
        return stop(sp, dp, UNDERFLOW);
    }
}
