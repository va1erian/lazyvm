// VMARGS: -Xmx16m
package thread;

/**
 * The main thread's handler fills the heap and returns: all went well, so there
 * is nothing to report, though the VM has no room left to do much on its way out.
 */
public class HandlerReturnsFullHeap {
    static final Object[] HELD = new Object[100000];

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            int i = 0;
            for (int size = 1 << 20; size >= 16;) {
                try {
                    HELD[i] = new byte[size];
                    i++;
                } catch (OutOfMemoryError oom) {
                    size /= 2;
                }
            }
        });
        throw new RuntimeException("x");
    }
}
