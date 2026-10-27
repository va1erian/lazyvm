package java.nio.charset;

/**
 * UTF-16 in 2-byte units (Spec C2). The "UTF-16" flavour reads a byte order
 * mark at the very start (big-endian without one) and writes FE FF before
 * the first char it is given; UTF-16BE and UTF-16LE have a fixed order.
 */
final class Utf16Codec extends Codec {
    private final boolean marked;
    private final boolean fixedBigEndian;
    private boolean bigEndian;
    private boolean atStart = true;

    Utf16Codec(boolean marked, boolean bigEndian) {
        this.marked = marked;
        this.fixedBigEndian = bigEndian;
        this.bigEndian = bigEndian;
    }

    void reset() {
        atStart = true;
        bigEndian = fixedBigEndian;
    }

    private int unit(byte[] src, int i) {
        int a = src[i] & 0xff;
        int b = src[i + 1] & 0xff;
        return bigEndian ? (a << 8) | b : (b << 8) | a;
    }

    int decode(byte[] src, int sp, int sl, char[] dst, int dp, int dl) {
        if (marked && atStart) {
            if (sl - sp < 2) {
                return stop(sp, dp, UNDERFLOW);
            }
            atStart = false;
            int u = ((src[sp] & 0xff) << 8) | (src[sp + 1] & 0xff);
            if (u == 0xFEFF || u == 0xFFFE) {
                bigEndian = u == 0xFEFF;
                sp += 2;
            }
        }
        while (sl - sp >= 2) {
            int u = unit(src, sp);
            // Spec C2: a unit valued U+FFFE (a reversed mark) is malformed in every flavour.
            if (u == 0xFFFE || Character.isLowSurrogate((char) u)) {
                return stop(sp, dp, 2);
            }
            if (!Character.isHighSurrogate((char) u)) {
                if (dp >= dl) {
                    return stop(sp, dp, OVERFLOW);
                }
                dst[dp++] = (char) u;
                sp += 2;
                continue;
            }
            if (sl - sp < 4) {
                return stop(sp, dp, UNDERFLOW);
            }
            int v = unit(src, sp + 2);
            if (!Character.isLowSurrogate((char) v)) {
                // Spec C2: a high surrogate and the unit after it are one unit of 4.
                return stop(sp, dp, 4);
            }
            if (dl - dp < 2) {
                return stop(sp, dp, OVERFLOW);
            }
            dst[dp++] = (char) u;
            dst[dp++] = (char) v;
            sp += 4;
        }
        return stop(sp, dp, UNDERFLOW);
    }

    private void put(byte[] dst, int dp, char c) {
        dst[dp] = (byte) (bigEndian ? c >> 8 : c);
        dst[dp + 1] = (byte) (bigEndian ? c : c >> 8);
    }

    int encode(char[] src, int sp, int sl, byte[] dst, int dp, int dl) {
        if (marked && atStart && sp < sl) {
            // Spec R3, E1: the mark goes out with the first char, so "" has no bytes.
            if (dl - dp < 2) {
                return stop(sp, dp, OVERFLOW);
            }
            atStart = false;
            put(dst, dp, '﻿');
            dp += 2;
        }
        while (sp < sl) {
            char c = src[sp];
            if (!Character.isSurrogate(c)) {
                if (dl - dp < 2) {
                    return stop(sp, dp, OVERFLOW);
                }
                put(dst, dp, c);
                dp += 2;
                sp++;
                continue;
            }
            int cp = pair(src, sp, sl);
            if (cp == NEED_MORE) {
                return stop(sp, dp, UNDERFLOW);
            }
            if (cp == LONE) {
                return stop(sp, dp, 1);
            }
            if (dl - dp < 4) {
                return stop(sp, dp, OVERFLOW);
            }
            put(dst, dp, c);
            put(dst, dp + 2, src[sp + 1]);
            dp += 4;
            sp += 2;
        }
        return stop(sp, dp, UNDERFLOW);
    }
}
