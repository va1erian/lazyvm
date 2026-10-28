package java.nio.charset;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

/**
 * A CharsetEncoder over a {@link Codec}: straight on the arrays when both
 * buffers have one, else through bounded scratch arrays (see CodecDecoder).
 */
final class CodecEncoder extends CharsetEncoder {
    private static final int CHUNK = 1024;
    private final Codec codec;
    /** How much input the last chunked call took. */
    private int hint;
    private char[] src = new char[0];
    private final byte[] dst = new byte[CHUNK];

    CodecEncoder(Charset cs, float average, float max, byte[] replacement, Codec codec) {
        super(cs, average, max, replacement);
        this.codec = codec;
    }

    protected void implReset() {
        codec.reset();
    }

    protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out) {
        if (in.hasArray() && out.hasArray()) {
            return onArrays(in, out);
        }
        codec.arrays = false;
        if (src.length < CHUNK) {
            src = new char[CHUNK];
        }
        while (true) {
            // A chunk about twice what the last call took: errors one by one copy little.
            // At least 16 chars, so a chunk never ends inside the first pair.
            int n = Math.min(in.remaining(), Math.min(src.length, Math.max(16, 2 * hint)));
            int m = Math.min(out.remaining(), dst.length);
            int start = in.position();
            in.get(src, 0, n);
            int r = codec.encode(src, 0, n, dst, 0, m);
            in.position(start + codec.in);
            hint = codec.in + (r > 0 ? r & ~Codec.UNMAPPABLE : 0);
            out.put(dst, 0, codec.out);
            boolean moreIn = n < in.remaining() + codec.in;
            // A chunk boundary is not the end of the input or of the room: go on.
            if ((r == Codec.UNDERFLOW && moreIn && codec.in > 0)
                    || (r == Codec.OVERFLOW && out.hasRemaining() && codec.out > 0)) {
                continue;
            }
            return result(r);
        }
    }

    /** As CodecDecoder.onArrays: the errors the actions settle are settled here. */
    private CoderResult onArrays(CharBuffer in, ByteBuffer out) {
        int ib = in.arrayOffset();
        int ob = out.arrayOffset();
        char[] a = in.array();
        byte[] b = out.array();
        int sp = ib + in.position();
        int sl = ib + in.limit();
        int dp = ob + out.position();
        int dl = ob + out.limit();
        byte[] rep = replacementBytes();
        codec.arrays = true;
        int r;
        while (true) {
            r = codec.encode(a, sp, sl, b, dp, dl);
            sp = codec.in;
            dp = codec.out;
            if (r <= 0) {
                break;
            }
            CodingErrorAction action = (r & Codec.UNMAPPABLE) != 0 ? unmappableCharacterAction() : malformedInputAction();
            if (action == CodingErrorAction.REPLACE && rep.length <= dl - dp) {
                System.arraycopy(rep, 0, b, dp, rep.length);
                dp += rep.length;
            } else if (action != CodingErrorAction.IGNORE) {
                break;
            }
            sp += r & ~Codec.UNMAPPABLE;
        }
        in.position(sp - ib);
        out.position(dp - ob);
        return result(r);
    }

    /** A {@link Codec} step result as a CoderResult. */
    static CoderResult result(int r) {
        if (r == Codec.UNDERFLOW) {
            return CoderResult.UNDERFLOW;
        }
        if (r == Codec.OVERFLOW) {
            return CoderResult.OVERFLOW;
        }
        if ((r & Codec.UNMAPPABLE) != 0) {
            return CoderResult.unmappableForLength(r & ~Codec.UNMAPPABLE);
        }
        return CoderResult.malformedForLength(r);
    }
}
