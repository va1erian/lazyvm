package java.util;

/** The alphabets and the array coders shared by Base64's encoders, decoders and streams. */
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

    /** The 6-bit value of an alphabet byte, or -1 for any other byte ('=' included). */
    static int value(byte b, boolean url) { return (url ? URL_VALUES : BASIC_VALUES)[b & 0xff]; }

    static byte letter(int sixBits, boolean url) { return (url ? URL : BASIC)[sixBits & 63]; }

    /** A computed array size, or the error Java gives for arrays it cannot make. */
    static int toInt(long size) {
        if (size > Integer.MAX_VALUE - 8) {
            throw new OutOfMemoryError("Base64 output too large for an array");
        }
        return (int) size;
    }

    /** Writes encoded characters into an array, breaking lines as B3 and B14 describe. */
    static final class Sink {
        private final Base64.Encoder encoder;
        private final byte[] dst;
        private int length;
        private int column;

        Sink(Base64.Encoder encoder, byte[] dst, int column) {
            this.encoder = encoder;
            this.dst = dst;
            this.column = column;
        }

        int length() { return length; }

        /** Characters on the current line, carried from one stream group to the next. */
        int column() { return column; }

        /** One group of `count` (1 to 3) bytes: 4 characters, or fewer when unpadded (B1, B2). */
        void group(byte a, byte b, byte c, int count) {
            int bits = (a & 0xff) << 16 | (b & 0xff) << 8 | (c & 0xff);
            boolean url = encoder.url();
            for (int k = 0; k <= count; k++) {
                put(letter(bits >> (18 - 6 * k), url));
            }
            if (encoder.pad()) {
                for (int k = count; k < 3; k++) {
                    put((byte) '=');
                }
            }
        }

        /** B3, B14: a separator only when a new line gets its first character. */
        private void put(byte ch) {
            byte[] sep = encoder.separator();
            if (sep != null && column == encoder.lineLength()) {
                System.arraycopy(sep, 0, dst, length, sep.length);
                length += sep.length;
                column = 0;
            }
            dst[length++] = ch;
            column++;
        }
    }

    /** B8-B12 on arrays: decodes src into dst and returns the number of bytes written. */
    static int decode(byte[] src, byte[] dst, boolean url, boolean mime) {
        int bits = 0;
        int p = 0;
        int d = 0;
        int i = 0;
        int n = src.length;
        while (i < n) {
            byte b = src[i];
            if (b == '=') {
                d = padded(src, i, p, bits, dst, d);
                return afterPadding(src, i + (p == 2 ? 2 : 1), mime, d);
            }
            int v = value(b, url);
            i++;
            if (v < 0) {
                if (!mime) {
                    throw new IllegalArgumentException("Illegal base64 character " + Integer.toString(b, 16));
                }
                continue;
            }
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

    /** B11: what may follow the padding, from index i. */
    private static int afterPadding(byte[] src, int i, boolean mime, int d) {
        for (int j = i; j < src.length; j++) {
            if (!mime) {
                throw new IllegalArgumentException("Input byte array has incorrect ending byte at " + j);
            }
            if (value(src[j], false) >= 0) {
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
