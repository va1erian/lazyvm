package java.nio.charset;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

/**
 * A CharsetDecoder over a {@link Codec}: straight on the arrays when both
 * buffers have one, else through bounded scratch arrays, so a buffer without
 * an array costs the same per byte however much input remains.
 */
final class CodecDecoder extends CharsetDecoder {
    private static final int CHUNK = 1024;
    private final Codec codec;
    /** How much input the last chunked call took. */
    private int hint;
    private byte[] src = new byte[0];
    private final char[] dst = new char[CHUNK];

    CodecDecoder(Charset cs, float average, float max, Codec codec) {
        super(cs, average, max);
        this.codec = codec;
    }

    protected void implReset() {
        codec.reset();
    }

    protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
        if (in.hasArray() && out.hasArray()) {
            int ib = in.arrayOffset();
            int ob = out.arrayOffset();
            int r = codec.decode(in.array(), ib + in.position(), ib + in.limit(),
                    out.array(), ob + out.position(), ob + out.limit());
            in.position(codec.in - ib);
            out.position(codec.out - ob);
            return CodecEncoder.result(r);
        }
        if (src.length < CHUNK) {
            src = new byte[CHUNK];
        }
        while (true) {
            // A chunk about twice what the last call took: errors one by one copy little.
            int n = Math.min(in.remaining(), Math.min(src.length, Math.max(16, 2 * hint)));
            int m = Math.min(out.remaining(), dst.length);
            int start = in.position();
            in.get(src, 0, n);
            int r = codec.decode(src, 0, n, dst, 0, m);
            in.position(start + codec.in);
            hint = codec.in + (r > 0 ? r & ~Codec.UNMAPPABLE : 0);
            out.put(dst, 0, codec.out);
            boolean moreIn = n < in.remaining() + codec.in;
            boolean moreOut = out.hasRemaining();
            // A chunk boundary is not the end of the input or of the room: go on.
            if ((r == Codec.UNDERFLOW && moreIn && codec.in > 0)
                    || (r == Codec.OVERFLOW && moreOut && codec.out > 0)) {
                continue;
            }
            if (r == Codec.UNDERFLOW && moreIn) {
                // An incomplete sequence at the edge of a chunk: take more input with it.
                if (n == src.length) {
                    src = new byte[src.length * 2];
                }
                hint = 2 * n;
                continue;
            }
            return CodecEncoder.result(r);
        }
    }
}
