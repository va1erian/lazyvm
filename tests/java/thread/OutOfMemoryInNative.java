// VMARGS: -Xmx16m
package thread;

/**
 * The first shortage of the program strikes in a native, which may take the heap
 * further past its limit than a bytecode, and with next to nothing left, as the
 * last blocks are small: its error can still be made and caught.
 */
public class OutOfMemoryInNative {
    public static void main(String[] args) {
        byte[] block = new byte[4 << 20];
        byte[] little = new byte[64];
        Object[] clones = new Object[1 << 19];
        int made = 0;
        try {
            for (int i = 0; i < 5; i++) {
                clones[made++] = block.clone();
            }
            while (made < clones.length) {
                clones[made++] = little.clone();
            }
            System.out.println("no error");
        } catch (OutOfMemoryError e) {
            clones = null;
            System.out.println(e.getMessage());
        }
        try {
            Object nothing = null;
            nothing.hashCode();
        } catch (NullPointerException e) {
            System.out.println("exceptions work again");
        }
    }
}
