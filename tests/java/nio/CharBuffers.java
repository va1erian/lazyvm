package nio;

import java.nio.*;
import java.util.concurrent.Callable;

/** Heap CharBuffer and CharBuffer.wrap(CharSequence): CharSequence behaviour (relative to the
 *  position), views, read-only wrappers, put(String), append, equals/compareTo/hashCode. */
public class CharBuffers {
    static void t(String label, Callable<Object> c) {
        String r;
        try {
            r = String.valueOf(c.call());
        } catch (Throwable e) {
            r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        System.out.println(label + ": " + r);
    }

    static String st(Buffer b) {
        return "pos=" + b.position() + " lim=" + b.limit() + " cap=" + b.capacity();
    }

    public static void main(String[] args) throws Exception {
        CharBuffer c = CharBuffer.allocate(8);
        t("allocate", () -> c.toString().length() + " " + st(c) + " " + c.length() + " " + c.hasArray() + " " + c.isReadOnly() + " " + c.order() + " " + (int) c.get(0));
        t("put/flip/toString", () -> { CharBuffer x = CharBuffer.allocate(8); x.put("hello"); x.flip(); x.get(); return x + " " + st(x) + " len=" + x.length() + " charAt(0)=" + x.charAt(0); });
        t("charAt out of range", () -> { CharBuffer x = CharBuffer.wrap("abc"); x.get(); return x.charAt(2); });
        t("charAt -1", () -> CharBuffer.wrap("abc").charAt(-1));
        t("subSequence", () -> { CharBuffer x = CharBuffer.wrap("abcdef"); x.position(1); CharBuffer s = x.subSequence(1, 3); return s + " " + st(s) + " " + s.isReadOnly() + " " + s.hasArray(); });
        t("subSequence of array buffer", () -> { CharBuffer x = CharBuffer.wrap("abcdef".toCharArray()); x.position(1); CharBuffer s = x.subSequence(1, 3); return s + " " + st(s) + " " + s.arrayOffset(); });
        t("subSequence bounds", () -> CharBuffer.wrap("abc").subSequence(2, 4));
        t("subSequence reversed", () -> CharBuffer.wrap("abc").subSequence(2, 1));
        t("wrap(String)", () -> { CharBuffer x = CharBuffer.wrap("abcdef", 1, 4); return x + " " + st(x) + " " + x.isReadOnly() + " " + x.hasArray() + " " + x.order(); });
        t("wrap(String) bounds", () -> CharBuffer.wrap("abc", 2, 4));
        t("wrap(String) put", () -> CharBuffer.wrap("abc").put('x'));
        t("wrap(String) array", () -> CharBuffer.wrap("abc").array());
        t("wrap(StringBuilder) is a snapshot?", () -> { StringBuilder sb = new StringBuilder("abc"); CharBuffer x = CharBuffer.wrap(sb); sb.setCharAt(0, 'z'); return x.toString(); });
        t("wrap(char[],off,len)", () -> { CharBuffer x = CharBuffer.wrap("abcdef".toCharArray(), 2, 3); return x + " " + st(x) + " off=" + x.arrayOffset(); });
        t("wrap(String) slice", () -> { CharBuffer x = CharBuffer.wrap("abcdef"); x.position(2); CharBuffer s = x.slice(); return s + " " + st(s) + " " + s.isReadOnly(); });
        t("wrap(String) duplicate", () -> { CharBuffer x = CharBuffer.wrap("abcdef"); x.position(2); CharBuffer d = x.duplicate(); return d + " " + st(d) + " " + d.isReadOnly(); });
        t("wrap(String) compact", () -> CharBuffer.wrap("abc").compact());
        t("put(String)", () -> { CharBuffer x = CharBuffer.allocate(6); x.put("abcdef", 1, 3); return st(x) + " [" + x.flip() + "]"; });
        t("put(String) overflow", () -> { CharBuffer x = CharBuffer.allocate(2); try { x.put("abc"); } finally { System.out.print("[" + st(x) + "] "); } return "?"; });
        t("put(String,start,end) bounds", () -> CharBuffer.allocate(6).put("abc", 2, 5));
        t("put(String) bounds before read-only", () -> CharBuffer.wrap("abc").put("xy", 1, 5));
        t("put(String) read-only", () -> CharBuffer.wrap("abc").put("xy", 0, 0));
        t("append", () -> { CharBuffer x = CharBuffer.allocate(16); x.append("ab").append(null).append('c').append("wxyz", 1, 3); return "[" + x.flip() + "]"; });
        t("append overflow", () -> CharBuffer.allocate(2).append("abc"));
        t("get(char[]) underflow", () -> CharBuffer.wrap("ab").get(new char[3]));
        t("read(CharBuffer)", () -> { CharBuffer src = CharBuffer.wrap("abcdef"); CharBuffer dst = CharBuffer.allocate(4); int n = src.read(dst); return n + " " + st(src) + " " + st(dst); });
        t("read empty", () -> { CharBuffer src = CharBuffer.wrap(""); return src.read(CharBuffer.allocate(4)); });
        t("read into full", () -> { CharBuffer src = CharBuffer.wrap("ab"); return src.read(CharBuffer.allocate(0)); });
        t("read self", () -> { CharBuffer x = CharBuffer.allocate(4); return x.read(x); });
        t("asReadOnly", () -> { CharBuffer x = CharBuffer.wrap("abc".toCharArray()).asReadOnlyBuffer(); return x + " " + x.hasArray() + " " + x.isReadOnly(); });
        t("ro array", () -> CharBuffer.allocate(2).asReadOnlyBuffer().array());
        t("ro put", () -> CharBuffer.allocate(2).asReadOnlyBuffer().put('a'));
        t("compact", () -> { CharBuffer x = CharBuffer.wrap("abcdef".toCharArray()); x.position(4); x.compact(); return st(x) + " [" + new String(x.array()) + "]"; });
        t("equals across kinds", () -> CharBuffer.wrap("abc").equals(CharBuffer.wrap("abc".toCharArray())) + " " + CharBuffer.wrap("abc").equals("abc") + " " + CharBuffer.wrap("xabc", 1, 4).equals(CharBuffer.wrap("abc")));
        t("hashCode", () -> CharBuffer.wrap("abc").hashCode() + " " + CharBuffer.wrap("abc".toCharArray()).hashCode() + " " + "abc".hashCode() + " " + CharBuffer.wrap("").hashCode());
        t("compareTo", () -> CharBuffer.wrap("abc").compareTo(CharBuffer.wrap("abd")) + " " + CharBuffer.wrap("abc").compareTo(CharBuffer.wrap("ab")) + " " + CharBuffer.wrap("￿").compareTo(CharBuffer.wrap("a")));
        t("chars as CharSequence", () -> { CharBuffer x = CharBuffer.wrap("hello"); x.position(1); CharSequence cs = x; return cs.length() + " " + cs.subSequence(0, 2) + " " + new StringBuilder(cs) + " " + String.valueOf(cs).length(); });
        t("String.contentEquals", () -> { CharBuffer x = CharBuffer.wrap("xhi"); x.get(); return "hi".contentEquals(x); });
        t("get/put index", () -> { CharBuffer x = CharBuffer.allocate(3); x.put(1, 'q'); return (int) x.get(0) + " " + x.get(1) + " " + st(x); });
        t("put(CharBuffer) self", () -> { CharBuffer x = CharBuffer.allocate(2); return x.put(x); });
        t("toString of empty remaining", () -> { CharBuffer x = CharBuffer.wrap("abc"); x.position(3); return "[" + x + "]"; });
        t("toString keeps position", () -> { CharBuffer x = CharBuffer.wrap("abc".toCharArray()); x.position(1); x.toString(); return st(x); });
        t("CharBuffer.allocate(-1)", () -> CharBuffer.allocate(-1));
        t("view of bytes, order", () -> { ByteBuffer b = ByteBuffer.wrap(new byte[]{'a', 0, 'b', 0}).order(ByteOrder.LITTLE_ENDIAN); CharBuffer x = b.asCharBuffer(); return x + " " + x.order() + " " + x.hasArray(); });
        t("IntBuffer", () -> { IntBuffer x = IntBuffer.wrap(new int[]{3, 1, 2}); x.position(1); return x.get() + " " + x.hashCode() + " " + x.equals(IntBuffer.wrap(new int[]{9, 1, 2}, 1, 2)) + " " + x.compareTo(IntBuffer.wrap(new int[]{2})); });
        t("Long/Short/Float/DoubleBuffer", () -> LongBuffer.allocate(2).put(5).capacity() + " " + ShortBuffer.wrap(new short[]{7}).get() + " " + FloatBuffer.wrap(new float[]{Float.NaN}).equals(FloatBuffer.wrap(new float[]{Float.NaN})) + " " + DoubleBuffer.wrap(new double[]{0.0}).equals(DoubleBuffer.wrap(new double[]{-0.0})));
        t("FloatBuffer compareTo NaN", () -> FloatBuffer.wrap(new float[]{Float.NaN}).compareTo(FloatBuffer.wrap(new float[]{1f})) + " " + FloatBuffer.wrap(new float[]{-0.0f}).compareTo(FloatBuffer.wrap(new float[]{0.0f})));
        System.out.println("done");
    }
}
