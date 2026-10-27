package java.lang;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

/**
 * String's byte conversions, through java.nio.charset so that String, the
 * readers and the writers code the same way (io.md section 5). Both sides
 * are arrays sized for the worst case, so the coders run straight on them.
 */
final class StringCoding {
    private StringCoding() {
    }

    static Charset lookup(String charsetName) throws UnsupportedEncodingException {
        return lazy.internal.Encodings.forName(charsetName);
    }

    /** Decodes with REPLACE, as new String does (Spec E1). */
    static char[] decode(Charset cs, byte[] b, int off, int len) {
        CharsetDecoder d = cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer in = ByteBuffer.wrap(b, off, len);
        CharBuffer out = CharBuffer.wrap(new char[(int) Math.min(Integer.MAX_VALUE - 8,
                (long) (len * (double) d.maxCharsPerByte()) + 4)]);
        while (true) {
            CoderResult cr = d.decode(in, out, true);
            if (cr.isUnderflow()) {
                cr = d.flush(out);
            }
            if (cr.isUnderflow()) {
                return Arrays.copyOf(out.array(), out.position());
            }
            // A charset whose maximum understates its output: grow and go on.
            CharBuffer bigger = CharBuffer.wrap(Arrays.copyOf(out.array(), out.capacity() * 2 + 16));
            bigger.position(out.position());
            out = bigger;
        }
    }

    /** Encodes with REPLACE, as getBytes does (Spec E1). */
    static byte[] encode(Charset cs, char[] v) {
        CharsetEncoder e = cs.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        CharBuffer in = CharBuffer.wrap(v);
        ByteBuffer out = ByteBuffer.wrap(new byte[(int) Math.min(Integer.MAX_VALUE - 8,
                (long) (v.length * (double) e.maxBytesPerChar()) + 4)]);
        while (true) {
            CoderResult cr = e.encode(in, out, true);
            if (cr.isUnderflow()) {
                cr = e.flush(out);
            }
            if (cr.isUnderflow()) {
                return Arrays.copyOf(out.array(), out.position());
            }
            ByteBuffer bigger = ByteBuffer.wrap(Arrays.copyOf(out.array(), out.capacity() * 2 + 16));
            bigger.position(out.position());
            out = bigger;
        }
    }
}
