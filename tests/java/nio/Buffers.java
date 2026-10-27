package nio;

import java.nio.*;
import java.util.concurrent.Callable;

/** Heap ByteBuffer: position/limit/mark/capacity, exceptions, views (slice, duplicate, read-only),
 *  compact/flip/rewind/clear, byte order and multi-byte access, equals/compareTo/hashCode/toString. */
public class Buffers {
    static void t(String label, Callable<Object> c) {
        String r;
        try {
            // toString starts with the runtime class name, which is the implementation's own choice.
            r = String.valueOf(c.call()).replaceAll("java\\.nio\\.\\w+\\[", "[");
        } catch (Throwable e) {
            r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        System.out.println(label + ": " + r);
    }

    static ByteBuffer seq(int n) {
        ByteBuffer b = ByteBuffer.allocate(n);
        for (int i = 0; i < n; i++) b.put(i, (byte) (i + 1));
        return b;
    }

    static String hex(ByteBuffer b) {
        StringBuilder sb = new StringBuilder();
        for (int i = b.position(); i < b.limit(); i++) sb.append(String.format("%02X", b.get(i) & 0xff));
        return sb + " " + b;
    }

    public static void main(String[] args) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(8);
        t("allocate", () -> b + " " + b.hasArray() + " " + b.arrayOffset() + " " + b.isDirect() + " " + b.isReadOnly() + " " + b.order() + " " + b.remaining() + " " + b.hasRemaining());
        t("allocate -1", () -> ByteBuffer.allocate(-1));
        t("allocate 0", () -> ByteBuffer.allocate(0));
        t("position 9", () -> b.position(9));
        t("position -1", () -> b.position(-1));
        t("limit 9", () -> b.limit(9));
        t("limit -1", () -> b.limit(-1));
        t("limit below position", () -> { ByteBuffer x = ByteBuffer.allocate(8); x.position(5); x.limit(3); return x; });
        t("limit below mark", () -> { ByteBuffer x = ByteBuffer.allocate(8); x.position(5).mark(); x.limit(6); x.limit(3); x.position(2); x.reset(); return x; });
        t("position below mark", () -> { ByteBuffer x = ByteBuffer.allocate(8); x.position(5).mark(); x.position(3); x.reset(); return x; });
        t("position at mark keeps it", () -> { ByteBuffer x = ByteBuffer.allocate(8); x.position(5).mark(); x.position(5); x.position(7); x.reset(); return x; });
        t("reset without mark", () -> ByteBuffer.allocate(8).reset());
        t("mark/reset", () -> { ByteBuffer x = ByteBuffer.allocate(8); x.position(2).mark(); x.position(6); x.reset(); return x; });
        t("flip", () -> { ByteBuffer x = ByteBuffer.allocate(8); x.put((byte) 1).put((byte) 2).mark(); x.flip(); return x + " " + tryReset(x); });
        t("rewind", () -> { ByteBuffer x = ByteBuffer.allocate(8); x.limit(6).position(4).mark(); x.rewind(); return x + " " + tryReset(x); });
        t("clear", () -> { ByteBuffer x = ByteBuffer.allocate(8); x.limit(6).position(4).mark(); x.clear(); return x + " " + tryReset(x); });
        t("compact", () -> { ByteBuffer x = seq(8); x.position(3).mark(); x.limit(6); x.compact(); return hexAll(x) + " " + tryReset(x); });
        t("compact empty", () -> { ByteBuffer x = seq(8); x.position(8); x.compact(); return x; });

        t("get underflow", () -> { ByteBuffer x = ByteBuffer.allocate(2); x.position(2); return x.get(); });
        t("put overflow", () -> { ByteBuffer x = ByteBuffer.allocate(2); x.position(2); return x.put((byte) 1); });
        t("get(i) out of range", () -> ByteBuffer.allocate(2).get(2));
        t("get(i) beyond limit", () -> { ByteBuffer x = ByteBuffer.allocate(4); x.limit(2); return x.get(3); });
        t("get(-1)", () -> ByteBuffer.allocate(2).get(-1));
        t("put(i) beyond limit", () -> { ByteBuffer x = ByteBuffer.allocate(4); x.limit(2); return x.put(2, (byte) 0); });
        t("get(byte[]) too many", () -> { ByteBuffer x = seq(4); x.position(2); byte[] d = new byte[3]; try { x.get(d); } finally { System.out.print("[pos " + x.position() + "] "); } return "?"; });
        t("get(byte[],off,len) bounds", () -> seq(4).get(new byte[3], 2, 2));
        t("get(byte[],off,len)", () -> { ByteBuffer x = seq(4); byte[] d = new byte[4]; x.get(d, 1, 2); return java.util.Arrays.toString(d) + " " + x; });
        t("put(byte[]) too many", () -> { ByteBuffer x = ByteBuffer.allocate(2); try { x.put(new byte[3]); } finally { System.out.print("[pos " + x.position() + "] "); } return "?"; });
        t("put(ByteBuffer)", () -> { ByteBuffer x = ByteBuffer.allocate(6); ByteBuffer s = seq(4); s.position(1); x.put(s); return hex((ByteBuffer) x.flip()) + " src " + s; });
        t("put(ByteBuffer) too big", () -> { ByteBuffer x = ByteBuffer.allocate(2); ByteBuffer s = seq(4); try { x.put(s); } finally { System.out.print("[pos " + x.position() + " src pos " + s.position() + "] "); } return "?"; });
        t("put(self)", () -> { ByteBuffer x = ByteBuffer.allocate(2); return x.put(x); });

        t("wrap", () -> ByteBuffer.wrap(new byte[10], 2, 5) + " " + ByteBuffer.wrap(new byte[10], 2, 5).arrayOffset());
        t("wrap bounds", () -> ByteBuffer.wrap(new byte[10], 8, 5));
        t("wrap shares array", () -> { byte[] a = new byte[3]; ByteBuffer x = ByteBuffer.wrap(a); a[1] = 9; x.put(2, (byte) 7); return x.get(1) + " " + a[2] + " " + (x.array() == a); });
        t("slice", () -> { ByteBuffer x = seq(8); x.position(2).limit(6); ByteBuffer s = x.slice(); s.put(0, (byte) 99); return s + " off=" + s.arrayOffset() + " " + x.get(2) + " " + hex(s) + " " + s.order(); });
        t("slice of slice", () -> { ByteBuffer x = seq(8); x.position(2); ByteBuffer s = x.slice(); s.position(1); ByteBuffer s2 = s.slice(); return s2 + " off=" + s2.arrayOffset() + " " + s2.get(0); });
        t("slice order reset", () -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).slice().order());
        t("duplicate", () -> { ByteBuffer x = seq(8); x.position(2).mark(); x.position(3).limit(7); ByteBuffer d = x.duplicate(); d.reset(); return d + " " + x + " " + d.order(); });
        t("duplicate order reset", () -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).duplicate().order());
        t("asReadOnly", () -> { ByteBuffer x = seq(8); x.position(1).mark(); x.position(2); ByteBuffer r = x.asReadOnlyBuffer(); r.reset(); return r + " " + r.isReadOnly() + " " + r.hasArray() + " " + r.get() + " " + r.equals(x.duplicate().reset()); });
        ByteBuffer ro = seq(4).asReadOnlyBuffer();
        t("ro put", () -> ro.put((byte) 1));
        t("ro put(i)", () -> ro.put(0, (byte) 1));
        t("ro putInt", () -> ro.putInt(1));
        t("ro array", () -> ro.array());
        t("ro arrayOffset", () -> ro.arrayOffset());
        t("ro compact", () -> ro.compact());
        t("ro put full", () -> { ByteBuffer r = ByteBuffer.allocate(0).asReadOnlyBuffer(); return r.put((byte) 1); });
        t("ro slice/duplicate", () -> ro.slice().isReadOnly() + " " + ro.duplicate().isReadOnly() + " " + ro.asReadOnlyBuffer().isReadOnly());

        t("default order", () -> ByteBuffer.allocate(1).order() + " " + ByteOrder.BIG_ENDIAN + " " + ByteOrder.LITTLE_ENDIAN);
        t("putInt BE", () -> hex((ByteBuffer) ByteBuffer.allocate(4).putInt(0x01020304).flip()));
        t("putInt LE", () -> hex((ByteBuffer) ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x01020304).flip()));
        t("multi-byte", () -> {
            ByteBuffer x = ByteBuffer.allocate(32);
            x.putShort((short) -2).putChar('€').putLong(0x0102030405060708L).putFloat(1.5f).putDouble(-0.25).putShort(0, (short) 0x1234);
            x.flip();
            return hex(x);
        });
        t("getInt/getLong mixed order", () -> { ByteBuffer x = seq(8); long a = x.getLong(0); x.order(ByteOrder.LITTLE_ENDIAN); return Long.toHexString(a) + " " + Long.toHexString(x.getLong(0)) + " " + Integer.toHexString(x.getInt(4)) + " " + (int) x.getChar(1) + " " + x.getShort(6); });
        t("getInt underflow", () -> { ByteBuffer x = seq(8); x.position(5); try { return x.getInt(); } finally { System.out.print("[pos " + x.position() + "] "); } });
        t("putLong overflow", () -> { ByteBuffer x = ByteBuffer.allocate(8); x.position(1); try { return x.putLong(1); } finally { System.out.print("[pos " + x.position() + "] "); } });
        t("getInt(i) bounds", () -> seq(8).getInt(5));
        t("getFloat NaN bits", () -> Integer.toHexString(Float.floatToRawIntBits(ByteBuffer.allocate(4).putFloat(0, Float.intBitsToFloat(0x7fc00001)).getFloat(0))));
        t("putFloat NaN bytes", () -> hex(ByteBuffer.allocate(4).putFloat(0, Float.intBitsToFloat(0x7fc00001))));
        t("asIntBuffer", () -> { ByteBuffer x = seq(10); x.position(1); IntBuffer ib = x.asIntBuffer(); return ib + " " + Integer.toHexString(ib.get(0)) + " " + ib.order() + " " + ib.isReadOnly(); });
        t("asIntBuffer LE", () -> { ByteBuffer x = seq(8).order(ByteOrder.LITTLE_ENDIAN); IntBuffer ib = x.asIntBuffer(); return Integer.toHexString(ib.get(1)) + " " + ib.order(); });
        t("asCharBuffer", () -> { ByteBuffer x = ByteBuffer.wrap(new byte[]{0, 'h', 0, 'i', 0}); CharBuffer cb = x.asCharBuffer(); return cb + " " + cb.length() + " " + cb.capacity(); });
        t("asLongBuffer write-through", () -> { ByteBuffer x = ByteBuffer.allocate(16); x.asLongBuffer().put(1, -1L); return x.get(8) + " " + x.get(7); });

        t("equals", () -> seq(4).equals(seq(4)) + " " + seq(4).equals(seq(5)) + " " + seq(4).equals(seq(5).limit(4)) + " " + seq(4).equals(seq(4).position(1)) + " " + seq(4).equals("x"));
        t("equals ignores order/cap", () -> { ByteBuffer x = seq(6); x.position(2); ByteBuffer y = ByteBuffer.wrap(new byte[]{3, 4, 5, 6}).order(ByteOrder.LITTLE_ENDIAN); return x.equals(y) + " " + x.hashCode() + " " + y.hashCode(); });
        t("compareTo", () -> seq(4).compareTo(seq(4)) + " " + seq(4).compareTo(seq(3)) + " " + seq(3).compareTo(seq(4)) + " " + ByteBuffer.wrap(new byte[]{-1}).compareTo(ByteBuffer.wrap(new byte[]{1})) + " " + ByteBuffer.wrap(new byte[]{5}).compareTo(ByteBuffer.wrap(new byte[]{1})));
        t("hashCode", () -> seq(4).hashCode() + " " + ByteBuffer.allocate(0).hashCode() + " " + seq(4).position(4).hashCode() + " " + ByteBuffer.wrap(new byte[]{-1, 1}).hashCode());
        t("hashCode depends on position", () -> { ByteBuffer x = seq(4); int h0 = x.hashCode(); x.get(); return (h0 == x.hashCode()) + " " + x.hashCode(); });
        t("toString", () -> ByteBuffer.allocate(5).position(1).limit(3) + " | " + ByteBuffer.allocate(5).asReadOnlyBuffer() + " | " + ByteBuffer.allocate(2).asIntBuffer());
        System.out.println("done");
    }

    static String hexAll(ByteBuffer b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.capacity(); i++) sb.append(String.format("%02X", b.get(i) & 0xff));
        return sb + " " + b;
    }

    static String tryReset(Buffer b) {
        try {
            b.reset();
            return "reset->" + b.position();
        } catch (InvalidMarkException e) {
            return "no mark";
        }
    }
}
