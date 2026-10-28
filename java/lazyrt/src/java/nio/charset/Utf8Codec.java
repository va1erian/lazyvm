package java.nio.charset;

/** UTF-8, with the malformed units of io.md section 5 (rules U1-U5). */
final class Utf8Codec extends Codec {
    private static boolean cont(int b) {
        return (b & 0xC0) == 0x80;
    }

    int decode(byte[] src, int sp, int sl, char[] dst, int dp, int dl) {
        while (sp < sl) {
            // ASCII runs first, a byte per char, with no unit checks.
            int run = sp + Math.min(sl - sp, dl - dp);
            while (sp < run && src[sp] >= 0) {
                dst[dp++] = (char) src[sp++];
            }
            if (sp >= sl) {
                break;
            }
            int b0 = src[sp] & 0xff;
            // Whole valid 2- and 3-byte sequences with room take a short path; the general
            // one below judges everything else in the order the rules give.
            if (b0 >= 0xC2 && b0 < 0xF0 && dp < dl) {
                if (b0 < 0xE0 && sp + 1 < sl && (src[sp + 1] & 0xC0) == 0x80) {
                    dst[dp++] = (char) (((b0 & 0x1F) << 6) | (src[sp + 1] & 0x3F));
                    sp += 2;
                    continue;
                }
                if (b0 >= 0xE0 && sp + 2 < sl) {
                    int b1 = src[sp + 1] & 0xff;
                    int c = ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (src[sp + 2] & 0x3F);
                    if ((b1 & 0xC0) == 0x80 && (src[sp + 2] & 0xC0) == 0x80 && (b0 != 0xE0 || b1 >= 0xA0)
                            && (c < 0xD800 || c > 0xDFFF)) {
                        dst[dp++] = (char) c;
                        sp += 3;
                        continue;
                    }
                }
            }
            int n = length(b0);
            int bad = n == 0 ? 1 : 0;
            if (n > 0) {
                // A bad byte ends the unit before it. Observed: when the whole sequence is there,
                // its last byte is judged only after the output is found to have room.
                int avail = sl - sp < n ? sl - sp : n;
                int early = avail < n ? avail : n - 1;
                for (int k = 1; k < early && bad == 0; k++) {
                    if (!valid(b0, k, src[sp + k] & 0xff)) {
                        bad = k;
                    }
                }
                if (bad == 0) {
                    if (avail < n) {
                        // Spec U5: a valid prefix cut by the end of the input waits for more.
                        return stop(sp, dp, UNDERFLOW);
                    }
                    if (dl - dp < (n == 4 ? 2 : 1)) {
                        return stop(sp, dp, OVERFLOW);
                    }
                    if (n > 1 && !valid(b0, n - 1, src[sp + n - 1] & 0xff)) {
                        bad = n - 1;
                    } else {
                        dp = put(src, sp, n, b0, dst, dp);
                        if (dp >= 0) {
                            sp += n;
                            continue;
                        }
                        // Spec U3: ED A0-BF xx encodes a surrogate, one unit of 3.
                        dp = -dp - 1;
                        bad = 3;
                    }
                }
            }
            if (fill == null || dl - dp < fill.length) {
                return stop(sp, dp, bad);
            }
            System.arraycopy(fill, 0, dst, dp, fill.length);
            dp += fill.length;
            sp += bad;
        }
        return stop(sp, dp, UNDERFLOW);
    }

    /** Writes the char(s) of a valid sequence: the new `dp`, or `-dp - 1` for a surrogate. */
    private static int put(byte[] src, int sp, int n, int b0, char[] dst, int dp) {
        if (n == 1) {
            dst[dp] = (char) b0;
            return dp + 1;
        }
        if (n == 2) {
            dst[dp] = (char) (((b0 & 0x1F) << 6) | (src[sp + 1] & 0x3F));
            return dp + 1;
        }
        if (n == 3) {
            int c = ((b0 & 0x0F) << 12) | ((src[sp + 1] & 0x3F) << 6) | (src[sp + 2] & 0x3F);
            if (c >= 0xD800 && c <= 0xDFFF) {
                return -dp - 1;
            }
            dst[dp] = (char) c;
            return dp + 1;
        }
        int cp = ((b0 & 0x07) << 18) | ((src[sp + 1] & 0x3F) << 12) | ((src[sp + 2] & 0x3F) << 6)
                | (src[sp + 3] & 0x3F);
        dst[dp] = (char) ((cp >>> 10) + 0xD7C0);
        dst[dp + 1] = (char) ((cp & 0x3FF) + 0xDC00);
        return dp + 2;
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
            int run = sp + Math.min(sl - sp, dl - dp);
            while (sp < run && src[sp] < 0x80) {
                dst[dp++] = (byte) src[sp++];
            }
            if (sp >= sl) {
                break;
            }
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
