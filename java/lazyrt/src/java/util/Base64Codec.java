package java.util;

/**
 * The alphabets and the coding loops shared by Base64's encoders, decoders and streams. The loops
 * keep everything they need in locals and handle whole units inline, because an interpreter pays
 * for every field read and call per byte.
 */
final class Base64Codec {
    private Base64Codec() {
    }

    private static final byte[] BASIC = alphabet('+', '/');
    private static final byte[] URL = alphabet('-', '_');
    private static final int[] BASIC_VALUES = values(BASIC);
    private static final int[] URL_VALUES = values(URL);

    private static byte[] alphabet(char c62, char c63) {
        byte[] a = new byte[64];
        for (int i = 0; i < 26; i++) {
            a[i] = (byte) ('A' + i);
            a[26 + i] = (byte) ('a' + i);
        }
        for (int i = 0; i < 10; i++) {
            a[52 + i] = (byte) ('0' + i);
        }
        a[62] = (byte) c62;
        a[63] = (byte) c63;
        return a;
    }

    private static int[] values(byte[] alphabet) {
        int[] v = new int[256];
        Arrays.fill(v, -1);
        for (int i = 0; i < 64; i++) {
            v[alphabet[i]] = i;
        }
        return v;
    }

    /** Byte (0-255) to its 6-bit value; -1 for every byte outside the alphabet, '=' included. */
    static int[] table(boolean url) { return url ? URL_VALUES : BASIC_VALUES; }

    /** A computed array size, or the error Java gives for arrays it cannot make. */
    static int toInt(long size) {
        if (size > Integer.MAX_VALUE - 8) {
            throw new OutOfMemoryError("Base64 output too large for an array");
        }
        return (int) size;
    }

    /**
     * Encodes `groups` whole 3-byte groups of src from sp into dst at dp and returns the new dp.
     * column[0] carries the characters on the current line between calls: a separator goes in only
     * when a new line gets its first character (B3, B14), and since line lengths are multiples of 4
     * that moment always falls between two groups.
     */
    static int encodeGroups(byte[] src, int sp, int groups, byte[] dst, int dp, Base64.Encoder e,
                            int[] column) {
        byte[] alpha = e.url() ? URL : BASIC;
        byte[] sep = e.separator();
        int line = e.lineLength();
        int col = column[0];
        for (int end = sp + 3 * groups; sp < end; sp += 3) {
            if (sep != null && col == line) {
                for (byte s : sep) {
                    dst[dp++] = s;
                }
                col = 0;
            }
            int bits = (src[sp] & 0xff) << 16 | (src[sp + 1] & 0xff) << 8 | (src[sp + 2] & 0xff);
            dst[dp] = alpha[bits >>> 18];
            dst[dp + 1] = alpha[(bits >>> 12) & 63];
            dst[dp + 2] = alpha[(bits >>> 6) & 63];
            dst[dp + 3] = alpha[bits & 63];
            dp += 4;
            col += 4;
        }
        column[0] = col;
        return dp;
    }

    /** The last 1 or 2 bytes: count + 1 characters, then '=' padding unless withoutPadding (B1, B2). */
    static int encodeTail(byte[] src, int sp, int count, byte[] dst, int dp, Base64.Encoder e, int[] column) {
        byte[] alpha = e.url() ? URL : BASIC;
        byte[] sep = e.separator();
        if (sep != null && column[0] == e.lineLength()) {
            for (byte s : sep) {
                dst[dp++] = s;
            }
            column[0] = 0;
        }
        int bits = (src[sp] & 0xff) << 16 | (count > 1 ? (src[sp + 1] & 0xff) << 8 : 0);
        dst[dp++] = alpha[bits >>> 18];
        dst[dp++] = alpha[(bits >>> 12) & 63];
        if (count > 1) {
            dst[dp++] = alpha[(bits >>> 6) & 63];
        }
        if (e.pad()) {
            for (int k = count; k < 3; k++) {
                dst[dp++] = '=';
            }
        }
        column[0] += 4;
        return dp;
    }

    /** B8-B12 on arrays: decodes src into dst and returns the number of bytes written. */
    static int decode(byte[] src, byte[] dst, boolean url, boolean mime) {
        int[] t = table(url);
        int n = src.length;
        int bits = 0;
        int p = 0;
        int d = 0;
        int i = 0;
        while (i < n) {
            if (p == 0 && i + 4 <= n) {
                // A whole unit of alphabet bytes, the usual case, in one step.
                int v0 = t[src[i] & 0xff];
                int v1 = t[src[i + 1] & 0xff];
                int v2 = t[src[i + 2] & 0xff];
                int v3 = t[src[i + 3] & 0xff];
                if ((v0 | v1 | v2 | v3) >= 0) {
                    int u = v0 << 18 | v1 << 12 | v2 << 6 | v3;
                    dst[d] = (byte) (u >> 16);
                    dst[d + 1] = (byte) (u >> 8);
                    dst[d + 2] = (byte) u;
                    d += 3;
                    i += 4;
                    continue;
                }
            }
            byte b = src[i];
            int v = t[b & 0xff];
            if (v < 0) {
                if (b == '=') {
                    d = padded(src, i, p, bits, dst, d);
                    return afterPadding(src, i + (p == 2 ? 2 : 1), mime, d);
                }
                if (!mime) {
                    throw new IllegalArgumentException("Illegal base64 character " + Integer.toString(b, 16));
                }
                i++; // B8: MIME skips it
                continue;
            }
            i++;
            bits = bits << 6 | v;
            if (++p == 4) {
                d = unit(bits, 3, dst, d);
                bits = 0;
                p = 0;
            }
        }
        if (p == 1) {
            throw new IllegalArgumentException("Last unit does not have enough valid bits");
        }
        return p == 0 ? d : unit(bits << (6 * (4 - p)), p - 1, dst, d);
    }

    /** B10: the unit that a '=' at index i ends, with p characters in it. */
    private static int padded(byte[] src, int i, int p, int bits, byte[] dst, int d) {
        if (p == 0 || p == 2 && (i + 1 >= src.length || src[i + 1] != '=')) {
            throw new IllegalArgumentException("Input byte array has wrong 4-byte ending unit");
        }
        if (p == 1) {
            throw new IllegalArgumentException("Last unit does not have enough valid bits");
        }
        return unit(bits << (6 * (4 - p)), p - 1, dst, d);
    }

    /**
     * B11: what may follow the padding, from index i. The basic and URL decoders refuse any byte;
     * MIME skips everything but an alphabet byte, and reports that byte's index plus one.
     */
    private static int afterPadding(byte[] src, int i, boolean mime, int d) {
        int[] t = BASIC_VALUES;
        for (int j = i; j < src.length; j++) {
            if (!mime) {
                throw new IllegalArgumentException("Input byte array has incorrect ending byte at " + j);
            }
            if (t[src[j] & 0xff] >= 0) {
                throw new IllegalArgumentException("Input byte array has incorrect ending byte at " + (j + 1));
            }
        }
        return d;
    }

    /** The top `count` bytes of a 24-bit unit. */
    static int unit(int bits, int count, byte[] dst, int d) {
        for (int k = 0; k < count; k++) {
            dst[d++] = (byte) (bits >> (16 - 8 * k));
        }
        return d;
    }
}
