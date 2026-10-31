// VMARGS: -Xmx16m
package thread;

/** Started threads whose uncaught exception handlers fail, with and without memory. */
public class HandlerFailsThreads {
    static Object[] held;

    static void fill() {
        held = new Object[100000];
        int i = 0;
        for (int size = 1 << 20; size >= 16;) {
            try {
                held[i] = new byte[size];
                i++;
            } catch (OutOfMemoryError e) {
                size /= 2;
            }
        }
    }

    static void runThread(String name, boolean fillFirst, Thread.UncaughtExceptionHandler handler)
            throws Exception {
        Thread t = new Thread(name) {
            @Override
            public void run() {
                if (fillFirst) {
                    fill();
                }
                throw new RuntimeException("x");
            }
        };
        if (handler != null) {
            t.setUncaughtExceptionHandler(handler);
        }
        t.start();
        t.join();
        held = null;
        System.out.flush();
        System.out.println(name + " ended, alive: " + t.isAlive());
    }

    public static void main(String[] args) throws Exception {
        runThread("full heap, default handler", true, null);
        runThread("handler out of memory", false, (t, e) -> {
            fill();
            byte[] tooBig = new byte[1 << 30];
            held[0] = tooBig;
        });
        runThread("handler throws", false, (t, e) -> {
            throw new IllegalStateException("from the handler");
        });
        runThread("handler prints and throws", false, (t, e) -> {
            System.err.print("in the handler");
            throw new IllegalArgumentException();
        });
        // The line about it goes out in modified UTF-8: a pair as two surrogates, a NUL in two bytes.
        Thread odd = new Thread(() -> {
            throw new RuntimeException("x");
        }, "pair 😀 lone \uD83D nul \u0000.");
        odd.setUncaughtExceptionHandler((t, e) -> {
            throw new IllegalStateException();
        });
        odd.start();
        odd.join();
        System.out.println("odd name ended");
    }
}
