// VMARGS: -Xmx16m
package s8;

/** Arrays past the heap or the VM's limit are a catchable OutOfMemoryError, and the VM goes on. */
public class HugeArray {
    interface Make {
        Object run();
    }

    static void expect(String name, Make m) {
        try {
            Object made = m.run();
            System.out.println(name + ": made " + (made != null));
        } catch (OutOfMemoryError e) {
            System.out.println(name + ": OutOfMemoryError: " + e.getMessage());
        } catch (Throwable t) {
            System.out.println(name + ": " + t.getClass().getName());
        }
    }

    public static void main(String[] args) {
        expect("int max", () -> new int[Integer.MAX_VALUE]);
        expect("int max-1", () -> new int[Integer.MAX_VALUE - 1]);
        expect("int max-2", () -> new int[Integer.MAX_VALUE - 2]);
        expect("long", () -> new long[Integer.MAX_VALUE - 2]);
        expect("double", () -> new double[1 << 30]);
        expect("byte", () -> new byte[1 << 30]);
        expect("char", () -> new char[1 << 29]);
        expect("short", () -> new short[1 << 29]);
        expect("boolean", () -> new boolean[1 << 30]);
        expect("float", () -> new float[1 << 28]);
        expect("Object", () -> new Object[1 << 28]);
        expect("String", () -> new String[Integer.MAX_VALUE]);
        expect("two dims", () -> new int[1 << 30][1 << 30]);
        expect("two dims, first", () -> new int[1 << 30][]);
        expect("three dims", () -> new int[4096][4096][4096]);
        expect("negative", () -> new int[-1]);
        expect("negative second", () -> new int[1][-1]);
        expect("small", () -> new int[16]);
        byte[][] keep = new byte[64][];
        int filled = 0;
        try {
            for (int i = 0; i < keep.length; i++) {
                keep[i] = new byte[1 << 20];
                filled++;
            }
        } catch (OutOfMemoryError e) {
            keep = null;
            System.out.println("filled the heap: " + (filled > 0));
        }
        expect("after", () -> new int[1024]);
        System.out.println("done");
    }
}
