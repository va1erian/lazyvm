package java.nio.charset;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

/** A CharsetDecoder over a {@link Codec}, straight on the arrays when both buffers have one. */
final class CodecDecoder extends CharsetDecoder {
    private final Codec codec;

    CodecDecoder(Charset cs, float average, float max, Codec codec) {
        super(cs, average, max);
        this.codec = codec;
    }

    protected void implReset() {
        codec.reset();
    }

    protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
        int ip = in.position();
        int op = out.position();
        int r;
        if (in.hasArray() && out.hasArray()) {
            int ib = in.arrayOffset();
            int ob = out.arrayOffset();
            r = codec.decode(in.array(), ib + ip, ib + in.limit(), out.array(), ob + op, ob + out.limit());
            in.position(codec.in - ib);
            out.position(codec.out - ob);
        } else {
            byte[] src = new byte[in.remaining()];
            in.duplicate().get(src);
            char[] dst = new char[out.remaining()];
            r = codec.decode(src, 0, src.length, dst, 0, dst.length);
            in.position(ip + codec.in);
            out.put(dst, 0, codec.out);
        }
        return CodecEncoder.result(r);
    }
}
