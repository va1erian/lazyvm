// VMARGS: -Xmx16m
package thread;

/**
 * The heap is full when an exception escapes main, so the default handler cannot
 * even start to report it. Java has not yet asked for the main thread's object
 * here, and nothing has been printed.
 */
public class HandlerFailsFullHeap {
    static final Object[] HELD = new Object[100000];

    public static void main(String[] args) {
        int i = 0;
        for (int size = 1 << 20; size >= 16;) {
            try {
                HELD[i] = new byte[size];
                i++;
            } catch (OutOfMemoryError e) {
                size /= 2;
            }
        }
        throw new RuntimeException("x");
    }
}
