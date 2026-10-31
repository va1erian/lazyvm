// VMARGS: -Xmx16m
package thread;

/**
 * The main thread's handler fills the heap and runs out itself: the report needs
 * no memory, and the exit status is the one for an uncaught exception.
 */
public class HandlerFailsMain {
    static final Object[] HELD = new Object[100000];

    static void fill() {
        int i = 0;
        for (int size = 1 << 20; size >= 16;) {
            try {
                HELD[i] = new byte[size];
                i++;
            } catch (OutOfMemoryError e) {
                size /= 2;
            }
        }
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            fill();
            byte[] tooBig = new byte[1 << 30];
            HELD[0] = tooBig;
        });
        System.out.println("throwing");
        throw new RuntimeException("x");
    }
}
