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
            int ib = in.arrayOffset();
            int ob = out.arrayOffset();
            codec.arrays = true;
            int r = codec.encode(in.array(), ib + in.position(), ib + in.limit(),
                    out.array(), ob + out.position(), ob + out.limit());
            in.position(codec.in - ib);
            out.position(codec.out - ob);
            return result(r);
        }
        codec.arrays = false;
        if (src.length < CHUNK) {
            src = new char[CHUNK];
        }
        while (true) {
            // A chunk about twice what the last call took: errors one by one copy little.
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
            if (r == Codec.UNDERFLOW && moreIn) {
                // A high surrogate at the edge of a chunk: take more input with it.
                if (n == src.length) {
                    src = new char[src.length * 2];
                }
                hint = 2 * n;
                continue;
            }
            return result(r);
        }
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
