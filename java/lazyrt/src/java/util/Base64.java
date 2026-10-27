package java.util;

import java.io.InputStream;
import java.io.OutputStream;

/** RFC 4648 and MIME base64 coders (docs/design/util-data.md sections 1-3, rules B1-B19). */
public class Base64 {
    private Base64() {
    }

    // B7: each getter returns one shared object.
    private static final Encoder BASIC_ENCODER = new Encoder(false, null, 0, null, true);
    private static final Encoder URL_ENCODER = new Encoder(true, null, 0, null, true);
    private static final Encoder MIME_ENCODER = new Encoder(false, new byte[] {'\r', '\n'}, 76, null, true);
    private static final Decoder BASIC_DECODER = new Decoder(false, false);
    private static final Decoder URL_DECODER = new Decoder(true, false);
    private static final Decoder MIME_DECODER = new Decoder(false, true);

    public static Encoder getEncoder() { return BASIC_ENCODER; }
    public static Encoder getUrlEncoder() { return URL_ENCODER; }
    public static Encoder getMimeEncoder() { return MIME_ENCODER; }
    public static Decoder getDecoder() { return BASIC_DECODER; }
    public static Decoder getUrlDecoder() { return URL_DECODER; }
    public static Decoder getMimeDecoder() { return MIME_DECODER; }

    /**
     * B4, B5: the separator is checked first; a line length under 4 means one unbroken line, whose
     * stream form still starts with the separator when the length is 1 to 3 (B14a).
     */
    public static Encoder getMimeEncoder(int lineLength, byte[] lineSeparator) {
        for (byte b : lineSeparator) {
            if (b == '=' || Base64Codec.value(b, false) >= 0) {
                throw new IllegalArgumentException(
                    "Illegal base64 line separator character 0x" + Integer.toString(b & 0xff, 16));
            }
        }
        int line = lineLength / 4 * 4;
        if (line > 0) {
            return new Encoder(false, lineSeparator.clone(), line, null, true);
        }
        return new Encoder(false, null, 0, lineLength > 0 ? lineSeparator.clone() : null, true);
    }

    /** Bytes to base64 text. */
    public static class Encoder {
        private final boolean url;
        private final byte[] separator; // null: no lines
        private final int lineLength;
        /** B14a: written by the stream before its first character; null for none. */
        private final byte[] streamPrefix;
        private final boolean pad;

        private Encoder(boolean url, byte[] separator, int lineLength, byte[] streamPrefix, boolean pad) {
            this.url = url;
            this.separator = separator;
            this.lineLength = lineLength;
            this.streamPrefix = streamPrefix;
            this.pad = pad;
        }

        /** B1. */
        public byte[] encode(byte[] src) {
            byte[] dst = new byte[Base64Codec.toInt(encodedLength(src.length))];
            encodeTo(src, dst);
            return dst;
        }

        /** B6: the exact length must fit, else nothing is written. */
        public int encode(byte[] src, byte[] dst) {
            if (encodedLength(src.length) > dst.length) {
                throw new IllegalArgumentException("Output byte array is too small for encoding all input bytes");
            }
            return encodeTo(src, dst);
        }

        public String encodeToString(byte[] src) {
            byte[] out = encode(src);
            char[] chars = new char[out.length];
            for (int i = 0; i < out.length; i++) {
                chars[i] = (char) (out[i] & 0xff); // ISO-8859-1: bytes 0x80-0xFF are U+0080-U+00FF
            }
            return new String(chars);
        }

        /** B2. */
        public Encoder withoutPadding() { return new Encoder(url, separator, lineLength, streamPrefix, false); }

        /** B14-B16. */
        public OutputStream wrap(OutputStream os) {
            Objects.requireNonNull(os);
            return new Base64Out(os, this);
        }

        /** B1, B3: characters, plus a separator before every line but the first. */
        private long encodedLength(int n) {
            long chars = pad ? (n + 2L) / 3 * 4 : n / 3 * 4L + (n % 3 == 0 ? 0 : n % 3 + 1);
            if (separator == null || chars == 0) {
                return chars;
            }
            return chars + (chars - 1) / lineLength * separator.length;
        }

        private int encodeTo(byte[] src, byte[] dst) {
            Base64Codec.Sink sink = new Base64Codec.Sink(this, dst, 0);
            int i = 0;
            for (; i + 3 <= src.length; i += 3) {
                sink.group(src[i], src[i + 1], src[i + 2], 3);
            }
            int left = src.length - i;
            if (left > 0) {
                sink.group(src[i], left > 1 ? src[i + 1] : 0, (byte) 0, left);
            }
            return sink.length();
        }

        boolean url() { return url; }
        byte[] separator() { return separator; }
        int lineLength() { return lineLength; }
        byte[] streamPrefix() { return streamPrefix; }
        boolean pad() { return pad; }
    }

    /** Base64 text to bytes. */
    public static class Decoder {
        private final boolean url;
        private final boolean mime;

        private Decoder(boolean url, boolean mime) {
            this.url = url;
            this.mime = mime;
        }

        /** B8-B12: decoded into an array of the expected size, then trimmed. */
        public byte[] decode(byte[] src) {
            byte[] dst = new byte[expectedLength(src)];
            int n = Base64Codec.decode(src, dst, url, mime);
            return n == dst.length ? dst : Arrays.copyOf(dst, n);
        }

        /** B9: chars above U+00FF become '?', as ISO-8859-1 encoding does. */
        public byte[] decode(String src) {
            byte[] bytes = new byte[src.length()];
            for (int i = 0; i < bytes.length; i++) {
                char c = src.charAt(i);
                bytes[i] = (byte) (c <= 0xff ? c : '?');
            }
            return decode(bytes);
        }

        /** B13: the expected size must fit before any byte is written. */
        public int decode(byte[] src, byte[] dst) {
            if (expectedLength(src) > dst.length) {
                throw new IllegalArgumentException("Output byte array is too small for decoding all input bytes");
            }
            return Base64Codec.decode(src, dst, url, mime);
        }

        /** B17-B19. */
        public InputStream wrap(InputStream is) {
            Objects.requireNonNull(is);
            return new Base64In(is, url, mime);
        }

        /** B12 (the length-1 refusal) then B13's size E. */
        private int expectedLength(byte[] src) {
            int n = src.length;
            if (mime) {
                int c = 0;
                for (int i = 0; i < n && src[i] != '='; i++) {
                    if (Base64Codec.value(src[i], false) >= 0) {
                        c++;
                    }
                }
                return 3 * (c / 4) + Math.max(c % 4 - 1, 0);
            }
            if (n == 1) {
                throw new IllegalArgumentException("Input byte[] should at least have 2 bytes for base64 bytes");
            }
            int pad = 0;
            if (n > 0 && src[n - 1] == '=') {
                pad = n > 1 && src[n - 2] == '=' ? 2 : 1;
            }
            if (pad == 0 && n % 4 != 0) {
                pad = 4 - n % 4;
            }
            return Base64Codec.toInt(3 * ((n + 3L) / 4) - pad);
        }
    }
}
