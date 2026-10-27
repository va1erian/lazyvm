package java.nio.charset;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

/** A CharsetEncoder over a {@link Codec}, straight on the arrays when both buffers have one. */
final class CodecEncoder extends CharsetEncoder {
    private final Codec codec;

    CodecEncoder(Charset cs, float average, float max, byte[] replacement, Codec codec) {
        super(cs, average, max, replacement);
        this.codec = codec;
    }

    protected void implReset() {
        codec.reset();
    }

    protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out) {
        int ip = in.position();
        int op = out.position();
        int r;
        if (in.hasArray() && out.hasArray()) {
            int ib = in.arrayOffset();
            int ob = out.arrayOffset();
            r = codec.encode(in.array(), ib + ip, ib + in.limit(), out.array(), ob + op, ob + out.limit());
            in.position(codec.in - ib);
            out.position(codec.out - ob);
        } else {
            char[] src = new char[in.remaining()];
            in.duplicate().get(src);
            byte[] dst = new byte[out.remaining()];
            r = codec.encode(src, 0, src.length, dst, 0, dst.length);
            in.position(ip + codec.in);
            out.put(dst, 0, codec.out);
        }
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
