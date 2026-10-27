package java.io;

/**
 * The byte layouts DataInput and DataOutput share (io.md D1-D4): big-endian
 * numbers, modified UTF-8 with its 2-byte length, and readLine's Latin-1
 * lines. Used by the Data streams and RandomAccessFile alike.
 */
final class DataFormat {
    private DataFormat() {
    }

    /** The low `n` bytes of `v`, high byte first, into b[0, n). */
    static void putBits(byte[] b, long v, int n) {
        for (int i = n - 1; i >= 0; i--) {
            b[i] = (byte) v;
            v >>>= 8;
        }
    }

    /** b[0, n) as an unsigned big-endian number. */
    static long bits(byte[] b, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            v = (v << 8) | (b[i] & 0xff);
        }
        return v;
    }

    /** Spec D1: writeBytes keeps each char's low byte. */
    static byte[] lowBytes(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) s.charAt(i);
        }
        return b;
    }

    static byte[] chars(String s) {
        byte[] b = new byte[2 * s.length()];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b[2 * i] = (byte) (c >> 8);
            b[2 * i + 1] = (byte) c;
        }
        return b;
    }

    /**
     * Spec D2: the 2-byte length then U+0000 as C0 80, 0001-007F in one byte,
     * up to 07FF in two, the rest (surrogates one by one) in three. Too long
     * is an error before anything is written.
     */
    static byte[] modifiedUtf8(String s) throws UTFDataFormatException {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            n += utfLength(s.charAt(i));
        }
        if (n > 65535) {
            throw new UTFDataFormatException("encoded string too long: " + n + " bytes");
        }
        byte[] b = new byte[(int) n + 2];
        b[0] = (byte) (n >> 8);
        b[1] = (byte) n;
        int p = 2;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int len = utfLength(c);
            if (len == 1) {
                b[p++] = (byte) c;
            } else if (len == 2) {
                b[p++] = (byte) (0xC0 | (c >> 6));
                b[p++] = (byte) (0x80 | (c & 0x3F));
            } else {
                b[p++] = (byte) (0xE0 | (c >> 12));
                b[p++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                b[p++] = (byte) (0x80 | (c & 0x3F));
            }
        }
        return b;
    }

    private static int utfLength(char c) {
        if (c >= 0x0001 && c <= 0x007F) {
            return 1;
        }
        return c <= 0x07FF ? 2 : 3;
    }

    /**
     * Spec D3: decodes `len` bytes of modified UTF-8. Any 2-byte form is
     * accepted; a unit that runs past the end is checked before its bytes.
     */
    static String fromModifiedUtf8(byte[] b, int len) throws UTFDataFormatException {
        char[] out = new char[len];
        int n = 0;
        int i = 0;
        while (i < len) {
            int c = b[i] & 0xff;
            int size = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : 0;
            if (size == 0) {
                throw malformed(i);
            }
            if (i + size > len) {
                throw new UTFDataFormatException("malformed input: partial character at end");
            }
            if (size == 1) {
                out[n++] = (char) c;
            } else if (size == 2) {
                int c2 = b[i + 1] & 0xff;
                if ((c2 & 0xC0) != 0x80) {
                    throw malformed(i + 2);
                }
                out[n++] = (char) (((c & 0x1F) << 6) | (c2 & 0x3F));
            } else {
                int c2 = b[i + 1] & 0xff;
                int c3 = b[i + 2] & 0xff;
                if ((c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80) {
                    throw malformed(i + 2);
                }
                out[n++] = (char) (((c & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F));
            }
            i += size;
        }
        return new String(out, 0, n);
    }

    private static UTFDataFormatException malformed(int at) {
        return new UTFDataFormatException("malformed input around byte " + at);
    }

    /** Where readLine reads its bytes, with one byte of look-back after a '\r'. */
    interface LineSource {
        int read() throws IOException;

        void unread() throws IOException;
    }

    // Spec FS4, D4: bytes as Latin-1 up to '\n', '\r' or "\r\n"; null at the end.
    static String readLine(LineSource in) throws IOException {
        StringBuilder sb = null;
        while (true) {
            int c = in.read();
            if (c < 0) {
                return sb == null ? null : sb.toString();
            }
            if (sb == null) {
                sb = new StringBuilder();
            }
            if (c == '\n') {
                return sb.toString();
            }
            if (c == '\r') {
                int d = in.read();
                if (d >= 0 && d != '\n') {
                    in.unread();
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
    }
}
