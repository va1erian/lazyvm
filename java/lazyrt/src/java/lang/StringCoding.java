package java.lang;

import java.io.UnsupportedEncodingException;

/** Charset conversions for String: UTF-8, ISO-8859-1 and US-ASCII only. */
final class StringCoding {
    private static final int UTF8 = 0;
    private static final int LATIN1 = 1;
    private static final int ASCII = 2;

    private StringCoding() {
    }

    private static int charset(String name) throws UnsupportedEncodingException {
        if (name == null) {
            throw new NullPointerException("charsetName");
        }
        if (name.equalsIgnoreCase("UTF-8") || name.equalsIgnoreCase("UTF8")) {
            return UTF8;
        }
        if (name.equalsIgnoreCase("ISO-8859-1") || name.equalsIgnoreCase("ISO8859_1")
                || name.equalsIgnoreCase("LATIN1")) {
            return LATIN1;
        }
        if (name.equalsIgnoreCase("US-ASCII") || name.equalsIgnoreCase("ASCII")) {
            return ASCII;
        }
        throw new UnsupportedEncodingException(name);
    }

    static boolean isUtf8(String name) {
        return name != null && (name.equalsIgnoreCase("UTF-8") || name.equalsIgnoreCase("UTF8"));
    }

    static byte[] encode(char[] v, int off, int len, String charsetName)
            throws UnsupportedEncodingException {
        int cs = charset(charsetName);
        return cs == UTF8 ? encodeUtf8(v, off, len) : encodeSingle(v, off, len, cs == LATIN1 ? 255 : 127);
    }

    static char[] decode(byte[] b, int off, int len, String charsetName)
            throws UnsupportedEncodingException {
        int cs = charset(charsetName);
        return cs == UTF8 ? decodeUtf8(b, off, len) : decodeSingle(b, off, len, cs == LATIN1);
    }

    private static byte[] encodeSingle(char[] v, int off, int len, int max) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            char c = v[off + i];
            out[i] = (byte) (c <= max ? c : '?');
        }
        return out;
    }

    private static char[] decodeSingle(byte[] b, int off, int len, boolean latin1) {
        char[] out = new char[len];
        for (int i = 0; i < len; i++) {
            int x = b[off + i] & 0xff;
            out[i] = (!latin1 && x > 127) ? '�' : (char) x;
        }
        return out;
    }

    /** UTF-8 with surrogate pairs as 4 bytes and unpaired surrogates as '?'. */
    static byte[] encodeUtf8(char[] v, int off, int len) {
        byte[] tmp = new byte[len * 3];
        int n = 0;
        int end = off + len;
        for (int i = off; i < end; i++) {
            int c = v[i];
            if (c < 0x80) {
                tmp[n++] = (byte) c;
            } else if (c < 0x800) {
                tmp[n++] = (byte) (0xC0 | (c >> 6));
                tmp[n++] = (byte) (0x80 | (c & 0x3F));
            } else if (c >= 0xD800 && c <= 0xDFFF) {
                if (c <= 0xDBFF && i + 1 < end && v[i + 1] >= 0xDC00 && v[i + 1] <= 0xDFFF) {
                    int cp = 0x10000 + ((c - 0xD800) << 10) + (v[i + 1] - 0xDC00);
                    i++;
                    // 4 bytes for 2 chars: fits the len*3 budget.
                    tmp[n++] = (byte) (0xF0 | (cp >> 18));
                    tmp[n++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                    tmp[n++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                    tmp[n++] = (byte) (0x80 | (cp & 0x3F));
                } else {
                    tmp[n++] = (byte) '?';
                }
            } else {
                tmp[n++] = (byte) (0xE0 | (c >> 12));
                tmp[n++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                tmp[n++] = (byte) (0x80 | (c & 0x3F));
            }
        }
        byte[] out = new byte[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    private static boolean cont(byte[] b, int i, int end) {
        return i < end && (b[i] & 0xC0) == 0x80;
    }

    /** UTF-8 decoding; every byte that cannot start or continue a sequence becomes U+FFFD. */
    static char[] decodeUtf8(byte[] b, int off, int len) {
        char[] tmp = new char[len];
        int n = 0;
        int end = off + len;
        int i = off;
        while (i < end) {
            int x = b[i] & 0xff;
            if (x < 0x80) {
                tmp[n++] = (char) x;
                i++;
            } else if (x >= 0xC2 && x <= 0xDF && cont(b, i + 1, end)) {
                tmp[n++] = (char) (((x & 0x1F) << 6) | (b[i + 1] & 0x3F));
                i += 2;
            } else if (x >= 0xE0 && x <= 0xEF && cont(b, i + 1, end) && cont(b, i + 2, end)
                    && validSecond(x, b[i + 1] & 0xff)) {
                tmp[n++] = (char) (((x & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F));
                i += 3;
            } else if (x >= 0xF0 && x <= 0xF4 && cont(b, i + 1, end) && cont(b, i + 2, end)
                    && cont(b, i + 3, end) && validSecond(x, b[i + 1] & 0xff)) {
                int cp = ((x & 0x07) << 18) | ((b[i + 1] & 0x3F) << 12) | ((b[i + 2] & 0x3F) << 6)
                        | (b[i + 3] & 0x3F);
                tmp[n++] = (char) (0xD800 + ((cp - 0x10000) >> 10));
                tmp[n++] = (char) (0xDC00 + ((cp - 0x10000) & 0x3FF));
                i += 4;
            } else {
                tmp[n++] = '�';
                i++;
            }
        }
        char[] out = new char[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /** Rejects overlong forms, surrogates and values above U+10FFFF. */
    private static boolean validSecond(int lead, int second) {
        if (lead == 0xE0) {
            return second >= 0xA0;
        }
        if (lead == 0xED) {
            return second < 0xA0;
        }
        if (lead == 0xF0) {
            return second >= 0x90;
        }
        if (lead == 0xF4) {
            return second < 0x90;
        }
        return true;
    }
}
