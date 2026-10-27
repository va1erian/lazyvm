package java.lang;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;

/**
 * String's byte conversions, through java.nio.charset so that String, the
 * readers and the writers code the same way (io.md section 5).
 */
final class StringCoding {
    private StringCoding() {
    }

    static Charset lookup(String charsetName) throws UnsupportedEncodingException {
        return lazy.internal.Encodings.forName(charsetName);
    }

    /** Decodes with REPLACE, as new String does (Spec E1). */
    static char[] decode(Charset cs, byte[] b, int off, int len) {
        try {
            CharBuffer cb = cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(b, off, len));
            char[] out = new char[cb.remaining()];
            cb.get(out);
            return out;
        } catch (CharacterCodingException e) {
            throw new InternalError(e);
        }
    }

    /** Encodes with REPLACE, as getBytes does (Spec E1). */
    static byte[] encode(Charset cs, char[] v) {
        try {
            ByteBuffer bb = cs.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE).encode(CharBuffer.wrap(v));
            byte[] out = new byte[bb.remaining()];
            bb.get(out);
            return out;
        } catch (CharacterCodingException e) {
            throw new InternalError(e);
        }
    }
}
