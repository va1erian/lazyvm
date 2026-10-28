package util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Base64;
import java.util.BitSet;

/** The java.nio overloads of Base64 and BitSet (docs/design/util-data.md, sections 3 and 6); needs S5-06. */
public class UtilBuffers {
    static String buf(ByteBuffer b) {
        StringBuilder sb = new StringBuilder("pos " + b.position() + " lim " + b.limit() + " cap " + b.capacity() + " [");
        for (int i = b.position(); i < b.limit(); i++) sb.append((char) (b.get(i) & 0xff));
        return sb.append(']').toString();
    }

    static ByteBuffer ascii(String s, int before) {
        ByteBuffer b = ByteBuffer.allocate(s.length() + before + 2);
        b.position(before);
        for (int i = 0; i < s.length(); i++) b.put((byte) s.charAt(i));
        b.limit(b.position());
        b.position(before);
        return b;
    }

    static String dec(Base64.Decoder d, String s) {
        ByteBuffer in = ascii(s, 3);
        try {
            ByteBuffer out = d.decode(in);
            StringBuilder sb = new StringBuilder("out pos " + out.position() + " lim " + out.limit() + " [");
            for (int i = out.position(); i < out.limit(); i++) sb.append(out.get(i)).append(i + 1 < out.limit() ? " " : "");
            return sb.append("] in pos ").append(in.position()).toString();
        } catch (IllegalArgumentException e) {
            return "IllegalArgumentException: " + e.getMessage() + " in pos " + in.position();
        }
    }

    public static void main(String[] args) {
        // B21: encode(ByteBuffer) consumes the input and returns a new buffer holding exactly the text.
        ByteBuffer in = ascii("hello world", 2);
        ByteBuffer out = Base64.getEncoder().encode(in);
        System.out.println("encode buffer " + buf(out) + " in " + buf(in) + " array " + out.hasArray());
        ByteBuffer direct = ByteBuffer.allocateDirect(4);
        direct.put(new byte[] {1, 2, 3, 4}).flip();
        System.out.println("encode direct " + buf(Base64.getMimeEncoder(4, new byte[] {'|'}).encode(direct)) + " in pos " + direct.position());
        System.out.println("encode empty " + buf(Base64.getEncoder().encode(ByteBuffer.allocate(0))));
        // B21: decode(ByteBuffer) consumes the input on success and leaves its position alone on failure.
        String[] ins = {"aGVsbG8=", "aGVsbG8", "", "QQ==QQ==", "QU!D", "QUJD\r\nQUJD", "A", "AA=", "QQ==\n"};
        for (String s : ins) {
            System.out.println("decode '" + s.replace("\r", "\\r").replace("\n", "\\n") + "' B " + dec(Base64.getDecoder(), s)
                + " | M " + dec(Base64.getMimeDecoder(), s));
        }
        // BS10: BitSet.valueOf(ByteBuffer) and (LongBuffer) read the remaining elements and leave the buffer unchanged.
        ByteBuffer bb = ByteBuffer.allocate(12);
        bb.put(new byte[] {(byte) 0xff, 1, 0, 0, 0, 0, 0, 0, 0, (byte) 0x80, 0, 0}).position(1);
        BitSet b1 = BitSet.valueOf(bb);
        System.out.println("valueOf bytes " + b1 + " size " + b1.size() + " buffer pos " + bb.position() + " lim " + bb.limit());
        ByteBuffer big = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        big.put(0, (byte) 1);
        System.out.println("valueOf bytes order ignored " + BitSet.valueOf(big) + " " + BitSet.valueOf(ByteBuffer.allocate(5)).size());
        LongBuffer lb = LongBuffer.wrap(new long[] {7, 0, 1, 0, 0}, 1, 4);
        BitSet b2 = BitSet.valueOf(lb);
        System.out.println("valueOf longs " + b2 + " size " + b2.size() + " buffer pos " + lb.position() + " lim " + lb.limit());
        // B20: without an accessible array (direct, read-only) the index counts from the position;
        // a slice counts from the start of its backing array, so its arrayOffset is included.
        byte[] text = "xxxQQ==QQ==".getBytes();
        ByteBuffer dir = ByteBuffer.allocateDirect(text.length);
        dir.put(text).position(3);
        ByteBuffer ro = ByteBuffer.wrap(text).asReadOnlyBuffer();
        ro.position(3);
        ByteBuffer slice = ByteBuffer.wrap(text, 1, 10).slice();
        slice.position(2);
        ByteBuffer heap = ByteBuffer.wrap(text);
        heap.position(3);
        for (ByteBuffer b : new ByteBuffer[] {dir, ro, slice, heap}) {
            String kind = b.isDirect() ? "direct" : b.isReadOnly() ? "read-only" : b.arrayOffset() > 0 ? "slice offset " + b.arrayOffset() : "heap";
            int at = b.position();
            String basic;
            String mime;
            try { Base64.getDecoder().decode(b); basic = "ok"; } catch (IllegalArgumentException e) { basic = e.getMessage(); }
            b.position(at);
            try { Base64.getMimeDecoder().decode(b); mime = "ok"; } catch (IllegalArgumentException e) { mime = e.getMessage(); }
            System.out.println("decode index " + kind + ": " + basic + " | MIME " + mime + " | pos " + b.position());
        }
        ByteBuffer rin = ByteBuffer.wrap("aGVsbG8=".getBytes()).asReadOnlyBuffer();
        ByteBuffer rout = Base64.getDecoder().decode(rin);
        System.out.println("decode read-only " + buf(rout) + " array " + rout.hasArray() + " in pos " + rin.position());
        System.out.println("done");
    }
}
