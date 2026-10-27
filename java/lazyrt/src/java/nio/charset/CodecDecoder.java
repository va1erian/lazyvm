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
            return onArrays(in, out);
        }
        if (src.length < CHUNK) {
            src = new byte[CHUNK];
        }
        while (true) {
            // A chunk about twice what the last call took: errors one by one copy little.
            // At least 16 bytes, more than any unit, so a chunk never ends inside the first.
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
            return CodecEncoder.result(r);
        }
    }

    /**
     * On the arrays, errors the actions settle (IGNORE, and REPLACE when the
     * replacement fits) are settled here as decode() would, without a return
     * to it per error.
     */
    private CoderResult onArrays(ByteBuffer in, CharBuffer out) {
        int ib = in.arrayOffset();
        int ob = out.arrayOffset();
        byte[] a = in.array();
        char[] c = out.array();
        int sp = ib + in.position();
        int sl = ib + in.limit();
        int dp = ob + out.position();
        int dl = ob + out.limit();
        String rep = replacement();
        CodingErrorAction bad = malformedInputAction();
        codec.fill = bad == CodingErrorAction.REPLACE ? rep.toCharArray() : bad == CodingErrorAction.IGNORE ? new char[0] : null;
        int r;
        while (true) {
            r = codec.decode(a, sp, sl, c, dp, dl);
            sp = codec.in;
            dp = codec.out;
            if (r <= 0) {
                break;
            }
            CodingErrorAction action = (r & Codec.UNMAPPABLE) != 0 ? unmappableCharacterAction() : malformedInputAction();
            if (action == CodingErrorAction.REPLACE && rep.length() <= dl - dp) {
                rep.getChars(0, rep.length(), c, dp);
                dp += rep.length();
            } else if (action != CodingErrorAction.IGNORE) {
                break;
            }
            sp += r & ~Codec.UNMAPPABLE;
        }
        codec.fill = null;
        in.position(sp - ib);
        out.position(dp - ob);
        return CodecEncoder.result(r);
    }
}
