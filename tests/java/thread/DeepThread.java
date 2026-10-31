package thread;

import java.lang.reflect.Method;

/**
 * A started thread nests calls from the VM into Java as deep as the main thread, and both as deep
 * as the oracle reaches in every run (decisions.md D15).
 */
public class DeepThread {
    static Method self;

    static int viaReflection(int n) throws Exception {
        if (n == 0) {
            return 0;
        }
        return 1 + (Integer) self.invoke(null, n - 1);
    }

    public static void main(String[] args) throws Exception {
        self = DeepThread.class.getDeclaredMethod("viaReflection", int.class);
        final String[] out = new String[1];
        Thread t = new Thread(() -> {
            try {
                out[0] = "reflective depth " + viaReflection(2000);
            } catch (Throwable e) {
                out[0] = "failed: " + e;
            }
        });
        t.start();
        t.join();
        System.out.println(out[0]);
        System.out.println("on the main thread " + viaReflection(2000));
    }
}
